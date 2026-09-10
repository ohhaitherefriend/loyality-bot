package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportFile;
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
import com.plstk.loyaltybot.service.importing.mailbox.FakeMailboxClient;
import com.plstk.loyaltybot.service.importing.mailbox.FetchedMessage;
import com.plstk.loyaltybot.service.importing.mailbox.SupplierSourceMatcher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 09 production-hardening end-to-end coverage: runs the real orchestrator services for
 * every pipeline stage back-to-back against real (H2) repositories, the same way the production
 * scheduled jobs would call them one tick at a time, rather than exercising a single stage in
 * isolation like {@code ImportBatchParsingServiceTest}/{@code ImportBatchNormalizingServiceTest}/
 * {@code ImportBatchMatchingServiceTest}/{@code ImportBatchValidationServiceTest}/
 * {@code ImportBatchApplyServiceTest} do.
 *
 * <p>Only {@link AiSpreadsheetLayoutDetector} and {@link AiCatalogMatcher} are faked (real DeepSeek
 * HTTP calls are covered separately by {@code DeepSeekSpreadsheetLayoutDetectorTest}/
 * {@code DeepSeekCatalogMatcherTest}); every other bean here is the real production
 * implementation. The storefront boundary is asserted directly against
 * {@code ProductRepository.searchStorefrontProducts(...)} - the exact query
 * {@code StorefrontService.listProducts} runs - rather than through the full
 * {@code StorefrontService}/{@code StorefrontController}, since that service's other
 * dependencies (bot/Telegram/order/subscription machinery) are unrelated to what this suite is
 * verifying and would only add unrelated setup and fragility.
 */
@DataJpaTest
@Import(SupplierImportEndToEndTest.TestConfig.class)
class SupplierImportEndToEndTest {

    private static final String SHOP_ID = "shop-e2e";

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
    private ImportRowRepository importRowRepository;
    @Autowired
    private ImportRuleVersionRepository importRuleVersionRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private SupplierOfferRepository supplierOfferRepository;
    @Autowired
    private SupplierProductLinkRepository supplierProductLinkRepository;
    @Autowired
    private TokenEncryptionService tokenEncryptionService;
    @Autowired
    private ImportFileStorage importFileStorage;
    @Autowired
    private FakeMailboxClient fakeMailboxClient;
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
    private ImportBatchResumeService resumeService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EntityManager entityManager;

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void overrideStorageBasePath(DynamicPropertyRegistry registry) {
        registry.add("supplier-import.storage.base-path", tempDir::toString);
    }

    @BeforeEach
    void setUp() {
        fakeAiLayoutDetector.reset();
        fakeAiCatalogMatcher.reset();
        // Safe fallback for any row whose fuzzy candidate search happens to surface a weak,
        // unrelated candidate (e.g. shared trigrams/units) once the catalog is no longer empty -
        // AI matching itself is exhaustively covered by ImportBatchMatchingServiceTest/
        // DeepSeekCatalogMatcherTest, so this suite only needs a safe default rather than
        // hand-crafting a response for every incidental weak candidate a fixture produces.
        fakeAiCatalogMatcher.alwaysNoMatch();
    }

    // ========== 1. Happy path: email -&gt; attachment -&gt; AI layout -&gt; parse -&gt; match -&gt; gates -&gt;
    //               automatic apply -&gt; commission applied -&gt; new product appears on storefront ==========

