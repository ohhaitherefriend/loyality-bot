package com.plstk.loyaltybot.controller;

import com.plstk.loyaltybot.entity.AdminUser;
import com.plstk.loyaltybot.entity.commerce.*;
import com.plstk.loyaltybot.service.ProductImportService;
import com.plstk.loyaltybot.service.ProductService;
import com.plstk.loyaltybot.service.ShopAccessService;
import com.plstk.loyaltybot.service.commerce.ImageStorageService;
import com.plstk.loyaltybot.service.commerce.OrderService;
import com.plstk.loyaltybot.service.commerce.ProductImageApprovalService;
import com.plstk.loyaltybot.service.commerce.ProductImagePipelineService;
import com.plstk.loyaltybot.service.commerce.ProductImageSearchStatusService;
import com.plstk.loyaltybot.service.commerce.ProductImageNormalizationService;
import com.plstk.loyaltybot.service.commerce.ProductImageSearchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/shops/{shopId}")
@RequiredArgsConstructor
public class CommerceAdminController {

    private final ShopAccessService shopAccessService;
    private final ProductImportService productImportService;
    private final ProductService productService;
    private final ProductImageSearchService productImageSearchService;
    private final ProductImageNormalizationService productImageNormalizationService;
    private final ProductImageApprovalService productImageApprovalService;
    private final ProductImagePipelineService productImagePipelineService;
    private final ProductImageSearchStatusService productImageSearchStatusService;
    private final ImageStorageService imageStorageService;
    private final OrderService orderService;

    @PostMapping(value = "/catalog/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProductImportResponse> importCatalog(
            @PathVariable String shopId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "35") BigDecimal defaultMarkupPercent,
            @RequestParam(defaultValue = "false") boolean makeImportedVisible,
            @RequestParam(defaultValue = "false") boolean overwriteManualFields,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        ProductImportService.ImportResult result = productImportService.importPriceList(
                shopId, file, defaultMarkupPercent, makeImportedVisible, overwriteManualFields);

