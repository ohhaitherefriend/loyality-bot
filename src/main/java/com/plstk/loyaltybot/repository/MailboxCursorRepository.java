package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.importing.MailboxCursor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface MailboxCursorRepository extends JpaRepository<MailboxCursor, Long> {

    Optional<MailboxCursor> findByMailboxConnectionId(Long mailboxConnectionId);

    /**
     * Only advances if this is a genuine resync (stored {@code uidValidity} unset or different) or
     * {@code newLastSeenUid} is not behind what is already stored - never regresses the cursor. An
     * unconditional write here previously let a stale-lease replica overwrite a further-along cursor
     * written by whichever replica actually took over the lease (see {@code ActiveClaimRegistry}/
     * {@code ImportJobClaimService} heartbeat fix): the regression itself never loses messages
     * (content-hash dedup in {@code ImportFileBatchWriter} makes re-ingestion a no-op) but causes
     * silent, repeated reprocessing of an ever-growing UID range on every subsequent poll.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE MailboxCursor c SET c.uidValidity = :uidValidity, c.lastSeenUid = :newLastSeenUid, "
            + "c.lastAdvancedAt = :now WHERE c.mailboxConnection.id = :mailboxConnectionId "
            + "AND (c.uidValidity IS NULL OR c.uidValidity <> :uidValidity "
            + "OR c.lastSeenUid IS NULL OR c.lastSeenUid <= :newLastSeenUid)")
    int advanceCursorIfNotBehind(
            @Param("mailboxConnectionId") Long mailboxConnectionId,
            @Param("uidValidity") long uidValidity,
            @Param("newLastSeenUid") long newLastSeenUid,
            @Param("now") LocalDateTime now);
}
