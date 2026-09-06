package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.MatchDecision;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import com.plstk.loyaltybot.repository.MatchDecisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Batch and row detail for the Prompt 07 operations UI (docs/ARCHITECTURE.md §12): everything an
 * operator sees after clicking into one batch or one exception-queue row - identity, pipeline
 * progress/apply diff for a batch, and raw/normalized/candidates/AI-audit for a row.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ImportBatchDetailService {

    private final ImportBatchRepository importBatchRepository;
    private final ImportRowRepository importRowRepository;
    private final MatchDecisionRepository matchDecisionRepository;
    private final ObjectMapper objectMapper;

    public Optional<BatchDetailResponse> getBatchDetail(String shopId, Long batchId) {
        return importBatchRepository.findDetailByShopIdAndId(shopId, batchId).map(this::toBatchDetail);
    }

    public Optional<Page<RowListItem>> listBatchRows(
            String shopId, Long batchId, List<ImportRowStatus> statusFilter, int page, int size) {
        Optional<ImportBatch> batch = importBatchRepository.findByShopIdAndId(shopId, batchId);
        if (batch.isEmpty()) {
            return Optional.empty();
        }
        Pageable pageable = PageRequest.of(page, size);
        Page<ImportRow> rows = (statusFilter == null || statusFilter.isEmpty())
                ? importRowRepository.findByImportBatchIdOrderBySourceRowNumberAsc(batchId, pageable)
                : importRowRepository.findByImportBatchIdAndStatusInOrderBySourceRowNumberAsc(batchId, statusFilter, pageable);
        return Optional.of(rows.map(this::toRowListItem));
    }

    public Optional<RowDetailResponse> getRowDetail(String shopId, Long rowId) {
        return importRowRepository.findDetailByShopIdAndId(shopId, rowId).map(this::toRowDetail);
    }

    private BatchDetailResponse toBatchDetail(ImportBatch batch) {
        SupplierSource source = batch.getSupplierSource();
        Map<String, Long> rowStatusCounts = new LinkedHashMap<>();
        for (Object[] row : importRowRepository.countByStatusForBatch(batch.getId())) {
            rowStatusCounts.put(((ImportRowStatus) row[0]).name(), ((Number) row[1]).longValue());
        }
        return new BatchDetailResponse(
                batch.getId(),
                batch.getStatus(),
                source.getId(),
                source.getLabel(),
                source.getSupplier().getId(),
                source.getSupplier().getName(),
                batch.getImportFile() != null ? batch.getImportFile().getOriginalFilename() : null,
                batch.getImportFile() != null ? batch.getImportFile().getSizeBytes() : null,
                batch.getImportFile() != null ? batch.getImportFile().getReceivedAt() : null,
                batch.getRuleVersion() != null ? batch.getRuleVersion().getId() : null,
                batch.getRuleVersion() != null ? batch.getRuleVersion().getVersion() : null,
                batch.getRuleVersion() != null ? batch.getRuleVersion().getStatus() : null,
                batch.getTotalRows(),
                batch.getValidRows(),
                batch.getInvalidRows(),
                batch.getAttemptNumber(),
                batch.getErrorMessage(),
                rowStatusCounts,
                batch.getOffersAddedCount(),
                batch.getOffersUpdatedCount(),
                batch.getOffersPriceChangedCount(),
                batch.getOffersUnchangedCount(),
                batch.getProductsRemovedFromStorefrontCount(),
                batch.getProductsReactivatedCount(),
                batch.getStartedAt(),
                batch.getFinishedAt(),
                batch.getAppliedAt(),
                batch.getCreatedAt(),
                batch.getUpdatedAt());
    }

    private RowListItem toRowListItem(ImportRow row) {
        Map<String, String> raw = readRaw(row.getRawData());
        return new RowListItem(
                row.getId(),
                row.getVersion(),
                row.getSourceSheet(),
                row.getSourceRowNumber(),
                row.getStatus(),
                raw.get(LayoutRuleDefinition.FIELD_RAW_NAME),
                raw.get(LayoutRuleDefinition.FIELD_BRAND),
                parsePrice(raw.get(LayoutRuleDefinition.FIELD_SUPPLIER_PRICE)),
                row.getMatchedProduct() != null ? row.getMatchedProduct().getId() : null,
                row.getMatchedProduct() != null ? row.getMatchedProduct().getName() : null,
                row.getCreatedAt());
    }

    private RowDetailResponse toRowDetail(ImportRow row) {
        List<MatchDecisionAudit> decisions = matchDecisionRepository.findByImportRowIdOrderByDecidedAtDesc(row.getId())
                .stream()
                .map(this::toDecisionAudit)
                .toList();
        return new RowDetailResponse(
                row.getId(),
                row.getVersion(),
                row.getImportBatch().getId(),
                row.getSourceSheet(),
                row.getSourceRowNumber(),
                row.getStatus(),
                readRaw(row.getRawData()),
                readNormalized(row.getNormalizedData()),
                readCandidates(row.getCandidateSearchResult()),
                row.getMatchedProduct() != null ? row.getMatchedProduct().getId() : null,
                row.getMatchedProduct() != null ? row.getMatchedProduct().getName() : null,
                decisions,
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private MatchDecisionAudit toDecisionAudit(MatchDecision decision) {
        return new MatchDecisionAudit(
                decision.getId(),
                decision.getDecisionType(),
                decision.getDecidedBy(),
                decision.getChosenProduct() != null ? decision.getChosenProduct().getId() : null,
                decision.getChosenProduct() != null ? decision.getChosenProduct().getName() : null,
                decision.getConfidenceScore(),
                decision.getModelProvider(),
                decision.getModelName(),
                decision.getPromptVersion(),
                readConflicts(decision.getConflicts()),
                decision.getReason(),
                decision.getReviewerUserId(),
                decision.getReviewerEmail(),
                decision.getDecidedAt());
    }

    private Map<String, String> readRaw(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private NormalizedRowData readNormalized(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, NormalizedRowData.class);
        } catch (Exception e) {
            return null;
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
            return List.of();
        }
    }

    private List<String> readConflicts(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private BigDecimal parsePrice(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
