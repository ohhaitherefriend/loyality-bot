package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportBatchStatus;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.SupplierSource;
import com.plstk.loyaltybot.repository.ImportBatchRepository;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Prompt 07 unified exception queue (docs/ARCHITECTURE.md §12): {@code NEEDS_REVIEW}/{@code INVALID}
 * rows and {@code QUARANTINED}/{@code FAILED}/{@code NEEDS_ATTENTION} batches across the whole shop,
 * each shop-scoped and paginated independently. Kept as two separate paginated queries rather than
 * one merged/re-paginated list - rows and batches have unrelated volumes and the frontend renders
 * them as two sections of the same "Exceptions" page anyway.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ImportExceptionQueueService {

    private static final List<ImportRowStatus> ROW_EXCEPTION_STATUSES =
            List.of(ImportRowStatus.NEEDS_REVIEW, ImportRowStatus.INVALID);

    private static final List<ImportBatchStatus> BATCH_EXCEPTION_STATUSES =
            List.of(ImportBatchStatus.QUARANTINED, ImportBatchStatus.FAILED, ImportBatchStatus.NEEDS_ATTENTION);

    private final ImportRowRepository importRowRepository;
    private final ImportBatchRepository importBatchRepository;
    private final ObjectMapper objectMapper;

    public Page<RowExceptionSummary> listRowExceptions(String shopId, Long supplierId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return importRowRepository
                .findExceptionRows(shopId, ROW_EXCEPTION_STATUSES, supplierId, pageable)
                .map(this::toRowSummary);
    }

    public Page<BatchExceptionSummary> listBatchExceptions(String shopId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return importBatchRepository
                .findByShopIdAndStatusInOrderByCreatedAtDesc(shopId, BATCH_EXCEPTION_STATUSES, pageable)
                .map(this::toBatchSummary);
    }

    private RowExceptionSummary toRowSummary(ImportRow row) {
        ImportBatch batch = row.getImportBatch();
        SupplierSource source = batch.getSupplierSource();
        Map<String, String> raw = readRaw(row.getRawData());
        return new RowExceptionSummary(
                row.getId(),
                row.getVersion(),
                batch.getId(),
                source.getId(),
                source.getLabel(),
                source.getSupplier().getId(),
                source.getSupplier().getName(),
                row.getSourceSheet(),
                row.getSourceRowNumber(),
                row.getStatus(),
                raw.get(LayoutRuleDefinition.FIELD_RAW_NAME),
                raw.get(LayoutRuleDefinition.FIELD_BRAND),
                parsePrice(raw.get(LayoutRuleDefinition.FIELD_SUPPLIER_PRICE)),
                row.getCreatedAt());
    }

    private BatchExceptionSummary toBatchSummary(ImportBatch batch) {
        SupplierSource source = batch.getSupplierSource();
        return new BatchExceptionSummary(
                batch.getId(),
                batch.getStatus(),
                source.getId(),
                source.getLabel(),
                source.getSupplier().getId(),
                source.getSupplier().getName(),
                batch.getImportFile() != null ? batch.getImportFile().getOriginalFilename() : null,
                batch.getTotalRows(),
                batch.getValidRows(),
                batch.getInvalidRows(),
                batch.getAttemptNumber(),
                batch.getErrorMessage(),
                batch.getCreatedAt(),
                batch.getFinishedAt());
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
