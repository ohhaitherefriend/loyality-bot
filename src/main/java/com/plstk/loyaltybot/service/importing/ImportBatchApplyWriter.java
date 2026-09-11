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
    private final RowAttributeNormalizer normalizer;
    private final CriticalAttributeConflictChecker conflictChecker;
    private final BrandAliasResolver brandAliasResolver;
    private final ProductCreationLock productCreationLock;

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
        // ADR-031 (Section 4): every product-creation decision this apply makes runs under ONE
        // shop-scoped, cross-instance lock held for the WHOLE of this transaction (acquired before
        // any row is read/written, released automatically on commit/rollback) - a second,
        // concurrent apply for the SAME shop (any supplier) is forced to wait until this one
        // commits or rolls back, then re-observes the resulting DB state with its own fresh
        // queries. No network call (IMAP/AI) ever happens inside this method, so nothing blocking
        // is ever held under the lock.
        productCreationLock.withLock(batch.getShopId(), () -> {
            doApplyBatch(batch);
            return null;
        });
    }

    private void doApplyBatch(ImportBatch batch) {
        Long batchId = batch.getId();
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

        int protectedFromDeactivation = 0;
        if (source.getSnapshotMode() == SnapshotMode.FULL) {
            List<SupplierOffer> stale = supplierOfferRepository.findStaleActiveOffersInScope(
                    shopId, supplier.getId(), source.getSnapshotScope(), batchId);
            Set<String> identifiersPresentInBatch = collectIdentifiersPresentInBatch(batchId);
            LocalDateTime now = LocalDateTime.now();
            for (SupplierOffer offer : stale) {
                if (isReferencedByIdentifier(offer, identifiersPresentInBatch)) {
                    // The row that should have refreshed this offer exists in this FULL file but
                    // failed processing (e.g. INVALID price) rather than the product genuinely
                    // being absent from the supplier's snapshot - do NOT deactivate/hide it from
                    // the storefront on the strength of a row-level processing error. The offer
                    // keeps its last known-good price/stock until a row for this identifier
                    // applies successfully. See docs/DECISIONS.md ADR-022.
                    protectedFromDeactivation++;
                    continue;
                }
                offer.setActive(false);
                offer.setDeactivatedAt(now);
                supplierOfferRepository.save(offer);
                touchedProductIds.add(offer.getProduct().getId());
            }
            if (protectedFromDeactivation > 0) {
                log.warn("Batch {} FULL snapshot: {} offer(s) matched by externalSku/barcode to a row present "
                                + "in this file that did not apply successfully - protected from stale deactivation",
                        batchId, protectedFromDeactivation);
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
        batch.setOffersProtectedFromDeactivationCount(protectedFromDeactivation);
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
     *
     * <p>ADR-030 (Section 5): the {@code NEW_PRODUCT} decision was made during the earlier MATCHING
     * stage, against the catalog as it existed AT THAT TIME - by the time apply actually runs, an
     * identical product may already exist because (a) a different row of THIS SAME batch (a
     * duplicate line in the supplier's own file) already created it earlier in this very loop, (b)
     * a different, already-applied batch (same or another supplier) created it first, or (c) it was
     * added manually. {@link #checkProductCreation} re-runs the exact same unbounded, brand-scoped
     * fingerprint check {@code DeterministicMatchResolver#resolveViaSafeFingerprint} used, but
     * against the CURRENT DB state inside this transaction (so it also sees this same transaction's
     * own not-yet-committed inserts from earlier rows) - a confirmed identical match is reused
     * instead of creating a duplicate; never merged/deleted, only reused going forward. ADR-031
     * (Section 3/4): its result is an explicit {@link ProductCreationCheck}, never a bare {@code
     * Optional} - an {@code Ambiguous}/{@code Unsafe} result always aborts (throws), rolling back
     * this whole batch's apply transaction rather than guessing; the whole method also runs under
     * {@link ProductCreationLock} (acquired once for the whole batch in {@link #applyBatch}) so a
     * concurrent apply for the same shop can never race this re-verification.
     */
    private Product resolveProduct(String shopId, ImportRow row, NormalizedRowData normalized) {
        if (row.getMatchedProduct() != null) {
            Long productId = row.getMatchedProduct().getId();
            Product matched = productRepository.findByShopIdAndId(shopId, productId)
                    .orElseThrow(() -> new IllegalStateException("Matched product " + productId + " not found"));
            verifyMatchedProductStillAgrees(shopId, row, normalized, matched);
            return matched;
        }

        // ADR-031 (Section 5): a row's normalizedData may have been computed by an OLDER
        // normalization-algorithm version (persisted before RowAttributeNormalizer.NORMALIZATION_VERSION
        // was bumped, e.g. an AUTO_APPROVED/APPROVED batch that sat unapplied across a version
        // upgrade) - its fingerprint format may no longer be comparable at all against catalog
        // products normalized by the CURRENT algorithm. Never trust it as-is: recompute fresh from
        // the row's own persisted raw data before running the safe-to-create check below, exactly
        // the same way the row would be normalized today - never just relabel the old value with
        // the current version number.
        NormalizedRowData effective = refreshIfStale(shopId, row, normalized);

        ProductCreationCheck check = checkProductCreation(shopId, effective);
        if (check instanceof ProductCreationCheck.ExistingMatch match) {
            log.warn("ImportRow {} was decided NEW_PRODUCT during matching, but product {} with an identical "
                            + "fingerprint now exists (created concurrently/since then, or the row's stale "
                            + "normalization was refreshed) - reusing it instead of creating a duplicate",
                    row.getId(), match.product().getId());
            return match.product();
        }
        if (check instanceof ProductCreationCheck.Ambiguous ambiguous) {
            List<Long> candidateIds = ambiguous.candidates().stream().map(Product::getId).toList();
            throw new UnsafeProductDecisionException(
                    "ImportRow " + row.getId() + " was decided NEW_PRODUCT during matching, but apply-time "
                            + "re-verification now finds " + ambiguous.candidates().size()
                            + " existing product(s) " + candidateIds + " with an identical fingerprint - "
                            + "refusing to auto-create a third product or auto-pick either existing one "
                            + "(" + ambiguous.reason() + "); this row/batch requires manual review");
        }
        if (check instanceof ProductCreationCheck.Unsafe unsafe) {
            throw new UnsafeProductDecisionException(
                    "ImportRow " + row.getId() + " was decided NEW_PRODUCT during matching, but apply-time "
                            + "re-verification cannot safely confirm this is still correct: " + unsafe.reason());
        }
        // ProductCreationCheck.SafeToCreate - fall through to actually create it below.

        Map<String, String> raw = readRaw(row);
        String rawName = raw.get(LayoutRuleDefinition.FIELD_RAW_NAME);
        String brand = effective.brand();
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
                .supplierArticle(effective.externalSku())
                .barcode(effective.barcode())
                .supplierPrice(effective.supplierPrice())
                .currency("RUB")
                .stockQuantity(effective.stock())
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
     * ADR-031 (Section 5): a MATCHED-product decision (EXACT/AI_MATCH/etc., made during the
     * earlier matching stage) carries the same staleness risk as a {@code NEW_PRODUCT} decision
     * when the row's {@code normalizedData} predates the current {@link
     * RowAttributeNormalizer#NORMALIZATION_VERSION} (or the shop's brand-alias configuration
     * changed since then) - the decision was correct for the algorithm/config in effect AT THAT
     * TIME, but is never re-trusted blindly here. Recomputes fresh from the row's own persisted
     * raw data and re-checks structural agreement (identical fingerprint, no conflicting critical
     * attributes) against the matched product's CURRENT on-the-fly fingerprint; a genuine
     * disagreement aborts the whole batch (never silently relabels/keeps the stale decision) so a
     * human can review it. A row already at the current version, or with no raw data to recompute
     * from, is left as-is - there is nothing new to re-verify against.
     */
    private void verifyMatchedProductStillAgrees(
            String shopId, ImportRow row, NormalizedRowData normalized, Product matched) {
        Integer version = normalized.normalizationVersion();
        if (version != null && version == RowAttributeNormalizer.NORMALIZATION_VERSION) {
            return;
        }
        Map<String, String> raw = readRaw(row);
        if (raw.isEmpty()) {
            log.warn("ImportRow {} matched product {} with stale normalizedData version {} (current {}) and no "
                            + "rawData to recompute from - proceeding with the stored match decision as-is",
                    row.getId(), matched.getId(), version, RowAttributeNormalizer.NORMALIZATION_VERSION);
            return;
        }
        NormalizedRowData fresh = normalizer.normalize(shopId, raw);
        if (fresh.brand() == null || fresh.fingerprint() == null) {
            return;
        }
        NormalizedRowData matchedNormalized = normalizer.normalizeProduct(shopId, matched);
        boolean sameFingerprint = fresh.fingerprint().equals(matchedNormalized.fingerprint());
        boolean noConflicts = conflictChecker.findConflicts(fresh, matchedNormalized).isEmpty();
        if (!sameFingerprint || !noConflicts) {
            throw new UnsafeProductDecisionException(
                    "ImportRow " + row.getId() + " was matched to product " + matched.getId() + " during an "
                            + "earlier matching stage, but its normalizedData was at a stale version (" + version
                            + ", current " + RowAttributeNormalizer.NORMALIZATION_VERSION + ") and re-normalizing "
                            + "fresh from raw data no longer safely agrees with that product (fingerprint/attribute "
                            + "mismatch under the current algorithm/alias configuration) - refusing to blindly "
                            + "trust the stale decision; this row/batch requires manual review");
        }
        log.warn("ImportRow {} matched product {} had stale normalizedData version {} (current {}) but "
                        + "re-verification against fresh raw data confirms the match still safely agrees",
                row.getId(), matched.getId(), version, RowAttributeNormalizer.NORMALIZATION_VERSION);
    }

    /**
     * ADR-031 (Section 5): a stale/legacy-version {@code NormalizedRowData} must never be trusted
     * as-is for a safety-critical creation decision - its fingerprint may not even be comparable
     * against current-format catalog fingerprints. Recomputes fresh from the row's own persisted
     * raw data using the CURRENT {@link RowAttributeNormalizer}, exactly the same way a brand-new
     * row would be normalized today - never simply relabels the old value with the current
     * version number. A row with no {@code rawData} to recompute from (should not happen - raw
     * values are always persisted at parse time) falls back to the stored value unchanged; {@link
     * #checkProductCreation} still treats a resulting blank brand/fingerprint as {@code Unsafe}.
     */
    private NormalizedRowData refreshIfStale(String shopId, ImportRow row, NormalizedRowData normalized) {
        Integer version = normalized.normalizationVersion();
        if (version != null && version == RowAttributeNormalizer.NORMALIZATION_VERSION) {
            return normalized;
        }
        Map<String, String> raw = readRaw(row);
        if (raw.isEmpty()) {
            log.warn("ImportRow {} normalizedData is at version {} (current {}) but has no rawData to "
                            + "recompute from - proceeding with the stale value, which the safe-to-create "
                            + "check will treat conservatively",
                    row.getId(), version, RowAttributeNormalizer.NORMALIZATION_VERSION);
            return normalized;
        }
        log.warn("ImportRow {} reached apply with normalizedData at version {} (current {}) - recomputing "
                        + "fresh from raw data before deciding NEW_PRODUCT creation, never trusting the old "
                        + "fingerprint format as-is",
                row.getId(), version, RowAttributeNormalizer.NORMALIZATION_VERSION);
        return normalizer.normalize(shopId, raw);
    }

    /**
     * ADR-031 (Section 3): the explicit, multi-state apply-time re-verification result - see
     * {@link ProductCreationCheck}. Deliberately NOT cached across rows/calls (unlike {@code
     * DeterministicMatchResolver}'s batch-scoped cache during matching): this must always see the
     * CURRENT transactional state, including a product this very apply loop just inserted for an
     * earlier duplicate row, so a stale in-memory snapshot could never hide a same-batch duplicate
     * from itself. NEW_PRODUCT rows are the minority of an apply batch, so the extra per-row query
     * cost here is bounded.
     */
    private ProductCreationCheck checkProductCreation(String shopId, NormalizedRowData normalized) {
        if (normalized.brand() == null || normalized.brand().isBlank()
                || normalized.fingerprint() == null || normalized.fingerprint().isBlank()) {
            return new ProductCreationCheck.Unsafe(
                    "row has no usable brand+fingerprint - the required exact-identity catalog check could not run");
        }
        Set<String> brandTokens = brandAliasResolver.expand(shopId, normalized.brand());
        if (brandTokens.isEmpty()) {
            return new ProductCreationCheck.Unsafe("brand could not be scoped for the exact-identity catalog check");
        }
        List<Product> matches = productRepository.findAllByShopIdAndBrandTokenIn(shopId, brandTokens).stream()
                .filter(p -> conflictChecker.findConflicts(normalized, normalizer.normalizeProduct(shopId, p)).isEmpty())
                .filter(p -> normalized.fingerprint().equals(normalizer.normalizeProduct(shopId, p).fingerprint()))
                .toList();
        if (matches.isEmpty()) {
            return new ProductCreationCheck.SafeToCreate();
        }
        if (matches.size() > 1) {
            return new ProductCreationCheck.Ambiguous(matches,
                    matches.size() + " existing products share an identical structural fingerprint with this row");
        }
        return new ProductCreationCheck.ExistingMatch(matches.get(0));
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
        // ADR-030: `normalized` was just computed fresh by the current RowAttributeNormalizer, so
        // it always carries the CURRENT algorithm version - a link written here is never "stale",
        // only links persisted before this versioning existed need the separate backfill service.
        link.setNormalizationVersion(normalized.normalizationVersion() != null
                ? normalized.normalizationVersion() : RowAttributeNormalizer.NORMALIZATION_VERSION);
        if (link.getConfirmedSource() == null) {
            link.setConfirmedSource(LinkConfirmationSource.AUTOMATIC);
        }
        link.setConfirmedAt(LocalDateTime.now());
        supplierProductLinkRepository.save(link);
    }

    /**
     * Every row's raw cell values are persisted at parse time regardless of the row's final status
     * (see {@code ImportBatchParseWriter#finalizeSuccess} / {@code SpreadsheetParser}) - an
     * {@code INVALID} row (e.g. bad price) still carries its raw {@code externalSku}/{@code barcode}
     * if the file had one. This scans every row of the batch, not just the appliable ones, so a
     * product that genuinely appears in the file but whose row failed processing is not confused
     * with a product that is truly absent from a FULL snapshot.
     */
    private Set<String> collectIdentifiersPresentInBatch(Long batchId) {
        Set<String> identifiers = new LinkedHashSet<>();
        for (ImportRow row : importRowRepository.findByImportBatchId(batchId)) {
            Map<String, String> raw = readRaw(row);
            String externalSku = raw.get(LayoutRuleDefinition.FIELD_EXTERNAL_SKU);
            String barcode = raw.get(LayoutRuleDefinition.FIELD_BARCODE);
            if (externalSku != null && !externalSku.isBlank()) {
                identifiers.add(externalSku);
            }
            if (barcode != null && !barcode.isBlank()) {
                identifiers.add(barcode);
            }
        }
        return identifiers;
    }

    private boolean isReferencedByIdentifier(SupplierOffer offer, Set<String> identifiersPresentInBatch) {
        return (offer.getExternalSku() != null && identifiersPresentInBatch.contains(offer.getExternalSku()))
                || (offer.getBarcode() != null && identifiersPresentInBatch.contains(offer.getBarcode()));
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
