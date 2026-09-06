package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportRuleVersion;
import com.plstk.loyaltybot.entity.importing.RuleVersionStatus;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportRuleVersionRepository;
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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forward-compatible "approve layout" operator action: a {@code DRAFT} rule version transitions to
 * {@code ACTIVE} and retires the previously-{@code ACTIVE} version for the same source.
 */
@DataJpaTest
@Import(ImportRuleVersionApprovalServiceTest.TestConfig.class)
class ImportRuleVersionApprovalServiceTest {

    private static final String SHOP_A = "shop-a";

    @Autowired
    private SupplierRepository supplierRepository;
    @Autowired
    private SupplierSourceRepository supplierSourceRepository;
    @Autowired
    private ImportRuleVersionRepository importRuleVersionRepository;
    @Autowired
    private ImportRuleVersionApprovalService importRuleVersionApprovalService;
    @Autowired
    private EntityManager entityManager;

    private SupplierSource source;

    @BeforeEach
    void setUp() {
        Supplier supplier = supplierRepository.save(Supplier.builder().shopId(SHOP_A).name("Supplier").build());
        source = supplierSourceRepository.save(SupplierSource.builder()
                .shopId(SHOP_A).supplier(supplier).label("main").build());
        flushClear();
    }

    @Test
    void approvingDraft_makesItActive_andRetiresThePreviouslyActiveVersion() {
        ImportRuleVersion oldActive = saveRuleVersion(1, RuleVersionStatus.ACTIVE);
        ImportRuleVersion draft = saveRuleVersion(2, RuleVersionStatus.DRAFT);
        flushClear();

        Optional<ImportRuleVersion> result = importRuleVersionApprovalService.approve(SHOP_A, draft.getId());

        assertTrue(result.isPresent());
        assertEquals(RuleVersionStatus.ACTIVE, result.get().getStatus());
        assertEquals(RuleVersionStatus.RETIRED, reload(oldActive.getId()).getStatus());
    }

    @Test
    void alreadyActive_throwsRowReviewException() {
        ImportRuleVersion active = saveRuleVersion(1, RuleVersionStatus.ACTIVE);
        flushClear();

        assertThrows(RowReviewException.class, () -> importRuleVersionApprovalService.approve(SHOP_A, active.getId()));
    }

    @Test
    void unknownId_returnsEmpty() {
        assertTrue(importRuleVersionApprovalService.approve(SHOP_A, 999_999L).isEmpty());
    }

    @Test
    void crossShopVersion_isNotVisibleToAnotherShop() {
        ImportRuleVersion draft = saveRuleVersion(1, RuleVersionStatus.DRAFT);
        flushClear();

        assertTrue(importRuleVersionApprovalService.approve("shop-b", draft.getId()).isEmpty());
    }

    // ===== helpers =====

    private ImportRuleVersion saveRuleVersion(int version, RuleVersionStatus status) {
        return importRuleVersionRepository.save(ImportRuleVersion.builder()
                .shopId(SHOP_A).supplierSource(source).version(version).status(status).ruleDefinition("{}").build());
    }

    private ImportRuleVersion reload(Long id) {
        return importRuleVersionRepository.findById(id).orElseThrow();
    }

    private void flushClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        ImportRuleVersionApprovalService importRuleVersionApprovalService(ImportRuleVersionRepository repository) {
            return new ImportRuleVersionApprovalService(repository);
        }
    }
}
