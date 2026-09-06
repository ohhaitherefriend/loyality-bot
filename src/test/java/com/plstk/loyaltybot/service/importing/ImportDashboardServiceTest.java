package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.DecidedBy;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportFile;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.MailAuthMode;
import com.plstk.loyaltybot.entity.importing.MailboxConnection;
import com.plstk.loyaltybot.entity.importing.MatchDecision;
import com.plstk.loyaltybot.entity.importing.MatchDecisionType;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import com.plstk.loyaltybot.repository.MailboxConnectionRepository;
import com.plstk.loyaltybot.repository.MatchDecisionRepository;
import com.plstk.loyaltybot.repository.SupplierRepository;
import com.plstk.loyaltybot.repository.SupplierSourceRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 07 automation-control-panel dashboard: automation rate, batch status counts, exception
 * queue size, mailbox health, per-supplier exception rates, and the recent-activity/product-change
 * summaries - each aggregated purely from existing pipeline state.
 */
@DataJpaTest
@Import(ImportDashboardServiceTest.TestConfig.class)
class ImportDashboardServiceTest {

    private static final String SHOP_A = "shop-a";

    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private SupplierSourceRepository supplierSourceRepository;
    @Autowired
    private ImportFileRepository importFileRepository;
    @Autowired
    private ImportBatchRepository importBatchRepository;
    @Autowired
    private ImportRowRepository importRowRepository;
    @Autowired
    private MailboxConnectionRepository mailboxConnectionRepository;
    @Autowired
    private MatchDecisionRepository matchDecisionRepository;
    @Autowired
    private ImportDashboardService importDashboardService;
    @Autowired
    private EntityManager entityManager;

    private Supplier supplier;
    private SupplierSource source;

    @BeforeEach
    void setUp() {
        supplier = supplierRepository.save(Supplier.builder().shopId(SHOP_A).name("Supplier").build());
        source = supplierSourceRepository.save(SupplierSource.builder()
                .shopId(SHOP_A).supplier(supplier).label("main").build());
        flushClear();
    }

    @Test
    void automationRate_countsAutoDecidedAndHumanDecidedRowsSeparately() {
        Long batchId = createBatch(ImportBatchStatus.APPLIED);
        addRow(batchId, ImportRowStatus.APPLIED); // auto
        addRow(batchId, ImportRowStatus.AUTO_APPROVED); // auto
        ImportRow humanRow = addRow(batchId, ImportRowStatus.APPROVED); // human-reviewed
        flushClear();

        matchDecisionRepository.save(MatchDecision.builder()
                .shopId(SHOP_A).importRow(reload(humanRow.getId())).decisionType(MatchDecisionType.MANUAL)
                .decidedBy(DecidedBy.HUMAN).reviewerUserId(1L).reviewerEmail("op@shop.test").build());
        flushClear();

        ImportDashboardResponse dashboard = importDashboardService.buildDashboard(SHOP_A, 24);

        assertEquals(2, dashboard.automationRate().autoDecidedRows());
        assertEquals(1, dashboard.automationRate().humanDecidedRows());
        assertEquals(200.0 / 3, dashboard.automationRate().ratePercent(), 0.001);
    }

    @Test
    void automationRate_withNoDecisionsAtAll_hasNullRate() {
        ImportDashboardResponse dashboard = importDashboardService.buildDashboard(SHOP_A, 24);
        assertEquals(0, dashboard.automationRate().autoDecidedRows());
        assertEquals(0, dashboard.automationRate().humanDecidedRows());
        assertEquals(null, dashboard.automationRate().ratePercent());
    }

    @Test
    void batchCounts_bucketsEachStatusCorrectly() {
        createBatch(ImportBatchStatus.MATCHING); // running
        createBatch(ImportBatchStatus.NEEDS_ATTENTION);
        createBatch(ImportBatchStatus.FAILED);
        createBatch(ImportBatchStatus.QUARANTINED);
        createBatch(ImportBatchStatus.APPLIED);
        flushClear();

        ImportDashboardResponse dashboard = importDashboardService.buildDashboard(SHOP_A, 24);

        assertEquals(5, dashboard.batches().total());
        assertEquals(1, dashboard.batches().running());
        assertEquals(1, dashboard.batches().needsAttention());
        assertEquals(1, dashboard.batches().failed());
        assertEquals(1, dashboard.batches().quarantined());
        assertEquals(1, dashboard.batches().applied());
    }