        return ResponseEntity.ok(ProductImportResponse.from(result));
    }

    @GetMapping("/products")
    public ResponseEntity<ProductPageResponse> listProducts(
            @PathVariable String shopId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) Boolean visible,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean missingImages,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        Page<Product> products = productService.listProducts(
                shopId, page, size, query, brand, visible, active, missingImages);

        List<Long> productIds = products.getContent().stream().map(Product::getId).toList();
        Map<Long, String> previewUrls = productImageSearchService.getPreviewImageUrls(shopId, productIds);

        return ResponseEntity.ok(ProductPageResponse.from(products, previewUrls));
    }

    @GetMapping("/products/missing-images")
    public ResponseEntity<ProductPageResponse> listMissingImages(
            @PathVariable String shopId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        Page<Product> products = productService.listProducts(
                shopId, page, size, null, null, null, null, true);
        List<Long> productIds = products.getContent().stream().map(Product::getId).toList();
        Map<Long, String> previewUrls = productImageSearchService.getPreviewImageUrls(shopId, productIds);
        return ResponseEntity.ok(ProductPageResponse.from(products, previewUrls));
    }

    @GetMapping("/products/{productId}")
    public ResponseEntity<ProductResponse> getProduct(
            @PathVariable String shopId,
            @PathVariable Long productId,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        Product product = productService.getProduct(shopId, productId);
        String previewImageUrl = productImageSearchService.getPreviewImageUrl(shopId, productId);
        return ResponseEntity.ok(ProductResponse.from(product, previewImageUrl));
    }

    @PutMapping("/products/{productId}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable String shopId,
            @PathVariable Long productId,
            @Valid @RequestBody ProductUpdateRequest request,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        Product product = productService.updateProduct(shopId, productId, request.toUpdate());
        String previewImageUrl = productImageSearchService.getPreviewImageUrl(shopId, productId);
        return ResponseEntity.ok(ProductResponse.from(product, previewImageUrl));
    }

    @PostMapping("/products/bulk")
    public ResponseEntity<BulkUpdateResponse> bulkUpdateProducts(
            @PathVariable String shopId,
            @Valid @RequestBody ProductBulkRequest request,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        ProductService.BulkUpdateResult result = productService.bulkUpdate(shopId, request.toBulk());
        return ResponseEntity.ok(new BulkUpdateResponse(result.updatedCount()));
    }

    @GetMapping("/products/brands")
    public ResponseEntity<List<String>> listBrands(
            @PathVariable String shopId,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        return ResponseEntity.ok(productService.listBrands(shopId));
    }

    @PostMapping("/products/{productId}/images/search-candidates")
    public ResponseEntity<List<ImageCandidateResponse>> searchImageCandidates(
            @PathVariable String shopId,
            @PathVariable Long productId,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        List<ImageCandidate> candidates = productImageSearchService.searchCandidates(shopId, productId);
        return ResponseEntity.ok(candidates.stream().map(ImageCandidateResponse::from).toList());
    }

    @PostMapping("/products/{productId}/images/download-candidate")
    public ResponseEntity<ProductImageResponse> downloadImageCandidate(
            @PathVariable String shopId,
            @PathVariable Long productId,
            @Valid @RequestBody DownloadCandidateRequest request,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        ProductImage image = productImageSearchService.downloadCandidate(
                shopId, productId, request.toServiceRequest());
        return ResponseEntity.ok(ProductImageResponse.from(image, productId, imageStorageService));
    }

    @PostMapping("/products/{productId}/images/{imageId}/normalize")
    public ResponseEntity<ProductImageResponse> normalizeProductImage(
            @PathVariable String shopId,
            @PathVariable Long productId,
            @PathVariable Long imageId,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        ProductImage image = productImageNormalizationService.normalizeImage(shopId, productId, imageId);
        return ResponseEntity.ok(ProductImageResponse.from(image, productId, imageStorageService));
    }

    @PostMapping("/products/{productId}/images/{imageId}/approve")
    public ResponseEntity<ProductImageResponse> approveProductImage(
            @PathVariable String shopId,
            @PathVariable Long productId,
            @PathVariable Long imageId,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        ProductImage image = productImageApprovalService.approve(shopId, productId, imageId);
        return ResponseEntity.ok(ProductImageResponse.from(image, productId, imageStorageService));
    }

    @PostMapping("/products/{productId}/images/{imageId}/reject")
    public ResponseEntity<ProductImageResponse> rejectProductImage(
            @PathVariable String shopId,
            @PathVariable Long productId,
            @PathVariable Long imageId,
            @RequestBody(required = false) RejectImageRequest request,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        String reason = request != null ? request.reason() : null;
        ProductImage image = productImageApprovalService.reject(shopId, productId, imageId, reason);
        return ResponseEntity.ok(ProductImageResponse.from(image, productId, imageStorageService));
    }

    @PostMapping(value = "/products/{productId}/images/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProductImageResponse> uploadProductImage(
            @PathVariable String shopId,
            @PathVariable Long productId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        ProductImage image = productImageSearchService.uploadImage(shopId, productId, file);
        return ResponseEntity.ok(ProductImageResponse.from(image, productId, imageStorageService));
    }

    @PostMapping("/products/{productId}/images/from-url")
    public ResponseEntity<ProductImageResponse> importProductImageFromUrl(
            @PathVariable String shopId,
            @PathVariable Long productId,
            @Valid @RequestBody ImageFromUrlRequest request,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        ProductImage image = productImageSearchService.importFromUrl(shopId, productId, request.url());
        return ResponseEntity.ok(ProductImageResponse.from(image, productId, imageStorageService));
    }

    @GetMapping("/products/{productId}/images")
    public ResponseEntity<List<ProductImageResponse>> listProductImages(
            @PathVariable String shopId,
            @PathVariable Long productId,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        List<ProductImage> images = productImageSearchService.listImages(shopId, productId);
        return ResponseEntity.ok(images.stream()
                .map(image -> ProductImageResponse.from(image, productId, imageStorageService))
                .toList());
    }

    @GetMapping("/products/images/search-status")
    public ResponseEntity<ImageSearchStatusResponse> getImageSearchStatus(
            @PathVariable String shopId,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        ProductImageSearchStatusService.SearchStatus status = productImageSearchStatusService.getStatus();
        return ResponseEntity.ok(ImageSearchStatusResponse.from(status));
    }

    @PostMapping("/products/images/search-bulk")
    public ResponseEntity<BulkImageSearchResponse> searchImagesBulk(
            @PathVariable String shopId,
            @Valid @RequestBody BulkImageSearchRequest request,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        ProductImagePipelineService.BulkSearchResult result = productImagePipelineService.searchBulk(
                shopId,
                new ProductImagePipelineService.BulkSearchRequest(
                        request.productIds(),
                        request.maxCandidatesPerProduct() != null ? request.maxCandidatesPerProduct() : 5,
                        request.downloadAndNormalize() == null || request.downloadAndNormalize()));
        return ResponseEntity.ok(BulkImageSearchResponse.from(result));
    }

    @GetMapping("/orders")
    public ResponseEntity<OrderPageResponse> listOrders(
            @PathVariable String shopId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        Page<CustomerOrder> orders = orderService.listOrders(shopId, status, page, size);
        return ResponseEntity.ok(OrderPageResponse.from(orders));
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(
            @PathVariable String shopId,
            @PathVariable Long orderId,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        OrderService.OrderDetails details = orderService.getOrder(shopId, orderId);
        return ResponseEntity.ok(OrderResponse.from(details));
    }

    @PatchMapping("/orders/{orderId}/status")
    public ResponseEntity<OrderResponse> updateOrderStatus(
            @PathVariable String shopId,
            @PathVariable Long orderId,
            @Valid @RequestBody OrderStatusUpdateRequest request,
            @AuthenticationPrincipal AdminUser user) {

        if (!shopAccessService.hasAccess(user, shopId)) {
            return ResponseEntity.status(403).build();
        }

        CustomerOrder order = orderService.updateStatus(shopId, orderId, request.status());
        OrderService.OrderDetails details = orderService.getOrder(shopId, order.getId());
        return ResponseEntity.ok(OrderResponse.from(details));
    }

    public record ProductImportResponse(
            Long batchId,
            String filename,
            LocalDate priceListDate,
            int totalRows,
            int importedCount,
            int updatedCount,
            int skippedCount,
            String status,
            String errorMessage
    ) {
        static ProductImportResponse from(ProductImportService.ImportResult result) {
            return new ProductImportResponse(
                    result.batchId(),
                    result.filename(),
                    result.priceListDate(),
                    result.totalRows(),
                    result.importedCount(),
                    result.updatedCount(),
                    result.skippedCount(),
                    result.status(),
                    result.errorMessage());
        }
    }

    public record ProductPageResponse(
            List<ProductResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
        static ProductPageResponse from(Page<Product> page, Map<Long, String> previewUrls) {
            return new ProductPageResponse(
                    page.getContent().stream()
                            .map(product -> ProductResponse.from(product, previewUrls.get(product.getId())))
                            .toList(),
                    page.getNumber(),
                    page.getSize(),
                    page.getTotalElements(),
                    page.getTotalPages());
        }
    }

    public record ProductResponse(
            Long id,
            String shopId,
            String supplierGuid,
            String sourceSheet,
            Integer sourceRow,
            String brand,
            String supplierArticle,
            String barcode,
            String name,
            String description,
            String categoryPath,
            BigDecimal supplierPrice,
            BigDecimal salePrice,
            BigDecimal oldPrice,
            String currency,
            Integer stockQuantity,
            AvailabilityMode availabilityMode,
            Boolean visible,
            Boolean active,
            String mainImageUrl,
            String previewImageUrl,
            ImageStatus imageStatus,
            LocalDate priceListDate,
            LocalDateTime lastImportedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        static ProductResponse from(Product product, String previewImageUrl) {
            return new ProductResponse(
                    product.getId(),
                    product.getShopId(),
                    product.getSupplierGuid(),
                    product.getSourceSheet(),
                    product.getSourceRow(),
                    product.getBrand(),
                    product.getSupplierArticle(),
                    product.getBarcode(),
                    product.getName(),
                    product.getDescription(),
                    product.getCategoryPath(),
                    product.getSupplierPrice(),
                    product.getSalePrice(),
                    product.getOldPrice(),
                    product.getCurrency(),
                    product.getStockQuantity(),
                    product.getAvailabilityMode(),
                    product.getVisible(),
                    product.getActive(),
                    product.getMainImageUrl(),
                    previewImageUrl,
                    product.getImageStatus(),
                    product.getPriceListDate(),
                    product.getLastImportedAt(),
                    product.getCreatedAt(),
                    product.getUpdatedAt());
        }
    }

    public record ProductUpdateRequest(
            BigDecimal salePrice,
            BigDecimal oldPrice,
            Boolean visible,
            Boolean active,
            Integer stockQuantity,
            AvailabilityMode availabilityMode,
            String description
    ) {
        ProductService.ProductUpdate toUpdate() {
            return new ProductService.ProductUpdate(
                    salePrice, oldPrice, visible, active, stockQuantity, availabilityMode, description);
        }
    }

    public record ProductBulkRequest(
            @NotEmpty List<Long> productIds,
            @NotBlank String action,
            Boolean visible,
            Boolean active,
            BigDecimal markupPercent,
            AvailabilityMode availabilityMode
    ) {
        ProductService.ProductBulkRequest toBulk() {
            return new ProductService.ProductBulkRequest(
                    productIds, action, visible, active, markupPercent, availabilityMode);
        }
    }

    public record BulkUpdateResponse(int updatedCount) {}

    public record ImageCandidateResponse(
            String title,
            String imageUrl,
            String thumbnailUrl,
            String pageUrl,
            String sourceDomain,
            String description,
            ImageSourceType sourceType,
            BigDecimal confidence,
            String matchedBy,
            Integer width,
            Integer height,
            Boolean hasWatermark,
            Boolean looksLikePackshot,
            Boolean needsReview,
            LocalDateTime foundAt
    ) {
        static ImageCandidateResponse from(ImageCandidate candidate) {
            return new ImageCandidateResponse(
                    candidate.title(),
                    candidate.imageUrl(),
                    candidate.thumbnailUrl(),
                    candidate.pageUrl(),
                    candidate.sourceDomain(),
                    candidate.description(),
                    candidate.sourceType(),
                    candidate.confidence(),
                    candidate.matchedBy(),
                    candidate.width(),
                    candidate.height(),
                    candidate.hasWatermark(),
                    candidate.looksLikePackshot(),
                    candidate.needsReview(),
                    candidate.foundAt());
        }
    }

    public record DownloadCandidateRequest(
            @NotBlank String imageUrl,
            String pageUrl,
            String sourceDomain,
            ImageSourceType sourceType,
            BigDecimal confidence,
            String matchedBy
    ) {
        ProductImageSearchService.DownloadCandidateRequest toServiceRequest() {
            return new ProductImageSearchService.DownloadCandidateRequest(
                    imageUrl, pageUrl, sourceDomain, sourceType, confidence, matchedBy);
        }
    }

    public record ImageFromUrlRequest(@NotBlank String url) {}

    public record RejectImageRequest(String reason) {}

    public record ProductImageResponse(
            Long id,
            Long productId,
            String shopId,
            ImageType imageType,
            ImageStatus status,
            ImageSourceType sourceType,
            String sourceUrl,
            String sourcePageUrl,
            String sourceDomain,
            String originalUrl,
            String normalizedUrl,
            BigDecimal confidence,
            String matchedBy,
            Boolean approvedByAdmin,
            Boolean aiNormalized,
            String rejectReason,
            Integer visualQualityScore,
            String qualityDecision,
            String qualityWarnings,
            String normalizationProvider,
            Boolean backgroundRemoved,
            Boolean scaleNormalized,
            Boolean angleNormalized,
            String manualReviewReason,
            String rankerReason,
            String rankerWarnings,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        static ProductImageResponse from(ProductImage image, Long productId, ImageStorageService storageService) {
            return new ProductImageResponse(
                    image.getId(),
                    productId,
                    image.getShopId(),
                    image.getImageType(),
                    image.getStatus(),
                    image.getSourceType(),
                    image.getSourceUrl(),
                    image.getSourcePageUrl(),
                    image.getSourceDomain(),
                    storageService.toAbsoluteUrl(image.getOriginalUrl()),
                    storageService.toAbsoluteUrl(image.getNormalizedUrl()),
                    image.getConfidence(),
                    image.getMatchedBy(),
                    image.getApprovedByAdmin(),
                    image.getAiNormalized(),
                    image.getRejectReason(),
                    image.getVisualQualityScore(),
                    image.getQualityDecision(),
                    image.getQualityWarnings(),
                    image.getNormalizationProvider(),
                    image.getBackgroundRemoved(),
                    image.getScaleNormalized(),
                    image.getAngleNormalized(),
                    image.getManualReviewReason(),
                    image.getRankerReason(),
                    image.getRankerWarnings(),
                    image.getCreatedAt(),
                    image.getUpdatedAt());
        }
    }

    public record ImageSearchStatusResponse(
            boolean imageSearchEnabled,
            String imageSearchProvider,
            boolean imageSearchConfigured,
            String ranker,
            boolean rankerConfigured,
            String backgroundRemovalProvider,
            boolean backgroundRemovalConfigured,
            boolean backgroundRemovalHealthy,
            String backgroundRemovalHealthUrl,
            int normalizationOutputSize
    ) {
        static ImageSearchStatusResponse from(ProductImageSearchStatusService.SearchStatus status) {
            return new ImageSearchStatusResponse(
                    status.imageSearchEnabled(),
                    status.imageSearchProvider(),
                    status.imageSearchConfigured(),
                    status.ranker(),
                    status.rankerConfigured(),
                    status.backgroundRemovalProvider(),
                    status.backgroundRemovalConfigured(),
                    status.backgroundRemovalHealthy(),
                    status.backgroundRemovalHealthUrl(),
                    status.normalizationOutputSize());
        }
    }

    public record BulkImageSearchRequest(
            @NotEmpty List<Long> productIds,
            Integer maxCandidatesPerProduct,
            Boolean downloadAndNormalize
    ) {}

    public record BulkImageSearchResponse(
            int processedProducts,
            int candidatesFound,
            int rankedMatches,
            int candidatesRejectedByQuality,
            int imagesDownloaded,
            int backgroundRemovalSucceeded,
            int backgroundRemovalFailed,
            int fallbackNormalized,
            int imagesNormalized,
            int needsReview,
            int failedCount,
            List<String> errors
    ) {
        static BulkImageSearchResponse from(ProductImagePipelineService.BulkSearchResult result) {
            return new BulkImageSearchResponse(
                    result.processedProducts(),
                    result.candidatesFound(),
                    result.rankedMatches(),
                    result.candidatesRejectedByQuality(),
                    result.imagesDownloaded(),
                    result.backgroundRemovalSucceeded(),
                    result.backgroundRemovalFailed(),
                    result.fallbackNormalized(),
                    result.imagesNormalized(),
                    result.needsReview(),
                    result.failedCount(),
                    result.errors());
        }
    }

    public record OrderStatusUpdateRequest(@NotNull OrderStatus status) {}

    public record OrderPageResponse(
            List<OrderSummaryResponse> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
        static OrderPageResponse from(Page<CustomerOrder> page) {
            return new OrderPageResponse(
                    page.getContent().stream().map(OrderSummaryResponse::from).toList(),
                    page.getNumber(),
                    page.getSize(),
                    page.getTotalElements(),
                    page.getTotalPages());
        }
    }

    public record OrderSummaryResponse(
            Long id,
            String shopId,
            Long userId,
            OrderStatus status,
            OrderSource source,
            BigDecimal itemsTotal,
            BigDecimal bonusSpent,
            BigDecimal totalToPay,
            BigDecimal bonusAccrued,
            String customerPhone,
            String customerName,
            DeliveryType deliveryType,
            LocalDateTime createdAt,
            LocalDateTime completedAt,
            LocalDateTime cancelledAt
    ) {
        static OrderSummaryResponse from(CustomerOrder order) {
            return new OrderSummaryResponse(
                    order.getId(),
                    order.getShopId(),
                    order.getUser().getId(),
                    order.getStatus(),
                    order.getSource(),
                    order.getItemsTotal(),
                    order.getBonusSpent(),
                    order.getTotalToPay(),
                    order.getBonusAccrued(),
                    order.getCustomerPhone(),
                    order.getCustomerName(),
                    order.getDeliveryType(),
                    order.getCreatedAt(),
                    order.getCompletedAt(),
                    order.getCancelledAt());
        }
    }

    public record OrderItemResponse(
            Long id,
            Long productId,
            String skuSnapshot,
            String barcodeSnapshot,
            String brandSnapshot,
            String nameSnapshot,
            BigDecimal priceSnapshot,
            AvailabilityMode availabilityModeSnapshot,
            Integer quantity,
            BigDecimal lineTotal
    ) {
        static OrderItemResponse from(OrderItem item) {
            return new OrderItemResponse(
                    item.getId(),
                    item.getProduct().getId(),
                    item.getSkuSnapshot(),
                    item.getBarcodeSnapshot(),
                    item.getBrandSnapshot(),
                    item.getNameSnapshot(),
                    item.getPriceSnapshot(),
                    item.getAvailabilityModeSnapshot(),
                    item.getQuantity(),
                    item.getLineTotal());
        }
    }

    public record OrderResponse(
            Long id,
            String shopId,
            Long userId,
            OrderStatus status,
            OrderSource source,
            BigDecimal itemsTotal,
            BigDecimal bonusSpent,
            BigDecimal totalToPay,
            BigDecimal bonusAccrued,
            String customerPhone,
            String customerName,
            DeliveryType deliveryType,
            String deliveryAddress,
            String customerComment,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime completedAt,
            LocalDateTime cancelledAt,
            List<OrderItemResponse> items
    ) {
        static OrderResponse from(OrderService.OrderDetails details) {
            CustomerOrder order = details.order();
            return new OrderResponse(
                    order.getId(),
                    order.getShopId(),
                    order.getUser().getId(),
                    order.getStatus(),
                    order.getSource(),
                    order.getItemsTotal(),
                    order.getBonusSpent(),
                    order.getTotalToPay(),
                    order.getBonusAccrued(),
                    order.getCustomerPhone(),
                    order.getCustomerName(),
                    order.getDeliveryType(),
                    order.getDeliveryAddress(),
                    order.getCustomerComment(),
                    order.getCreatedAt(),
                    order.getUpdatedAt(),
                    order.getCompletedAt(),
                    order.getCancelledAt(),
                    details.items().stream().map(OrderItemResponse::from).toList());
        }
    }
}
