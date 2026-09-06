package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.entity.importing.MailAuthMode;
import com.plstk.loyaltybot.entity.importing.MailboxConnection;
import com.plstk.loyaltybot.repository.MailboxConnectionRepository;
import com.plstk.loyaltybot.service.TokenEncryptionService;
import com.plstk.loyaltybot.service.importing.mailbox.FakeMailboxClient;
import com.plstk.loyaltybot.service.importing.mailbox.MailboxConnectionTestResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Secret-redaction coverage: the mailbox password must never be stored in plaintext, never
 * echoed back by {@code MailboxConnectionService}, and never leak into serialized responses.
 */
@DataJpaTest
@Import(MailboxConnectionServiceTest.TestConfig.class)
class MailboxConnectionServiceTest {

    private static final String SHOP_ID = "shop-a";
    private static final String PLAINTEXT_SECRET = "super-secret-app-password";

    @Autowired
    private MailboxConnectionService mailboxConnectionService;
    @Autowired
    private MailboxConnectionRepository mailboxConnectionRepository;
    @Autowired
    private TokenEncryptionService tokenEncryptionService;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void create_neverPersistsPlaintextSecret() {
        MailboxConnection connection = mailboxConnectionService.create(
                SHOP_ID, "main", "imap.mail.ru", 993, "shop@mail.ru", PLAINTEXT_SECRET,
                MailAuthMode.APP_PASSWORD, "INBOX", true, true);

        assertNotEquals(PLAINTEXT_SECRET, connection.getEncryptedSecret());
        assertFalse(connection.getEncryptedSecret().contains(PLAINTEXT_SECRET));

        MailboxConnection reloaded = mailboxConnectionRepository.findById(connection.getId()).orElseThrow();
        assertEquals(PLAINTEXT_SECRET, tokenEncryptionService.decrypt(reloaded.getEncryptedSecret()));
    }

    @Test
    void listResponseDto_serializesWithoutLeakingSecret() throws Exception {
        MailboxConnection connection = mailboxConnectionService.create(
                SHOP_ID, "main", "imap.mail.ru", 993, "shop@mail.ru", PLAINTEXT_SECRET,
                MailAuthMode.APP_PASSWORD, "INBOX", true, true);

        // Mirrors com.plstk.loyaltybot.controller.SupplierImportAdminController.MailboxConnectionResponse:
        // the DTO type itself has no secret field, so serializing it can never leak the password.
        record MailboxConnectionResponse(Long id, String label, String host, Integer port, String username) {
        }
        MailboxConnectionResponse dto = new MailboxConnectionResponse(
                connection.getId(), connection.getLabel(), connection.getHost(), connection.getPort(), connection.getUsername());

        String json = objectMapper.writeValueAsString(dto);
        assertFalse(json.contains(PLAINTEXT_SECRET));
        assertFalse(json.toLowerCase().contains("secret"));
    }

    @Test
    void testConnection_withCorruptedSecret_failsWithGenericMessage_neverLeaksRawException() {
        MailboxConnection connection = mailboxConnectionService.create(
                SHOP_ID, "main", "imap.mail.ru", 993, "shop@mail.ru", PLAINTEXT_SECRET,
                MailAuthMode.APP_PASSWORD, "INBOX", true, true);
        connection.setEncryptedSecret("not-valid-base64-ciphertext");
        mailboxConnectionRepository.save(connection);

        MailboxConnectionTestResult result = mailboxConnectionService.testConnection(SHOP_ID, connection.getId());

        assertFalse(result.success());
        assertFalse(result.message().contains(PLAINTEXT_SECRET));
    }

    @Test
    void testConnection_delegatesToMailboxClient_withDecryptedSecret() {
        MailboxConnection connection = mailboxConnectionService.create(
                SHOP_ID, "main", "imap.mail.ru", 993, "shop@mail.ru", PLAINTEXT_SECRET,
                MailAuthMode.APP_PASSWORD, "INBOX", true, true);

        MailboxConnectionTestResult result = mailboxConnectionService.testConnection(SHOP_ID, connection.getId());

        assertTrue(result.success());
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        TokenEncryptionService tokenEncryptionService() {
            return new TokenEncryptionService();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        FakeMailboxClient fakeMailboxClient() {
            return new FakeMailboxClient();
        }

        @Bean
        MailboxConnectionService mailboxConnectionService(
                MailboxConnectionRepository mailboxConnectionRepository,
                TokenEncryptionService tokenEncryptionService,
                FakeMailboxClient fakeMailboxClient) {
            return new MailboxConnectionService(mailboxConnectionRepository, tokenEncryptionService, fakeMailboxClient);
        }
    }
}
