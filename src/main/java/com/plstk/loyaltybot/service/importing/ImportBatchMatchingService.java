package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.config.SupplierImportProperties;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.MatchDecisionType;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orchestrates the automatic {@code MATCHING -&gt; VALIDATING} transition for one
 * {@link ImportBatch} (Prompt 05, docs/ARCHITECTURE.md §7/§9.4/§10): runs {@link AiCatalogMatcher}
 * for every {@code PENDING} row that has at least one deterministic fuzzy candidate (Prompt 04),
 * evaluates zero-candidate rows for a safe {@code NEW_PRODUCT} decision, and promotes already-safe
 * {@code EXACT_MATCH}/{@code LEARNED_MATCH} rows to {@code AUTO_APPROVED}. Deliberately does NOT
 * gate on {@code SupplierSource.shadowMode}/{@code autoApply}: this stage answers "is this row's
 * match safe enough to auto-apply", which is a data/audit fact, independent of whether the source
 * has actually graduated to applying anything yet. {@code AUTO_APPROVED} rows are computed and
 * persisted identically for a shadow-mode source (verified by
 * {@code ImportBatchMatchingServiceTest#shadowModeSource_stillComputesAndPersistsDecisions}); the
 * future Apply stage (Prompt 06) is what actually consults {@code shadowMode}/{@code autoApply}
 * before doing anything with an {@code AUTO_APPROVED} row.
 *
 * <p>Deliberately NOT {@code @Transactional} itself, mirroring
 * {@code ImportBatchNormalizingService}: the AI HTTP call happens here, outside any DB transaction;
 * every persistence step is delegated to {@link ImportBatchMatchWriter}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportBatchMatchingService {

    private static final List<ImportRowStatus> GATED_STATUSES =
            List.of(ImportRowStatus.PENDING, ImportRowStatus.EXACT_MATCH, ImportRowStatus.LEARNED_MATCH);

    private final ImportBatchRepository importBatchRepository;
    private final ImportRowRepository importRowRepository;
    private final AiCatalogMatcher aiCatalogMatcher;
    private final CatalogMatchResponseValidator responseValidator;
    private final SupplierImportProperties properties;
    private final ObjectMapper objectMapper;
    private final ImportBatchMatchWriter writer;

    public void matchBatch(Long batchId) {
        if (!writer.claimForMatching(batchId)) {
            log.debug("Batch {} is not in MATCHING status (already claimed/processed) - skipping", batchId);
            return;
        }

        ImportBatch batch = importBatchRepository.findByIdWithSupplierSourceAndSupplier(batchId)
                .orElseThrow(() -> new IllegalStateException("ImportBatch " + batchId + " not found after claim"));
        SupplierSource source = batch.getSupplierSource();

        try {
            List<ImportRow> rows = importRowRepository.findByImportBatchIdAndStatusIn(batchId, GATED_STATUSES);
            List<RowMatchOutcome> outcomes = new ArrayList<>(rows.size());
            for (ImportRow row : rows) {
                outcomes.add(processRow(source, row));
            }
            writer.finalizeSuccess(batchId, outcomes);
            log.info("Batch {} matched: {} row(s) gated", batchId, outcomes.size());
        } catch (Exception e) {
            log.error("Batch {} failed with unexpected error during matching", batchId, e);
            writer.finalizeFailed(batchId, "Unexpected matching error: " + safeMessage(e));
        }
    }

    private RowMatchOutcome processRow(SupplierSource source, ImportRow row) {
        if (row.getStatus() == ImportRowStatus.EXACT_MATCH || row.getStatus() == ImportRowStatus.LEARNED_MATCH) {
            return RowMatchOutcome.promoteDeterministic(row);
        }

        NormalizedRowData normalized;
        List<ScoredCandidate> candidates;
        SearchCompleteness completeness;
        try {
            normalized = readNormalizedData(row.getNormalizedData());
            candidates = readCandidates(row.getCandidateSearchResult());
            completeness = readCompleteness(row.getCandidateSearchDiagnostics());
        } catch (Exception e) {
            // One row with corrupted/missing normalizedData/candidateSearchResult JSON must never
            // abort the whole batch (it previously did, via the outer catch in matchBatch()) -
            // isolate it to NEEDS_REVIEW so every other row in the batch still gets matched.
            log.error("ImportRow {} has unreadable normalizedData/candidateSearchResult - routing it to "
                    + "NEEDS_REVIEW instead of failing the whole batch", row.getId(), e);
            return RowMatchOutcome.review(
                    row, MatchDecisionType.NO_MATCH, null, List.of(),
                    "Corrupted or missing normalizedData/candidateSearchResult: " + safeMessage(e),
                    null, null, null, null);
        }

        if (candidates.isEmpty()) {
            return evaluateNewProductOrReview(row, normalized, completeness, null, "No fuzzy candidates found");
        }

        String rowId = row.getId().toString();
        AiMatchResponse response = aiCatalogMatcher.match(new AiMatchRequest(rowId, normalized, candidates));
        if (!response.success()) {
            return RowMatchOutcome.review(
                    row, MatchDecisionType.NO_MATCH, null, List.of(),
                    "AI matcher call failed: " + response.errorMessage(),
                    response.provider(), response.model(), response.promptVersion(), null);
        }

        Set<Long> allowedIds = candidates.stream().map(ScoredCandidate::productId).collect(Collectors.toSet());
        CatalogMatchResult parsed = responseValidator.validate(response.rawContent(), rowId, allowedIds);
        if (!parsed.valid()) {
            return RowMatchOutcome.review(
                    row, MatchDecisionType.NO_MATCH, null, List.of(),
                    "Invalid AI response: " + String.join("; ", parsed.validationErrors()),
                    response.provider(), response.model(), response.promptVersion(), null);
        }

        if (!parsed.matched()) {
            return evaluateNewProductOrReview(row, normalized, completeness, response, "AI NO_MATCH: " + parsed.reason());
        }

        ScoredCandidate chosen = candidates.stream()
                .filter(c -> c.productId().equals(parsed.candidateProductId()))
                .findFirst()
                // membership already verified by the validator against the same candidate list
                .orElseThrow(() -> new IllegalStateException("Validated candidate_id not found among offered candidates"));

        if (chosen.hasConflicts()) {
            return RowMatchOutcome.review(
                    row, MatchDecisionType.AI_MATCH, chosen.productId(), chosen.conflicts(),
                    "AI selected candidate " + chosen.productId() + " but backend detected critical conflicts: "
                            + chosen.conflicts() + " (AI reason: " + parsed.reason() + ")",
                    response.provider(), response.model(), response.promptVersion(), parsed.confidence());
        }

        boolean scoreOk = chosen.totalScore() != null && chosen.totalScore().doubleValue() >= minScore(source);
        boolean confidenceOk = parsed.confidence() != null && parsed.confidence().doubleValue() >= minConfidence(source);
        String reason = String.format(
                "AI_MATCH candidate=%d deterministicScore=%s aiConfidence=%s (reason: %s)",
                chosen.productId(), chosen.totalScore(), parsed.confidence(), parsed.reason());

        if (scoreOk && confidenceOk) {
            return RowMatchOutcome.auto(
                    row, MatchDecisionType.AI_MATCH, chosen.productId(), List.of(), reason,
                    response.provider(), response.model(), response.promptVersion(), parsed.confidence());
        }
        return RowMatchOutcome.review(
                row, MatchDecisionType.AI_MATCH, chosen.productId(), List.of(), reason,
                response.provider(), response.model(), response.promptVersion(), parsed.confidence());
    }

    /**
     * A row with no viable existing catalog candidate (either zero fuzzy candidates at all, or the
     * AI explicitly said NO_MATCH against the offered ones) may become a safe {@code NEW_PRODUCT}.
     * {@code rawName}/{@code supplierPrice}/an identifier are already guaranteed present by
     * {@code SpreadsheetParser} row validity; brand is the one additional completeness gate
     * (docs/ARCHITECTURE.md prompt 05: "Полностью описанная безопасная NEW_PRODUCT допускается к
     * AUTO_APPROVED; неполная или противоречивая остаётся исключением"). Product creation itself
     * stays out of scope here - Prompt 06's Apply stage does it.
     */
    /**
     * ADR-030: a row with no viable existing catalog candidate may become a safe {@code
     * NEW_PRODUCT} ONLY when BOTH (a) brand is present (unchanged pre-existing gate) AND (b) the
     * required unbounded exact-identity catalog check actually ran to completion for this row
     * ({@code completeness.requiredStagesCompleted()}) - "AI said NO_MATCH"/"zero fuzzy
     * candidates" only ever means "no match among what was actually checked"; it is NOT proof
     * that the row is a genuinely new product if the exact-identity check itself could not run
     * (missing brand/fingerprint) or its result is unknown (a row persisted before this
     * diagnostic existed - legacy data is never treated as "search was complete").
     */
    private RowMatchOutcome evaluateNewProductOrReview(
            ImportRow row, NormalizedRowData normalized, SearchCompleteness completeness,
            AiMatchResponse response, String baseReason) {
        boolean brandOk = !properties.getMatching().isNewProductRequireBrand()
                || (normalized.brand() != null && !normalized.brand().isBlank());
        boolean searchCompleteOk = !SearchCompleteness.isUnknown(completeness) && completeness.requiredStagesCompleted();

        String provider = response == null ? null : response.provider();
        String model = response == null ? null : response.model();
        String promptVersion = response == null ? null : response.promptVersion();

        if (brandOk && searchCompleteOk) {
            return RowMatchOutcome.auto(
                    row, MatchDecisionType.NEW_PRODUCT, null, List.of(),
                    baseReason + "; safe NEW_PRODUCT (brand present, rawName/price/identifier already "
                            + "required, exact-identity catalog check completed with no match)",
                    provider, model, promptVersion, null);
        }
        MatchDecisionType decisionType = response == null ? MatchDecisionType.NO_MATCH : MatchDecisionType.AI_NO_MATCH;
        String gap = !brandOk
                ? "missing brand, cannot safely auto-create NEW_PRODUCT"
                : "search completeness " + (SearchCompleteness.isUnknown(completeness) ? "unknown (row predates this diagnostic)"
                        : "incomplete (" + completeness.reason() + ")") + " - cannot safely auto-create NEW_PRODUCT";
        return RowMatchOutcome.review(
                row, decisionType, null, List.of(),
                baseReason + "; " + gap,
                provider, model, promptVersion, null);
    }

    private SearchCompleteness readCompleteness(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SearchCompleteness.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse ImportRow.candidateSearchDiagnostics as JSON", e);
        }
    }

    private double minScore(SupplierSource source) {
        return source.getAiAutoApproveMinScoreOverride() != null
                ? source.getAiAutoApproveMinScoreOverride().doubleValue()
                : properties.getMatching().getAiAutoApproveMinScore();
    }

    private double minConfidence(SupplierSource source) {
        return source.getAiMinConfidenceOverride() != null
                ? source.getAiMinConfidenceOverride().doubleValue()
                : properties.getMatching().getAiMinConfidence();
    }

    private NormalizedRowData readNormalizedData(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("ImportRow reached matching stage without normalizedData");
        }
        try {
            return objectMapper.readValue(json, NormalizedRowData.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse ImportRow.normalizedData as JSON", e);
        }
    }

    private List<ScoredCandidate> readCandidates(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ScoredCandidate>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse ImportRow.candidateSearchResult as JSON", e);
        }
    }

    private String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message != null ? message : t.getClass().getSimpleName();
    }
}
