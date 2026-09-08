package com.plstk.loyaltybot.service.importing;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Stage 10 ({@code /actuator/health/supplierImport}): exposes the DeepSeek circuit breaker state
 * as an operational signal, without ever failing overall app health/readiness because of it.
 *
 * <p>Deliberately always UP (see {@link #health()}): the DeepSeek account being
 * degraded or unconfigured must never take the whole app "not ready" - Zabotik's own rule is that
 * import automation, catalog matching AI, and image search are all optional and the app "must
 * start even if image search and AI providers are disabled" (see workspace rules). An open circuit
 * here means "supplier-import AI matching is temporarily degraded", not "the app is unhealthy" -
 * an operator watching the {@code supplier_import_deepseek_circuit_breaker_state} gauge (registered
 * directly by {@link DeepSeekCircuitBreaker}) in Prometheus (see
 * {@code docs/monitoring/prometheus-alerts.yml}, ADR-018) is the intended alerting path for this,
 * not a failing health/readiness probe that would pull the whole instance out of a load balancer.
 * This indicator's details are for human debugging via {@code /actuator/health} when
 * {@code show-details} allows it, not for machine alerting.</p>
 */
@Component("supplierImport")
@RequiredArgsConstructor
public class SupplierImportHealthIndicator implements HealthIndicator {

    private final DeepSeekCircuitBreaker circuitBreaker;

    @Override
    public Health health() {
        DeepSeekCircuitBreaker.State state = circuitBreaker.getState();
        Health.Builder builder = Health.up()
                .withDetail("deepSeekCircuitBreaker", state.name());
        if (state == DeepSeekCircuitBreaker.State.CLOSED) {
            builder.withDetail("deepSeekConsecutiveFailures", circuitBreaker.getConsecutiveFailures());
        } else {
            builder.withDetail("deepSeekOpenForMs", circuitBreaker.getMillisSinceOpened());
        }
        return builder.build();
    }
}
