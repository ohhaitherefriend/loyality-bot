package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportFile;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.SnapshotMode;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import com.plstk.loyaltybot.repository.MailboxConnectionRepository;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Six-bug hardening pass regression: {@code autoApply} must only ever be reachable through the
 * SAME safety gate (successful shadow run, no open blocking batches, no unresolved NEEDS_REVIEW
 * rows, non-empty senderAllowlist) whether the caller uses a plain PATCH ({@link
 * SupplierSourceAdminService#updateSource}) or {@code /graduate} ({@link
 * SupplierSourceAdminService#graduate}) - a plain PATCH must never be an easier path (ADR-025).
 */
@DataJpaTest
@Import(SupplierSourceAdminServiceTest.TestConfig.class)
class SupplierSourceAdminServiceTest {

    private static final String SHOP_ID = "shop-source-admin";

    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private SupplierSourceRepository supplierSourceRepository;
    @Autowired
    private ImportBatchRepository importBatchRepository;
    @Autowired
    private ImportRowRepository importRowRepository;
    @Autowired
    private ImportFileRepository importFileRepository;
    @Autowired
    private SupplierSourceAdminService service;
    @Autowired
    private EntityManager entityManager;

    private Supplier supplier;

    @BeforeEach
    void setUp() {
        supplier = supplierRepository.save(Supplier.builder().shopId(SHOP_ID).name("Test Supplier").build());
        entityManager.flush();
    }

    private SupplierSource saveSource(String senderAllowlist, boolean shadowMode) {
        return supplierSourceRepository.save(SupplierSource.builder()
                .shopId(SHOP_ID).supplier(supplier).label("main-" + System.nanoTime())
                .senderAllowlist(senderAllowlist)
                .snapshotMode(SnapshotMode.FULL).snapshotScope("ALL")
                .autoApply(false).shadowMode(shadowMode)
                .build());
    }

    private void saveSuccessfulShadowBatch(SupplierSource source) {
        ImportFile file = importFileRepository.save(ImportFile.builder()
                .shopId(SHOP_ID).supplierSource(source).sha256("sha-" + System.nanoTime())
                .sizeBytes(10L).mediaType("application/octet-stream").originalFilename("price.xlsx")
                .storageKey("key-" + System.nanoTime()).receivedAt(LocalDateTime.now()).build());
        importBatchRepository.save(ImportBatch.builder()
                .shopId(SHOP_ID).supplierSource(source).importFile(file)
                .status(ImportBatchStatus.APPLIED).attemptNumber(1).build());
        entityManager.flush();
    }

    /**
     * Mirrors the exact bypass the report reproduced: a plain PATCH that flips {@code shadowMode}
     * off and {@code autoApply} on together in one request (the same net effect as {@code
     * /graduate}), without going through any of {@code /graduate}'s own safety checks.
     */
    private UpdateSupplierSourceRequest patchAutoApply(boolean confirmAutoApply) {
        return new UpdateSupplierSourceRequest(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, false, true, confirmAutoApply, null, null, null, null);
    }

    @Test
    void patch_autoApplyOn_withoutAnySuccessfulShadowBatch_isRejected_sameGateAsGraduate() {
        SupplierSource source = saveSource("supplier.ru", true);
        entityManager.flush();

        SupplierSourceValidationException ex = assertThrows(SupplierSourceValidationException.class,
                () -> service.updateSource(SHOP_ID, source.getId(), patchAutoApply(true)));

        assertTrue(ex.getFieldErrors().containsKey("shadowMode"),
                "PATCH must enforce the same 'at least one successful shadow batch' gate as /graduate");
    }

    @Test
    void patch_autoApplyOn_withEmptySenderAllowlist_isRejected() {
        SupplierSource source = saveSource(null, true);
        saveSuccessfulShadowBatch(source);

        SupplierSourceValidationException ex = assertThrows(SupplierSourceValidationException.class,
                () -> service.updateSource(SHOP_ID, source.getId(), patchAutoApply(true)));

        assertTrue(ex.getFieldErrors().containsKey("senderAllowlist"),
                "an empty sender allowlist must block autoApply - it would accept mail from any sender");
    }

    @Test
    void patch_autoApplyOn_withUnresolvedNeedsReviewRow_isRejected() {
        SupplierSource source = saveSource("supplier.ru", true);
        saveSuccessfulShadowBatch(source);
        ImportFile file = importFileRepository.save(ImportFile.builder()
                .shopId(SHOP_ID).supplierSource(source).sha256("sha-pending")
                .sizeBytes(10L).mediaType("application/octet-stream").originalFilename("price2.xlsx")
                .storageKey("key-pending").receivedAt(LocalDateTime.now()).build());
        ImportBatch pendingBatch = importBatchRepository.save(ImportBatch.builder()
                .shopId(SHOP_ID).supplierSource(source).importFile(file)
                .status(ImportBatchStatus.NEEDS_ATTENTION).attemptNumber(1).build());
        importRowRepository.save(ImportRow.builder()
                .shopId(SHOP_ID).importBatch(pendingBatch).sourceRowNumber(1).rawData("{}")
                .status(ImportRowStatus.NEEDS_REVIEW).build());
        entityManager.flush();

        SupplierSourceValidationException ex = assertThrows(SupplierSourceValidationException.class,
                () -> service.updateSource(SHOP_ID, source.getId(), patchAutoApply(true)));

        assertTrue(ex.getFieldErrors().containsKey("rows"));
    }

    @Test
    void patch_autoApplyOn_whenAllGatesPass_succeeds() {
        SupplierSource source = saveSource("supplier.ru", true);
        saveSuccessfulShadowBatch(source);

        SupplierSource updated = service.updateSource(SHOP_ID, source.getId(), patchAutoApply(true));

        assertTrue(updated.getAutoApply());
    }

    @Test
    void createSource_withEmptySenderAllowlist_isStillAllowed_butCannotLaterAutoApply() {
        // Creating a shadow-mode source with no allowlist yet is allowed (a freshly created source
        // is not yet dangerous - it can't apply anything until it's graduated), but it must never be
        // possible to flip it straight to autoApply while the allowlist is still empty.
        SupplierSource source = service.createSource(SHOP_ID, supplier.getId(), "no-allowlist-yet", null, null, null, null);
        assertTrue(source.getSenderAllowlist() == null || source.getSenderAllowlist().isBlank());

        saveSuccessfulShadowBatch(source);
        SupplierSourceValidationException ex = assertThrows(SupplierSourceValidationException.class,
                () -> service.updateSource(SHOP_ID, source.getId(), patchAutoApply(true)));
        assertTrue(ex.getFieldErrors().containsKey("senderAllowlist"));
    }

    @Test
    void graduate_withoutConfirm_isRejected() {
        SupplierSource source = saveSource("supplier.ru", true);

        assertThrows(SupplierSourceValidationException.class, () -> service.graduate(SHOP_ID, source.getId(), false));
    }

    @Test
    void graduate_withEmptySenderAllowlist_isRejected_sameGateAsPatch() {
        SupplierSource source = saveSource(null, true);
        saveSuccessfulShadowBatch(source);

        SupplierSourceValidationException ex = assertThrows(SupplierSourceValidationException.class,
                () -> service.graduate(SHOP_ID, source.getId(), true));

        assertTrue(ex.getFieldErrors().containsKey("senderAllowlist"));
    }

    @Test
    void graduate_whenAllGatesPass_turnsOffShadowModeAndOnAutoApply() {
        SupplierSource source = saveSource("supplier.ru", true);
        saveSuccessfulShadowBatch(source);

        SupplierSource updated = service.graduate(SHOP_ID, source.getId(), true);

        assertFalse(updated.getShadowMode());
        assertTrue(updated.getAutoApply());
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        SupplierSourceAdminService supplierSourceAdminService(
                SupplierRepository supplierRepository,
                SupplierSourceRepository supplierSourceRepository,
                MailboxConnectionRepository mailboxConnectionRepository,
                ImportBatchRepository importBatchRepository,
                ImportRowRepository importRowRepository) {
            return new SupplierSourceAdminService(
                    supplierRepository, supplierSourceRepository, mailboxConnectionRepository,
                    importBatchRepository, importRowRepository);
        }
    }
}
