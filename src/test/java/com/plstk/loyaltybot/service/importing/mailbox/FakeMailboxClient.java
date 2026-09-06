package com.plstk.loyaltybot.service.importing.mailbox;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic {@link MailboxClient} test double. Configure the mailbox "server state" once
 * (uidValidity + full message list) and this behaves like a real IMAP server would: each
 * {@link #fetchNewMessages} call replays server-side UID filtering based on the cursor position
 * it is given, exactly like {@link ImapMailboxClient} does against a real server.
 */
public class FakeMailboxClient implements MailboxClient {

    private long uidValidity = 1L;
    private List<FetchedMessage> serverMessages = new ArrayList<>();
    private MailboxClientException nextFetchFailure;
    private final List<MailboxCursorPosition> requestedPositions = new ArrayList<>();

    public void setUidValidity(long uidValidity) {
        this.uidValidity = uidValidity;
    }

    public void setServerMessages(List<FetchedMessage> messages) {
        this.serverMessages = new ArrayList<>(messages);
    }

    public void failNextFetchWith(MailboxClientException exception) {
        this.nextFetchFailure = exception;
    }

    public List<MailboxCursorPosition> requestedPositions() {
        return requestedPositions;
    }

    @Override
    public MailboxConnectionTestResult testConnection(MailboxConnectionConfig config) {
        return MailboxConnectionTestResult.ok("fake connection ok");
    }

    @Override
    public MailboxFetchResult fetchNewMessages(
            MailboxConnectionConfig config, MailboxCursorPosition cursorPosition, int maxMessages)
            throws MailboxClientException {
        requestedPositions.add(cursorPosition);
        if (nextFetchFailure != null) {
            MailboxClientException failure = nextFetchFailure;
            nextFetchFailure = null;
            throw failure;
        }

        boolean resync = cursorPosition.uidValidity() == null || cursorPosition.uidValidity() != uidValidity;
        long startUid = resync ? 0L : cursorPosition.lastSeenUid();
        List<FetchedMessage> filtered = serverMessages.stream()
                .filter(m -> m.uid() > startUid)
                .sorted((a, b) -> Long.compare(a.uid(), b.uid()))
                .limit(Math.max(1, maxMessages))
                .toList();

        long capturedUidValidity = uidValidity;
        return new MailboxFetchResult() {
            @Override
            public long uidValidity() {
                return capturedUidValidity;
            }

            @Override
            public List<FetchedMessage> messages() {
                return filtered;
            }

            @Override
            public void close() {
                // Nothing to release; no real connection is held by this fake.
            }
        };
    }

    public static FetchedAttachment attachment(String filename, String contentType, byte[] content) {
        return new FetchedAttachment() {
            @Override
            public String filename() {
                return filename;
            }

            @Override
            public String contentType() {
                return contentType;
            }

            @Override
            public InputStream openStream() {
                return new ByteArrayInputStream(content);
            }
        };
    }
}
