package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.MailboxConnection;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportFileRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import com.plstk.loyaltybot.repository.MailboxConnectionRepository;
import com.plstk.loyaltybot.repository.MatchDecisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Builds the Prompt 07 automation control-panel dashboard (docs/ARCHITECTURE.md §12/§15): the one
 * screen an operator should be able to trust without opening every successful batch. Read-only,
 * built entirely from existing pipeline state - no new counters are written anywhere else.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ImportDashboardService {

    private static final List<ImportBatchStatus> RUNNING_STATUSES = List.of(
            ImportBatchStatus.RECEIVED, ImportBatchStatus.STORED, ImportBatchStatus.PARSING,
            ImportBatchStatus.NORMALIZING, ImportBatchStatus.MATCHING, ImportBatchStatus.VALIDATING,
            ImportBatchStatus.AUTO_APPROVED, ImportBatchStatus.APPROVED, ImportBatchStatus.APPLYING);

    private static final List<ImportRowStatus> EXCEPTION_ROW_STATUSES =
            List.of(ImportRowStatus.NEEDS_REVIEW, ImportRowStatus.INVALID);

    /** A mailbox that has never once completed a successful poll, or whose last poll failed, needs attention. */
    private static final Duration STALE_POLL_THRESHOLD = Duration.ofHours(6);

    private final ImportBatchRepository importBatchRepository;
    private final ImportRowRepository importRowRepository;
    private final ImportFileRepository importFileRepository;
    private final MailboxConnectionRepository mailboxConnectionRepository;
    private final MatchDecisionRepository matchDecisionRepository;

    public ImportDashboardResponse buildDashboard(String shopId, int windowHours) {
        LocalDateTime since = LocalDateTime.now().minusHours(windowHours);

        return new ImportDashboardResponse(
                buildAutomationRate(shopId),
                buildBatchCounts(shopId),
                buildExceptionQueueSize(shopId),
                buildMailboxHealth(shopId),
                buildSupplierExceptionRates(shopId),
                buildRecentActivity(shopId, windowHours, since),
                buildProductChanges(shopId, windowHours, since));
    }

    private ImportDashboardResponse.AutomationRate buildAutomationRate(String shopId) {
        long autoDecided = importRowRepository.countByShopIdAndStatusIn(
                shopId, List.of(ImportRowStatus.AUTO_APPROVED, ImportRowStatus.APPLIED));
        long humanDecided = matchDecisionRepository.countDistinctRowsWithHumanDecision(shopId);
        long total = autoDecided + humanDecided;
        Double ratePercent = total == 0 ? null : (autoDecided * 100.0) / total;
        return new ImportDashboardResponse.AutomationRate(autoDecided, humanDecided, ratePercent);
    }

    private ImportDashboardResponse.BatchStatusCounts buildBatchCounts(String shopId) {
        long total = importBatchRepository.countByShopId(shopId);
        long running = importBatchRepository.countByShopIdAndStatusIn(shopId, RUNNING_STATUSES);
        long needsAttention = importBatchRepository.countByShopIdAndStatus(shopId, ImportBatchStatus.NEEDS_ATTENTION);
        long failed = importBatchRepository.countByShopIdAndStatus(shopId, ImportBatchStatus.FAILED);
        long quarantined = importBatchRepository.countByShopIdAndStatus(shopId, ImportBatchStatus.QUARANTINED);
        long applied = importBatchRepository.countByShopIdAndStatus(shopId, ImportBatchStatus.APPLIED);
        return new ImportDashboardResponse.BatchStatusCounts(total, running, needsAttention, failed, quarantined, applied);
    }

    private long buildExceptionQueueSize(String shopId) {
        long exceptionRows = importRowRepository.countByShopIdAndStatusIn(shopId, EXCEPTION_ROW_STATUSES);
        long quarantinedBatches = importBatchRepository.countByShopIdAndStatus(shopId, ImportBatchStatus.QUARANTINED);
        return exceptionRows + quarantinedBatches;
    }

    private List<ImportDashboardResponse.MailboxHealthEntry> buildMailboxHealth(String shopId) {
        LocalDateTime staleThreshold = LocalDateTime.now().minus(STALE_POLL_THRESHOLD);
        return mailboxConnectionRepository.findByShopId(shopId).stream()
                .map(m -> toMailboxHealth(m, staleThreshold))
                .toList();
    }

    private ImportDashboardResponse.MailboxHealthEntry toMailboxHealth(MailboxConnection m, LocalDateTime staleThreshold) {
        boolean healthy = Boolean.TRUE.equals(m.getEnabled())
                && m.getLastPollError() == null
                && m.getLastPollSuccessAt() != null
                && m.getLastPollSuccessAt().isAfter(staleThreshold);
        return new ImportDashboardResponse.MailboxHealthEntry(
                m.getId(), m.getLabel(), Boolean.TRUE.equals(m.getEnabled()),
                m.getLastPollAt(), m.getLastPollSuccessAt(), m.getLastPollError(), healthy);
    }

    private List<ImportDashboardResponse.SupplierExceptionRate> buildSupplierExceptionRates(String shopId) {
        return importRowRepository.countExceptionsBySupplier(shopId).stream()
                .map(row -> {
                    Long supplierId = (Long) row[0];
                    String supplierName = (String) row[1];
                    long totalRows = ((Number) row[2]).longValue();
                    long exceptionRows = ((Number) row[3]).longValue();
                    double ratePercent = totalRows == 0 ? 0.0 : (exceptionRows * 100.0) / totalRows;
                    return new ImportDashboardResponse.SupplierExceptionRate(
                            supplierId, supplierName, totalRows, exceptionRows, ratePercent);
                })
                .toList();
    }

    private ImportDashboardResponse.RecentActivity buildRecentActivity(String shopId, int windowHours, LocalDateTime since) {
        long files = importFileRepository.countByShopIdAndReceivedAtAfter(shopId, since);
        long rows = importRowRepository.countByShopIdAndCreatedAtAfter(shopId, since);
        return new ImportDashboardResponse.RecentActivity(windowHours, files, rows);
    }

    private ImportDashboardResponse.ProductChangeSummary buildProductChanges(
            String shopId, int windowHours, LocalDateTime since) {
        List<ImportBatch> applied = importBatchRepository.findAppliedSince(shopId, since);
        int added = 0;
        int updated = 0;
        int priceChanged = 0;
        int removed = 0;
        int reactivated = 0;
        for (ImportBatch batch : applied) {
            added += orZero(batch.getOffersAddedCount());
            updated += orZero(batch.getOffersUpdatedCount());
            priceChanged += orZero(batch.getOffersPriceChangedCount());
            removed += orZero(batch.getProductsRemovedFromStorefrontCount());
            reactivated += orZero(batch.getProductsReactivatedCount());
        }
        return new ImportDashboardResponse.ProductChangeSummary(windowHours, added, updated, priceChanged, removed, reactivated);
    }

    private int orZero(Integer value) {
        return value != null ? value : 0;
    }
}
