package com.plstk.loyaltybot.service.importing;

import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.MailboxConnection;
import com.plstk.loyaltybot.entity.importing.SnapshotMode;
import com.plstk.loyaltybot.entity.importing.Supplier;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import com.plstk.loyaltybot.repository.MailboxConnectionRepository;
import com.plstk.loyaltybot.repository.SupplierRepository;
import com.plstk.loyaltybot.repository.SupplierSourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Shop-scoped CRUD for {@link Supplier} and {@link SupplierSource} used by the mailbox/source
 * minimal admin UI (Prompt 02). Matching/parsing rules, snapshot policy tuning etc. remain out of
 * scope here — this only lets an operator wire "this mailbox + these filters -> this supplier".
 */
@Service
@RequiredArgsConstructor
public class SupplierSourceAdminService {

    /** Batches "safely processed" for the graduate positive gate - guards already passed. */
    private static final List<ImportBatchStatus> GRADUATE_SUCCESSFUL_STATUSES = List.of(
            ImportBatchStatus.NEEDS_ATTENTION, ImportBatchStatus.APPROVED, ImportBatchStatus.AUTO_APPROVED,
            ImportBatchStatus.APPLYING, ImportBatchStatus.APPLIED);

    /** Open batches that must be resolved before graduation (blocking gate). */
    private static final List<ImportBatchStatus> GRADUATE_BLOCKING_STATUSES = List.of(
            ImportBatchStatus.QUARANTINED, ImportBatchStatus.FAILED);

    private final SupplierRepository supplierRepository;
    private final SupplierSourceRepository supplierSourceRepository;
    private final MailboxConnectionRepository mailboxConnectionRepository;
    private final ImportBatchRepository importBatchRepository;
    private final ImportRowRepository importRowRepository;

    public List<Supplier> listSuppliers(String shopId) {
        return supplierRepository.findByShopId(shopId);
    }

    @Transactional
    public Supplier createSupplier(String shopId, String name, String code) {
        Supplier supplier = Supplier.builder()
                .shopId(shopId)
                .name(name)
                .code(code)
                .build();
        return supplierRepository.save(supplier);
    }

    public List<SupplierSource> listSources(String shopId) {
        return supplierSourceRepository.findByShopId(shopId);
    }

