package com.plstk.loyaltybot.service.importing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 10 (docs/DECISIONS.md ADR-018): {@link SupplierImportHealthIndicator} must always report
 * {@code UP}, regardless of the DeepSeek circuit breaker's state - a degraded/open circuit is an
 * operational signal for the Prometheus gauge/alerts, never a reason to fail readiness/liveness.
 */
class SupplierImportHealthIndicatorTest {

    @Test
    void circuitClosed_reportsUpWithConsecutiveFailureDetail() {
        DeepSeekCircuitBreaker breaker = new DeepSeekCircuitBreaker();
        SupplierImportHealthIndicator indicator = new SupplierImportHealthIndicator(breaker);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("CLOSED", health.getDetails().get("deepSeekCircuitBreaker"));
        assertTrue(health.getDetails().containsKey("deepSeekConsecutiveFailures"));
    }

    @Test
    void circuitOpen_stillReportsUp_withOpenDurationDetailInstead() {
        DeepSeekCircuitBreaker breaker = new DeepSeekCircuitBreaker();
        for (int i = 0; i < 5; i++) {
            breaker.tryAcquirePermission(30_000);
            breaker.recordFailure(5);
        }
        SupplierImportHealthIndicator indicator = new SupplierImportHealthIndicator(breaker);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus(), "an open DeepSeek circuit must never fail overall app health");
        assertEquals("OPEN", health.getDetails().get("deepSeekCircuitBreaker"));
        assertTrue(health.getDetails().containsKey("deepSeekOpenForMs"));
    }
}
