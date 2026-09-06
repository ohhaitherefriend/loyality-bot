package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportRuleVersion;
import com.plstk.loyaltybot.entity.importing.RuleVersionStatus;
import com.plstk.loyaltybot.repository.ImportRuleVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Prompt 07 "approve layout" operator action (docs/ARCHITECTURE.md §12). Forward-compatible only:
 * as of Prompt 03/07, {@link ImportBatchParsingService} always auto-publishes an AI-detected layout
 * straight to {@link RuleVersionStatus#ACTIVE} (or quarantines the batch) - nothing today ever
 * creates a {@link RuleVersionStatus#DRAFT} row for a human to approve. This service exists so the
 * UI action and endpoint are already wired for the day a "quarantine instead of auto-publish, let a
 * human approve the layout first" mode is added, without another migration/endpoint round-trip.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportRuleVersionApprovalService {

    private final ImportRuleVersionRepository importRuleVersionRepository;

    /** @return empty if not found for this shop; the caller distinguishes "not DRAFT" via the current status. */
    @Transactional
    public Optional<ImportRuleVersion> approve(String shopId, Long ruleVersionId) {
        Optional<ImportRuleVersion> found = importRuleVersionRepository.findByShopIdAndId(shopId, ruleVersionId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ImportRuleVersion version = found.get();
        if (version.getStatus() != RuleVersionStatus.DRAFT) {
            throw new RowReviewException(
                    "Rule version " + ruleVersionId + " is not DRAFT (current status " + version.getStatus() + ")");
        }
        importRuleVersionRepository
                .findByShopIdAndSupplierSourceIdAndStatus(shopId, version.getSupplierSource().getId(), RuleVersionStatus.ACTIVE)
                .ifPresent(old -> {
                    old.setStatus(RuleVersionStatus.RETIRED);
                    importRuleVersionRepository.save(old);
                });
        version.setStatus(RuleVersionStatus.ACTIVE);
        version = importRuleVersionRepository.save(version);
        log.info("Rule version {} (shop {}) approved DRAFT -> ACTIVE by operator", ruleVersionId, shopId);
        return Optional.of(version);
    }
}
