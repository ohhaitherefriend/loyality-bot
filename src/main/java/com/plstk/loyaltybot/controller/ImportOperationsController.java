package com.plstk.loyaltybot.controller;

import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.entity.commerce.Product;
import com.plstk.loyaltybot.entity.importing.ImportBatch;
import com.plstk.loyaltybot.entity.importing.ImportRow;
import com.plstk.loyaltybot.entity.importing.ImportRowStatus;
import com.plstk.loyaltybot.service.ShopAccessService;
import com.plstk.loyaltybot.service.importing.BatchExceptionSummary;
import com.plstk.loyaltybot.service.importing.BulkReviewResult;
import com.plstk.loyaltybot.service.importing.ImportBatchDetailService;
import com.plstk.loyaltybot.service.importing.ImportBatchResumeService;
import com.plstk.loyaltybot.service.importing.ImportDashboardResponse;
import com.plstk.loyaltybot.service.importing.ImportDashboardService;
import com.plstk.loyaltybot.service.importing.ImportExceptionQueueService;
import com.plstk.loyaltybot.service.importing.ImportRowReviewService;
import com.plstk.loyaltybot.service.importing.ImportRuleVersionApprovalService;
import com.plstk.loyaltybot.service.importing.ProductVisibilityOverrideService;
import com.plstk.loyaltybot.service.importing.RowDetailResponse;
import com.plstk.loyaltybot.service.importing.RowExceptionSummary;
import com.plstk.loyaltybot.service.importing.RowListItem;
import com.plstk.loyaltybot.service.importing.RowReviewAction;
import com.plstk.loyaltybot.service.importing.RowReviewException;
import com.plstk.loyaltybot.service.importing.RowVersionConflictException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Prompt 07 automation control-panel endpoints (docs/ARCHITECTURE.md §12): dashboard, unified
 * exception queue, batch/row detail, row review actions, batch resume, manual-hidden override, and
 * layout-rule approval. Every endpoint is shop-scoped and gated by {@link ShopAccessService}, same
 * as every other admin controller in this codebase. No manual-upload endpoint here by design (Prompt
 * 07 scope explicitly excludes it - see prompts/07-operations-ui.md).
 */
@RestController
@RequestMapping("/api/shops/{shopId}/operations")
@RequiredArgsConstructor
public class ImportOperationsController {

    private final ShopAccessService shopAccessService;
    private final ImportDashboardService importDashboardService;
    private final ImportExceptionQueueService importExceptionQueueService;
    private final ImportBatchDetailService importBatchDetailService;
    private final ImportRowReviewService importRowReviewService;
    private final ImportBatchResumeService importBatchResumeService;
    private final ProductVisibilityOverrideService productVisibilityOverrideService;
    private final ImportRuleVersionApprovalService importRuleVersionApprovalService;

    // ========== Dashboard ==========

    @GetMapping("/dashboard")
    public ResponseEntity<ImportDashboardResponse> getDashboard(
            @PathVariable String shopId,
            @RequestParam(defaultValue = "24") int windowHours,
            @AuthenticationPrincipal AdminUser user) {
        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(importDashboardService.buildDashboard(shopId, windowHours));
    }

    // ========== Exception queue ==========

    @GetMapping("/exceptions/rows")
    public ResponseEntity<PageResponse<RowExceptionSummary>> listRowExceptions(
            @PathVariable String shopId,
            @RequestParam(required = false) Long supplierId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AdminUser user) {
        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(PageResponse.from(
                importExceptionQueueService.listRowExceptions(shopId, supplierId, clampPage(page), clampSize(size))));
    }

    @GetMapping("/exceptions/batches")
    public ResponseEntity<PageResponse<BatchExceptionSummary>> listBatchExceptions(
            @PathVariable String shopId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AdminUser user) {
        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(PageResponse.from(
                importExceptionQueueService.listBatchExceptions(shopId, clampPage(page), clampSize(size))));
    }

    // ========== Batch / row detail ==========

