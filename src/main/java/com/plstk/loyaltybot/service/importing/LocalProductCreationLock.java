package com.plstk.loyaltybot.service.importing;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * JVM-local fallback {@link ProductCreationLock}, selected for H2 (dev/test) where {@code
 * pg_advisory_xact_lock} does not exist - see {@code ProductCreationLockConfig}. Correct for a
 * SINGLE application instance (which is what dev/test always is), but deliberately NOT a
 * cross-instance guarantee - a real multi-instance production deployment must run on PostgreSQL
 * (already the documented prod requirement, see docs/DECISIONS.md) so {@link
 * PostgresAdvisoryProductCreationLock} is selected instead.
 */
@Component
public class LocalProductCreationLock implements ProductCreationLock {

    private final ConcurrentHashMap<String, ReentrantLock> locksByShop = new ConcurrentHashMap<>();

    @Override
    public <T> T withLock(String shopId, Supplier<T> criticalSection) {
        ReentrantLock lock = locksByShop.computeIfAbsent(shopId, k -> new ReentrantLock());
        lock.lock();
        try {
            return criticalSection.get();
        } finally {
            lock.unlock();
        }
    }
}
