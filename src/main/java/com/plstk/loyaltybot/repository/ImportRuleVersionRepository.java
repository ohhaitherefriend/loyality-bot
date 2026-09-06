package com.plstk.loyaltybot.repository;

import com.plstk.loyaltybot.entity.importing.ImportRuleVersion;
import com.plstk.loyaltybot.entity.importing.RuleVersionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ImportRuleVersionRepository extends JpaRepository<ImportRuleVersion, Long> {

    List<ImportRuleVersion> findByShopIdAndSupplierSourceId(String shopId, Long supplierSourceId);

    Optional<ImportRuleVersion> findByShopIdAndSupplierSourceIdAndStatus(
            String shopId, Long supplierSourceId, RuleVersionStatus status);

    Optional<ImportRuleVersion> findTopBySupplierSourceIdOrderByVersionDesc(Long supplierSourceId);

    /** Prompt 07 "approve layout" admin action (forward-compatible - see {@code ImportRuleVersionApprovalService}). */
    Optional<ImportRuleVersion> findByShopIdAndId(String shopId, Long id);
}
