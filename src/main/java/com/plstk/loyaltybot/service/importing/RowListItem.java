package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportRowStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One row of a batch detail page's row table (docs/ARCHITECTURE.md §12) - any status, not just exceptions. */
public record RowListItem(
        Long rowId,
        Long version,
        String sourceSheet,
        Integer sourceRowNumber,
        ImportRowStatus status,
        String rawNamePreview,
        String brandPreview,
        BigDecimal supplierPricePreview,
        Long matchedProductId,
        String matchedProductName,
        LocalDateTime createdAt) {
}
