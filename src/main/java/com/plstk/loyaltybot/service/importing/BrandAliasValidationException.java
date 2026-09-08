package com.plstk.loyaltybot.service.importing;

import java.util.Map;

/** Validation failure for a {@code BrandAlias} create request (Stage 4 of the production-hardening pass). */
public class BrandAliasValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public BrandAliasValidationException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = fieldErrors;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
