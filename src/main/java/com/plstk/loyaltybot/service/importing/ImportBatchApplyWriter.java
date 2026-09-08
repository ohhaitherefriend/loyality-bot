package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.entity.ShopSettings;
import com.plstk.loyaltybot.entity.commerce.AvailabilityMode;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.LinkConfirmationSource;
import com.plstk.loyaltybot.entity.importing.PriceRoundingPolicy;
import com.plstk.loyaltybot.entity.importing.SnapshotMode;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierOffer;
import com.plstk.loyaltybot.entity.importing.SupplierProductLink;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import com.plstk.loyaltybot.repository.ShopSettingsRepository;
import com.plstk.loyaltybot.repository.SupplierOfferRepository;
import com.plstk.loyaltybot.repository.SupplierProductLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Transactional core of the Prompt 06 Apply stage (docs/ARCHITECTURE.md §14): upserts one
 * {@code SupplierOffer} per appliable {@link ImportRow}, creates safe {@code NEW_PRODUCT}
 * {@link Product}s, learns/refreshes {@link SupplierProductLink}s, deactivates stale offers for a
 * FULL snapshot's scope, and recomputes storefront availability for every touched product - all in
 * ONE database transaction per batch. Atomicity is deliberate: if anything throws, nothing this
 * batch would have changed is left half-applied (the "rollback" requirement) - the batch simply
 * stays/returns to a state {@link ImportBatchApplyService} can safely retry from.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportBatchApplyWriter {

    private static final List<ImportRowStatus> APPLIABLE_STATUSES =
            List.of(ImportRowStatus.AUTO_APPROVED, ImportRowStatus.APPROVED);

    private final ImportBatchRepository importBatchRepository;
    private final ImportRowRepository importRowRepository;
    private final ProductRepository productRepository;
    private final SupplierOfferRepository supplierOfferRepository;
    private final SupplierProductLinkRepository supplierProductLinkRepository;
    private final ShopSettingsRepository shopSettingsRepository;
    private final PricingService pricingService;
    private final CatalogAvailabilityService catalogAvailabilityService;
    private final ObjectMapper objectMapper;

    /** @return true if this call won the AUTO_APPROVED/APPROVED -&gt; APPLYING claim. */
    @Transactional
    public boolean claimForApplying(Long batchId) {
        return importBatchRepository.claimForApplying(batchId, LocalDateTime.now()) == 1;
    }

    @Transactional
    public void applyBatch(Long batchId) {
        ImportBatch batch = importBatchRepository.findByIdWithSupplierSourceAndSupplier(batchId)
                .orElseThrow(() -> new IllegalStateException("ImportBatch " + batchId + " not found"));
        if (batch.getStatus() != ImportBatchStatus.APPLYING) {
            log.debug("Batch {} is not in APPLYING status ({}) - nothing to do", batchId, batch.getStatus());
            return;
        }

        SupplierSource source = batch.getSupplierSource();
        Supplier supplier = source.getSupplier();
        String shopId = batch.getShopId();
        ShopSettings shopSettings = shopSettingsRepository.findByShopId(shopId).orElse(null);
        BigDecimal commissionPercent = pricingService.resolveCommissionPercent(source, shopSettings);
        PriceRoundingPolicy roundingPolicy = source.getRoundingPolicy();

        List<ImportRow> rows = importRowRepository.findByImportBatchIdAndStatusIn(batchId, APPLIABLE_STATUSES);
        Set<Long> touchedProductIds = new LinkedHashSet<>();
        int added = 0;
        int updated = 0;
        int priceChanged = 0;
        int unchanged = 0;

        for (ImportRow row : rows) {
            NormalizedRowData normalized = readNormalized(row);
            if (normalized == null || normalized.supplierPrice() == null) {
                throw new IllegalStateException(
                        "ImportRow " + row.getId() + " reached apply without a valid normalizedData/supplierPrice");
            }

            Product product = resolveProduct(shopId, row, normalized);
            touchedProductIds.add(product.getId());

            BigDecimal supplierPrice = normalized.supplierPrice();
            BigDecimal sitePrice = pricingService.calculateSitePrice(supplierPrice, commissionPercent, roundingPolicy);

            Optional<SupplierOffer> existingOpt = supplierOfferRepository
                    .findByShopIdAndSupplierIdAndSnapshotScopeAndProductId(
                            shopId, supplier.getId(), source.getSnapshotScope(), product.getId());
            LocalDateTime now = LocalDateTime.now();
            if (existingOpt.isPresent()) {
                SupplierOffer existing = existingOpt.get();
                boolean priceDelta = existing.getCalculatedSitePrice() == null
                        || existing.getCalculatedSitePrice().compareTo(sitePrice) != 0;
                boolean stockDelta = !Objects.equals(existing.getStockQuantity(), normalized.stock());
                existing.setSupplierPrice(supplierPrice);
                existing.setAppliedCommissionPercent(commissionPercent);
                existing.setCalculatedSitePrice(sitePrice);
                existing.setStockQuantity(normalized.stock());
                // The offer's identity (shop+supplier+snapshotScope+product) never changes here -
                // only which SupplierSource most recently wrote it, so that several transport
                // sources sharing one scope (e.g. a re-sent duplicate file under a new source
                // record) keep updating the same logical offer instead of creating a duplicate.
                existing.setSupplierSource(source);
                if (normalized.externalSku() != null) {
                    existing.setExternalSku(normalized.externalSku());
                }
                if (normalized.barcode() != null) {
                    existing.setBarcode(normalized.barcode());
                }
                existing.setActive(true);
                existing.setLastSeenBatch(batch);
                existing.setLastSeenAt(now);
                if (existing.getFirstSeenAt() == null) {
                    existing.setFirstSeenAt(now);
                }
                supplierOfferRepository.save(existing);
                updated++;
                if (priceDelta) {
                    priceChanged++;
                } else if (!stockDelta) {
                    unchanged++;
                }
            } else {
                SupplierOffer created = SupplierOffer.builder()
                        .shopId(shopId)
                        .supplier(supplier)
                        .supplierSource(source)
                        .snapshotScope(source.getSnapshotScope())
                        .product(product)
                        .externalSku(normalized.externalSku())
                        .barcode(normalized.barcode())
                        .supplierPrice(supplierPrice)
                        .appliedCommissionPercent(commissionPercent)
                        .calculatedSitePrice(sitePrice)
                        .stockQuantity(normalized.stock())
                        .active(true)
                        .lastSeenBatch(batch)
                        .firstSeenAt(now)
                        .lastSeenAt(now)
                        .build();
                supplierOfferRepository.save(created);
                added++;
            }

            upsertLink(shopId, supplier, product, normalized);

            row.setStatus(ImportRowStatus.APPLIED);
            row.setMatchedProduct(product);
            importRowRepository.save(row);
        }

        if (source.getSnapshotMode() == SnapshotMode.FULL) {
            List<SupplierOffer> stale = supplierOfferRepository.findStaleActiveOffersInScope(
                    shopId, supplier.getId(), source.getSnapshotScope(), batchId);
            LocalDateTime now = LocalDateTime.now();
            for (SupplierOffer offer : stale) {
                offer.setActive(false);
                offer.setDeactivatedAt(now);
                supplierOfferRepository.save(offer);
                touchedProductIds.add(offer.getProduct().getId());
            }
        }

        int removedFromStorefront = 0;
        int reactivated = 0;
        for (Long productId : touchedProductIds) {
            Product before = productRepository.findByShopIdAndId(shopId, productId)
                    .orElseThrow(() -> new IllegalStateException("Product " + productId + " not found"));
            boolean wasVisible = Boolean.TRUE.equals(before.getVisible());
            boolean isVisible = catalogAvailabilityService.recompute(shopId, productId);
            if (wasVisible && !isVisible) {
                removedFromStorefront++;
            } else if (!wasVisible && isVisible) {
                reactivated++;
            }
        }

        LocalDateTime finishedAt = LocalDateTime.now();
        batch.setStatus(ImportBatchStatus.APPLIED);
        batch.setAppliedAt(finishedAt);
        batch.setFinishedAt(finishedAt);
        batch.setOffersAddedCount(added);
        batch.setOffersUpdatedCount(updated);
        batch.setOffersPriceChangedCount(priceChanged);
        batch.setOffersUnchangedCount(unchanged);
        batch.setProductsRemovedFromStorefrontCount(removedFromStorefront);
        batch.setProductsReactivatedCount(reactivated);
        importBatchRepository.save(batch);
        log.info("Batch {} applied: {} row(s), {} added, {} updated ({} price-changed), {} deactivated-scope-removed, "
                        + "{} reactivated -> APPLIED",
                batchId, rows.size(), added, updated, priceChanged, removedFromStorefront, reactivated);
    }

    /**
     * Conditional on the batch still being {@code APPLYING} (see {@code
     * ImportBatchRepository#finalizeFailedFrom}) - a losing side of a genuine concurrent-apply race
     * must never overwrite an already-{@code APPLIED} batch's terminal status.
     */
    @Transactional
    public void finalizeFailed(Long batchId, String reason) {
        int updated = importBatchRepository.finalizeFailedFrom(
                batchId, ImportBatchStatus.APPLYING, reason, LocalDateTime.now());
        if (updated == 1) {
            log.error("Batch {} failed: {}", batchId, reason);
        } else {
            log.warn("Batch {} was no longer APPLYING when apply failed ({}); leaving its actual status untouched",
                    batchId, reason);
        }
    }

    /**
     * NEW_PRODUCT rows (Prompt 05) have {@code matchedProduct == null}; every other AUTO_APPROVED/
     * APPROVED decision type already has one. New products are created hidden
     * ({@code visible=false}) - {@link CatalogAvailabilityService#recompute} flips them visible once
     * their freshly-created active offer is taken into account below, exactly like any other
     * newly-reactivated product, so "a valid new product automatically appears" holds without a
     * separate code path.
     */
    private Product resolveProduct(String shopId, ImportRow row, NormalizedRowData normalized) {
        if (row.getMatchedProduct() != null) {
            Long productId = row.getMatchedProduct().getId();
            return productRepository.findByShopIdAndId(shopId, productId)
                    .orElseThrow(() -> new IllegalStateException("Matched product " + productId + " not found"));
        }
        Map<String, String> raw = readRaw(row);
        String rawName = raw.get(LayoutRuleDefinition.FIELD_RAW_NAME);
        String brand = normalized.brand();
        String name = (rawName != null && !rawName.isBlank()) ? rawName : brand;
        if (name == null || name.isBlank()) {
            // Should be unreachable: SpreadsheetParser row validity already requires a non-blank
            // name for every row that reaches this stage, and ImportBatchMatchingService only
            // auto-approves NEW_PRODUCT when brand is present too (see evaluateNewProductOrReview).
            // The only path that could still get here is a manual CREATE_PRODUCT review action on a
            // row that skips those gates - fail loudly instead of silently publishing a catalog
            // product named "Import row 1234", which would be a real, customer-visible garbage name.
            throw new IllegalStateException(
                    "ImportRow " + row.getId() + " has no rawName and no brand - refusing to create a "
                            + "NEW_PRODUCT with a synthesized placeholder name");
        }

        LocalDateTime now = LocalDateTime.now();
        Product product = Product.builder()
                .shopId(shopId)
                .brand(brand)
                .name(name)
                .supplierArticle(normalized.externalSku())
                .barcode(normalized.barcode())
                .supplierPrice(normalized.supplierPrice())
                .currency("RUB")
                .stockQuantity(normalized.stock())
                .availabilityMode(AvailabilityMode.PREORDER)
                .visible(false)
                .active(true)
                .manualHidden(false)
                .sourceSheet(row.getSourceSheet())
                .sourceRow(row.getSourceRowNumber())
                .lastImportedAt(now)
                .build();
        return productRepository.save(product);
    }

    /**
     * Refreshes/creates the {@code SupplierProductLink} that lets the next batch from the same
     * supplier skip AI entirely for this row (D-006/ADR-004 step 1). No-op when the row has neither
     * a stable {@code externalSku} nor {@code barcode} - a fingerprint-only match has nothing
     * unique to persist a link keyed on.
     */
    private void upsertLink(String shopId, Supplier supplier, Product product, NormalizedRowData normalized) {
        String externalSku = normalized.externalSku();
        String barcode = normalized.barcode();
        if (externalSku == null && barcode == null) {
            return;
        }
        Optional<SupplierProductLink> existing = Optional.empty();
        if (externalSku != null) {
            existing = supplierProductLinkRepository.findByShopIdAndSupplierIdAndExternalSku(shopId, supplier.getId(), externalSku);
        }
        if (existing.isEmpty() && barcode != null) {
            existing = supplierProductLinkRepository.findByShopIdAndSupplierIdAndBarcode(shopId, supplier.getId(), barcode);
        }
        SupplierProductLink link = existing.orElseGet(() -> SupplierProductLink.builder()
                .shopId(shopId)
                .supplier(supplier)
                .build());
        link.setProduct(product);
        if (externalSku != null) {
            link.setExternalSku(externalSku);
        }
        if (barcode != null) {
            link.setBarcode(barcode);
        }
        link.setFingerprint(normalized.fingerprint());
        if (link.getConfirmedSource() == null) {
            link.setConfirmedSource(LinkConfirmationSource.AUTOMATIC);
        }
        link.setConfirmedAt(LocalDateTime.now());
        supplierProductLinkRepository.save(link);
    }

    private NormalizedRowData readNormalized(ImportRow row) {
        String json = row.getNormalizedData();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, NormalizedRowData.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse ImportRow.normalizedData as JSON for row " + row.getId(), e);
        }
    }

    private Map<String, String> readRaw(ImportRow row) {
        String json = row.getRawData();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse ImportRow.rawData as JSON for row " + row.getId(), e);
        }
    }
}
