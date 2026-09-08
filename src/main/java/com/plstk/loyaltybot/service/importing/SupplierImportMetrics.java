package com.plstk.loyaltybot.service.importing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Pipeline-health counters for the supplier-import module (Prompt 09 hardening,
 * docs/ARCHITECTURE.md &sect;22). Exposed via {@code /actuator/prometheus}. Every counter here is
 * a rate signal an operator should be able to alert on without querying the DB directly:
 *
 * <ul>
 *   <li>{@code supplier_import_mailbox_poll_total{result=...}} - IMAP polling health
 *       (success/failure/skipped_claimed/skipped_disabled).</li>
 *   <li>{@code supplier_import_ai_call_total{kind=...,result=...}} - DeepSeek call health for
 *       both layout detection and catalog matching (success/retryable_failure/failure).</li>
 *   <li>{@code supplier_import_batch_validation_total{decision=...}} - how batches resolve out of
 *       {@code VALIDATING}; a rising {@code QUARANTINED} rate means guard trips are becoming
 *       routine rather than exceptional.</li>
 *   <li>{@code supplier_import_batch_apply_total{result=...}} - Apply-stage outcomes
 *       (applied/failed).</li>
 *   <li>{@code supplier_import_job_claim_total{job_type=...,result=...}} - claim contention per
 *       job type; a high {@code contended} rate for a job type that should run on one instance
 *       may indicate the lease duration is too short relative to how long a run actually takes.</li>
 *   <li>{@code supplier_import_retention_deletion_total{result=...}} - Stage 10 retention sweep
 *       outcomes (deleted/failed); a rising {@code failed} rate usually means the storage backend
 *       (S3 credentials/bucket policy, or a stale local-disk mount) needs attention.</li>
 * </ul>
 *
 * (The DeepSeek circuit breaker's state is exposed as a gauge directly by
 * {@link DeepSeekCircuitBreaker} rather than here, since a {@code MeterRegistry} is optional
 * there to keep its no-arg test constructor metrics-free.)
 *
 * Deliberately built directly on {@link MeterRegistry} (auto-configured by
 * spring-boot-starter-actuator) rather than a new abstraction - every counter is cheap,
 * dimension cardinality is small and bounded (fixed enum-like tag values), and nothing here
 * blocks or throws if metrics are unavailable in a given environment.
 */
@Component
public class SupplierImportMetrics {

    private final MeterRegistry registry;

    public SupplierImportMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void mailboxPoll(String result) {
        counter("supplier_import_mailbox_poll_total", "result", result).increment();
    }

    public void aiCall(String kind, String result) {
        counter("supplier_import_ai_call_total", "kind", kind, "result", result).increment();
    }

    public void batchValidationDecision(String decision) {
        counter("supplier_import_batch_validation_total", "decision", decision).increment();
    }

    public void batchApply(String result) {
        counter("supplier_import_batch_apply_total", "result", result).increment();
    }

    public void jobClaim(String jobType, String result) {
        counter("supplier_import_job_claim_total", "job_type", jobType, "result", result).increment();
    }

    public void retentionDeletion(String result) {
        counter("supplier_import_retention_deletion_total", "result", result).increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }
}
