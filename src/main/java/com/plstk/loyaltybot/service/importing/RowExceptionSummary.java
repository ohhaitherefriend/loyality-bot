package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportRowStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row-level entry of the Prompt 07 unified exception queue (docs/ARCHITECTURE.md §12):
 * {@code NEEDS_REVIEW}/{@code INVALID} rows across every batch of this shop. Deliberately a flat
 * list preview (raw name/brand/price only) - the full raw/normalized/candidates/audit payload is
 * {@code ImportBatchDetailService#getRowDetail}'s job, fetched only when an operator opens one row.
 */
public record RowExceptionSummary(
        Long rowId,
        Long version,
        Long batchId,
        Long supplierSourceId,
        String supplierSourceLabel,
        Long supplierId,
        String supplierName,
        String sourceSheet,
        Integer sourceRowNumber,
        ImportRowStatus status,
        String rawNamePreview,
        String brandPreview,
        BigDecimal supplierPricePreview,
        LocalDateTime createdAt) {
}
