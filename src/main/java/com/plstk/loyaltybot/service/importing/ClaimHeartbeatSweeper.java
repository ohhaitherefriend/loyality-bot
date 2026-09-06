package com.plstk.loyaltybot.service.importing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically renews the lease of every claim currently tracked in {@link ActiveClaimRegistry},
 * independent of whichever mailbox-poll/batch-stage thread actually acquired it. Runs on its own
 * scheduler thread, so each renewal is its own short DB transaction that commits immediately -
 * unlike the long-running batch/poll transaction it is renewing on behalf of, this heartbeat is
 * visible to other replicas right away, which is the whole point (see {@link ActiveClaimRegistry}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClaimHeartbeatSweeper {

    private final ActiveClaimRegistry registry;
    private final ImportJobClaimService importJobClaimService;

    @Scheduled(
            fixedDelayString = "${supplier-import.job.heartbeat-interval-ms:20000}",
            initialDelayString = "${supplier-import.job.heartbeat-interval-ms:20000}")
    public void renewActiveLeases() {
        for (ActiveClaimRegistry.Entry entry : registry.snapshot()) {
            ImportJobClaimService.ClaimHandle handle = entry.handle();
            boolean renewed = importJobClaimService.heartbeat(handle, entry.leaseDuration());
            if (!renewed) {
                log.warn("Could not renew lease for {}/{} (claim {}) - it was likely already taken over "
                                + "by another replica after expiring; no longer heartbeating it",
                        handle.jobType(), handle.jobKey(), handle.id());
                registry.forget(handle.id());
            } else {
                log.debug("Renewed lease for {}/{} (claim {})", handle.jobType(), handle.jobKey(), handle.id());
            }
        }
    }
}
