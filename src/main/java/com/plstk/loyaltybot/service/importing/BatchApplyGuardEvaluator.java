package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.SnapshotMode;
import com.plstk.loyaltybot.entity.importing.SupplierOffer;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.SupplierOfferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Batch-level apply guards (Prompt 06, D-012, docs/ARCHITECTURE.md §14.2): evaluated once, when a
 * batch reaches {@code VALIDATING}, before {@link ImportBatchApplyService} is ever allowed to touch
 * a single {@code SupplierOffer}. Every check here is defense against a corrupted or truncated file
 * silently wiping out or mispricing the assortment - none of them second-guess row-level match
 * quality (that is Prompt 05's job), only aggregate batch shape/price sanity.
 */
@Component
@RequiredArgsConstructor
public class BatchApplyGuardEvaluator {

    private final ImportBatchRepository importBatchRepository;
    private final SupplierOfferRepository supplierOfferRepository;
    private final SupplierImportProperties properties;
    private final ObjectMapper objectMapper;

    public GuardResult evaluate(ImportBatch batch, List<ImportRow> appliableRows) {
        SupplierSource source = batch.getSupplierSource();
        GuardResult.Builder builder = new GuardResult.Builder();

        boolean isFull = source.getSnapshotMode() == SnapshotMode.FULL;

        // An empty FULL snapshot would deactivate every currently-active offer in scope - the
        // single most dangerous failure mode this guard set exists to prevent
        // ("Пустой... файл не может массово убрать товары с сайта").
        builder.failIf(isFull && appliableRows.isEmpty(),
                "FULL snapshot has zero appliable rows - refusing to deactivate the entire scope");

        if (isFull) {
            checkRowCountCollapse(batch, source, appliableRows.size(), builder);
        }

        checkDuplicateExplosion(appliableRows, builder);
        checkPriceDeltas(batch, appliableRows, builder);

        return builder.build();
    }

    /**
     * Compares like-for-like: {@code currentAppliableCount} is post-matching (only rows that
     * actually reached {@code AUTO_APPROVED}/{@code APPROVED}), so the baseline must be the
     * previous applied batch's own appliable count too - {@code offersAddedCount +
     * offersUpdatedCount} (every applied row created exactly one of the two, see {@code
     * ImportBatchApplyWriter#applyBatch}) - not its parse-stage {@code validRows}. Comparing against
     * {@code validRows} mixes two different metrics (rows that merely parsed vs. rows that were
     * actually safe to auto-apply) and produces spurious pass/fail decisions whenever the
     * match/gate ambiguity rate differs between the two batches even though nothing about the file
     * itself collapsed.
     */
    private void checkRowCountCollapse(
            ImportBatch batch, SupplierSource source, int currentAppliableCount, GuardResult.Builder builder) {
        Optional<ImportBatch> previous = importBatchRepository
                .findTopByShopIdAndSupplierSourceIdAndStatusAndIdNotOrderByFinishedAtDesc(
                        batch.getShopId(), source.getId(), ImportBatchStatus.APPLIED, batch.getId());
        if (previous.isEmpty()) {
            return;
        }
        Integer previousAdded = previous.get().getOffersAddedCount();
        Integer previousUpdated = previous.get().getOffersUpdatedCount();
        if (previousAdded == null || previousUpdated == null) {
            return;
        }
        int previousAppliableCount = previousAdded + previousUpdated;
        if (previousAppliableCount <= 0) {
            return;
        }
        double minRatio = properties.getReconciliation().getRowCountCollapseMinRatio();
        double actualRatio = currentAppliableCount / (double) previousAppliableCount;
        builder.failIf(actualRatio < minRatio, String.format(
                "Row-count collapse: %d appliable row(s) vs %d applied in the previous applied batch (ratio %.2f < min %.2f)",
                currentAppliableCount, previousAppliableCount, actualRatio, minRatio));
    }

    private void checkDuplicateExplosion(List<ImportRow> appliableRows, GuardResult.Builder builder) {
        Map<String, Integer> identifierCounts = new HashMap<>();
        int rowsWithIdentifier = 0;
        for (ImportRow row : appliableRows) {
            NormalizedRowData normalized = readNormalized(row);
            if (normalized == null) {
                continue;
            }
            String identifier = normalized.externalSku() != null ? "sku:" + normalized.externalSku()
                    : normalized.barcode() != null ? "barcode:" + normalized.barcode()
                    : null;
            if (identifier == null) {
                continue;
            }
            rowsWithIdentifier++;
            identifierCounts.merge(identifier, 1, Integer::sum);
        }
        if (rowsWithIdentifier == 0) {
            return;
        }
        int duplicates = identifierCounts.values().stream()
                .mapToInt(count -> Math.max(0, count - 1))
                .sum();
        double ratio = duplicates / (double) rowsWithIdentifier;
        double maxRatio = properties.getReconciliation().getDuplicateIdentifierMaxRatio();
        builder.failIf(ratio > maxRatio, String.format(
                "Duplicate identifier explosion: %d duplicate(s) among %d identified row(s) (ratio %.2f > max %.2f)",
                duplicates, rowsWithIdentifier, ratio, maxRatio));
    }

    private void checkPriceDeltas(ImportBatch batch, List<ImportRow> appliableRows, GuardResult.Builder builder) {
        String shopId = batch.getShopId();
        Long supplierId = batch.getSupplierSource().getSupplier().getId();
        String snapshotScope = batch.getSupplierSource().getSnapshotScope();
        int comparedRows = 0;
        int anomalousRows = 0;
        double warnRatio = properties.getReconciliation().getPriceDeltaWarnRatio();

        for (ImportRow row : appliableRows) {
            if (row.getMatchedProduct() == null) {
                continue; // NEW_PRODUCT rows have nothing existing to compare against.
            }
            NormalizedRowData normalized = readNormalized(row);
            if (normalized == null || normalized.supplierPrice() == null) {
                continue;
            }
            Optional<SupplierOffer> existing = supplierOfferRepository
                    .findByShopIdAndSupplierIdAndSnapshotScopeAndProductId(
                            shopId, supplierId, snapshotScope, row.getMatchedProduct().getId());
            if (existing.isEmpty()) {
                continue; // first offer for this product/supplier - no prior price to compare.
            }
            BigDecimal oldPrice = existing.get().getSupplierPrice();
            if (oldPrice == null || oldPrice.signum() == 0) {
                continue;
            }
            comparedRows++;
            BigDecimal delta = normalized.supplierPrice().subtract(oldPrice).abs();
            double ratio = delta.divide(oldPrice, 10, java.math.RoundingMode.HALF_UP).doubleValue();
            if (ratio > warnRatio) {
                anomalousRows++;
            }
        }
        if (comparedRows == 0) {
            return;
        }
        double anomalousRatio = anomalousRows / (double) comparedRows;
        double maxRatio = properties.getReconciliation().getPriceDeltaMaxAnomalousRowRatio();
        builder.failIf(anomalousRatio > maxRatio, String.format(
                "Anomalous price delta: %d/%d compared row(s) changed by more than %.0f%% (ratio %.2f > max %.2f)",
                anomalousRows, comparedRows, warnRatio * 100, anomalousRatio, maxRatio));
    }

    private NormalizedRowData readNormalized(ImportRow row) {
        String json = row.getNormalizedData();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, NormalizedRowData.class);
        } catch (Exception e) {
            return null;
        }
    }
}
