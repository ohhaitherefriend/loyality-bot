package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.RuleVersionStatus;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Batch-level detail page (docs/ARCHITECTURE.md §12): identity, pipeline progress, and the apply
 * "diff" for a batch that reached {@code APPLIED} - the added/updated/priceChanged/removed/
 * reactivated counters already computed by {@link ImportBatchApplyWriter} are exactly the "batch
 * diff" the operator wants without recomputing anything.
 */
public record BatchDetailResponse(
        Long batchId,
        ImportBatchStatus status,
        Long supplierSourceId,
        String supplierSourceLabel,
        Long supplierId,
        String supplierName,
        String originalFilename,
        Long fileSizeBytes,
        LocalDateTime fileReceivedAt,
        Long ruleVersionId,
        Integer ruleVersionNumber,
        RuleVersionStatus ruleVersionStatus,
        Integer totalRows,
        Integer validRows,
        Integer invalidRows,
        Integer attemptNumber,
        String errorMessage,
        Map<String, Long> rowStatusCounts,
        Integer offersAddedCount,
        Integer offersUpdatedCount,
        Integer offersPriceChangedCount,
        Integer offersUnchangedCount,
        Integer productsRemovedFromStorefrontCount,
        Integer productsReactivatedCount,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime appliedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
