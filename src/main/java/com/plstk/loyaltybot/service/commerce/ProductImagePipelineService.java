package com.plstk.loyaltybot.service.commerce;

import com.plstk.loyaltybot.config.CommerceProperties;
import com.plstk.loyaltybot.entity.commerce.*;
import com.plstk.loyaltybot.repository.ProductImageRepository;
import com.plstk.loyaltybot.repository.ProductRepository;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductImagePipelineService {

    private final CommerceProperties commerceProperties;
    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductImageSearchProvider searchProvider;
    private final ProductImageCandidateRanker candidateRanker;
    private final ProductImageQualityAssessor qualityAssessor;
    private final ProductImageVisualNormalizationService visualNormalizationService;
    private final ImageStorageService imageStorageService;
    private final RestTemplate restTemplate;
    private final RuleBasedProductImageCandidateRanker ruleBasedRanker;

    @Transactional
    public BulkSearchResult searchBulk(String shopId, BulkSearchRequest request) {
        validateBulkRequest(request);

        BulkSearchResult.BulkSearchResultBuilder result = BulkSearchResult.builder();
        List<String> errors = new ArrayList<>();

        int processed = 0;
        int candidatesFound = 0;
        int rankedMatches = 0;
        int rejectedByQuality = 0;
        int downloaded = 0;
        int bgSuccess = 0;
        int bgFailed = 0;
        int fallbackNormalized = 0;
        int normalized = 0;
        int needsReview = 0;
        int failed = 0;

        for (Long productId : request.productIds()) {
            processed++;
            try {
                Product product = productRepository.findByShopIdAndId(shopId, productId)
                        .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

                List<ImageCandidate> candidates = searchProvider.search(product);
                candidatesFound += candidates.size();
                if (candidates.isEmpty()) {
                    continue;
                }

                RankedCandidates ranked = rankCandidates(product, candidates, request.maxCandidatesPerProduct());
                if (ranked.candidates().isEmpty()) {
                    continue;
                }
                rankedMatches++;

                int normalizedForProduct = 0;
                for (ImageCandidate candidate : ranked.candidates()) {
                    if (normalizedForProduct >= 3) {
                        break;
                    }

                    byte[] bytes;
                    try {
                        bytes = downloadBytes(candidate.imageUrl());
                    } catch (Exception e) {
                        log.debug("Download failed for product {} candidate: {}", productId, e.getMessage());
                        failed++;
                        continue;
                    }
                    downloaded++;

                    BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                    QualityAssessment quality = qualityAssessor.assess(
                            product, candidate, image, effectiveConfidence(candidate, ranked.rankResult()));

                    if ("REJECT_AND_TRY_NEXT".equals(quality.getDecision())) {
                        rejectedByQuality++;
                        continue;
                    }

                    ProductImage saved = saveCandidateImage(shopId, product, candidate, quality, ranked.rankResult());
                    if (!request.downloadAndNormalize()) {
                        needsReview++;
                        normalizedForProduct++;
                        continue;
                    }

                    try {
                        storeOriginal(saved, shopId, productId, bytes, candidate.imageUrl());
                        ProductImageVisualNormalizationService.NormalizationOutcome outcome =
                                visualNormalizationService.normalizeBytes(product, bytes, "candidate.jpg", "image/jpeg");
                        ImageStorageService.StoredImage stored = imageStorageService.saveNormalized(
                                shopId, productId, saved.getId(), new ByteArrayInputStream(outcome.normalizedBytes()));
                        saved.setNormalizedUrl(stored.publicPath());
                        saved.setStatus(ImageStatus.NEEDS_REVIEW);
                        saved.setNormalizationProvider(outcome.provider());
                        saved.setBackgroundRemoved(outcome.backgroundRemoved());
                        saved.setScaleNormalized(true);
                        productImageRepository.save(saved);

                        normalized++;
                        normalizedForProduct++;
                        needsReview++;
                        if (outcome.backgroundRemoved()) {
                            bgSuccess++;
                        } else if ("fallback".equals(outcome.provider())) {
                            fallbackNormalized++;
                            bgFailed++;
                        } else {
                            bgFailed++;
                        }
                        updateProductStatus(product, ImageStatus.NEEDS_REVIEW);
                        break;
                    } catch (Exception e) {
                        failed++;
                        errors.add("product " + productId + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                failed++;
                errors.add("product " + productId + ": " + e.getMessage());
                log.warn("Bulk image search failed for product {}: {}", productId, e.getMessage());
            }
        }

        return result
                .processedProducts(processed)
                .candidatesFound(candidatesFound)
                .rankedMatches(rankedMatches)
                .candidatesRejectedByQuality(rejectedByQuality)
                .imagesDownloaded(downloaded)
                .backgroundRemovalSucceeded(bgSuccess)
                .backgroundRemovalFailed(bgFailed)
                .fallbackNormalized(fallbackNormalized)
                .imagesNormalized(normalized)
                .needsReview(needsReview)
                .failedCount(failed)
                .errors(errors)
                .build();
    }

    private RankedCandidates rankCandidates(Product product, List<ImageCandidate> candidates, int maxCandidates) {
        CandidateRankResult rankResult = candidateRanker.rank(product, candidates);
        if (!isDownloadableMatch(rankResult)) {
            rankResult = ruleBasedRanker.rank(product, candidates);
        }
        if (!isDownloadableMatch(rankResult)) {
            log.info("No downloadable image match for product {} after rankers", product.getId());
            return RankedCandidates.empty();
        }

        List<ImageCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparing((ImageCandidate c) -> c.confidence() != null ? c.confidence() : BigDecimal.ZERO).reversed());

        List<ImageCandidate> result = new LinkedList<>();
        result.add(withRankerConfidence(rankResult.bestCandidate(), rankResult));
        for (ImageCandidate candidate : sorted) {
            if (result.size() >= maxCandidates) {
                break;
            }
            if (!Objects.equals(candidate.imageUrl(), rankResult.bestCandidate().imageUrl())) {
                result.add(candidate);
            }
        }
        return new RankedCandidates(rankResult, result);
    }

    private ImageCandidate withRankerConfidence(ImageCandidate candidate, CandidateRankResult rankResult) {
        if (rankResult.confidence() == null) {
            return candidate;
        }
        return ImageCandidate.builder()
                .title(candidate.title())
                .imageUrl(candidate.imageUrl())
                .thumbnailUrl(candidate.thumbnailUrl())
                .pageUrl(candidate.pageUrl())
                .sourceDomain(candidate.sourceDomain())
                .description(candidate.description())
                .sourceType(candidate.sourceType())
                .confidence(rankResult.confidence())
                .matchedBy(rankResult.matchedBy() != null ? rankResult.matchedBy() : candidate.matchedBy())
                .width(candidate.width())
                .height(candidate.height())
                .hasWatermark(candidate.hasWatermark())
                .looksLikePackshot(candidate.looksLikePackshot())
                .needsReview(candidate.needsReview())
                .foundAt(candidate.foundAt())
                .build();
    }

    private int effectiveConfidence(ImageCandidate candidate, CandidateRankResult rankResult) {
        if (candidate.confidence() != null && candidate.confidence().intValue() >= 50) {
            return candidate.confidence().intValue();
        }
        if (rankResult != null && rankResult.confidence() != null) {
            return rankResult.confidence().intValue();
        }
        return candidate.confidence() != null ? candidate.confidence().intValue() : 50;
    }

    private record RankedCandidates(CandidateRankResult rankResult, List<ImageCandidate> candidates) {
        static RankedCandidates empty() {
            return new RankedCandidates(null, List.of());
        }
    }

    private boolean isDownloadableMatch(CandidateRankResult rankResult) {
        if (rankResult == null || !rankResult.matched() || rankResult.bestCandidate() == null) {
            return false;
        }
        if (rankResult.confidence() == null) {
            return true;
        }
        return rankResult.confidence().intValue()
                >= commerceProperties.getImageSearch().getMinConfidenceToDownload();
    }

    private ProductImage saveCandidateImage(
            String shopId,
            Product product,
            ImageCandidate candidate,
            QualityAssessment quality,
            CandidateRankResult rankResult) {
        ProductImage image = ProductImage.builder()
                .shopId(shopId)
                .product(product)
                .imageType(ImageType.CANDIDATE)
                .status(ImageStatus.NEEDS_REVIEW)
                .sourceType(candidate.sourceType() != null ? candidate.sourceType() : ImageSourceType.MARKETPLACE_CANDIDATE)
                .sourceUrl(candidate.imageUrl())
                .sourcePageUrl(candidate.pageUrl())
                .sourceDomain(candidate.sourceDomain())
                .confidence(candidate.confidence())
                .matchedBy(candidate.matchedBy())
                .approvedByAdmin(false)
                .aiNormalized(false)
                .build();
        visualNormalizationService.applyQualityMetadata(image, quality, rankResult);
        return productImageRepository.save(image);
    }

    private void storeOriginal(ProductImage image, String shopId, Long productId, byte[] bytes, String url) throws Exception {
        String extension = url != null && url.contains(".png") ? "png" : "jpg";
        ImageStorageService.StoredImage stored = imageStorageService.saveOriginal(
                shopId, productId, image.getId(), new ByteArrayInputStream(bytes), extension);
        image.setOriginalUrl(stored.publicPath());
        productImageRepository.save(image);
    }

    private byte[] downloadBytes(String url) {
        ResponseEntity<byte[]> response = restTemplate.getForEntity(url, byte[].class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null || response.getBody().length == 0) {
            throw new IllegalArgumentException("Failed to download image from URL");
        }
        return response.getBody();
    }

    private void updateProductStatus(Product product, ImageStatus status) {
        if (product.getImageStatus() == ImageStatus.APPROVED) {
            return;
        }
        product.setImageStatus(status);
        product.setImageUpdatedAt(java.time.LocalDateTime.now());
        productRepository.save(product);
    }

    private void validateBulkRequest(BulkSearchRequest request) {
        if (request.productIds() == null || request.productIds().isEmpty()) {
            throw new IllegalArgumentException("productIds is required");
        }
        if (request.productIds().size() > commerceProperties.getImageSearch().getMaxProductsPerBatch()) {
            throw new IllegalArgumentException("Too many products in batch. Max "
                    + commerceProperties.getImageSearch().getMaxProductsPerBatch());
        }
    }

    public record BulkSearchRequest(
            List<Long> productIds,
            int maxCandidatesPerProduct,
            boolean downloadAndNormalize
    ) {}

    @Builder
    public record BulkSearchResult(
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
    ) {}
}
