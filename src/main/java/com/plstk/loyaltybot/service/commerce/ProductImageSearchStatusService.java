package com.plstk.loyaltybot.service.commerce;

import com.plstk.loyaltybot.config.CommerceProperties;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductImageSearchStatusService {

    private final CommerceProperties commerceProperties;
    private final BraveImageSearchProvider braveImageSearchProvider;
    private final DeepSeekProductImageCandidateRanker deepSeekRanker;
    private final RembgBackgroundRemovalProvider rembgBackgroundRemovalProvider;

    public SearchStatus getStatus() {
        String searchProvider = commerceProperties.getImageSearch().getProvider();
        String ranker = commerceProperties.getImageRanker().getProvider();
        String bgProvider = commerceProperties.getImageNormalization().getBackgroundRemoval().getProvider();

        boolean rembgConfigured = "rembg".equalsIgnoreCase(bgProvider);
        boolean rembgHealthy = rembgConfigured && rembgBackgroundRemovalProvider.isHealthy();

        return SearchStatus.builder()
                .imageSearchEnabled(commerceProperties.getImageSearch().isEnabled())
                .imageSearchProvider(searchProvider)
                .imageSearchConfigured(braveImageSearchProvider.isConfigured())
                .ranker(ranker)
                .rankerConfigured(deepSeekRanker.isApiConfigured() || "rule-based".equalsIgnoreCase(ranker))
                .backgroundRemovalProvider(bgProvider)
                .backgroundRemovalConfigured(rembgConfigured)
                .backgroundRemovalHealthy(rembgHealthy)
                .backgroundRemovalHealthUrl(
                        commerceProperties.getImageNormalization().getBackgroundRemoval().getRembg().getHealthUrl())
                .normalizationOutputSize(commerceProperties.getImageNormalization().getOutputSize())
                .build();
    }

    @Builder
    public record SearchStatus(
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
    ) {}
}
