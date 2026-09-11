package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.commerce.Product;

import java.util.List;

/**
 * ADR-031 (Section 3): the EXPLICIT, multi-state result of apply-time re-verification before a
 * {@code NEW_PRODUCT} row is either created or reused ({@code ImportBatchApplyWriter#resolveProduct}).
 * Deliberately NOT an {@code Optional<Product>} - the original design conflated "zero existing
 * matches, safe to create" and "more than one existing match, NOT safe to create" into the exact
 * same {@code Optional.empty()} value (Section 1 scenario B: a genuine ambiguity silently became
 * "go ahead and create a third product"). Every state below must be handled explicitly by the
 * caller; there is no default/implicit "otherwise safe to create" fallthrough.
 */
public sealed interface ProductCreationCheck {

    /** Exactly one existing product structurally matches this row - reuse it, never create a new one. */
    record ExistingMatch(Product product) implements ProductCreationCheck {
    }

    /** The search ran to completion and found zero existing matches - genuinely safe to create. */
    record SafeToCreate() implements ProductCreationCheck {
    }

    /**
     * More than one existing product structurally matches this row - a data-quality anomaly.
     * Neither auto-picking one of them NOR creating a third product is safe; the row/batch must
     * stop and go to review instead.
     */
    record Ambiguous(List<Product> candidates, String reason) implements ProductCreationCheck {
    }

    /**
     * The check could not be trusted at all - the row's stored normalization is stale (different
     * {@code normalizationVersion} than current and could not be safely recomputed), or an
     * unexpected error occurred. Never create, never reuse - the row/batch must stop and go to
     * review instead.
     */
    record Unsafe(String reason) implements ProductCreationCheck {
    }
}
