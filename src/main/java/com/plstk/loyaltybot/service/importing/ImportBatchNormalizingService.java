package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the automatic {@code NORMALIZING -&gt; MATCHING} transition for one
 * {@link ImportBatch} (Prompt 04, docs/ARCHITECTURE.md §7/§9): extracts structured attributes for
 * every {@code PENDING} row, then runs deterministic candidate search
 * ({@link DeterministicMatchResolver}) in order (SupplierProductLink -&gt; exact barcode -&gt; safe
 * fingerprint -&gt; explainable fuzzy candidates). No AI matcher runs here - a row that falls through
 * every deterministic stage stays {@code PENDING} with its top candidates persisted for Prompt 05.
 *
 * <p>Deliberately NOT {@code @Transactional} itself, mirroring {@link ImportBatchParsingService}:
 * every persistence step is delegated to {@link ImportBatchNormalizeWriter}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportBatchNormalizingService {

    private final ImportBatchRepository importBatchRepository;
    private final ImportRowRepository importRowRepository;
    private final RowAttributeNormalizer normalizer;
    private final DeterministicMatchResolver matchResolver;
    private final ObjectMapper objectMapper;
    private final ImportBatchNormalizeWriter writer;

    public void normalizeBatch(Long batchId) {
        if (!writer.claimForNormalizing(batchId)) {
            log.debug("Batch {} is not in NORMALIZING status (already claimed/processed) - skipping", batchId);
            return;
        }

        ImportBatch batch = importBatchRepository.findByIdWithSupplierSourceAndSupplier(batchId)
                .orElseThrow(() -> new IllegalStateException("ImportBatch " + batchId + " not found after claim"));
        String shopId = batch.getShopId();
        Long supplierId = batch.getSupplierSource().getSupplier().getId();
        matchResolver.startNewBatch(shopId);

        try {
            List<ImportRow> rows = importRowRepository.findByImportBatchIdAndStatus(batchId, ImportRowStatus.PENDING);
            List<RowNormalizationOutcome> outcomes = new ArrayList<>(rows.size());
            for (ImportRow row : rows) {
                outcomes.add(processRow(shopId, supplierId, row));
            }
            writer.finalizeSuccess(batchId, outcomes);
            log.info("Batch {} normalized: {} row(s) processed", batchId, outcomes.size());
        } catch (Exception e) {
            log.error("Batch {} failed with unexpected error during normalizing", batchId, e);
            writer.finalizeFailed(batchId, "Unexpected normalizing error: " + safeMessage(e));
        }
    }

    private RowNormalizationOutcome processRow(String shopId, Long supplierId, ImportRow row) {
        Map<String, String> rawValues = readRawValues(row.getRawData());
        NormalizedRowData normalized = normalizer.normalize(rawValues);
        String normalizedJson = toJson(normalized);

        MatchResolution resolution = matchResolver.resolve(shopId, supplierId, normalized);
        if (resolution.isResolved()) {
            return RowNormalizationOutcome.matched(
                    row, normalizedJson, resolution.matchedProductId(), resolution.decisionType());
        }

        String candidatesJson = resolution.candidates().isEmpty() ? null : toJson(resolution.candidates());
        return RowNormalizationOutcome.unresolved(row, normalizedJson, candidatesJson);
    }

    private Map<String, String> readRawValues(String rawDataJson) {
        if (rawDataJson == null || rawDataJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawDataJson, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse ImportRow.rawData as JSON", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize normalization result", e);
        }
    }

    private String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message != null ? message : t.getClass().getSimpleName();
    }
}
