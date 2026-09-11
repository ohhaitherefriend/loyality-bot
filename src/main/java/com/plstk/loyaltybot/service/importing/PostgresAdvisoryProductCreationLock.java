package com.plstk.loyaltybot.service.importing;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * ADR-031 (Section 4): cross-instance implementation of {@link ProductCreationLock} using
 * PostgreSQL's transaction-scoped advisory lock ({@code pg_advisory_xact_lock}), keyed by a hash
 * of {@code shopId}. Chosen over a "canonical-identity registry + unique constraint" alternative
 * because:
 * <ul>
 *   <li>it needs NO schema migration and no new table - a unique-constraint-based registry would
 *       require deciding, and migrating, how EVERY historical/legacy row that might already
 *       violate that uniqueness is handled first (explicitly forbidden without a migration
 *       strategy per the report);</li>
 *   <li>it naturally covers EVERY current and future product-creation code path inside one
 *       apply transaction with a single acquisition point ({@code ImportBatchApplyWriter#applyBatch}
 *       acquires it once, before touching any row), rather than requiring every INSERT call site to
 *       remember to also write/check a registry row;</li>
 *   <li>{@code pg_advisory_xact_lock} is automatically released on COMMIT or ROLLBACK of the
 *       holding transaction - no separate unlock code path to forget, and safe against a crashed
 *       connection (the lock releases the moment the underlying DB session ends).</li>
 * </ul>
 *
 * <p>Blocks (waits) until the lock is free, so a second concurrent apply for the SAME shop is
 * genuinely serialized at the database - not merely "checked and hoped" - until the first
 * transaction commits or rolls back; the second call then re-observes the database with its OWN
 * fresh queries (the re-check the report requires), executed AFTER acquiring this lock.
 */
@Component
public class PostgresAdvisoryProductCreationLock implements ProductCreationLock {

    private final EntityManager entityManager;

    public PostgresAdvisoryProductCreationLock(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public <T> T withLock(String shopId, Supplier<T> criticalSection) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:lockKey))")
                .setParameter("lockKey", "supplier-import:product-creation:" + shopId)
                .getSingleResult();
        return criticalSection.get();
    }
}
