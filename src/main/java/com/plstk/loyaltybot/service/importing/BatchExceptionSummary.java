package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;

import java.time.LocalDateTime;

/**
 * One batch-level entry of the Prompt 07 unified exception queue (docs/ARCHITECTURE.md §12):
 * {@code QUARANTINED} (includes suspicious price/row-count/schema-change guard failures, whose
 * reason text lives in {@code errorMessage} - see {@link BatchApplyGuardEvaluator}), {@code FAILED},
 * and {@code NEEDS_ATTENTION} (fully decided, apply withheld pending operator approval).
 */
public record BatchExceptionSummary(
        Long batchId,
        ImportBatchStatus status,
        Long supplierSourceId,
        String supplierSourceLabel,
        Long supplierId,
        String supplierName,
        String originalFilename,
        Integer totalRows,
        Integer validRows,
        Integer invalidRows,
        Integer attemptNumber,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime finishedAt) {
}
