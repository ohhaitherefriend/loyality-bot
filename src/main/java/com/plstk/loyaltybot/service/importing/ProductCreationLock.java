package com.plstk.loyaltybot.service.importing;

import java.util.function.Supplier;

/**
 * ADR-031 (Section 4): closes the product-creation race at the DB level. A re-{@code SELECT}
 * inside one {@code @Transactional} apply is NOT sufficient on its own - two different batches
 * (different suppliers, possibly different application instances) can both run their re-SELECT
 * and both see "does not exist yet" at the same time, before either one's {@code INSERT} commits
 * (Section 1 scenario D). {@link #withLock} must be acquired BEFORE re-checking catalog state for
 * a shop and held for the remainder of that check-and-create critical section, so a second,
 * concurrent caller for the SAME shop is forced to wait until the first one's transaction
 * commits/rolls back, and then re-observes that committed (or rolled-back) state for itself.
 *
 * <p>Implementations MUST NOT perform any network call (IMAP/AI) while the lock is held - only
 * DB reads/writes for the shop's own catalog. {@code ImportBatchApplyWriter} never calls IMAP/AI
 * during apply at all, so this is satisfied by construction for every current caller.
 */
public interface ProductCreationLock {

    /**
     * Runs {@code criticalSection} while holding an exclusive, shop-scoped lock that is visible to
     * every other caller of this method for the SAME {@code shopId} - across threads AND, for a
     * cross-instance-capable implementation, across application instances/JVMs sharing the same
     * database. Must be called from within an active database transaction so a transaction-scoped
     * lock implementation can tie the lock's lifetime to that transaction's commit/rollback.
     */
    <T> T withLock(String shopId, Supplier<T> criticalSection);
}