    @GetMapping("/batches/{batchId}")
    public ResponseEntity<?> getBatchDetail(
            @PathVariable String shopId, @PathVariable Long batchId, @AuthenticationPrincipal AdminUser user) {
        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        return importBatchDetailService.getBatchDetail(shopId, batchId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/batches/{batchId}/rows")
    public ResponseEntity<?> listBatchRows(
            @PathVariable String shopId,
            @PathVariable Long batchId,
            @RequestParam(required = false) List<ImportRowStatus> status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @AuthenticationPrincipal AdminUser user) {
        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        return importBatchDetailService.listBatchRows(shopId, batchId, status, clampPage(page), clampSize(size))
                .<ResponseEntity<?>>map(rows -> ResponseEntity.ok(PageResponse.from(rows)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/rows/{rowId}")
    public ResponseEntity<RowDetailResponse> getRowDetail(
            @PathVariable String shopId, @PathVariable Long rowId, @AuthenticationPrincipal AdminUser user) {
        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        return importBatchDetailService.getRowDetail(shopId, rowId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ========== Row review actions ==========

    @PostMapping("/rows/{rowId}/review")
    public ResponseEntity<?> reviewRow(
            @PathVariable String shopId,
            @PathVariable Long rowId,
            @Valid @RequestBody RowReviewRequest request,
            @AuthenticationPrincipal AdminUser user) {
        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        try {
            return importRowReviewService
                    .reviewRow(shopId, rowId, request.action(), request.expectedVersion(), request.productId(), request.note(), user)
                    .<ResponseEntity<?>>map(row -> ResponseEntity.ok(RowReviewResultResponse.from(row)))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (RowVersionConflictException e) {
            return ResponseEntity.status(409).body(new VersionConflictResponse(e.getMessage(), e.getCurrentVersion()));
        } catch (RowReviewException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_ACTION", e.getMessage()));
        }
    }

    @PostMapping("/rows/bulk-review")
    public ResponseEntity<?> bulkReviewRows(
            @PathVariable String shopId, @Valid @RequestBody BulkRowReviewRequest request, @AuthenticationPrincipal AdminUser user) {
        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        try {
            BulkReviewResult result = importRowReviewService.bulkReview(
                    shopId, request.rowIds(), request.action(), request.note(), user);
            return ResponseEntity.ok(result);
        } catch (RowReviewException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_ACTION", e.getMessage()));
        }
    }

    // ========== Batch resume ==========

    @PostMapping("/batches/{batchId}/resume")
    public ResponseEntity<?> resumeBatch(
            @PathVariable String shopId, @PathVariable Long batchId, @AuthenticationPrincipal AdminUser user) {
        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        try {
            return importBatchResumeService.resume(shopId, batchId)
                    .<ResponseEntity<?>>map(this::batchResumeResponse)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (RowVersionConflictException e) {
            return ResponseEntity.status(409).body(new VersionConflictResponse(e.getMessage(), e.getCurrentVersion()));
        } catch (RowReviewException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_ACTION", e.getMessage()));
        }
    }

    private ResponseEntity<?> batchResumeResponse(ImportBatch batch) {
        return ResponseEntity.ok(Map.of("batchId", batch.getId(), "status", batch.getStatus()));
    }

    // ========== Pagination guards ==========

    private static final int MAX_PAGE_SIZE = 200;

    /**
     * Every {@code Pageable}/{@code PageRequest.of(page, size)} downstream of these operator queue
     * endpoints has no built-in ceiling: an unbounded {@code size} could load/serialize an entire
     * shop's exception queue in one response, and a negative {@code page}/{@code size} would throw
     * an uncaught {@code IllegalArgumentException} (500) instead of a controlled response. Clamp
     * both here, once, for every paginated endpoint in this controller.
     */
    private int clampPage(int page) {
        return Math.max(page, 0);
    }

    private int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    // ========== Manual hidden ==========

    @PostMapping("/products/{productId}/manual-hidden")
    public ResponseEntity<?> setManualHidden(
            @PathVariable String shopId,
            @PathVariable Long productId,
            @Valid @RequestBody ManualHiddenRequest request,
            @AuthenticationPrincipal AdminUser user) {
        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        return productVisibilityOverrideService.setManualHidden(shopId, productId, request.hidden())
                .<ResponseEntity<?>>map(product -> ResponseEntity.ok(ManualHiddenResponse.from(product)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ========== Rule version approval ("approve layout") ==========

    @PostMapping("/rule-versions/{ruleVersionId}/approve")
    public ResponseEntity<?> approveRuleVersion(
            @PathVariable String shopId, @PathVariable Long ruleVersionId, @AuthenticationPrincipal AdminUser user) {
        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }
        try {
            return importRuleVersionApprovalService.approve(shopId, ruleVersionId)
                    .<ResponseEntity<?>>map(v -> ResponseEntity.ok(Map.of("ruleVersionId", v.getId(), "status", v.getStatus())))
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (RowReviewException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_ACTION", e.getMessage()));
        }
    }

    // ========== DTOs ==========

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        static <T> PageResponse<T> from(Page<T> page) {
            return new PageResponse<>(
                    page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
        }
    }

    public record ErrorResponse(String code, String message) {
    }

    public record VersionConflictResponse(String message, Long currentVersion) {
    }

    public record RowReviewRequest(
            @NotNull RowReviewAction action,
            Long expectedVersion,
            Long productId,
            String note) {
    }

    public record RowReviewResultResponse(Long rowId, Long version, ImportRowStatus status, Long matchedProductId) {
        static RowReviewResultResponse from(ImportRow row) {
            return new RowReviewResultResponse(
                    row.getId(), row.getVersion(), row.getStatus(),
                    row.getMatchedProduct() != null ? row.getMatchedProduct().getId() : null);
        }
    }

    public record BulkRowReviewRequest(@NotEmpty List<Long> rowIds, @NotNull RowReviewAction action, String note) {
    }

    public record ManualHiddenRequest(@NotNull Boolean hidden) {
    }

    public record ManualHiddenResponse(Long productId, Boolean manualHidden, Boolean visible) {
        static ManualHiddenResponse from(Product product) {
            return new ManualHiddenResponse(product.getId(), product.getManualHidden(), product.getVisible());
        }
    }
}
