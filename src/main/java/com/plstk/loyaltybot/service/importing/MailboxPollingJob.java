package com.plstk.loyaltybot.service.importing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls every enabled mailbox on a configurable interval (default 5 minutes, see
 * {@code supplier-import.job.mailbox-poll-interval-ms}). Concurrency across replicas is handled
 * by {@link MailboxPollingService} via {@link ImportJobClaimService}, so it is safe to run this
 * on every application instance.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MailboxPollingJob {

    private final MailboxPollingService mailboxPollingService;

    @Scheduled(
            fixedDelayString = "${supplier-import.job.mailbox-poll-interval-ms:300000}",
            initialDelayString = "${supplier-import.job.mailbox-poll-initial-delay-ms:15000}")
    public void pollMailboxes() {
        mailboxPollingService.pollAllEnabledMailboxes();
    }
}
