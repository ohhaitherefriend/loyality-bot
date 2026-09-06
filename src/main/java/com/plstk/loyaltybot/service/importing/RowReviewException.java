package com.plstk.loyaltybot.service.importing;

/**
 * Validation failure for a Prompt 07 human row-review action (bad request, target state, or
 * missing data required by that action) - distinct from {@link RowVersionConflictException}
 * (optimistic-lock / stale-client conflict, HTTP 409) so the controller can map the two to
 * different status codes.
 */
public class RowReviewException extends RuntimeException {
    public RowReviewException(String message) {
        super(message);
    }
}
