package com.plstk.loyaltybot.service.importing;

import java.util.List;

/**
 * Outcome of {@link LayoutRuleValidator#validate}. {@code rule} is non-null only when {@code valid}
 * is true.
 */
public record LayoutRuleValidationResult(
        boolean valid,
        LayoutRuleDefinition rule,
        List<String> errors) {

    public static LayoutRuleValidationResult valid(LayoutRuleDefinition rule) {
        return new LayoutRuleValidationResult(true, rule, List.of());
    }

    public static LayoutRuleValidationResult invalid(List<String> errors) {
        return new LayoutRuleValidationResult(false, null, errors);
    }

    public static LayoutRuleValidationResult invalid(String error) {
        return invalid(List.of(error));
    }
}
