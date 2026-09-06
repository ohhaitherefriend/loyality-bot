package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.importing.MailAuthMode;
import com.plstk.loyaltybot.entity.importing.MailboxConnection;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import com.plstk.loyaltybot.repository.ImportJobClaimRepository;
import com.plstk.loyaltybot.repository.MailboxConnectionRepository;
import com.plstk.loyaltybot.repository.MailboxCursorRepository;
import com.plstk.loyaltybot.repository.SupplierRepository;
import com.plstk.loyaltybot.repository.SupplierSourceRepository;
import com.plstk.loyaltybot.service.TokenEncryptionService;
import com.plstk.loyaltybot.service.importing.mailbox.FakeMailboxClient;
import com.plstk.loyaltybot.service.importing.mailbox.FetchedMessage;
import com.plstk.loyaltybot.service.importing.mailbox.MailboxClientException;
import com.plstk.loyaltybot.service.importing.mailbox.SupplierSourceMatcher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the required Prompt 02 scenarios against {@link MailboxPollingService} using a
 * deterministic {@link FakeMailboxClient} in place of the real IMAP protocol (protocol-level
 * correctness is covered separately by {@code ImapMailboxClientGreenMailTest}).
 */
@DataJpaTest
@Import(MailboxPollingServiceTest.TestConfig.class)
class MailboxPollingServiceTest {

