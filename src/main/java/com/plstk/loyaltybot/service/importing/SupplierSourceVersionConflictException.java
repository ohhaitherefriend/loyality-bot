package com.plstk.loyaltybot.service.importing;

/**
 * The client's {@code expectedVersion} no longer matches the source's current
 * {@link com.plstk.loyaltybot.entity.importing.SupplierSource#getVersion()} - mirrors
 * {@link RowVersionConflictException}'s reasoning, applied to the Stage 1 {@code PATCH
 * supplier-sources/{id}} / {@code graduate} endpoints.
 */
public class SupplierSourceVersionConflictException extends RuntimeException {
    private final Long currentVersion;

    public SupplierSourceVersionConflictException(Long currentVersion) {
        super("Supplier source was modified concurrently (expected a different version) - reload and retry");
        this.currentVersion = currentVersion;
    }

    public Long getCurrentVersion() {
        return currentVersion;
    }
}
