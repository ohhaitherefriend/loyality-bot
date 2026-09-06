package com.plstk.loyaltybot.service.importing;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of {@link BatchApplyGuardEvaluator#evaluate}: either the batch is safe to apply, or a
 * non-empty list of human-readable reasons explaining why it was quarantined instead
 * (docs/ARCHITECTURE.md §14.2/D-012).
 */
public record GuardResult(boolean passed, List<String> reasons) {

    public static GuardResult pass() {
        return new GuardResult(true, List.of());
    }

    public static GuardResult fail(String reason) {
        return new GuardResult(false, List.of(reason));
    }

    public static final class Builder {
        private final List<String> reasons = new ArrayList<>();

        public Builder failIf(boolean condition, String reason) {
            if (condition) {
                reasons.add(reason);
            }
            return this;
        }

        public GuardResult build() {
            return reasons.isEmpty() ? GuardResult.pass() : new GuardResult(false, List.copyOf(reasons));
        }
    }
}