    @Test
    void happyPath_emailToStorefront_newProductAutoAppliedWithCommission() {
        Supplier supplier = supplierRepository.save(Supplier.builder().shopId(SHOP_ID).name("Perfume Supplier").build());
        MailboxConnection mailbox = mailboxConnectionRepository.save(MailboxConnection.builder()
                .shopId(SHOP_ID).label("main").host("imap.mail.ru").port(993).username("shop@mail.ru")
                .encryptedSecret(tokenEncryptionService.encrypt("app-password"))
                .authMode(MailAuthMode.APP_PASSWORD).enabled(true)
                .build());
        SupplierSource source = supplierSourceRepository.save(SupplierSource.builder()
                .shopId(SHOP_ID).supplier(supplier).label("main").mailboxConnection(mailbox)
                .senderAllowlist("supplier.ru")
                .snapshotMode(SnapshotMode.FULL).snapshotScope("ALL")
                .autoApply(true).shadowMode(false)
                .commissionPercentOverride(new BigDecimal("30.00"))
                .roundingPolicy(PriceRoundingPolicy.WHOLE_UNIT_HALF_UP)
                .build());
        entityManager.flush();

        // 1. Email arrives with an xlsx attachment (real IMAP protocol covered separately by
        //    ImapMailboxClientGreenMailTest; FakeMailboxClient replays server-side UID filtering
        //    exactly like MailboxPollingServiceTest does).
        fakeMailboxClient.setUidValidity(1L);
        byte[] workbookBytes = SupplierWorkbookFixtures.toBytes(SupplierWorkbookFixtures.standardLayoutWorkbook());
        fakeMailboxClient.setServerMessages(List.of(new FetchedMessage(1L, "price@supplier.ru", "Price list",
                List.of(FakeMailboxClient.attachment("price.xlsx", "application/octet-stream", workbookBytes)))));

        PollResult pollResult = mailboxPollingService.pollOne(mailbox.getId());
        entityManager.flush();
        entityManager.clear();
        assertEquals("SUCCESS", pollResult.status());
        assertEquals(1, pollResult.ingestedCount());

        ImportBatch stored = onlyBatch(source.getId());
        assertEquals(ImportBatchStatus.STORED, stored.getStatus());
        Long batchId = stored.getId();

        // 2. AI layout detection (first batch for this source, no published rule yet).
        fakeAiLayoutDetector.enqueue(LayoutDetectionResponse.success(
                SupplierWorkbookFixtures.standardLayoutRuleJson(), "deepseek", "deepseek-chat", 120L));

        runToApply(batchId);

        assertEquals(1, fakeAiLayoutDetector.callCount(), "unknown layout must call AI exactly once");
        assertEquals(0, fakeAiCatalogMatcher.callCount(),
                "a brand new catalog has zero fuzzy candidates for every row, so NEW_PRODUCT is decided "
                        + "without ever calling the AI catalog matcher");

        ImportBatch applied = importBatchRepository.findById(batchId).orElseThrow();
        assertEquals(ImportBatchStatus.APPLIED, applied.getStatus());
        assertTrue(applied.getOffersAddedCount() >= 1);

        // 3. Commission applied: Chanel No 5 100 ml costs 12500.00 from the fixture, +30% commission.
        Product chanel = productRepository.findAll().stream()
                .filter(p -> p.getName() != null && p.getName().contains("Chanel No 5"))
                .findFirst().orElseThrow(() -> new AssertionError("Chanel No 5 product was not created"));
        assertEquals(new BigDecimal("16250.00"), chanel.getSalePrice());
        assertTrue(chanel.getVisible(), "a new product with an active offer must be visible");
        assertTrue(chanel.getActive());

        SupplierOffer offer = supplierOfferRepository
                .findByShopIdAndSupplierIdAndSnapshotScopeAndProductId(SHOP_ID, supplier.getId(), "ALL", chanel.getId())
                .orElseThrow();
        assertEquals(new BigDecimal("30.00"), offer.getAppliedCommissionPercent());
        assertTrue(offer.getActive());

        // 4. New product appears on storefront: the exact query StorefrontService.listProducts runs.
        List<Product> storefrontResults = productRepository.searchStorefrontProducts(
                SHOP_ID, null, null, null, Pageable.unpaged()).getContent();
        assertTrue(storefrontResults.stream().anyMatch(p -> p.getId().equals(chanel.getId())),
                "new product must be visible through the storefront product listing query");
    }

    // ========== 2. Snapshot reconciliation: disappearance / multi-supplier / reappearance ==========