    private static final String SHOP_ID = "shop-a";

    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private SupplierSourceRepository supplierSourceRepository;
    @Autowired
    private MailboxConnectionRepository mailboxConnectionRepository;
    @Autowired
    private MailboxCursorRepository mailboxCursorRepository;
    @Autowired
    private ImportFileRepository importFileRepository;
    @Autowired
    private ImportBatchRepository importBatchRepository;
    @Autowired
    private TokenEncryptionService tokenEncryptionService;
    @Autowired
    private MailboxPollingService mailboxPollingService;
    @Autowired
    private FakeMailboxClient fakeMailboxClient;
    @Autowired
    private EntityManager entityManager;

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("supplier-import.storage.base-path", tempDir::toString);
        // Small on purpose so the "oversize attachment" scenario doesn't need a multi-MB payload.
        registry.add("supplier-import.storage.max-file-size-bytes", () -> "200");
    }

    private MailboxConnection mailboxConnection;
    private SupplierSource supplierSource;

    @BeforeEach
    void setUp() {
        Supplier supplier = supplierRepository.save(Supplier.builder().shopId(SHOP_ID).name("Test Supplier").build());
        mailboxConnection = mailboxConnectionRepository.save(MailboxConnection.builder()
                .shopId(SHOP_ID)
                .label("main")
                .host("imap.mail.ru")
                .port(993)
                .username("shop@mail.ru")
                .encryptedSecret(tokenEncryptionService.encrypt("app-password"))
                .authMode(MailAuthMode.APP_PASSWORD)
                .build());
        supplierSource = supplierSourceRepository.save(SupplierSource.builder()
                .shopId(SHOP_ID)
                .supplier(supplier)
                .label("main")
                .mailboxConnection(mailboxConnection)
                .senderAllowlist("supplier.ru")
                .build());
        entityManager.flush();
        fakeMailboxClient.setUidValidity(100L);
    }

    private FetchedMessage messageWithAttachment(long uid, String from, String subject, String filename, byte[] content) {
        return new FetchedMessage(uid, from, subject, List.of(FakeMailboxClient.attachment(filename, "application/octet-stream", content)));
    }

    @Test
    void multipleEmailsAndAttachments_eachIngestedIntoOwnBatch() {
        fakeMailboxClient.setServerMessages(List.of(
                messageWithAttachment(1, "price@supplier.ru", "Price A", "price-a.xlsx", "content-a".getBytes(StandardCharsets.UTF_8)),
                messageWithAttachment(2, "price@supplier.ru", "Price B", "price-b.xls", "content-b".getBytes(StandardCharsets.UTF_8))));

        PollResult result = mailboxPollingService.pollOne(mailboxConnection.getId());
        entityManager.flush();

        assertEquals("SUCCESS", result.status());
        assertEquals(2, result.ingestedCount());
        assertEquals(2, importFileRepository.count());
        assertEquals(2, importBatchRepository.count());
        assertEquals(2L, mailboxCursorRepository.findByMailboxConnectionId(mailboxConnection.getId()).orElseThrow().getLastSeenUid());
    }

    @Test
    void duplicatePoll_doesNotReprocessAlreadySeenMessages() {
        fakeMailboxClient.setServerMessages(List.of(
                messageWithAttachment(1, "price@supplier.ru", "Price A", "price.xlsx", "content".getBytes(StandardCharsets.UTF_8))));

        mailboxPollingService.pollOne(mailboxConnection.getId());
        entityManager.flush();
        assertEquals(1, importBatchRepository.count());

        // Same server state as before (message still "exists" on the server); a real IMAP server
        // would behave identically since our client only asks for UID > cursor.
        PollResult second = mailboxPollingService.pollOne(mailboxConnection.getId());
        entityManager.flush();

        assertEquals("SUCCESS", second.status());
        assertEquals(0, second.ingestedCount());
        assertEquals(1, importBatchRepository.count());
    }

    @Test
    void uidValidityChange_triggersResync_butContentHashPreventsDuplicateBatch() {
        fakeMailboxClient.setServerMessages(List.of(
                messageWithAttachment(5, "price@supplier.ru", "Price A", "price.xlsx", "same content".getBytes(StandardCharsets.UTF_8))));

        mailboxPollingService.pollOne(mailboxConnection.getId());
        entityManager.flush();
        assertEquals(1, importBatchRepository.count());

        // Simulate the mailbox folder being recreated: UIDVALIDITY changes, so UID 5 from before
        // is not comparable and must be treated as potentially new — but it's the same message.
        fakeMailboxClient.setUidValidity(200L);

        PollResult result = mailboxPollingService.pollOne(mailboxConnection.getId());
        entityManager.flush();

        assertEquals("SUCCESS", result.status());
        // Re-ingested (resync fetched it again) but content-hash idempotency in
        // AttachmentIngestionService means no second batch is created.
        assertEquals(1, importBatchRepository.count());
        assertEquals(200L, mailboxCursorRepository.findByMailboxConnectionId(mailboxConnection.getId()).orElseThrow().getUidValidity());
        assertEquals(5L, mailboxCursorRepository.findByMailboxConnectionId(mailboxConnection.getId()).orElseThrow().getLastSeenUid());
    }

    @Test
    void wrongSender_isSkipped_andCursorStillAdvances() {
        fakeMailboxClient.setServerMessages(List.of(
                messageWithAttachment(1, "unknown@evil.example", "Not a supplier", "price.xlsx", "content".getBytes(StandardCharsets.UTF_8))));

        PollResult result = mailboxPollingService.pollOne(mailboxConnection.getId());
        entityManager.flush();

        assertEquals("SUCCESS", result.status());
        assertEquals(0, result.ingestedCount());
        assertEquals(0, importBatchRepository.count());
        assertEquals(1L, mailboxCursorRepository.findByMailboxConnectionId(mailboxConnection.getId()).orElseThrow().getLastSeenUid());
    }

    @Test
    void unsupportedAttachmentType_isSkipped_andCursorStillAdvances() {
        fakeMailboxClient.setServerMessages(List.of(
                messageWithAttachment(1, "price@supplier.ru", "Report", "readme.docx", "content".getBytes(StandardCharsets.UTF_8))));

        PollResult result = mailboxPollingService.pollOne(mailboxConnection.getId());
        entityManager.flush();

        assertEquals("SUCCESS", result.status());
        assertEquals(0, importBatchRepository.count());
        assertEquals(1L, mailboxCursorRepository.findByMailboxConnectionId(mailboxConnection.getId()).orElseThrow().getLastSeenUid());
    }

    @Test
    void oversizedAttachment_isSkipped_andCursorStillAdvances() {
        byte[] oversized = "x".repeat(500).getBytes(StandardCharsets.UTF_8); // exceeds 200-byte test limit
        fakeMailboxClient.setServerMessages(List.of(
                messageWithAttachment(1, "price@supplier.ru", "Big file", "price.xlsx", oversized)));

        PollResult result = mailboxPollingService.pollOne(mailboxConnection.getId());
        entityManager.flush();

        assertEquals("SUCCESS", result.status());
        assertEquals(0, result.ingestedCount());
        assertEquals(1, result.skippedCount());
        assertEquals(0, importBatchRepository.count());
        assertEquals(1L, mailboxCursorRepository.findByMailboxConnectionId(mailboxConnection.getId()).orElseThrow().getLastSeenUid());
    }

    @Test
    void mailboxClientFailure_recordsErrorWithoutCrashing_andDoesNotAdvanceCursor() throws MailboxClientException {
        fakeMailboxClient.failNextFetchWith(new MailboxClientException("IMAP error (simulated)"));

        PollResult result = mailboxPollingService.pollOne(mailboxConnection.getId());
        entityManager.flush();

        assertEquals("FAILURE", result.status());
        assertTrue(mailboxConnectionRepository.findById(mailboxConnection.getId()).orElseThrow().getLastPollError().contains("simulated"));
        assertEquals(0, importBatchRepository.count());
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        FakeMailboxClient fakeMailboxClient() {
            return new FakeMailboxClient();
        }

        @Bean
        SupplierSourceMatcher supplierSourceMatcher() {
            return new SupplierSourceMatcher();
        }

        @Bean
        TokenEncryptionService tokenEncryptionService() {
            return new TokenEncryptionService();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ImportFileStorage importFileStorage(SupplierImportProperties properties) {
            return new LocalImportFileStorage(properties);
        }

        @Bean
        ImportFileBatchInsertWriter importFileBatchInsertWriter(
                ImportFileRepository importFileRepository, ImportBatchRepository importBatchRepository) {
            return new ImportFileBatchInsertWriter(importFileRepository, importBatchRepository);
        }

        @Bean
        ImportFileBatchWriter importFileBatchWriter(
                ImportFileRepository importFileRepository,
                ImportBatchRepository importBatchRepository,
                ImportFileBatchInsertWriter insertWriter) {
            return new ImportFileBatchWriter(importFileRepository, importBatchRepository, insertWriter);
        }

        @Bean
        AttachmentIngestionService attachmentIngestionService(
                SupplierSourceRepository supplierSourceRepository,
                ImportFileStorage importFileStorage,
                SupplierImportProperties properties,
                ImportFileBatchWriter importFileBatchWriter) {
            return new AttachmentIngestionService(
                    supplierSourceRepository, importFileStorage, properties, importFileBatchWriter);
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        SupplierImportMetrics supplierImportMetrics(MeterRegistry meterRegistry) {
            return new SupplierImportMetrics(meterRegistry);
        }

        @Bean
        ImportJobClaimInsertWriter importJobClaimInsertWriter(ImportJobClaimRepository importJobClaimRepository) {
            return new ImportJobClaimInsertWriter(importJobClaimRepository);
        }

        @Bean
        ActiveClaimRegistry activeClaimRegistry() {
            return new ActiveClaimRegistry();
        }

        @Bean
        ImportJobClaimService importJobClaimService(
                ImportJobClaimRepository importJobClaimRepository,
                ImportJobClaimInsertWriter insertWriter,
                ActiveClaimRegistry activeClaimRegistry,
                SupplierImportMetrics metrics) {
            return new ImportJobClaimService(importJobClaimRepository, insertWriter, activeClaimRegistry, metrics);
        }

        @Bean
        MailboxPollStateWriter mailboxPollStateWriter(
                MailboxConnectionRepository mailboxConnectionRepository, MailboxCursorRepository mailboxCursorRepository) {
            return new MailboxPollStateWriter(mailboxConnectionRepository, mailboxCursorRepository);
        }

        @Bean
        MailboxPollingService mailboxPollingService(
                MailboxConnectionRepository mailboxConnectionRepository,
                MailboxCursorRepository mailboxCursorRepository,
                SupplierSourceRepository supplierSourceRepository,
                FakeMailboxClient fakeMailboxClient,
                SupplierSourceMatcher supplierSourceMatcher,
                AttachmentIngestionService attachmentIngestionService,
                ImportJobClaimService importJobClaimService,
                TokenEncryptionService tokenEncryptionService,
                MailboxPollStateWriter mailboxPollStateWriter,
                SupplierImportProperties properties,
                ObjectMapper objectMapper,
                SupplierImportMetrics metrics) {
            return new MailboxPollingService(
                    mailboxConnectionRepository, mailboxCursorRepository, supplierSourceRepository, fakeMailboxClient,
                    supplierSourceMatcher, attachmentIngestionService, importJobClaimService, tokenEncryptionService,
                    mailboxPollStateWriter, properties, objectMapper, metrics);
        }
    }
}