    @Transactional
    public SupplierSource createSource(
            String shopId, Long supplierId, String label, Long mailboxConnectionId,
            String senderAllowlist, String subjectPattern, String filenamePattern) {

        Supplier supplier = supplierRepository.findByShopIdAndId(shopId, supplierId)
                .orElseThrow(() -> new IllegalArgumentException("Supplier " + supplierId + " not found for shop " + shopId));

        MailboxConnection mailboxConnection = null;
        if (mailboxConnectionId != null) {
            mailboxConnection = mailboxConnectionRepository.findByShopIdAndId(shopId, mailboxConnectionId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Mailbox " + mailboxConnectionId + " not found for shop " + shopId));
        }

        validateRegexOrThrow(subjectPattern, "subjectPattern");
        validateRegexOrThrow(filenamePattern, "filenamePattern");

        SupplierSource source = SupplierSource.builder()
                .shopId(shopId)
                .supplier(supplier)
                .label(label)
                .mailboxConnection(mailboxConnection)
                .senderAllowlist(senderAllowlist)
                .subjectPattern(blankToNull(subjectPattern))
                .filenamePattern(blankToNull(filenamePattern))
                .build();
        return supplierSourceRepository.save(source);
    }

    // ========== Stage 1: PATCH /supplier-sources/{id} ==========

    /**
     * Partial update of policy/routing fields. Every {@code *Clear} flag lets the caller explicitly
     * null out a nullable override (a plain JSON {@code null} value is indistinguishable from "field
     * omitted" once bound to a record, so PATCH semantics need an explicit clear signal for anything
     * that can legitimately go back to "use the default").
     */
    @Transactional
    public SupplierSource updateSource(String shopId, Long sourceId, UpdateSupplierSourceRequest request) {
        SupplierSource source = supplierSourceRepository.findByShopIdAndId(shopId, sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Supplier source " + sourceId + " not found for shop " + shopId));

        checkVersion(source, request.expectedVersion());

        if (request.label() != null) {
            source.setLabel(request.label());
        }
        if (request.mailboxConnectionId() != null) {
            MailboxConnection mailbox = mailboxConnectionRepository.findByShopIdAndId(shopId, request.mailboxConnectionId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Mailbox " + request.mailboxConnectionId() + " not found for shop " + shopId));
            source.setMailboxConnection(mailbox);
        } else if (Boolean.TRUE.equals(request.clearMailboxConnectionId())) {
            source.setMailboxConnection(null);
        }
        if (request.senderAllowlist() != null) {
            source.setSenderAllowlist(blankToNull(request.senderAllowlist()));
        }
        if (request.subjectPattern() != null) {
            validateRegexOrThrow(request.subjectPattern(), "subjectPattern");
            source.setSubjectPattern(blankToNull(request.subjectPattern()));
        }
        if (request.filenamePattern() != null) {
            validateRegexOrThrow(request.filenamePattern(), "filenamePattern");
            source.setFilenamePattern(blankToNull(request.filenamePattern()));
        }
        if (request.enabled() != null) {
            source.setEnabled(request.enabled());
        }
        if (request.snapshotMode() != null) {
            source.setSnapshotMode(request.snapshotMode());
        }
        if (request.snapshotScope() != null) {
            source.setSnapshotScope(request.snapshotScope());
        }
        if (request.commissionPercentOverride() != null) {
            source.setCommissionPercentOverride(request.commissionPercentOverride());
        } else if (Boolean.TRUE.equals(request.clearCommissionPercentOverride())) {
            source.setCommissionPercentOverride(null);
        }
        if (request.publicPriceStrategy() != null) {
            source.setPublicPriceStrategy(request.publicPriceStrategy());
        }
        if (request.roundingPolicy() != null) {
            source.setRoundingPolicy(request.roundingPolicy());
        }
        if (request.aiAutoApproveMinScoreOverride() != null) {
            source.setAiAutoApproveMinScoreOverride(request.aiAutoApproveMinScoreOverride());
        } else if (Boolean.TRUE.equals(request.clearAiAutoApproveMinScoreOverride())) {
            source.setAiAutoApproveMinScoreOverride(null);
        }
        if (request.aiMinConfidenceOverride() != null) {
            source.setAiMinConfidenceOverride(request.aiMinConfidenceOverride());
        } else if (Boolean.TRUE.equals(request.clearAiMinConfidenceOverride())) {
            source.setAiMinConfidenceOverride(null);
        }

        boolean autoApplyTurningOn = request.autoApply() != null
                && Boolean.TRUE.equals(request.autoApply())
                && !Boolean.TRUE.equals(source.getAutoApply());
        if (request.autoApply() != null) {
            source.setAutoApply(request.autoApply());
        }
        if (request.shadowMode() != null) {
            source.setShadowMode(request.shadowMode());
        }

        validateInvariants(source, autoApplyTurningOn, request.confirmAutoApply());
        // ADR-025: a plain PATCH must never be able to grant autoApply on an easier path than
        // /graduate - both go through the exact same shadow-run/open-batch/sender-allowlist gate.
        if (autoApplyTurningOn) {
            assertReadyForAutoApply(shopId, sourceId, source);
        }

        return saveWithOptimisticLockTranslation(source);
    }

    // ========== Stage 1: POST /supplier-sources/{id}/graduate ==========

    /**
     * Safe, explicit shadow-&gt;live activation: turns {@code shadowMode} off and {@code autoApply}
     * on in one atomic step, but only after confirming (a) the operator explicitly asked for this
     * ({@code confirm=true}), (b) at least one batch from this source already passed guard
     * evaluation while in shadow mode (so this is not the very first, unvetted file), and (c) there
     * is no open {@code QUARANTINED}/{@code FAILED} batch or unresolved {@code NEEDS_REVIEW} row
     * still waiting on an operator decision for this source.
     */
    @Transactional
    public SupplierSource graduate(String shopId, Long sourceId, boolean confirm) {
        if (!confirm) {
            throw new SupplierSourceValidationException(
                    "Explicit confirmation is required to graduate a source out of shadow mode",
                    Map.of("confirm", "must be true - graduating enables automatic catalog changes"));
        }

        SupplierSource source = supplierSourceRepository.findByShopIdAndId(shopId, sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Supplier source " + sourceId + " not found for shop " + shopId));

        assertReadyForAutoApply(shopId, sourceId, source);

        source.setShadowMode(false);
        source.setAutoApply(true);
        // enabled is intentionally left untouched: graduation is about apply policy, not routing.
        return saveWithOptimisticLockTranslation(source);
    }

    /**
     * The single gate for turning {@code autoApply} on, shared by both {@link #graduate} and a
     * plain {@link #updateSource} PATCH that flips {@code autoApply} itself (ADR-025) - there must
     * be exactly one path with exactly one set of checks, not a stricter one guarding {@code
     * /graduate} and a looser one reachable via PATCH.
     */
    private void assertReadyForAutoApply(String shopId, Long sourceId, SupplierSource source) {
        long successfulShadowBatches = importBatchRepository.countByShopIdAndSupplierSourceIdAndStatusIn(
                shopId, sourceId, GRADUATE_SUCCESSFUL_STATUSES);
        long openBlockingBatches = importBatchRepository.countByShopIdAndSupplierSourceIdAndStatusIn(
                shopId, sourceId, GRADUATE_BLOCKING_STATUSES);
        long unresolvedNeedsReview = importRowRepository.countBySupplierSourceIdAndStatus(
                shopId, sourceId, ImportRowStatus.NEEDS_REVIEW);

        SupplierSourceValidationException.Builder errors = SupplierSourceValidationException.builder()
                .rejectIf(successfulShadowBatches == 0, "shadowMode",
                        "No batch from this source has successfully passed guard evaluation yet - "
                                + "run at least one real file through shadow mode first")
                .rejectIf(openBlockingBatches > 0, "batches",
                        "There is at least one open QUARANTINED/FAILED batch for this source that must be "
                                + "resolved (resumed/investigated) before graduating")
                .rejectIf(unresolvedNeedsReview > 0, "rows",
                        "There are " + unresolvedNeedsReview + " row(s) still in NEEDS_REVIEW for this source "
                                + "that require an operator decision before graduating")
                // An empty allowlist means SupplierSourceMatcher accepts mail from ANY sender
                // (see its own javadoc) - tolerable for a shadow/manually-reviewed source, but never
                // for one that will auto-apply unattended. Reproduced by the six-bug report.
                .rejectIf(source.getSenderAllowlist() == null || source.getSenderAllowlist().isBlank(),
                        "senderAllowlist",
                        "must be set before autoApply can be enabled - an empty allowlist accepts mail "
                                + "from any sender, so autoApply would apply unverified attachments unattended");
        errors.throwIfInvalid();
    }

    private SupplierSource saveWithOptimisticLockTranslation(SupplierSource source) {
        try {
            return supplierSourceRepository.save(source);
        } catch (OptimisticLockingFailureException e) {
            throw new SupplierSourceVersionConflictException(null);
        }
    }

    private void checkVersion(SupplierSource source, Long expectedVersion) {
        if (expectedVersion != null && !Objects.equals(expectedVersion, source.getVersion())) {
            throw new SupplierSourceVersionConflictException(source.getVersion());
        }
    }

    /**
     * Cross-field rules that must hold regardless of which individual fields a PATCH touched -
     * evaluated once against the fully-merged in-memory entity so a request that changes several
     * fields at once can never land in an invalid combination.
     */
    private void validateInvariants(SupplierSource source, boolean autoApplyTurningOn, Boolean confirmAutoApply) {
        SupplierSourceValidationException.Builder errors = SupplierSourceValidationException.builder()
                .rejectIf(source.getCommissionPercentOverride() != null
                                && source.getCommissionPercentOverride().compareTo(BigDecimal.ZERO) < 0,
                        "commissionPercentOverride", "must not be negative")
                .rejectIf(isOutOfUnitRange(source.getAiAutoApproveMinScoreOverride()),
                        "aiAutoApproveMinScoreOverride", "must be between 0 and 1")
                .rejectIf(isOutOfUnitRange(source.getAiMinConfidenceOverride()),
                        "aiMinConfidenceOverride", "must be between 0 and 1")
                .rejectIf(source.getSnapshotMode() == SnapshotMode.FULL
                                && (source.getSnapshotScope() == null || source.getSnapshotScope().isBlank()),
                        "snapshotScope", "is required when snapshotMode is FULL")
                .rejectIf(Boolean.TRUE.equals(source.getShadowMode()) && Boolean.TRUE.equals(source.getAutoApply()),
                        "autoApply", "cannot be enabled together with shadowMode - graduate the source first")
                .rejectIf(autoApplyTurningOn && !Boolean.TRUE.equals(confirmAutoApply),
                        "confirmAutoApply", "must be true to explicitly enable autoApply "
                                + "(a FULL snapshot can hide products that disappear from the file)")
                // ADR-028: assertReadyForAutoApply only runs on the transition into autoApply=true,
                // so a PATCH that leaves autoApply already true untouched while blanking
                // senderAllowlist in the same request used to sail through unchecked - the source
                // kept applying attachments unattended while now accepting mail from ANY sender
                // (SupplierSourceMatcher's documented permissive-when-blank behavior). This runs on
                // EVERY update, not just the on-transition, so the invariant holds continuously for
                // as long as autoApply stays enabled, not merely at the moment it was turned on.
                .rejectIf(Boolean.TRUE.equals(source.getAutoApply())
                                && (source.getSenderAllowlist() == null || source.getSenderAllowlist().isBlank()),
                        "senderAllowlist", "cannot be blank while autoApply is enabled - disable autoApply "
                                + "first, or set a non-empty allowlist in the same request");
        errors.throwIfInvalid();
    }

    private boolean isOutOfUnitRange(BigDecimal value) {
        return value != null && (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0);
    }

    // ========== Shared helpers ==========

    private void validateRegexOrThrow(String pattern, String fieldName) {
        if (pattern == null || pattern.isBlank()) {
            return;
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regex for " + fieldName + ": " + e.getMessage());
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