    @Test
    void snapshotReconciliation_disappearance_multiSupplier_reappearance() {
        Supplier supplierA = supplierRepository.save(Supplier.builder().shopId(SHOP_ID).name("Supplier A").build());
        Supplier supplierB = supplierRepository.save(Supplier.builder().shopId(SHOP_ID).name("Supplier B").build());
        SupplierSource sourceA = saveSource(supplierA, "source-a");
        entityManager.flush();

        // Batch 1: FULL snapshot from Supplier A carrying two products.
        Long batch1 = createStoredBatch(sourceA, SupplierWorkbookFixtures.standardLayoutWorkbook(
                List.of(
                        SupplierWorkbookFixtures.row("ONLY-A", "Nivea", "Only-A cream 50 ml", new BigDecimal("100.00")),
                        SupplierWorkbookFixtures.row("SHARED", "Chanel", "Shared perfume 50 ml", new BigDecimal("200.00"))),
                List.of()));
        fakeAiLayoutDetector.enqueue(LayoutDetectionResponse.success(
                SupplierWorkbookFixtures.standardLayoutRuleJson(), "deepseek", "deepseek-chat", 100L));
        runToApply(batch1);
        assertEquals(ImportBatchStatus.APPLIED, reloadBatch(batch1).getStatus());

        Product onlyA = requireProductByName("Only-A cream 50 ml");
        Product shared = requireProductByName("Shared perfume 50 ml");
        assertTrue(onlyA.getVisible());
        assertTrue(shared.getVisible());

        // Supplier B also carries the SHARED product (seeded directly: cross-supplier fuzzy/AI
        // matching itself is already covered by ImportBatchMatchingServiceTest/DeepSeekCatalogMatcherTest;
        // this suite's job is to verify the Apply stage's multi-supplier availability rule when driven
        // through the real pipeline, not to re-derive the AI matching decision for a second supplier).
        supplierOfferRepository.save(SupplierOffer.builder()
                .shopId(SHOP_ID).supplier(supplierB).supplierSource(sourceA).snapshotScope("ALL").product(shared)
                .supplierPrice(new BigDecimal("190.00")).appliedCommissionPercent(new BigDecimal("30.00"))
                .calculatedSitePrice(new BigDecimal("247.00")).active(true).build());
        entityManager.flush();

        // Batch 2: next FULL snapshot from Supplier A no longer contains ONLY-A or SHARED (only an
        // unrelated filler row, so the "empty FULL snapshot" guard does not fire).
        Long batch2 = createStoredBatch(sourceA, SupplierWorkbookFixtures.standardLayoutWorkbook(
                List.of(SupplierWorkbookFixtures.row("FILLER", "Garnier", "Filler shampoo 400 ml", new BigDecimal("50.00"))),
                List.of()));
        runToApply(batch2);
        assertEquals(1, fakeAiLayoutDetector.callCount(),
                "same header layout must reuse the published rule, no additional AI call beyond batch1's initial one");
        assertEquals(ImportBatchStatus.APPLIED, reloadBatch(batch2).getStatus());

        SupplierOffer onlyAOfferAfterBatch2 = supplierOfferRepository
                .findByShopIdAndSupplierIdAndSnapshotScopeAndProductId(SHOP_ID, supplierA.getId(), "ALL", onlyA.getId())
                .orElseThrow();
        assertFalse(onlyAOfferAfterBatch2.getActive(), "Supplier A's offer must be deactivated when it disappears from a FULL snapshot");
        assertFalse(reloadProduct(onlyA.getId()).getVisible(),
                "a product with zero active offers must disappear from the storefront");

        SupplierOffer sharedOfferAfterBatch2 = supplierOfferRepository
                .findByShopIdAndSupplierIdAndSnapshotScopeAndProductId(SHOP_ID, supplierA.getId(), "ALL", shared.getId())
                .orElseThrow();
        assertFalse(sharedOfferAfterBatch2.getActive(), "Supplier A's own offer for SHARED must also be deactivated");
        assertTrue(reloadProduct(shared.getId()).getVisible(),
                "SHARED must remain visible on storefront because Supplier B's offer is still active");

        // Batch 3: ONLY-A and SHARED both reappear in the next FULL snapshot from Supplier A.
        Long batch3 = createStoredBatch(sourceA, SupplierWorkbookFixtures.standardLayoutWorkbook(
                List.of(
                        SupplierWorkbookFixtures.row("ONLY-A", "Nivea", "Only-A cream 50 ml", new BigDecimal("110.00")),
                        SupplierWorkbookFixtures.row("SHARED", "Chanel", "Shared perfume 50 ml", new BigDecimal("205.00"))),
                List.of()));
        runToApply(batch3);
        assertEquals(ImportBatchStatus.APPLIED, reloadBatch(batch3).getStatus());

        SupplierOffer onlyAOfferAfterBatch3 = supplierOfferRepository
                .findByShopIdAndSupplierIdAndSnapshotScopeAndProductId(SHOP_ID, supplierA.getId(), "ALL", onlyA.getId())
                .orElseThrow();
        assertTrue(onlyAOfferAfterBatch3.getActive(), "a reappearing offer must be reactivated automatically");
        assertTrue(reloadProduct(onlyA.getId()).getVisible(), "the product must return to the storefront automatically");
    }