    @Test
    void exceptionQueueSize_addsExceptionRowsAndQuarantinedBatches() {
        Long okBatch = createBatch(ImportBatchStatus.NEEDS_ATTENTION);
        addRow(okBatch, ImportRowStatus.NEEDS_REVIEW);
        addRow(okBatch, ImportRowStatus.INVALID);
        createBatch(ImportBatchStatus.QUARANTINED);
        flushClear();

        ImportDashboardResponse dashboard = importDashboardService.buildDashboard(SHOP_A, 24);

        assertEquals(3, dashboard.exceptionQueueSize());
    }

    @Test
    void mailboxHealth_flagsRecentSuccessAsHealthy_andFailureOrStaleAsUnhealthy() {
        mailboxConnectionRepository.save(mailbox("healthy", true, LocalDateTime.now().minusMinutes(10), null));
        mailboxConnectionRepository.save(mailbox("stale", true, LocalDateTime.now().minusHours(12), null));
        mailboxConnectionRepository.save(mailbox("erroring", true, LocalDateTime.now().minusMinutes(5), "IMAP timeout"));
        mailboxConnectionRepository.save(mailbox("disabled", false, LocalDateTime.now().minusMinutes(1), null));
        flushClear();

        ImportDashboardResponse dashboard = importDashboardService.buildDashboard(SHOP_A, 24);

        assertEquals(4, dashboard.mailboxes().size());
        assertTrue(dashboard.mailboxes().stream().anyMatch(m -> m.label().equals("healthy") && m.healthy()));
        assertTrue(dashboard.mailboxes().stream().anyMatch(m -> m.label().equals("stale") && !m.healthy()));
        assertTrue(dashboard.mailboxes().stream().anyMatch(m -> m.label().equals("erroring") && !m.healthy()));
        assertTrue(dashboard.mailboxes().stream().anyMatch(m -> m.label().equals("disabled") && !m.healthy()));
    }

    @Test
    void supplierExceptionRates_computesPercentPerSupplier() {
        Long batchId = createBatch(ImportBatchStatus.NEEDS_ATTENTION);
        addRow(batchId, ImportRowStatus.NEEDS_REVIEW);
        addRow(batchId, ImportRowStatus.APPLIED);
        addRow(batchId, ImportRowStatus.APPLIED);
        addRow(batchId, ImportRowStatus.APPLIED);
        flushClear();

        ImportDashboardResponse dashboard = importDashboardService.buildDashboard(SHOP_A, 24);

        assertEquals(1, dashboard.supplierExceptionRates().size());
        ImportDashboardResponse.SupplierExceptionRate rate = dashboard.supplierExceptionRates().get(0);
        assertEquals(supplier.getId(), rate.supplierId());
        assertEquals(4, rate.totalRows());
        assertEquals(1, rate.exceptionRows());
        assertEquals(25.0, rate.exceptionRatePercent(), 0.001);
    }

    @Test
    void recentActivity_countsFilesAndRowsWithinWindow_excludesOlder() {
        createFileWithReceivedAt(LocalDateTime.now().minusHours(1)); // within window
        createFileWithReceivedAt(LocalDateTime.now().minusHours(48)); // outside window
        Long batchId = createBatch(ImportBatchStatus.APPLIED); // adds one more recent file
        addRow(batchId, ImportRowStatus.APPLIED);
        flushClear();

        ImportDashboardResponse dashboard = importDashboardService.buildDashboard(SHOP_A, 24);

        assertEquals(24, dashboard.recentActivity().windowHours());
        assertEquals(2, dashboard.recentActivity().filesProcessed());
        assertEquals(1, dashboard.recentActivity().rowsProcessed());
    }

