package com.plstk.loyaltybot.service.importing;

import java.util.List;

/**
 * Prompt 07 automation control-panel dashboard (docs/ARCHITECTURE.md §12/§15): everything an
 * operator needs to answer "is the automation healthy" without opening a single batch. All counts
 * are current-state snapshots (e.g. {@code batches}) except {@code recentActivity}, which is scoped
 * to {@code windowHours} (see {@link ImportDashboardService}).
 */
public record ImportDashboardResponse(
        AutomationRate automationRate,
        BatchStatusCounts batches,
        long exceptionQueueSize,
        List<MailboxHealthEntry> mailboxes,
        List<SupplierExceptionRate> supplierExceptionRates,
        RecentActivity recentActivity,
        ProductChangeSummary recentProductChanges) {

    /**
     * Share of matching decisions the system resolved without a human touching the row.
     * {@code ratePercent} is {@code null} when {@code totalDecidedRows == 0} (nothing decided yet -
     * showing 0% or 100% would both be misleading).
     */
    public record AutomationRate(long autoDecidedRows, long humanDecidedRows, Double ratePercent) {
    }

    /**
     * Current count of every {@link com.plstk.loyaltybot.entity.importing.ImportBatch} for this
     * shop, bucketed the way an operator thinks about batch health rather than by raw pipeline
     * status: {@code running} covers every in-flight stage (PARSING..APPLYING) since none of them
     * need their own dashboard tile.
     */
    public record BatchStatusCounts(
            long total, long running, long needsAttention, long failed, long quarantined, long applied) {
    }

    public record MailboxHealthEntry(
            Long mailboxId,
            String label,
            boolean enabled,
            java.time.LocalDateTime lastPollAt,
            java.time.LocalDateTime lastPollSuccessAt,
            String lastPollError,
            boolean healthy) {
    }

    public record SupplierExceptionRate(
            Long supplierId, String supplierName, long totalRows, long exceptionRows, double exceptionRatePercent) {
    }

    /** Throughput within the dashboard's lookback window - "processed emails/files/rows". */
    public record RecentActivity(int windowHours, long filesProcessed, long rowsProcessed) {
    }

    /** Sum of {@code ImportBatch} apply-audit counters over batches that reached APPLIED within the window. */
    public record ProductChangeSummary(
            int windowHours, int added, int updated, int priceChanged, int removedFromStorefront, int reactivated) {
    }
}