    // ========== 3. Exception path: schema drift -&gt; quarantine -&gt; operator resume -&gt; success ==========

    @Test
    void schemaDrift_quarantinesBatch_thenOperatorResumeRetriesSuccessfully() {
        Supplier supplier = supplierRepository.save(Supplier.builder().shopId(SHOP_ID).name("Drift Supplier").build());
        SupplierSource source = saveSource(supplier, "drift-source");
        entityManager.flush();

        // Establish an ACTIVE rule via a first, normal batch.
        Long batch1 = createStoredBatch(source, SupplierWorkbookFixtures.standardLayoutWorkbook(
                List.of(SupplierWorkbookFixtures.row("SKU-1", "Nivea", "Baseline cream 50 ml", new BigDecimal("100.00"))),
                List.of()));
        fakeAiLayoutDetector.enqueue(LayoutDetectionResponse.success(
                SupplierWorkbookFixtures.standardLayoutRuleJson(), "deepseek", "deepseek-chat", 100L));
        runToApply(batch1);
        assertEquals(ImportBatchStatus.APPLIED, reloadBatch(batch1).getStatus());

        // Next file has schema drift (header moved, one column renamed) - the ACTIVE rule's header
        // signature no longer matches, so parsing falls back to AI detection. Simulate an AI/transport
        // anomaly on the first attempt.
        Long batch2 = createStoredBatch(source, SupplierWorkbookFixtures.driftedLayoutWorkbook());
        fakeAiLayoutDetector.enqueue(LayoutDetectionResponse.failure(
                "DeepSeek call failed after 3 attempt(s): Read timed out", true, "deepseek"));

        parsingService.parseBatch(batch2);
        entityManager.flush();
        entityManager.clear();

        ImportBatch quarantined = reloadBatch(batch2);
        assertEquals(ImportBatchStatus.QUARANTINED, quarantined.getStatus());
        assertTrue(quarantined.getErrorMessage() != null
                && quarantined.getErrorMessage().contains("AI layout detection failed"));

        // Operator decision: resume the quarantined batch (docs/ARCHITECTURE.md §12, same action as
        // ImportOperationsController's POST /batches/{batchId}/resume).
        Optional<ImportBatch> resumed = resumeService.resume(SHOP_ID, batch2);
        assertTrue(resumed.isPresent());
        assertEquals(ImportBatchStatus.STORED, resumed.get().getStatus(),
                "a batch that never got a ruleVersion must resume all the way back to STORED");
        assertEquals(2, resumed.get().getAttemptNumber());
        entityManager.flush();

        // Retry succeeds this time with the corrected layout rule for the drifted header.
        fakeAiLayoutDetector.enqueue(LayoutDetectionResponse.success(driftedLayoutRuleJson(), "deepseek", "deepseek-chat", 100L));
        runToApply(batch2);

        ImportBatch finalBatch = reloadBatch(batch2);
        assertEquals(ImportBatchStatus.APPLIED, finalBatch.getStatus());
        assertEquals(3, fakeAiLayoutDetector.callCount(),
                "batch1's initial layout call, plus one failed + one successful AI layout call for batch2's two attempts");

        Product product = requireProductByName("Крем для рук 100 мл");
        assertTrue(product.getVisible());
    }

    // ========== helpers ==========

    private SupplierSource saveSource(Supplier supplier, String label) {
        return supplierSourceRepository.save(SupplierSource.builder()
                .shopId(SHOP_ID).supplier(supplier).label(label)
                .snapshotMode(SnapshotMode.FULL).snapshotScope("ALL")
                .autoApply(true).shadowMode(false)
                .commissionPercentOverride(new BigDecimal("30.00"))
                .roundingPolicy(PriceRoundingPolicy.WHOLE_UNIT_HALF_UP)
                .build());
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
        // Clear before applying: otherwise the batch entity just loaded above (status
        // AUTO_APPROVED) stays cached in this @DataJpaTest's single shared persistence context, and
        // ImportBatchApplyWriter.applyBatch's own by-id lookup would silently return that same stale
        // managed instance instead of reflecting the APPLYING status the claim UPDATE below just
        // committed - a test-only artifact of one shared EntityManager across every stage. In
        // production each stage's @Transactional service method opens its own fresh EntityManager
        // since nothing wraps the scheduled job in an outer transaction, so this never happens there.
        entityManager.clear();
        if (statusAfterValidation == ImportBatchStatus.AUTO_APPROVED) {
            applyService.applyNewly(batchId);
            entityManager.flush();
            entityManager.clear();
        }
    }

