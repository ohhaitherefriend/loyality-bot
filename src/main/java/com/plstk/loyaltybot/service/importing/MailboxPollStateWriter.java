package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.MailboxConnection;
import com.plstk.loyaltybot.entity.importing.MailboxCursor;
import com.plstk.loyaltybot.repository.MailboxConnectionRepository;
import com.plstk.loyaltybot.repository.MailboxCursorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Small transactional seam for {@link MailboxPollingService}, mirroring
 * {@code ImportFileBatchWriter}: kept as a separate bean (not a private method on
 * {@code MailboxPollingService}) so {@code @Transactional} actually applies via the Spring AOP
 * proxy instead of being silently skipped by self-invocation.
 */
@Service
@RequiredArgsConstructor
class MailboxPollStateWriter {

    private static final int MAX_ERROR_LENGTH = 1024;

    private final MailboxConnectionRepository mailboxConnectionRepository;
    private final MailboxCursorRepository mailboxCursorRepository;

    /**
     * Conditional on this write not regressing the cursor (see {@code
     * MailboxCursorRepository#advanceCursorIfNotBehind}) - the plain insert path below only runs
     * once, ever, per mailbox (its very first poll, before any cursor row exists at all).
     */
    @Transactional
    public void advanceCursor(Long mailboxConnectionId, long uidValidity, long lastSeenUid) {
        LocalDateTime now = LocalDateTime.now();
        int updated = mailboxCursorRepository.advanceCursorIfNotBehind(mailboxConnectionId, uidValidity, lastSeenUid, now);
        if (updated == 0 && mailboxCursorRepository.findByMailboxConnectionId(mailboxConnectionId).isEmpty()) {
            MailboxConnection connection = mailboxConnectionRepository.getReferenceById(mailboxConnectionId);
            MailboxCursor cursor = MailboxCursor.builder()
                    .mailboxConnection(connection)
                    .uidValidity(uidValidity)
                    .lastSeenUid(lastSeenUid)
                    .lastAdvancedAt(now)
                    .build();
            mailboxCursorRepository.save(cursor);
        }
    }

    @Transactional
    public void recordPollSuccess(Long mailboxConnectionId) {
        mailboxConnectionRepository.findById(mailboxConnectionId).ifPresent(connection -> {
            LocalDateTime now = LocalDateTime.now();
            connection.setLastPollAt(now);
            connection.setLastPollSuccessAt(now);
            connection.setLastPollError(null);
            mailboxConnectionRepository.save(connection);
        });
    }

    @Transactional
    public void recordPollFailure(Long mailboxConnectionId, String errorMessage) {
        mailboxConnectionRepository.findById(mailboxConnectionId).ifPresent(connection -> {
            connection.setLastPollAt(LocalDateTime.now());
            connection.setLastPollError(truncate(errorMessage));
            mailboxConnectionRepository.save(connection);
        });
    }

    private String truncate(String message) {
        if (message == null) {
            return "Unknown error";
        }
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
