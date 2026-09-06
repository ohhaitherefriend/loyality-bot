package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.DecidedBy;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.entity.importing.MatchDecision;
import com.plstk.loyaltybot.entity.importing.MatchDecisionType;
import com.plstk.loyaltybot.repository.ImportRowRepository;
import com.plstk.loyaltybot.repository.MatchDecisionRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Human review actions on one {@link ImportRow} for the Prompt 07 exception queue
 * (docs/ARCHITECTURE.md §12): {@code MATCH}/{@code NO_MATCH}/{@code CREATE_PRODUCT}/{@code IGNORE}.
 * Every action is audited as a {@code HUMAN} {@link MatchDecision} (reviewer identity, timestamp,
 * and the row's decision history already gives "previous decision" for free via
 * {@link MatchDecisionRepository#findByImportRowIdOrderByDecidedAtDesc}).
 *
 * <p>Only rows currently in {@link ImportRowStatus#NEEDS_REVIEW} or {@link ImportRowStatus#INVALID}
 * are reviewable - a row already automatically resolved ({@code AUTO_APPROVED}/{@code APPLIED}) or
 * already reviewed ({@code APPROVED}/{@code IGNORED}) must not be silently re-decided through this
 * path (docs/ARCHITECTURE.md §12 optimistic locking requirement covers the concurrent-edit case;
 * this status gate covers the "wrong lifecycle stage" case).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportRowReviewService {

    private static final Set<ImportRowStatus> REVIEWABLE_STATUSES =
            Set.of(ImportRowStatus.NEEDS_REVIEW, ImportRowStatus.INVALID);

    /** Bulk actions are restricted to decisions that need no per-row input (docs/ARCHITECTURE.md §12). */
    private static final Set<RowReviewAction> BULK_ALLOWED_ACTIONS =
            Set.of(RowReviewAction.NO_MATCH, RowReviewAction.IGNORE);

    private final ImportRowRepository importRowRepository;
    private final ProductRepository productRepository;
    private final MatchDecisionRepository matchDecisionRepository;
    private final ObjectMapper objectMapper;

    /** @return empty if the row does not exist for this shop (controller maps this to 404). */
    @Transactional
    public Optional<ImportRow> reviewRow(
            String shopId,
            Long rowId,
            RowReviewAction action,
            Long expectedVersion,
            Long productId,
            String note,
            AdminUser reviewer) {

        Optional<ImportRow> found = importRowRepository.findDetailByShopIdAndId(shopId, rowId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ImportRow row = found.get();

        if (expectedVersion != null && !expectedVersion.equals(row.getVersion())) {
            throw new RowVersionConflictException(row.getVersion());
        }
        if (!REVIEWABLE_STATUSES.contains(row.getStatus())) {
            throw new RowReviewException(
                    "Row " + rowId + " is not reviewable in its current status " + row.getStatus());
        }

        Product chosenProduct = applyAction(shopId, row, action, productId);
        row.setStatus(resultingStatus(action));
        try {
            row = importRowRepository.save(row);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new RowVersionConflictException(null);
        }

        matchDecisionRepository.save(buildDecision(shopId, row, action, chosenProduct, note, reviewer));
        log.info("Row {} (shop {}) manually reviewed by {}: {} -> {}",
                rowId, shopId, reviewer != null ? reviewer.getEmail() : "unknown", action, row.getStatus());
        return Optional.of(row);
    }

    /**
     * @return per-row success/failure map; incompatible rows (wrong action, wrong status, not
     *     found, cross-shop) never abort the rows that were fine (docs/ARCHITECTURE.md §12).
     */
    @Transactional
    public BulkReviewResult bulkReview(
            String shopId, List<Long> rowIds, RowReviewAction action, String note, AdminUser reviewer) {

        if (!BULK_ALLOWED_ACTIONS.contains(action)) {
            throw new RowReviewException(
                    "Bulk action " + action + " is not supported - MATCH/CREATE_PRODUCT require per-row input");
        }

        List<ImportRow> rows = importRowRepository.findByIdInAndShopId(rowIds, shopId);
        var rowsById = new LinkedHashMap<Long, ImportRow>();
        rows.forEach(r -> rowsById.put(r.getId(), r));

        List<Long> succeeded = new java.util.ArrayList<>();
        var failures = new LinkedHashMap<Long, String>();

        for (Long rowId : rowIds) {
            ImportRow row = rowsById.get(rowId);
            if (row == null) {
                failures.put(rowId, "Not found for this shop");
                continue;
            }
            if (!REVIEWABLE_STATUSES.contains(row.getStatus())) {
                failures.put(rowId, "Not reviewable in status " + row.getStatus());
                continue;
            }
            row.setStatus(resultingStatus(action));
            try {
                importRowRepository.save(row);
            } catch (ObjectOptimisticLockingFailureException e) {
                failures.put(rowId, "Modified concurrently - skipped");
                continue;
            }
            matchDecisionRepository.save(buildDecision(shopId, row, action, null, note, reviewer));
            succeeded.add(rowId);
        }

        log.info("Bulk review shop {} action {}: {} succeeded, {} failed",
                shopId, action, succeeded.size(), failures.size());
        return new BulkReviewResult(succeeded, failures);
    }

    private Product applyAction(String shopId, ImportRow row, RowReviewAction action, Long productId) {
        switch (action) {
            case MATCH -> {
                if (productId == null) {
                    throw new RowReviewException("MATCH requires productId");
                }
                Product product = productRepository.findByShopIdAndId(shopId, productId)
                        .orElseThrow(() -> new RowReviewException("Product " + productId + " not found for this shop"));
                requireApplyReadyNormalizedData(row);
                row.setMatchedProduct(product);
                return product;
            }
            case CREATE_PRODUCT -> {
                requireApplyReadyNormalizedData(row);
                row.setMatchedProduct(null);
                return null;
            }
            case NO_MATCH, IGNORE -> {
                row.setMatchedProduct(null);
                return null;
            }
            default -> throw new RowReviewException("Unsupported action " + action);
        }
    }

    /**
     * MATCH/CREATE_PRODUCT feed directly into {@code ImportBatchApplyWriter} once the row becomes
     * {@code APPROVED}; that writer requires a parseable {@code normalizedData.supplierPrice}. An
     * {@code INVALID} row (rejected by the parser before normalization ever ran) usually has none -
     * reject early here with a clear reason instead of letting the row silently fail deep inside
     * the apply stage later.
     */
    private void requireApplyReadyNormalizedData(ImportRow row) {
        String json = row.getNormalizedData();
        if (json == null || json.isBlank()) {
            throw new RowReviewException(
                    "Row " + row.getId() + " has no normalized price data - cannot approve for apply");
        }
        try {
            NormalizedRowData normalized = objectMapper.readValue(json, NormalizedRowData.class);
            if (normalized.supplierPrice() == null) {
                throw new RowReviewException(
                        "Row " + row.getId() + " has no supplier price - cannot approve for apply");
            }
        } catch (RowReviewException e) {
            throw e;
        } catch (Exception e) {
            throw new RowReviewException("Row " + row.getId() + " normalizedData is not valid JSON");
        }
    }

    private ImportRowStatus resultingStatus(RowReviewAction action) {
        return switch (action) {
            case MATCH, CREATE_PRODUCT -> ImportRowStatus.APPROVED;
            case NO_MATCH, IGNORE -> ImportRowStatus.IGNORED;
        };
    }

    private MatchDecisionType decisionType(RowReviewAction action) {
        return switch (action) {
            case MATCH -> MatchDecisionType.MANUAL;
            case CREATE_PRODUCT -> MatchDecisionType.NEW_PRODUCT;
            case NO_MATCH -> MatchDecisionType.NO_MATCH;
            case IGNORE -> MatchDecisionType.MANUAL;
        };
    }

    private MatchDecision buildDecision(
            String shopId, ImportRow row, RowReviewAction action, Product chosenProduct, String note, AdminUser reviewer) {
        String reason = (note != null && !note.isBlank()) ? note : "Operator action: " + action;
        return MatchDecision.builder()
                .shopId(shopId)
                .importRow(row)
                .candidateProductIds(chosenProduct != null ? toJson(List.of(chosenProduct.getId())) : "[]")
                .chosenProduct(chosenProduct)
                .decisionType(decisionType(action))
                .confidenceScore(BigDecimal.ONE)
                .conflicts("[]")
                .reason(reason)
                .decidedBy(DecidedBy.HUMAN)
                .reviewerUserId(reviewer != null ? reviewer.getId() : null)
                .reviewerEmail(reviewer != null ? reviewer.getEmail() : null)
                .decidedAt(LocalDateTime.now())
                .build();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize match decision data", e);
        }
    }
}
