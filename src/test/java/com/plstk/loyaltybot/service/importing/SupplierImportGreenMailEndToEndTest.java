package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.MailAuthMode;
import com.plstk.loyaltybot.entity.importing.MailboxConnection;
import com.plstk.loyaltybot.entity.importing.PriceRoundingPolicy;
import com.plstk.loyaltybot.entity.importing.SnapshotMode;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierOffer;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.BrandAliasRepository;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import com.plstk.loyaltybot.repository.ImportRuleVersionRepository;
import com.plstk.loyaltybot.repository.MailboxConnectionRepository;
import com.plstk.loyaltybot.repository.MailboxCursorRepository;
import com.plstk.loyaltybot.repository.MatchDecisionRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import com.plstk.loyaltybot.repository.SupplierOfferRepository;
import com.plstk.loyaltybot.repository.SupplierProductLinkRepository;
import com.plstk.loyaltybot.repository.SupplierRepository;
import com.plstk.loyaltybot.repository.SupplierSourceRepository;
import com.plstk.loyaltybot.service.TokenEncryptionService;
import com.plstk.loyaltybot.service.importing.fixtures.SupplierWorkbookFixtures;
import com.plstk.loyaltybot.service.importing.mailbox.ImapMailboxClient;
import com.plstk.loyaltybot.service.importing.mailbox.MailboxClient;
import com.plstk.loyaltybot.service.importing.mailbox.SupplierSourceMatcher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-031 (Section 7): the ONE end-to-end test in the suite that never fakes ANY part of the
 * email-transport boundary - a real (embedded) IMAP server (GreenMail), a real generated XLSX
 * workbook (byte-for-byte the same structure {@code SupplierWorkbookFixtures} produces for every
 * other test), and the PRODUCTION {@link ImapMailboxClient} (never {@code FakeMailboxClient}) -
 * driven through every real pipeline stage (poll -&gt; ingest -&gt; parse -&gt; normalize -&gt; match -&gt;
 * validate -&gt; apply) all the way to {@code APPLIED}, with a genuinely new product appearing on
 * the storefront query with the exact commission-adjusted price.
 *
 * <p>Every other suite ({@code SupplierImportEndToEndTest}) intentionally uses
 * {@code FakeMailboxClient} to keep those tests fast and focused on the pipeline logic itself
 * (protocol-level IMAP correctness is separately, exhaustively covered by
 * {@code ImapMailboxClientGreenMailTest}); this test's whole purpose is to prove those two
 * previously-separate pieces (real IMAP fetch + the real downstream pipeline) actually compose
 * correctly end-to-end, which neither of those two test classes alone verifies.
 */
@DataJpaTest
@Import(SupplierImportGreenMailEndToEndTest.TestConfig.class)
class SupplierImportGreenMailEndToEndTest {

