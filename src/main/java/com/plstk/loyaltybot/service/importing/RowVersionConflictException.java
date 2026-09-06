package com.plstk.loyaltybot.service.importing;

/**
 * The client's {@code expectedVersion} no longer matches the row's current
 * {@link com.plstk.loyaltybot.entity.importing.ImportRow#getVersion()} (docs/ARCHITECTURE.md §12
 * "Используй optimistic locking"): either another operator already decided this row, or the
 * automatic pipeline moved it on. The caller must re-fetch the row and retry with the fresh state
 * rather than blindly overwriting a decision it never saw.
 */
public class RowVersionConflictException extends RuntimeException {
    private final Long currentVersion;

    public RowVersionConflictException(Long currentVersion) {
        super("Row was modified concurrently (expected a different version) - reload and retry");
        this.currentVersion = currentVersion;
    }

    public Long getCurrentVersion() {
        return currentVersion;
    }
}
