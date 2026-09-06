package com.plstk.loyaltybot.service.importing.mailbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.NoSuchProviderException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.imap.IMAPMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * First real {@link MailboxClient} implementation: app-password IMAP/IMAPS
 * ({@code MailAuthMode.OAUTH2} has no implementation yet — see class javadoc on
 * {@link MailboxClient}). Read-only: the folder is always opened with
 * {@link Folder#READ_ONLY} and closed without expunging, so messages are never marked read,
 * deleted or moved.
 */
@Component
@Slf4j
public class ImapMailboxClient implements MailboxClient {

    @Override
    public MailboxConnectionTestResult testConnection(MailboxConnectionConfig config) {
        try (StoreHandle handle = connect(config)) {
            Folder folder = handle.store().getFolder(config.folder());
            if (!folder.exists()) {
                return MailboxConnectionTestResult.failure("Folder '" + config.folder() + "' does not exist");
            }
            folder.open(Folder.READ_ONLY);
            try {
                return MailboxConnectionTestResult.ok(
                        "Connected, folder '" + config.folder() + "' has " + folder.getMessageCount() + " message(s)");
            } finally {
                folder.close(false);
            }
        } catch (AuthenticationFailedException e) {
            return MailboxConnectionTestResult.failure("Authentication failed");
        } catch (NoSuchProviderException e) {
            return MailboxConnectionTestResult.failure("Unsupported mail provider configuration");
        } catch (Exception e) {
            return MailboxConnectionTestResult.failure(redact(e, config));
        }
    }

    @Override
    public MailboxFetchResult fetchNewMessages(
            MailboxConnectionConfig config, MailboxCursorPosition cursorPosition, int maxMessages)
            throws MailboxClientException {
        StoreHandle handle;
        try {
            handle = connect(config);
        } catch (Exception e) {
            throw new MailboxClientException(redact(e, config), e);
        }

        Folder folder;
        try {
            folder = handle.store().getFolder(config.folder());
            folder.open(Folder.READ_ONLY);
        } catch (MessagingException e) {
            handle.close();
            throw new MailboxClientException(redact(e, config), e);
        }

        try {
            UIDFolder uidFolder = (UIDFolder) folder;
            long currentUidValidity = uidFolder.getUIDValidity();
            boolean resync = cursorPosition.uidValidity() == null
                    || cursorPosition.uidValidity() != currentUidValidity;
            long startUid = resync ? 1L : cursorPosition.lastSeenUid() + 1;

            Message[] rawMessages = uidFolder.getMessagesByUID(startUid, UIDFolder.LASTUID);
            // Resolve UIDs first (cheap - no MIME parsing yet) so the backlog can be capped by UID
            // order BEFORE the expensive per-message MIME parse below runs on only the oldest
            // maxMessages of them; a mailbox with a large backlog must never force one poll cycle to
            // fully parse (and hold in memory) every message since the last cursor position under a
            // single claim/lease. Any UID beyond the cap is simply picked up by the next poll cycle -
            // the cursor only ever advances up to the last message actually processed below, never
            // skips ahead of unprocessed backlog.
            Map<Long, Message> byUid = new HashMap<>();
            for (Message message : rawMessages) {
                if (message == null) {
                    continue;
                }
                long uid;
                try {
                    uid = uidFolder.getUID(message);
                } catch (MessagingException e) {
                    log.warn("Failed to read UID for a fetched message on {} - skipping it this poll "
                            + "(will retry once the cursor catches up to it)", config.host(), e);
                    continue;
                }
                if (uid < startUid) {
                    // Defensive re-filter: some servers include the boundary message even when
                    // startUid is already past the last real UID.
                    continue;
                }
                byUid.put(uid, message);
            }
            List<Long> orderedUids = byUid.keySet().stream()
                    .sorted()
                    .limit(Math.max(1, maxMessages))
                    .toList();

            List<FetchedMessage> messages = new ArrayList<>();
            for (Long uid : orderedUids) {
                Message message = byUid.get(uid);
                try {
                    messages.add(toFetchedMessage(uid, message));
                } catch (MessagingException | IOException | RuntimeException e) {
                    // A single message with malformed MIME structure must never abort the whole
                    // fetch - that would permanently stall this mailbox, since the same message
                    // would be re-fetched (and re-fail) on every subsequent poll (see
                    // MailboxPollingService, which only advances the cursor past what this call
                    // returns). Treat it as a no-attachments message: the cursor advances past it
                    // and any real attachments it had are lost, but every later message is unblocked.
                    log.warn("Failed to parse message uid={} on {} (malformed MIME?) - treating it as having no "
                            + "attachments so it does not permanently stall this mailbox", uid, config.host(), e);
                    messages.add(new FetchedMessage(uid, "", null, List.of()));
                }
            }

            return new ImapFetchResult(handle, folder, currentUidValidity, messages);
        } catch (MessagingException e) {
            safeCloseFolder(folder);
            handle.close();
            throw new MailboxClientException(redact(e, config), e);
        } catch (RuntimeException e) {
            safeCloseFolder(folder);
            handle.close();
            throw e;
        }
    }

    private FetchedMessage toFetchedMessage(long uid, Message message) throws MessagingException, IOException {
        if (message instanceof IMAPMessage imapMessage) {
            // Belt-and-suspenders: Folder.READ_ONLY already makes Angus Mail use BODY.PEEK[]
            // internally, but setPeek(true) makes the "never mark as read" contract explicit
            // and independent of that folder-mode inference.
            imapMessage.setPeek(true);
        }
        String from = extractFromAddress(message);
        String subject = message.getSubject();
        List<FetchedAttachment> attachments = MimeAttachmentExtractor.extract(message);
        return new FetchedMessage(uid, from, subject, attachments);
    }

    private String extractFromAddress(Message message) throws MessagingException {
        jakarta.mail.Address[] from = message.getFrom();
        if (from == null || from.length == 0) {
            return "";
        }
        if (from[0] instanceof InternetAddress internetAddress) {
            String address = internetAddress.getAddress();
            return address == null ? "" : address.toLowerCase();
        }
        return from[0].toString().toLowerCase();
    }

    private StoreHandle connect(MailboxConnectionConfig config) throws MessagingException {
        Properties props = new Properties();
        String protocol = config.useTls() ? "imaps" : "imap";
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".host", config.host());
        props.put("mail." + protocol + ".port", String.valueOf(config.port()));
        props.put("mail." + protocol + ".connectiontimeout", "10000");
        props.put("mail." + protocol + ".timeout", "20000");
        // Pin the exact implementation class instead of relying on javamail.providers discovery:
        // test dependencies (GreenMail) bundle their own provider file that also claims "imap",
        // which otherwise wins the lookup and breaks the real client with a legacy classname.
        props.put("mail." + protocol + ".class",
                config.useTls() ? "org.eclipse.angus.mail.imap.IMAPSSLStore" : "org.eclipse.angus.mail.imap.IMAPStore");
        // Read-only intent is enforced by opening folders with Folder.READ_ONLY, not by any
        // provider-level flag; there is no such flag in jakarta.mail.

        Session session = Session.getInstance(props);
        Store store = session.getStore(protocol);
        store.connect(config.host(), config.port(), config.username(), config.password());
        return new StoreHandle(store);
    }

    private void safeCloseFolder(Folder folder) {
        try {
            if (folder != null && folder.isOpen()) {
                folder.close(false);
            }
        } catch (MessagingException e) {
            log.debug("Failed to close IMAP folder cleanly", e);
        }
    }

    /**
     * Strips anything that could leak the password (provider exception text sometimes echoes
     * connection parameters) and returns a short, safe-to-store/log message.
     */
    private String redact(Exception e, MailboxConnectionConfig config) {
        String raw = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String redacted = raw.replace(config.password(), "***");
        return "IMAP error (" + e.getClass().getSimpleName() + "): " + redacted;
    }

    private record StoreHandle(Store store) implements AutoCloseable {
        @Override
        public void close() {
            try {
                if (store.isConnected()) {
                    store.close();
                }
            } catch (MessagingException ignored) {
                // Best-effort cleanup only.
            }
        }
    }

    private static final class ImapFetchResult implements MailboxFetchResult {
        private final StoreHandle handle;
        private final Folder folder;
        private final long uidValidity;
        private final List<FetchedMessage> messages;

        private ImapFetchResult(StoreHandle handle, Folder folder, long uidValidity, List<FetchedMessage> messages) {
            this.handle = handle;
            this.folder = folder;
            this.uidValidity = uidValidity;
            this.messages = messages;
        }

        @Override
        public long uidValidity() {
            return uidValidity;
        }

        @Override
        public List<FetchedMessage> messages() {
            return messages;
        }

        @Override
        public void close() {
            try {
                if (folder.isOpen()) {
                    // false = never expunge; this connection is read-only by contract.
                    folder.close(false);
                }
            } catch (MessagingException ignored) {
                // Best-effort cleanup only.
            } finally {
                handle.close();
            }
        }
    }
}