    private ImportBatch onlyBatch(Long supplierSourceId) {
        List<ImportBatch> batches = importBatchRepository.findAll().stream()
                .filter(b -> b.getSupplierSource().getId().equals(supplierSourceId))
                .toList();
        assertEquals(1, batches.size());
        return batches.get(0);
    }

    private ImportBatch reloadBatch(Long batchId) {
        return importBatchRepository.findById(batchId).orElseThrow();
    }

    private Product reloadProduct(Long productId) {
        return productRepository.findById(productId).orElseThrow();
    }

    private Product requireProductByName(String nameSubstring) {
        return productRepository.findAll().stream()
                .filter(p -> p.getName() != null && p.getName().contains(nameSubstring))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Product containing '" + nameSubstring + "' was not found"));
    }

    private String driftedLayoutRuleJson() {
        return """
                {
                  "sheetSelectors": ["%s"],
                  "headerRow": 3,
                  "firstDataRow": 4,
                  "columns": {
                    "externalSku": {"headerAliases": ["Артикул"], "type": "STRING", "required": true},
                    "barcode": {"headerAliases": ["Штрихкод"], "type": "BARCODE", "required": false},
                    "rawName": {"headerAliases": ["Наименование"], "type": "STRING", "required": true},
                    "brand": {"headerAliases": ["Бренд"], "type": "STRING", "required": false},
                    "supplierPrice": {"headerAliases": ["Цена"], "type": "DECIMAL", "required": true}
                  }
                }
                """.formatted(SupplierWorkbookFixtures.SHEET_COSMETICS);
    }

    private Long createStoredBatch(SupplierSource source, Workbook workbook) {
        try {
            byte[] bytes = SupplierWorkbookFixtures.toBytes(workbook);
            Path tempFile = Files.createTempFile("e2e-workbook-", ".xlsx");
            Files.write(tempFile, bytes);
            String sha256 = sha256Hex(bytes);
            String storageKey = importFileStorage.store(SHOP_ID, sha256, "price.xlsx", tempFile);
            Files.deleteIfExists(tempFile);

            ImportFile importFile = importFileRepository.save(ImportFile.builder()
                    .shopId(SHOP_ID)
                    .supplierSource(source)
                    .sha256(sha256)
                    .sizeBytes((long) bytes.length)
                    .mediaType("application/octet-stream")
                    .originalFilename("price.xlsx")
                    .storageKey(storageKey)
                    .receivedAt(LocalDateTime.now())
                    .build());

            ImportBatch batch = importBatchRepository.save(ImportBatch.builder()
                    .shopId(SHOP_ID)
                    .supplierSource(source)
                    .importFile(importFile)
                    .status(ImportBatchStatus.STORED)
                    .attemptNumber(1)
                    .build());
            entityManager.flush();
            entityManager.clear();
            return batch.getId();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
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

        // ----- mailbox polling (happy path only) -----

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
        RowAttributeNormalizer rowAttributeNormalizer() {
            return new RowAttributeNormalizer();
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
                ObjectMapper objectMapper) {
            return new ImportBatchApplyWriter(
                    importBatchRepository, importRowRepository, productRepository, supplierOfferRepository,
                    supplierProductLinkRepository, shopSettingsRepository, pricingService, catalogAvailabilityService,
                    objectMapper);
        }

        @Bean
        ImportBatchApplyService importBatchApplyService(ImportBatchApplyWriter writer, SupplierImportMetrics metrics) {
            return new ImportBatchApplyService(writer, metrics);
        }

        // ----- resume (exception path operator action) -----

        @Bean
        ImportBatchResumeService importBatchResumeService(
                ImportBatchRepository importBatchRepository, ImportRowRepository importRowRepository) {
            return new ImportBatchResumeService(importBatchRepository, importRowRepository);
        }
    }
}
