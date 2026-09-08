package com.plstk.loyaltybot.service.importing;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * JVM-wide circuit breaker for the DeepSeek account shared by
 * {@link DeepSeekCatalogMatcher}/{@link DeepSeekSpreadsheetLayoutDetector} (Stage 5 hardening).
 * When DeepSeek is down/misconfigured, retrying every single row/batch call still burns its full
 * retry budget (see docs/DECISIONS.md ADR-005 §5) — this breaker fails fast instead once a
 * threshold of consecutive failures is reached, and periodically lets one trial call through to
 * detect recovery without a restart.
 *
 * <p>Deliberately a single singleton bean (not per-provider-call-site): layout detection and
 * catalog matching hit the same DeepSeek account/rate limit, so a failure in one is a signal about
 * the other too.
 */
@Component
@Slf4j
public class DeepSeekCircuitBreaker {

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private State state = State.CLOSED;
    private long openedAtMs;
    private boolean halfOpenTrialInFlight;

    public DeepSeekCircuitBreaker() {
        this(null);
    }

    /**
     * {@code registry} is {@code @Nullable} (rather than required) purely so plain unit tests can
     * keep using {@code new DeepSeekCircuitBreaker()} without a Micrometer dependency; Spring
     * always supplies the auto-configured {@link MeterRegistry} in the real app so the
     * {@code supplier_import_deepseek_circuit_breaker_state} gauge (Stage 10, see
     * docs/monitoring/prometheus-alerts.yml) is always registered in production.
     */
    @Autowired
    public DeepSeekCircuitBreaker(@Nullable MeterRegistry registry) {
        if (registry != null) {
            Gauge.builder("supplier_import_deepseek_circuit_breaker_state", this,
                            cb -> switch (cb.getState()) {
                                case CLOSED -> 0;
                                case OPEN -> 1;
                                case HALF_OPEN -> 2;
                            })
                    .description("DeepSeek circuit breaker state: 0=CLOSED, 1=OPEN, 2=HALF_OPEN")
                    .register(registry);
        }
    }

    /**
     * Must be called before attempting a call. Returns {@code true} when the call may proceed
     * (CLOSED, or the single allowed HALF_OPEN trial), {@code false} when the caller must fail fast
     * without touching the network. Every {@code true} result must eventually be paired with
     * {@link #recordSuccess()}, {@link #recordFailure(int)}, or {@link #abortTrial()}.
     */
    public synchronized boolean tryAcquirePermission(long openDurationMs) {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (System.currentTimeMillis() - openedAtMs < openDurationMs) {
                    return false;
                }
                log.info("DeepSeek circuit breaker cooldown elapsed, allowing one half-open trial call");
                state = State.HALF_OPEN;
                halfOpenTrialInFlight = true;
                return true;
            case HALF_OPEN:
                if (halfOpenTrialInFlight) {
                    return false;
                }
                halfOpenTrialInFlight = true;
                return true;
            default:
                return false;
        }
    }

    /** Call succeeded: closes the circuit and resets the failure count. */
    public synchronized void recordSuccess() {
        if (state != State.CLOSED) {
            log.info("DeepSeek circuit breaker closing after successful call (was {})", state);
        }
        state = State.CLOSED;
        halfOpenTrialInFlight = false;
        consecutiveFailures.set(0);
    }

    /** Call failed (after exhausting its own retries). May open the circuit. */
    public synchronized void recordFailure(int failureThreshold) {
        halfOpenTrialInFlight = false;
        if (state == State.HALF_OPEN) {
            log.warn("DeepSeek circuit breaker re-opening after failed half-open trial call");
            open();
            return;
        }
        int failures = consecutiveFailures.incrementAndGet();
        if (state == State.CLOSED && failures >= Math.max(1, failureThreshold)) {
            log.warn("DeepSeek circuit breaker opening after {} consecutive failures", failures);
            open();
        }
    }

    /**
     * The permission granted by {@link #tryAcquirePermission} was never used (e.g. a local
     * concurrency limit was hit before the HTTP call happened) — releases the half-open trial slot
     * without counting it as a success or failure against DeepSeek itself.
     */
    public synchronized void abortTrial() {
        halfOpenTrialInFlight = false;
    }

    private void open() {
        state = State.OPEN;
        openedAtMs = System.currentTimeMillis();
        consecutiveFailures.set(0);
    }

    public synchronized State getState() {
        return state;
    }

    /** Consecutive failures accumulated so far towards the open threshold; 0 once OPEN/HALF_OPEN. */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /** Millis since the circuit last opened; meaningless (0) while CLOSED and never yet opened. */
    public synchronized long getMillisSinceOpened() {
        return openedAtMs == 0 ? 0 : System.currentTimeMillis() - openedAtMs;
    }
}
