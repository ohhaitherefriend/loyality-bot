package com.plstk.loyaltybot.service.importing;

/**
 * ADR-031 (Section 3): thrown from inside {@code ImportBatchApplyWriter#applyBatch}'s single
 * transaction when a {@code NEW_PRODUCT} row's apply-time re-verification comes back {@code
 * Ambiguous} or {@code Unsafe} (see {@link ProductCreationCheck}). Deliberately an unchecked
 * exception with no special handling INSIDE {@code applyBatch}: letting it propagate rolls back
 * every write this batch's apply attempt made so far in ONE atomic transaction (no partial
 * storefront update, no partial FULL reconciliation), and {@code ImportBatchApplyService#runApply}
 * catches it exactly like any other unexpected apply error, recording the stop reason via {@code
 * ImportBatchApplyWriter#finalizeFailed} in a SEPARATE, correct transaction.
 */
public class UnsafeProductDecisionException extends RuntimeException {

    public UnsafeProductDecisionException(String message) {
        super(message);
    }
}