    private static final String SHOP_ID = "shop-greenmail-e2e";
    private static final String SUPPLIER_EMAIL = "price@greenmail-supplier.test";
    private static final String SUPPLIER_PASSWORD = "app-password-e2e-123";

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.IMAP);

    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private SupplierSourceRepository supplierSourceRepository;
    @Autowired
    private MailboxConnectionRepository mailboxConnectionRepository;
    @Autowired
    private ImportBatchRepository importBatchRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private SupplierOfferRepository supplierOfferRepository;
    @Autowired
    private TokenEncryptionService tokenEncryptionService;
    @Autowired
    private MailboxPollingService mailboxPollingService;
    @Autowired
    private FakeAiSpreadsheetLayoutDetector fakeAiLayoutDetector;
    @Autowired
    private FakeAiCatalogMatcher fakeAiCatalogMatcher;
    @Autowired
    private ImportBatchParsingService parsingService;
    @Autowired
    private ImportBatchNormalizingService normalizingService;
    @Autowired
    private ImportBatchMatchingService matchingService;
    @Autowired
    private ImportBatchValidationService validationService;
    @Autowired
    private ImportBatchApplyService applyService;
    @Autowired
    private EntityManager entityManager;

    @org.junit.jupiter.api.io.TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void overrideStorageBasePath(DynamicPropertyRegistry registry) {
        registry.add("supplier-import.storage.base-path", tempDir::toString);
    }

    @BeforeEach
    void setUp() {
        fakeAiLayoutDetector.reset();
        fakeAiCatalogMatcher.reset();
        fakeAiCatalogMatcher.alwaysNoMatch();
    }

    @Test
    void realImapFetch_realXlsx_throughEveryPipelineStage_toAppliedWithCorrectCommissionPrice() throws Exception {
        Supplier supplier = supplierRepository.save(Supplier.builder().shopId(SHOP_ID).name("GreenMail Supplier").build());
        GreenMailUser user = greenMail.setUser(SUPPLIER_EMAIL, SUPPLIER_PASSWORD);
        MailboxConnection mailbox = mailboxConnectionRepository.save(MailboxConnection.builder()
                .shopId(SHOP_ID).label("main")
                .host("127.0.0.1").port(greenMail.getImap().getPort())
                .username(SUPPLIER_EMAIL)
                .encryptedSecret(tokenEncryptionService.encrypt(SUPPLIER_PASSWORD))
                .authMode(MailAuthMode.APP_PASSWORD)
                .folder("INBOX").useTls(false).enabled(true)
                .build());
        SupplierSource source = supplierSourceRepository.save(SupplierSource.builder()
                .shopId(SHOP_ID).supplier(supplier).label("main").mailboxConnection(mailbox)
                .senderAllowlist("greenmail-supplier.test")
                .snapshotMode(SnapshotMode.FULL).snapshotScope("ALL")
                .autoApply(true).shadowMode(false)
                .commissionPercentOverride(new BigDecimal("30.00"))
                .roundingPolicy(PriceRoundingPolicy.WHOLE_UNIT_HALF_UP)
                .build());
        entityManager.flush();

        // A real XLSX workbook (exact structure documented in the workspace rules), sent as a real
        // MIME attachment through a real embedded IMAP server - never a hand-built FetchedMessage.
        byte[] xlsxBytes = SupplierWorkbookFixtures.toBytes(SupplierWorkbookFixtures.standardLayoutWorkbook(
                List.of(SupplierWorkbookFixtures.row("SKU-NEW-1", "Nivea", "Крем для лица GreenMail 100 мл", new BigDecimal("1000.00"))),
                List.of()));
        deliverPriceListEmail(user, "price.xlsx", xlsxBytes);

        // 1. Real IMAP poll: production ImapMailboxClient connects to the embedded GreenMail
        //    server, lists the new message by UID, downloads the real attachment bytes.
        PollResult pollResult = mailboxPollingService.pollOne(mailbox.getId());
        entityManager.flush();
        entityManager.clear();
        assertEquals("SUCCESS", pollResult.status());
        assertEquals(1, pollResult.ingestedCount());

        List<ImportBatch> batches = importBatchRepository.findAll().stream()
                .filter(b -> b.getSupplierSource().getId().equals(source.getId()))
                .toList();
        assertEquals(1, batches.size());
        Long batchId = batches.get(0).getId();
        assertEquals(ImportBatchStatus.STORED, batches.get(0).getStatus());

        // 2. Real downstream pipeline: AI layout detection (faked, same as every other e2e suite -
        //    real DeepSeek HTTP calls are covered separately) -> parse -> normalize -> match -> validate -> apply.
        fakeAiLayoutDetector.enqueue(LayoutDetectionResponse.success(
                SupplierWorkbookFixtures.standardLayoutRuleJson(), "deepseek", "deepseek-chat", 100L));
        runToApply(batchId);

        ImportBatch applied = importBatchRepository.findById(batchId).orElseThrow();
        assertEquals(ImportBatchStatus.APPLIED, applied.getStatus(),
                "the batch ingested via a REAL IMAP fetch must reach APPLIED through the real pipeline");
        assertEquals(1, applied.getOffersAddedCount());

        // 3. Price 1000.00 + 30% commission = 1300.00 (Section 7 acceptance criterion), new product,
        //    visible on the exact storefront listing query.
        Product product = productRepository.findAll().stream()
                .filter(p -> p.getName() != null && p.getName().contains("GreenMail"))
                .findFirst().orElseThrow(() -> new AssertionError("product from the GreenMail-delivered file was not created"));
        assertEquals(new BigDecimal("1300.00"), product.getSalePrice());
        assertTrue(product.getVisible());

        SupplierOffer offer = supplierOfferRepository
                .findByShopIdAndSupplierIdAndSnapshotScopeAndProductId(SHOP_ID, supplier.getId(), "ALL", product.getId())
                .orElseThrow();
        assertEquals(new BigDecimal("1000.00"), offer.getSupplierPrice());
        assertEquals(new BigDecimal("1300.00"), offer.getCalculatedSitePrice());
        assertTrue(offer.getActive());

        List<Product> storefrontResults = productRepository.searchStorefrontProducts(
                SHOP_ID, null, null, null, Pageable.unpaged()).getContent();
        assertTrue(storefrontResults.stream().anyMatch(p -> p.getId().equals(product.getId())),
                "the product ingested via real IMAP must appear through the storefront listing query");
    }

    private void deliverPriceListEmail(GreenMailUser user, String filename, byte[] attachmentBytes) throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(SUPPLIER_EMAIL));
        message.setRecipients(Message.RecipientType.TO, SUPPLIER_EMAIL);
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
    }

    /** Runs parse -&gt; normalize -&gt; match -&gt; validate -&gt; (apply if AUTO_APPROVED) for one batch. */
    private void runToApply(Long batchId) {
        parsingService.parseBatch(batchId);
        entityManager.flush();
        entityManager.clear();
        normalizingService.normalizeBatch(batchId);
        entityManager.flush();
        entityManager.clear();
        matchingService.matchBatch(batchId);
        entityManager.flush();
        entityManager.clear();
        validationService.validateBatch(batchId);
        entityManager.flush();
        entityManager.clear();
        ImportBatch afterValidation = importBatchRepository.findById(batchId).orElseThrow();
        ImportBatchStatus statusAfterValidation = afterValidation.getStatus();
        entityManager.clear();
        if (statusAfterValidation == ImportBatchStatus.AUTO_APPROVED) {
            applyService.applyNewly(batchId);
            entityManager.flush();
            entityManager.clear();
        }
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
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
        ImportFileStorage importFileStorage(SupplierImportProperties properties) {
            return new LocalImportFileStorage(properties);
        }

        // ----- mailbox polling: REAL ImapMailboxClient, never FakeMailboxClient -----

        @Bean
        MailboxClient mailboxClient() {
            return new ImapMailboxClient();
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
        ImportJobClaimInsertWriter importJobClaimInsertWriter(
                com.plstk.loyaltybot.repository.ImportJobClaimRepository importJobClaimRepository) {
            return new ImportJobClaimInsertWriter(importJobClaimRepository);
        }

        @Bean
        ActiveClaimRegistry activeClaimRegistry() {
            return new ActiveClaimRegistry();
        }

        @Bean
        ImportJobClaimService importJobClaimService(
                com.plstk.loyaltybot.repository.ImportJobClaimRepository importJobClaimRepository,
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
                MailboxClient mailboxClient,
                SupplierSourceMatcher supplierSourceMatcher,
                AttachmentIngestionService attachmentIngestionService,
                ImportJobClaimService importJobClaimService,
                TokenEncryptionService tokenEncryptionService,
                MailboxPollStateWriter mailboxPollStateWriter,
                SupplierImportProperties properties,
                ObjectMapper objectMapper,
                SupplierImportMetrics metrics) {
            return new MailboxPollingService(
                    mailboxConnectionRepository, mailboxCursorRepository, supplierSourceRepository, mailboxClient,
                    supplierSourceMatcher, attachmentIngestionService, importJobClaimService, tokenEncryptionService,
                    mailboxPollStateWriter, properties, objectMapper, metrics);
        }

        // ----- parsing -----

        @Bean
        SpreadsheetParser spreadsheetParser(SupplierImportProperties properties) {
            return new SpreadsheetParser(properties);
        }

        @Bean
        LayoutRuleValidator layoutRuleValidator(ObjectMapper objectMapper) {
            return new LayoutRuleValidator(objectMapper);
        }

        @Bean
        FakeAiSpreadsheetLayoutDetector fakeAiSpreadsheetLayoutDetector() {
            return new FakeAiSpreadsheetLayoutDetector();
        }

        @Bean
        ImportBatchParseWriter importBatchParseWriter(
                ImportBatchRepository importBatchRepository,
                ImportRuleVersionRepository importRuleVersionRepository,
                ImportRowRepository importRowRepository,
                ObjectMapper objectMapper) {
            return new ImportBatchParseWriter(
                    importBatchRepository, importRuleVersionRepository, importRowRepository, objectMapper);
        }

        @Bean
        ImportBatchParsingService importBatchParsingService(
                ImportBatchRepository importBatchRepository,
                ImportRuleVersionRepository importRuleVersionRepository,
                ImportFileStorage importFileStorage,
                SpreadsheetParser spreadsheetParser,
                FakeAiSpreadsheetLayoutDetector fakeAiSpreadsheetLayoutDetector,
                LayoutRuleValidator layoutRuleValidator,
                SupplierImportProperties properties,
                ImportBatchParseWriter importBatchParseWriter) {
            return new ImportBatchParsingService(
                    importBatchRepository, importRuleVersionRepository, importFileStorage, spreadsheetParser,
                    fakeAiSpreadsheetLayoutDetector, layoutRuleValidator, properties, importBatchParseWriter);
        }

        // ----- normalizing -----

        @Bean
        RowAttributeNormalizer rowAttributeNormalizer(BrandAliasResolver brandAliasResolver) {
            return new RowAttributeNormalizer(brandAliasResolver);
        }

        @Bean
        BrandNormalizer brandNormalizer() {
            return new BrandNormalizer();
        }

        @Bean
        BrandAliasResolver brandAliasResolver(BrandAliasRepository brandAliasRepository, BrandNormalizer brandNormalizer) {
            return new BrandAliasResolver(brandAliasRepository, brandNormalizer);
        }

        @Bean
        CriticalAttributeConflictChecker criticalAttributeConflictChecker() {
            return new CriticalAttributeConflictChecker();
        }

        @Bean
        CandidateScorer candidateScorer(BrandAliasResolver brandAliasResolver, CriticalAttributeConflictChecker conflictChecker) {
            return new CandidateScorer(brandAliasResolver, conflictChecker);
        }

        @Bean
        ProductCandidateFetcher productCandidateFetcher(
                ProductRepository productRepository, BrandAliasResolver brandAliasResolver, BrandNormalizer brandNormalizer) {
            return new SimpleProductCandidateFetcher(productRepository, brandAliasResolver, brandNormalizer);
        }

        @Bean
        CandidateSearchService candidateSearchService(
                ProductCandidateFetcher candidateFetcher, RowAttributeNormalizer normalizer,
                CandidateScorer scorer, SupplierImportProperties properties, BrandAliasResolver brandAliasResolver) {
            return new CandidateSearchService(candidateFetcher, normalizer, scorer, properties, brandAliasResolver);
        }

        @Bean
        DeterministicMatchResolver deterministicMatchResolver(
                SupplierProductLinkRepository supplierProductLinkRepository,
                ProductRepository productRepository,
                RowAttributeNormalizer normalizer,
                CriticalAttributeConflictChecker conflictChecker,
                CandidateSearchService candidateSearchService,
                BrandNormalizer brandNormalizer,
                BrandAliasResolver brandAliasResolver) {
            return new DeterministicMatchResolver(
                    supplierProductLinkRepository, productRepository, normalizer, conflictChecker,
                    candidateSearchService, brandNormalizer, brandAliasResolver);
        }

        @Bean
        ImportBatchNormalizeWriter importBatchNormalizeWriter(
                ImportBatchRepository importBatchRepository,
                ImportRowRepository importRowRepository,
                ProductRepository productRepository,
                MatchDecisionRepository matchDecisionRepository,
                ObjectMapper objectMapper) {
            return new ImportBatchNormalizeWriter(
                    importBatchRepository, importRowRepository, productRepository, matchDecisionRepository, objectMapper);
        }

        @Bean
        ImportBatchNormalizingService importBatchNormalizingService(
                ImportBatchRepository importBatchRepository,
                ImportRowRepository importRowRepository,
                RowAttributeNormalizer normalizer,
                DeterministicMatchResolver matchResolver,
                ObjectMapper objectMapper,
                ImportBatchNormalizeWriter writer) {
            return new ImportBatchNormalizingService(
                    importBatchRepository, importRowRepository, normalizer, matchResolver, objectMapper, writer);
        }

        // ----- matching -----

        @Bean
        FakeAiCatalogMatcher fakeAiCatalogMatcher() {
            return new FakeAiCatalogMatcher();
        }

        @Bean
        CatalogMatchResponseValidator catalogMatchResponseValidator(ObjectMapper objectMapper) {
            return new CatalogMatchResponseValidator(objectMapper);
        }

        @Bean
        ImportBatchMatchWriter importBatchMatchWriter(
                ImportBatchRepository importBatchRepository,
                ImportRowRepository importRowRepository,
                ProductRepository productRepository,
                MatchDecisionRepository matchDecisionRepository,
                ObjectMapper objectMapper) {
            return new ImportBatchMatchWriter(
                    importBatchRepository, importRowRepository, productRepository, matchDecisionRepository, objectMapper);
        }

        @Bean
        ImportBatchMatchingService importBatchMatchingService(
                ImportBatchRepository importBatchRepository,
                ImportRowRepository importRowRepository,
                FakeAiCatalogMatcher fakeAiCatalogMatcher,
                CatalogMatchResponseValidator catalogMatchResponseValidator,
                SupplierImportProperties properties,
                ObjectMapper objectMapper,
                ImportBatchMatchWriter writer) {
            return new ImportBatchMatchingService(
                    importBatchRepository, importRowRepository, fakeAiCatalogMatcher, catalogMatchResponseValidator,
                    properties, objectMapper, writer);
        }

        // ----- validation (apply guards) -----

        @Bean
        BatchApplyGuardEvaluator batchApplyGuardEvaluator(
                ImportBatchRepository importBatchRepository,
                SupplierOfferRepository supplierOfferRepository,
                SupplierImportProperties properties,
                ObjectMapper objectMapper) {
            return new BatchApplyGuardEvaluator(importBatchRepository, supplierOfferRepository, properties, objectMapper);
        }

        @Bean
        ImportBatchValidationWriter importBatchValidationWriter(ImportBatchRepository importBatchRepository) {
            return new ImportBatchValidationWriter(importBatchRepository);
        }

        @Bean
        ImportBatchValidationService importBatchValidationService(
                ImportBatchRepository importBatchRepository,
                ImportRowRepository importRowRepository,
                BatchApplyGuardEvaluator guardEvaluator,
                ImportBatchValidationWriter writer,
                SupplierImportMetrics metrics) {
            return new ImportBatchValidationService(importBatchRepository, importRowRepository, guardEvaluator, writer, metrics);
        }

        // ----- apply -----

        @Bean
        PricingService pricingService(SupplierImportProperties properties) {
            return new PricingService(properties);
        }

        @Bean
        CatalogAvailabilityService catalogAvailabilityService(
                ProductRepository productRepository,
                SupplierOfferRepository supplierOfferRepository,
                PricingService pricingService) {
            return new CatalogAvailabilityService(productRepository, supplierOfferRepository, pricingService);
        }

        @Bean
        ImportBatchApplyWriter importBatchApplyWriter(
                ImportBatchRepository importBatchRepository,
                ImportRowRepository importRowRepository,
                ProductRepository productRepository,
                SupplierOfferRepository supplierOfferRepository,
                SupplierProductLinkRepository supplierProductLinkRepository,
                com.plstk.loyaltybot.repository.ShopSettingsRepository shopSettingsRepository,
                PricingService pricingService,
                CatalogAvailabilityService catalogAvailabilityService,
                ObjectMapper objectMapper,
                RowAttributeNormalizer normalizer,
                CriticalAttributeConflictChecker conflictChecker,
                BrandAliasResolver brandAliasResolver,
                ProductCreationLock productCreationLock) {
            return new ImportBatchApplyWriter(
                    importBatchRepository, importRowRepository, productRepository, supplierOfferRepository,
                    supplierProductLinkRepository, shopSettingsRepository, pricingService, catalogAvailabilityService,
                    objectMapper, normalizer, conflictChecker, brandAliasResolver, productCreationLock);
        }

        @Bean
        ProductCreationLock productCreationLock() {
            return new LocalProductCreationLock();
        }

        @Bean
        ImportBatchApplyService importBatchApplyService(ImportBatchApplyWriter writer, SupplierImportMetrics metrics) {
            return new ImportBatchApplyService(writer, metrics);
        }
    }
}
