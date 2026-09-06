package com.plstk.loyaltybot.service.importing;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks every claim/lease this JVM currently holds while it is actively processing (mailbox poll
 * in progress, or a batch stage's row loop running), so {@link ClaimHeartbeatSweeper} can
 * periodically renew their leases on its own independent scheduler thread/transaction.
 *
 * <p>Without this, every {@code @Scheduled} job claims a lease once up front and holds it,
 * unrenewed, for however long its actual processing takes (mailbox fetch, per-row AI matching,
 * batch apply). A poll/batch that genuinely outlives its static lease duration lets
 * {@code ImportJobClaimRepository#takeOverIfFree} legitimately hand the same claim to a second
 * replica while the first is still working - real concurrent double-processing, not just a
 * theoretical race. Centralizing register/unregister inside {@link ImportJobClaimService} (rather
 * than touching all six job call sites) keeps this fix additive: no existing claim/release call
 * site or its tests need to change.
 */
@Component
public class ActiveClaimRegistry {

    /** Keyed by claim id (unique per row in {@code import_job_claims}). */
    private final Map<Long, Entry> active = new ConcurrentHashMap<>();

    public void register(ImportJobClaimService.ClaimHandle handle, Duration leaseDuration) {
        active.put(handle.id(), new Entry(handle, leaseDuration));
    }

    public void unregister(ImportJobClaimService.ClaimHandle handle) {
        active.remove(handle.id());
    }

    public void forget(Long claimId) {
        active.remove(claimId);
    }

    public Collection<Entry> snapshot() {
        return List.copyOf(active.values());
    }

    public record Entry(ImportJobClaimService.ClaimHandle handle, Duration leaseDuration) {
    }
}
