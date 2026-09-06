package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.ImportRuleVersion;
import com.plstk.loyaltybot.entity.importing.RuleVersionSource;
import com.plstk.loyaltybot.entity.importing.RuleVersionStatus;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import com.plstk.loyaltybot.repository.ImportRuleVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Transactional half of {@link ImportBatchParsingService}: kept as a separate Spring bean (same
 * pattern as {@code ImportFileBatchWriter}) so {@code @Transactional} is honoured through the proxy
 * instead of being silently skipped by self-invocation, and so AI/network calls and workbook IO in
 * the orchestrator never run inside an open DB transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportBatchParseWriter {

    private final ImportBatchRepository importBatchRepository;
    private final ImportRuleVersionRepository importRuleVersionRepository;
    private final ImportRowRepository importRowRepository;
    private final ObjectMapper objectMapper;

    /** @return true if this call won the STORED -&gt; PARSING transition, false if already taken. */
    @Transactional
    public boolean claimForParsing(Long batchId) {
        return importBatchRepository.claimForParsing(batchId, LocalDateTime.now()) == 1;
    }

    /**
     * Publishes {@code rule} as the new ACTIVE {@link ImportRuleVersion} for this source, retiring
     * the previous ACTIVE version (exactly one ACTIVE version per source at a time). The version is
     * immutable once created — a later schema drift creates a new version rather than editing this one.
     */
    @Transactional
    public ImportRuleVersion publishNewRuleVersion(
            String shopId, SupplierSource supplierSource, LayoutRuleDefinition rule) {

        importRuleVersionRepository
                .findByShopIdAndSupplierSourceIdAndStatus(shopId, supplierSource.getId(), RuleVersionStatus.ACTIVE)
                .ifPresent(old -> {
                    old.setStatus(RuleVersionStatus.RETIRED);
                    importRuleVersionRepository.save(old);
                });

        int nextVersion = importRuleVersionRepository.findTopBySupplierSourceIdOrderByVersionDesc(supplierSource.getId())
                .map(v -> v.getVersion() + 1)
                .orElse(1);

        String ruleJson;
        try {
            ruleJson = objectMapper.writeValueAsString(rule);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize new rule version", e);
        }

        ImportRuleVersion version = ImportRuleVersion.builder()
                .shopId(shopId)
                .supplierSource(supplierSource)
                .version(nextVersion)
                .status(RuleVersionStatus.ACTIVE)
                .source(RuleVersionSource.AI_GENERATED)
                .ruleDefinition(ruleJson)
                .build();

        version = importRuleVersionRepository.save(version);
        log.info("Published new rule version {} (v{}) for supplierSource {} (shop {})",
                version.getId(), version.getVersion(), supplierSource.getId(), shopId);
        return version;
    }

    @Transactional
    public void finalizeSuccess(Long batchId, ImportRuleVersion ruleVersion, WorkbookParseResult result) {
        ImportBatch batch = requireBatch(batchId);
        batch.setRuleVersion(ruleVersion);
        batch.setTotalRows(result.totalRows());
        batch.setValidRows(result.validRows());
        batch.setInvalidRows(result.invalidRows());
        batch.setStatus(ImportBatchStatus.NORMALIZING);
        batch.setFinishedAt(LocalDateTime.now());
        importBatchRepository.save(batch);

        List<ImportRow> rows = new ArrayList<>();
        for (ParsedRow row : result.allRows()) {
            rows.add(ImportRow.builder()
                    .shopId(batch.getShopId())
                    .importBatch(batch)
                    .sourceSheet(row.sheetName())
                    .sourceRowNumber(row.sourceRowNumber())
                    .rawData(toJson(row.rawValues()))
                    .status(row.valid() ? ImportRowStatus.PENDING : ImportRowStatus.INVALID)
                    .build());
        }
        importRowRepository.saveAll(rows);

        log.info("Batch {} parsed successfully: total={} valid={} invalid={} -> NORMALIZING",
                batchId, result.totalRows(), result.validRows(), result.invalidRows());
    }

    /**
     * Conditional on the batch still being {@code PARSING} - same reasoning as {@link
     * #finalizeFailed}: a losing side of a concurrent parsing race must never overwrite a batch
     * another caller already moved past this stage.
     */
    @Transactional
    public void finalizeQuarantine(Long batchId, String reason) {
        int updated = importBatchRepository.finalizeQuarantineFrom(
                batchId, ImportBatchStatus.PARSING, reason, LocalDateTime.now());
        if (updated == 1) {
            log.warn("Batch {} quarantined: {}", batchId, reason);
        } else {
            log.warn("Batch {} was no longer PARSING when it was about to be quarantined ({}); "
                    + "leaving its actual status untouched", batchId, reason);
        }
    }

    /**
     * Conditional on the batch still being {@code PARSING} (see {@code
     * ImportBatchRepository#finalizeFailedFrom}) - a losing side of a concurrent parsing race must
     * never overwrite a batch another caller already moved past this stage.
     */
    @Transactional
    public void finalizeFailed(Long batchId, String reason) {
        int updated = importBatchRepository.finalizeFailedFrom(
                batchId, ImportBatchStatus.PARSING, reason, LocalDateTime.now());
        if (updated == 1) {
            log.error("Batch {} failed: {}", batchId, reason);
        } else {
            log.warn("Batch {} was no longer PARSING when parsing failed ({}); leaving its actual status untouched",
                    batchId, reason);
        }
    }

    private ImportBatch requireBatch(Long batchId) {
        return importBatchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalStateException("ImportBatch " + batchId + " not found"));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize row data", e);
        }
    }
}
