package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.entity.importing.DecidedBy;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.MatchDecision;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import com.plstk.loyaltybot.repository.MatchDecisionRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Transactional half of {@link ImportBatchMatchingService}, same split as
 * {@code ImportBatchNormalizeWriter}/{@code ImportBatchNormalizingService} so {@code @Transactional}
 * is honoured through the Spring proxy instead of being silently skipped by self-invocation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportBatchMatchWriter {

    private static final int REASON_MAX_LENGTH = 512;

    private final ImportBatchRepository importBatchRepository;
    private final ImportRowRepository importRowRepository;
    private final ProductRepository productRepository;
    private final MatchDecisionRepository matchDecisionRepository;
    private final ObjectMapper objectMapper;

    /** @return true if this call won the MATCHING -&gt; VALIDATING transition, false if already taken. */
    @Transactional
    public boolean claimForMatching(Long batchId) {
        return importBatchRepository.claimForMatching(batchId) == 1;
    }

    @Transactional
    public void finalizeSuccess(Long batchId, List<RowMatchOutcome> outcomes) {
        ImportBatch batch = requireBatch(batchId);
        // claimForMatching() already flipped the DB row to VALIDATING via a bulk @Modifying query,
        // which bypasses the persistence context - keep the in-memory instance in sync too, same
        // reasoning as ImportBatchNormalizeWriter#finalizeSuccess.
        batch.setStatus(ImportBatchStatus.VALIDATING);

        int autoApproved = 0;
        for (RowMatchOutcome outcome : outcomes) {
            ImportRow row = outcome.row();
            row.setStatus(outcome.status());
            if (outcome.matchedProductId() != null) {
                row.setMatchedProduct(productRepository.getReferenceById(outcome.matchedProductId()));
            }
            importRowRepository.save(row);

            if (outcome.writeDecision()) {
                matchDecisionRepository.save(buildDecision(batch, row, outcome));
            }
            if (outcome.status() == ImportRowStatus.AUTO_APPROVED) {
                autoApproved++;
            }
        }

        batch.setFinishedAt(LocalDateTime.now());
        importBatchRepository.save(batch);
        log.info("Batch {} matched: {} row(s) total, {} auto-approved -> VALIDATING",
                batchId, outcomes.size(), autoApproved);
    }

    /**
     * Conditional on the batch still being {@code MATCHING} (see {@code
     * ImportBatchRepository#finalizeFailedFrom}) - a losing side of a concurrent matching race must
     * never overwrite a batch another caller already moved past this stage.
     */
    @Transactional
    public void finalizeFailed(Long batchId, String reason) {
        int updated = importBatchRepository.finalizeFailedFrom(
                batchId, ImportBatchStatus.MATCHING, reason, LocalDateTime.now());
        if (updated == 1) {
            log.error("Batch {} failed: {}", batchId, reason);
        } else {
            log.warn("Batch {} was no longer MATCHING when matching failed ({}); leaving its actual status untouched",
                    batchId, reason);
        }
    }

    private MatchDecision buildDecision(ImportBatch batch, ImportRow row, RowMatchOutcome outcome) {
        return MatchDecision.builder()
                .shopId(batch.getShopId())
                .importRow(row)
                .candidateProductIds(toJson(outcome.matchedProductId() == null ? List.of() : List.of(outcome.matchedProductId())))
                .chosenProduct(outcome.matchedProductId() == null ? null : productRepository.getReferenceById(outcome.matchedProductId()))
                .decisionType(outcome.decisionType())
                .confidenceScore(outcome.confidenceScore())
                .modelProvider(outcome.modelProvider())
                .modelName(outcome.modelName())
                .promptVersion(outcome.promptVersion())
                .conflicts(toJson(outcome.conflicts() == null ? List.of() : outcome.conflicts()))
                .reason(truncate(outcome.reason()))
                .decidedBy(DecidedBy.SYSTEM)
                .build();
    }

    private ImportBatch requireBatch(Long batchId) {
        return importBatchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalStateException("ImportBatch " + batchId + " not found"));
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= REASON_MAX_LENGTH ? value : value.substring(0, REASON_MAX_LENGTH);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize match decision data", e);
        }
    }
}
