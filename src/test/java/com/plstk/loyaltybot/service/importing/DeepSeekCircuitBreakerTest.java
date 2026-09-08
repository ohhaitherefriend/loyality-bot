package com.plstk.loyaltybot.service.importing;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link DeepSeekCircuitBreaker} state transitions (Stage 5 hardening). */
class DeepSeekCircuitBreakerTest {

    private final DeepSeekCircuitBreaker breaker = new DeepSeekCircuitBreaker();

    @Test
    void startsClosed_allowsCalls() {
        assertEquals(DeepSeekCircuitBreaker.State.CLOSED, breaker.getState());
        assertTrue(breaker.tryAcquirePermission(30_000));
    }

    @Test
    void staysClosedBelowFailureThreshold() {
        breaker.tryAcquirePermission(30_000);
        breaker.recordFailure(3);
        breaker.tryAcquirePermission(30_000);
        breaker.recordFailure(3);

        assertEquals(DeepSeekCircuitBreaker.State.CLOSED, breaker.getState());
        assertTrue(breaker.tryAcquirePermission(30_000));
    }

    @Test
    void opensAfterConsecutiveFailuresReachThreshold_andFailsFastWhileOpen() {
        for (int i = 0; i < 3; i++) {
            breaker.tryAcquirePermission(30_000);
            breaker.recordFailure(3);
        }

        assertEquals(DeepSeekCircuitBreaker.State.OPEN, breaker.getState());
        assertFalse(breaker.tryAcquirePermission(30_000), "circuit is open, cooldown not elapsed");
    }

    @Test
    void successResetsConsecutiveFailureCount() {
        breaker.tryAcquirePermission(30_000);
        breaker.recordFailure(3);
        breaker.tryAcquirePermission(30_000);
        breaker.recordFailure(3);
        breaker.tryAcquirePermission(30_000);
        breaker.recordSuccess();

        // Two prior failures were reset by the success; two more failures must not open the circuit.
        breaker.tryAcquirePermission(30_000);
        breaker.recordFailure(3);
        breaker.tryAcquirePermission(30_000);
        breaker.recordFailure(3);

        assertEquals(DeepSeekCircuitBreaker.State.CLOSED, breaker.getState());
    }

    @Test
    void allowsHalfOpenTrialAfterCooldownElapses_closesOnSuccess() {
        for (int i = 0; i < 3; i++) {
            breaker.tryAcquirePermission(0); // cooldown 0 -> open then immediately eligible again
            breaker.recordFailure(3);
        }
        assertEquals(DeepSeekCircuitBreaker.State.OPEN, breaker.getState());

        assertTrue(breaker.tryAcquirePermission(0), "cooldown elapsed (0ms) -> one half-open trial allowed");
        assertEquals(DeepSeekCircuitBreaker.State.HALF_OPEN, breaker.getState());

        // A second concurrent caller must not get its own trial while one is already in flight.
        assertFalse(breaker.tryAcquirePermission(0));

        breaker.recordSuccess();
        assertEquals(DeepSeekCircuitBreaker.State.CLOSED, breaker.getState());
        assertTrue(breaker.tryAcquirePermission(30_000));
    }

    @Test
    void halfOpenTrialFailure_reopensCircuit() {
        for (int i = 0; i < 3; i++) {
            breaker.tryAcquirePermission(0);
            breaker.recordFailure(3);
        }
        assertTrue(breaker.tryAcquirePermission(0));
        assertEquals(DeepSeekCircuitBreaker.State.HALF_OPEN, breaker.getState());

        breaker.recordFailure(3);

        assertEquals(DeepSeekCircuitBreaker.State.OPEN, breaker.getState());
        assertFalse(breaker.tryAcquirePermission(30_000));
    }

    @Test
    void abortTrial_releasesHalfOpenSlotWithoutClosingOrReopening() {
        for (int i = 0; i < 3; i++) {
            breaker.tryAcquirePermission(0);
            breaker.recordFailure(3);
        }
        assertTrue(breaker.tryAcquirePermission(0));
        assertEquals(DeepSeekCircuitBreaker.State.HALF_OPEN, breaker.getState());

        breaker.abortTrial();

        assertEquals(DeepSeekCircuitBreaker.State.HALF_OPEN, breaker.getState());
        assertTrue(breaker.tryAcquirePermission(0), "trial slot must be free again after abort");
    }

    @Test
    void withMeterRegistry_registersStateGaugeReflectingCurrentState() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeepSeekCircuitBreaker instrumented = new DeepSeekCircuitBreaker(registry);

        assertEquals(0.0, registry.get("supplier_import_deepseek_circuit_breaker_state").gauge().value());

        for (int i = 0; i < 3; i++) {
            instrumented.tryAcquirePermission(30_000);
            instrumented.recordFailure(3);
        }
        assertEquals(DeepSeekCircuitBreaker.State.OPEN, instrumented.getState());
        assertEquals(1.0, registry.get("supplier_import_deepseek_circuit_breaker_state").gauge().value());
    }

    @Test
    void withNullMeterRegistry_stillWorksWithoutRegisteringGauge() {
        DeepSeekCircuitBreaker instrumented = new DeepSeekCircuitBreaker(null);
        assertEquals(DeepSeekCircuitBreaker.State.CLOSED, instrumented.getState());
        assertTrue(instrumented.tryAcquirePermission(30_000));
    }
}
