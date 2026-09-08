package com.plstk.loyaltybot.service.importing;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validation failure for a {@code PATCH}/{@code graduate} {@link
 * com.plstk.loyaltybot.entity.importing.SupplierSource} request (Stage 1 of the production
 * hardening pass). Carries per-field errors so the controller can return an actionable JSON body
 * instead of an empty {@code 400} - the requirement explicitly calls out "ошибки валидации
 * возвращают понятный JSON, а не пустой 400".
 */
public class SupplierSourceValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public SupplierSourceValidationException(String message) {
        super(message);
        this.fieldErrors = Map.of();
    }

    public SupplierSourceValidationException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = fieldErrors;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Accumulates every violated field before throwing, so the client sees all problems at once. */
    public static final class Builder {
        private final Map<String, String> errors = new LinkedHashMap<>();

        public Builder rejectIf(boolean condition, String field, String message) {
            if (condition) {
                errors.put(field, message);
            }
            return this;
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public void throwIfInvalid() {
            if (hasErrors()) {
                throw new SupplierSourceValidationException(
                        "Validation failed: " + String.join(", ", errors.values()), errors);
            }
        }
    }
}
