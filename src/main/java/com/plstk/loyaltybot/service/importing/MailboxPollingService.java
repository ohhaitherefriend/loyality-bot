package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.importing.MailboxConnection;
import com.plstk.loyaltybot.entity.importing.MailboxCursor;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.MailboxConnectionRepository;
import com.plstk.loyaltybot.repository.MailboxCursorRepository;
import com.plstk.loyaltybot.repository.SupplierSourceRepository;
import com.plstk.loyaltybot.service.TokenEncryptionService;
import com.plstk.loyaltybot.service.importing.mailbox.FetchedAttachment;
import com.plstk.loyaltybot.service.importing.mailbox.FetchedMessage;
import com.plstk.loyaltybot.service.importing.mailbox.MailboxClient;
import com.plstk.loyaltybot.service.importing.mailbox.MailboxClientException;
import com.plstk.loyaltybot.service.importing.mailbox.MailboxConnectionConfig;
import com.plstk.loyaltybot.service.importing.mailbox.MailboxCursorPosition;
import com.plstk.loyaltybot.service.importing.mailbox.MailboxFetchResult;
import com.plstk.loyaltybot.service.importing.mailbox.SupplierSourceMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Core email ingestion vertical slice: mailbox -&gt; attachment -&gt; STORED batch.
 *
 * One poll of one mailbox:
 * 1. claims a DB-backed lease so at most one replica/thread polls a given mailbox at a time;
 * 2. decrypts the mailbox secret just-in-time (never logged, never persisted in plaintext);
 * 3. fetches messages with UID greater than the stored cursor (or everything, if UIDVALIDITY
 *    changed — a reconnect/folder-recreate case where old UIDs are no longer comparable);
 * 4. for every attachment, routes it to the {@link SupplierSource}(s) whose sender/subject/
 *    filename filters accept it, and hands matching xlsx/xls attachments to the same
 *    {@link AttachmentIngestionService} manual upload will use later (Prompt 08);
 * 5. advances the cursor strictly up to the last message that was fully processed, so a crash
 *    mid-poll retries only the unprocessed tail next time, never re-ingests processed messages.
 *
 * Never marks a message read, deletes it or moves it — enforced by {@link MailboxClient}
 * implementations opening folders read-only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailboxPollingService {

    private static final String JOB_TYPE = "MAILBOX_POLL";
    private static final long MIN_LEASE_MILLIS = 60_000L;

    private final MailboxConnectionRepository mailboxConnectionRepository;
    private final MailboxCursorRepository mailboxCursorRepository;
    private final SupplierSourceRepository supplierSourceRepository;
    private final MailboxClient mailboxClient;
    private final SupplierSourceMatcher supplierSourceMatcher;
    private final AttachmentIngestionService attachmentIngestionService;
    private final ImportJobClaimService importJobClaimService;
    private final TokenEncryptionService tokenEncryptionService;
    private final MailboxPollStateWriter stateWriter;
    private final SupplierImportProperties properties;
    private final ObjectMapper objectMapper;
    private final SupplierImportMetrics metrics;

    public void pollAllEnabledMailboxes() {
        List<MailboxConnection> connections = mailboxConnectionRepository.findByEnabledTrue();
        for (MailboxConnection connection : connections) {
            try {
                pollOne(connection.getId());
            } catch (Exception e) {
                log.error("Unhandled error polling mailbox {}", connection.getId(), e);
            }
        }
    }

    public PollResult pollOne(Long mailboxConnectionId) {
        Duration leaseDuration = Duration.ofMillis(
                Math.max(properties.getJob().getMailboxPollIntervalMs(), MIN_LEASE_MILLIS));
        Optional<ImportJobClaimService.ClaimHandle> claim =
                importJobClaimService.tryClaim(JOB_TYPE, mailboxConnectionId.toString(), leaseDuration);
        if (claim.isEmpty()) {
            log.debug("Mailbox {} is already being polled by another replica/thread", mailboxConnectionId);
            return PollResult.skippedAlreadyClaimed();
        }
        try {
            PollResult result = doPoll(mailboxConnectionId);
            metrics.mailboxPoll(result.status().toLowerCase());
            return result;
        } finally {
            importJobClaimService.release(claim.get());
        }
    }

    private PollResult doPoll(Long mailboxConnectionId) {
        Optional<MailboxConnection> connectionOpt = mailboxConnectionRepository.findById(mailboxConnectionId);
        if (connectionOpt.isEmpty() || !Boolean.TRUE.equals(connectionOpt.get().getEnabled())) {
            return PollResult.skippedDisabledOrMissing();
        }
        MailboxConnection connection = connectionOpt.get();

        String plainSecret;
        try {
            plainSecret = tokenEncryptionService.decrypt(connection.getEncryptedSecret());
        } catch (Exception e) {
            log.error("Failed to decrypt secret for mailbox {}", mailboxConnectionId, e);
            stateWriter.recordPollFailure(mailboxConnectionId, "Failed to decrypt mailbox secret");
            return PollResult.failure("Failed to decrypt mailbox secret");
        }

        MailboxConnectionConfig config = new MailboxConnectionConfig(
                connection.getHost(), connection.getPort(), connection.getUsername(),
                plainSecret, Boolean.TRUE.equals(connection.getUseTls()), connection.getFolder());

        MailboxCursor existingCursor = mailboxCursorRepository.findByMailboxConnectionId(mailboxConnectionId).orElse(null);
        long lastSeenUid = existingCursor != null && existingCursor.getLastSeenUid() != null
                ? existingCursor.getLastSeenUid() : 0L;
        Long storedUidValidity = existingCursor != null ? existingCursor.getUidValidity() : null;
        MailboxCursorPosition position = new MailboxCursorPosition(storedUidValidity, lastSeenUid);

        List<SupplierSource> candidateSources = supplierSourceRepository
                .findByMailboxConnectionIdAndEnabledTrue(mailboxConnectionId);

        int maxMessages = properties.getJob().getMailboxPollMaxMessagesPerCycle();
        try (MailboxFetchResult fetchResult = mailboxClient.fetchNewMessages(config, position, maxMessages)) {
            boolean resync = storedUidValidity == null || storedUidValidity != fetchResult.uidValidity();
            long advancedUid = resync ? 0L : lastSeenUid;
            int ingested = 0;
            int skipped = 0;

            for (FetchedMessage message : fetchResult.messages()) {
                try {
                    int[] counts = processMessage(mailboxConnectionId, fetchResult.uidValidity(), message, candidateSources);
                    ingested += counts[0];
                    skipped += counts[1];
                } catch (RuntimeException e) {
                    // Skip-and-advance, not break-and-retry: a message that fails for a durable
                    // reason (e.g. its matching SupplierSource was deleted concurrently) would fail
                    // identically on every future poll, permanently stalling every later message on
                    // this mailbox if we left the cursor behind it. One skipped message is a bounded,
                    // logged loss; a permanently stuck mailbox is not.
                    log.error("Failed to process message uid={} from mailbox {}; skipping it and advancing past it",
                            message.uid(), mailboxConnectionId, e);
                    skipped++;
                }
                advancedUid = message.uid();
            }

            stateWriter.advanceCursor(mailboxConnectionId, fetchResult.uidValidity(), advancedUid);
            stateWriter.recordPollSuccess(mailboxConnectionId);
            return PollResult.success(ingested, skipped);
        } catch (MailboxClientException e) {
            log.warn("Mailbox {} poll failed: {}", mailboxConnectionId, e.getMessage());
            stateWriter.recordPollFailure(mailboxConnectionId, e.getMessage());
            return PollResult.failure(e.getMessage());
        }
    }

    /** @return {ingestedCount, skippedCount} for this single message. */
    private int[] processMessage(
            Long mailboxConnectionId, long uidValidity, FetchedMessage message, List<SupplierSource> candidateSources) {
        int ingested = 0;
        int skipped = 0;
        int attachmentIndex = 0;
        for (FetchedAttachment attachment : message.attachments()) {
            List<SupplierSource> matches = supplierSourceMatcher.matchAll(
                    candidateSources, message.fromAddress(), message.subject(), attachment.filename());

            if (matches.isEmpty()) {
                log.debug("No SupplierSource matched attachment '{}' from {} on mailbox {}, skipping",
                        attachment.filename(), message.fromAddress(), mailboxConnectionId);
                skipped++;
                attachmentIndex++;
                continue;
            }
            if (!supplierSourceMatcher.isSupportedAttachmentType(attachment.filename())) {
                log.debug("Unsupported attachment type '{}' on mailbox {}, skipping", attachment.filename(), mailboxConnectionId);
                skipped++;
                attachmentIndex++;
                continue;
            }

            String sourceIdentity = buildSourceIdentity(mailboxConnectionId, uidValidity, message.uid(), attachmentIndex);
            for (SupplierSource source : matches) {
                try {
                    AttachmentMetadata metadata = new AttachmentMetadata(
                            attachment.filename(), attachment.contentType(), sourceIdentity);
                    attachmentIngestionService.ingest(source.getShopId(), source.getId(), metadata, attachment.openStream());
                    ingested++;
                } catch (AttachmentIngestionService.AttachmentTooLargeException e) {
                    log.warn("Attachment '{}' from mailbox {} exceeds max size for source {}, skipping",
                            attachment.filename(), mailboxConnectionId, source.getId());
                    skipped++;
                } catch (IOException e) {
                    log.error("Failed to ingest attachment '{}' from mailbox {} for source {}",
                            attachment.filename(), mailboxConnectionId, source.getId(), e);
                    skipped++;
                }
            }
            attachmentIndex++;
        }
        return new int[]{ingested, skipped};
    }

    private String buildSourceIdentity(Long mailboxConnectionId, long uidValidity, long uid, int attachmentIndex) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("mailboxConnectionId", mailboxConnectionId);
        identity.put("uidValidity", uidValidity);
        identity.put("uid", uid);
        identity.put("attachmentIndex", attachmentIndex);
        try {
            return objectMapper.writeValueAsString(identity);
        } catch (Exception e) {
            // Never fatal: sourceIdentity is audit metadata, not a correctness-critical field.
            return "{\"mailboxConnectionId\":" + mailboxConnectionId + ",\"uid\":" + uid + "}";
        }
    }
}
