package com.plstk.loyaltybot.service.importing.mailbox;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.activation.DataHandler;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol-level correctness test for {@link ImapMailboxClient} against a real (embedded) IMAP
 * server: connect, list new messages/attachments by UID, and never mark anything read.
 */
class ImapMailboxClientGreenMailTest {

    private static final String EMAIL = "supplier@test.local";
    private static final String PASSWORD = "app-password-123";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.IMAP);

    private final ImapMailboxClient client = new ImapMailboxClient();

    @Test
    void testConnection_correctCredentials_succeeds() {
        deliverPriceListMessage("price.xlsx", "fake xlsx bytes".getBytes(StandardCharsets.UTF_8));

        MailboxConnectionTestResult result = client.testConnection(configWithPassword(PASSWORD));

        assertTrue(result.success());
    }

    @Test
    void testConnection_wrongPassword_failsWithoutLeakingPassword() {
        MailboxConnectionTestResult result = client.testConnection(configWithPassword("totally-wrong-password"));

        assertFalse(result.success());
        assertFalse(result.message().contains("totally-wrong-password"));
    }

    @Test
    void fetchNewMessages_returnsAttachmentBytesAndSenderForNewMessage() throws Exception {
        byte[] xlsxBytes = "fake xlsx bytes for greenmail test".getBytes(StandardCharsets.UTF_8);
        deliverPriceListMessage("price.xlsx", xlsxBytes);

        try (MailboxFetchResult result =
                client.fetchNewMessages(configWithPassword(PASSWORD), MailboxCursorPosition.initial(), 100)) {
            assertEquals(1, result.messages().size());
            FetchedMessage message = result.messages().get(0);
            assertEquals(EMAIL, message.fromAddress());
            assertEquals(1, message.attachments().size());
            FetchedAttachment attachment = message.attachments().get(0);
            assertEquals("price.xlsx", attachment.filename());
            assertArrayEquals(xlsxBytes, readAll(attachment.openStream()));
        }
    }

    @Test
    void fetchNewMessages_secondPollWithAdvancedCursor_returnsNoDuplicates() throws Exception {
        deliverPriceListMessage("price.xlsx", "content".getBytes(StandardCharsets.UTF_8));

        long uidValidity;
        long lastUid;
        try (MailboxFetchResult first =
                client.fetchNewMessages(configWithPassword(PASSWORD), MailboxCursorPosition.initial(), 100)) {
            assertEquals(1, first.messages().size());
            uidValidity = first.uidValidity();
            lastUid = first.messages().get(0).uid();
        }

        try (MailboxFetchResult second = client.fetchNewMessages(
                configWithPassword(PASSWORD), new MailboxCursorPosition(uidValidity, lastUid), 100)) {
            assertTrue(second.messages().isEmpty());
        }
    }

    @Test
    void fetchNewMessages_neverMarksMessageAsSeen() throws Exception {
        deliverPriceListMessage("price.xlsx", "content".getBytes(StandardCharsets.UTF_8));

        try (MailboxFetchResult result =
                client.fetchNewMessages(configWithPassword(PASSWORD), MailboxCursorPosition.initial(), 100)) {
            assertEquals(1, result.messages().size());
        }

        assertEquals(1, countUnreadDirectly());
    }

    @Test
    void fetchNewMessages_capsAtMaxMessages_oldestUidsFirst_cursorAdvancesOnlyPastReturned() throws Exception {
        deliverPriceListMessage("price-1.xlsx", "content-1".getBytes(StandardCharsets.UTF_8));
        deliverPriceListMessage("price-2.xlsx", "content-2".getBytes(StandardCharsets.UTF_8));
        deliverPriceListMessage("price-3.xlsx", "content-3".getBytes(StandardCharsets.UTF_8));

        try (MailboxFetchResult result =
                client.fetchNewMessages(configWithPassword(PASSWORD), MailboxCursorPosition.initial(), 2)) {
            assertEquals(2, result.messages().size());
            assertEquals("price-1.xlsx", result.messages().get(0).attachments().get(0).filename());
            assertEquals("price-2.xlsx", result.messages().get(1).attachments().get(0).filename());
            long lastReturnedUid = result.messages().get(1).uid();

            try (MailboxFetchResult next = client.fetchNewMessages(
                    configWithPassword(PASSWORD),
                    new MailboxCursorPosition(result.uidValidity(), lastReturnedUid),
                    2)) {
                assertEquals(1, next.messages().size());
                assertEquals("price-3.xlsx", next.messages().get(0).attachments().get(0).filename());
            }
        }
    }

    private void deliverPriceListMessage(String filename, byte[] attachmentBytes) {
        try {
            GreenMailUser user = greenMail.setUser(EMAIL, PASSWORD);
            Session session = Session.getInstance(new Properties());
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(EMAIL));
            message.setRecipients(Message.RecipientType.TO, EMAIL);
            message.setSubject("Price list");

            MimeMultipart multipart = new MimeMultipart();
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText("see attached price list");
            multipart.addBodyPart(textPart);

            MimeBodyPart attachmentPart = new MimeBodyPart();
            attachmentPart.setDataHandler(new DataHandler(new ByteArrayDataSource(attachmentBytes, "application/octet-stream")));
            attachmentPart.setFileName(filename);
            multipart.addBodyPart(attachmentPart);

            message.setContent(multipart);
            message.saveChanges();

            user.deliver(message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deliver test message via GreenMail", e);
        }
    }

    private int countUnreadDirectly() throws Exception {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imap");
        props.put("mail.imap.class", "org.eclipse.angus.mail.imap.IMAPStore");
        Session session = Session.getInstance(props);
        Store store = session.getStore("imap");
        store.connect("127.0.0.1", greenMail.getImap().getPort(), EMAIL, PASSWORD);
        try {
            Folder folder = store.getFolder("INBOX");
            folder.open(Folder.READ_ONLY);
            try {
                return folder.getUnreadMessageCount();
            } finally {
                folder.close(false);
            }
        } finally {
            store.close();
        }
    }

    private MailboxConnectionConfig configWithPassword(String password) {
        return new MailboxConnectionConfig("127.0.0.1", greenMail.getImap().getPort(), EMAIL, password, false, "INBOX");
    }

    private byte[] readAll(InputStream in) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