    @Test
    void productChanges_sumsApplyCountersAcrossBatchesAppliedWithinWindow() {
        Long batchId = createBatch(ImportBatchStatus.APPLIED);
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        batch.setAppliedAt(LocalDateTime.now().minusHours(1));
        batch.setOffersAddedCount(3);
        batch.setOffersUpdatedCount(2);
        batch.setOffersPriceChangedCount(1);
        batch.setProductsRemovedFromStorefrontCount(1);
        batch.setProductsReactivatedCount(1);
        importBatchRepository.save(batch);
        flushClear();

        ImportDashboardResponse dashboard = importDashboardService.buildDashboard(SHOP_A, 24);

        assertEquals(3, dashboard.recentProductChanges().added());
        assertEquals(2, dashboard.recentProductChanges().updated());
        assertEquals(1, dashboard.recentProductChanges().priceChanged());
        assertEquals(1, dashboard.recentProductChanges().removedFromStorefront());
        assertEquals(1, dashboard.recentProductChanges().reactivated());
    }

    @Test
    void shopIsolation_dashboardNeverIncludesAnotherShopsData() {
        Supplier supplierB = supplierRepository.save(Supplier.builder().shopId("shop-b").name("Supplier B").build());
        SupplierSource sourceB = supplierSourceRepository.save(SupplierSource.builder()
                .shopId("shop-b").supplier(supplierB).label("main").build());
        ImportFile fileB = importFileRepository.save(ImportFile.builder()
                .shopId("shop-b").supplierSource(sourceB).sha256("sha-b").sizeBytes(1L)
                .mediaType("application/octet-stream").originalFilename("b.xlsx")
                .storageKey("key-b").receivedAt(LocalDateTime.now()).build());
        importBatchRepository.save(ImportBatch.builder()
                .shopId("shop-b").supplierSource(sourceB).importFile(fileB)
                .status(ImportBatchStatus.QUARANTINED).attemptNumber(1).build());
        flushClear();

        ImportDashboardResponse dashboard = importDashboardService.buildDashboard(SHOP_A, 24);

        assertEquals(0, dashboard.batches().total());
        assertEquals(0, dashboard.exceptionQueueSize());
    }

    // ===== helpers =====

    private Long createBatch(ImportBatchStatus status) {
        ImportFile importFile = importFileRepository.save(ImportFile.builder()
                .shopId(SHOP_A).supplierSource(source).sha256("sha-" + System.nanoTime())
                .sizeBytes(1L).mediaType("application/octet-stream").originalFilename("price.xlsx")
                .storageKey("key-" + System.nanoTime()).receivedAt(LocalDateTime.now()).build());
        ImportBatch batch = importBatchRepository.save(ImportBatch.builder()
                .shopId(SHOP_A).supplierSource(source).importFile(importFile)
                .status(status).attemptNumber(1).build());
        entityManager.flush();
        return batch.getId();
    }

    private void createFileWithReceivedAt(LocalDateTime receivedAt) {
        importFileRepository.save(ImportFile.builder()
                .shopId(SHOP_A).supplierSource(source).sha256("sha-" + System.nanoTime())
                .sizeBytes(1L).mediaType("application/octet-stream").originalFilename("price.xlsx")
                .storageKey("key-" + System.nanoTime()).receivedAt(receivedAt).build());
    }

    private ImportRow addRow(Long batchId, ImportRowStatus status) {
        ImportBatch batch = importBatchRepository.findById(batchId).orElseThrow();
        return importRowRepository.save(ImportRow.builder()
                .shopId(SHOP_A).importBatch(batch).sourceRowNumber(1).rawData("{}").status(status).build());
    }

    private MailboxConnection mailbox(String label, boolean enabled, LocalDateTime lastPollSuccessAt, String lastPollError) {
        return MailboxConnection.builder()
                .shopId(SHOP_A).label(label).host("imap.mail.ru").port(993).username("shop@mail.ru")
                .authMode(MailAuthMode.APP_PASSWORD).enabled(enabled)
                .lastPollAt(LocalDateTime.now()).lastPollSuccessAt(lastPollSuccessAt).lastPollError(lastPollError)
                .build();
    }

    private ImportRow reload(Long rowId) {
        return importRowRepository.findById(rowId).orElseThrow();
    }

    private void flushClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        ImportDashboardService importDashboardService(
                ImportBatchRepository importBatchRepository,
                ImportRowRepository importRowRepository,
                ImportFileRepository importFileRepository,
                MailboxConnectionRepository mailboxConnectionRepository,
                MatchDecisionRepository matchDecisionRepository) {
            return new ImportDashboardService(
                    importBatchRepository, importRowRepository, importFileRepository,
                    mailboxConnectionRepository, matchDecisionRepository);
        }
    }
}
