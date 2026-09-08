package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.PriceRoundingPolicy;
import com.plstk.loyaltybot.entity.importing.PublicPriceStrategy;
import com.plstk.loyaltybot.entity.importing.SnapshotMode;

import java.math.BigDecimal;

/**
 * Stage 1 {@code PATCH /api/shops/{shopId}/supplier-sources/{sourceId}} request body. Every field
 * is optional (merge-patch semantics): a {@code null} field means "leave unchanged", except the
 * explicit {@code clear*} flags which let the caller null out a nullable override on purpose (see
 * {@code SupplierSourceAdminService#updateSource}).
 */
public record UpdateSupplierSourceRequest(
        Long expectedVersion,
        String label,
        Long mailboxConnectionId,
        Boolean clearMailboxConnectionId,
        String senderAllowlist,
        String subjectPattern,
        String filenamePattern,
        Boolean enabled,
        SnapshotMode snapshotMode,
        String snapshotScope,
        BigDecimal commissionPercentOverride,
        Boolean clearCommissionPercentOverride,
        PriceRoundingPolicy roundingPolicy,
        PublicPriceStrategy publicPriceStrategy,
        Boolean shadowMode,
        Boolean autoApply,
        /** Required to be {@code true} in the same request that flips {@code autoApply} false -> true. */
        Boolean confirmAutoApply,
        BigDecimal aiAutoApproveMinScoreOverride,
        Boolean clearAiAutoApproveMinScoreOverride,
        BigDecimal aiMinConfidenceOverride,
        Boolean clearAiMinConfidenceOverride) {
}
