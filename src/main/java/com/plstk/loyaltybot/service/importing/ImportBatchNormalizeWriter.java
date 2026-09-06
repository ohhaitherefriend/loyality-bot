package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.entity.importing.DecidedBy;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.MatchDecision;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import com.plstk.loyaltybot.repository.MatchDecisionRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Transactional half of {@link ImportBatchNormalizingService}, same split as
 * {@code ImportBatchParseWriter}/{@code ImportBatchParsingService} so {@code @Transactional} is
 * honoured through the Spring proxy instead of being silently skipped by self-invocation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportBatchNormalizeWriter {

    private final ImportBatchRepository importBatchRepository;
    private final ImportRowRepository importRowRepository;
    private final ProductRepository productRepository;
    private final MatchDecisionRepository matchDecisionRepository;
    private final ObjectMapper objectMapper;

    /** @return true if this call won the NORMALIZING -&gt; MATCHING transition, false if already taken. */
    @Transactional
    public boolean claimForNormalizing(Long batchId) {
        return importBatchRepository.claimForNormalizing(batchId) == 1;
    }

    @Transactional
    public void finalizeSuccess(Long batchId, List<RowNormalizationOutcome> outcomes) {
        ImportBatch batch = requireBatch(batchId);
        // claimForNormalizing() already flipped the DB row to MATCHING via a bulk @Modifying query,
        // which bypasses the persistence context - if requireBatch() returns an already-managed,
        // stale in-memory instance (e.g. still held from an earlier save() in this same
        // transaction/session), the save() below would silently overwrite MATCHING back to
        // NORMALIZING unless the in-memory field is explicitly kept in sync here too.
        batch.setStatus(ImportBatchStatus.MATCHING);

        int matched = 0;
        for (RowNormalizationOutcome outcome : outcomes) {
            ImportRow row = outcome.row();
            row.setNormalizedData(outcome.normalizedDataJson());
            row.setCandidateSearchResult(outcome.candidateSearchResultJson());
            row.setStatus(outcome.status());

            if (outcome.matchedProductId() != null) {
                matched++;
                row.setMatchedProduct(productRepository.getReferenceById(outcome.matchedProductId()));
                importRowRepository.save(row);
                matchDecisionRepository.save(buildDeterministicDecision(batch, row, outcome));
            } else {
                importRowRepository.save(row);
            }
        }

        batch.setFinishedAt(LocalDateTime.now());
        importBatchRepository.save(batch);
        log.info("Batch {} normalized: {} row(s) total, {} deterministically matched -> MATCHING",
                batchId, outcomes.size(), matched);
    }

    /**
     * Conditional on the batch still being {@code NORMALIZING} (see {@code
     * ImportBatchRepository#finalizeFailedFrom}) - a losing side of a concurrent normalizing race
     * must never overwrite a batch another caller already moved past this stage.
     */
    @Transactional
    public void finalizeFailed(Long batchId, String reason) {
        int updated = importBatchRepository.finalizeFailedFrom(
                batchId, ImportBatchStatus.NORMALIZING, reason, LocalDateTime.now());
        if (updated == 1) {
            log.error("Batch {} failed: {}", batchId, reason);
        } else {
            log.warn("Batch {} was no longer NORMALIZING when normalizing failed ({}); leaving its actual status untouched",
                    batchId, reason);
        }
    }

    private MatchDecision buildDeterministicDecision(ImportBatch batch, ImportRow row, RowNormalizationOutcome outcome) {
        return MatchDecision.builder()
                .shopId(batch.getShopId())
                .importRow(row)
                .candidateProductIds(toJson(List.of(outcome.matchedProductId())))
                .chosenProduct(productRepository.getReferenceById(outcome.matchedProductId()))
                .decisionType(outcome.decisionType())
                .confidenceScore(BigDecimal.ONE)
                .conflicts("[]")
                .reason("Deterministic " + outcome.decisionType() + " match (Prompt 04 candidate search)")
                .decidedBy(DecidedBy.SYSTEM)
                .build();
    }

    private ImportBatch requireBatch(Long batchId) {
        return importBatchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalStateException("ImportBatch " + batchId + " not found"));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize match decision data", e);
        }
    }
}
