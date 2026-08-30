package com.plstk.loyaltybot.config;

import com.plstk.loyaltybot.service.commerce.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class CommerceImageProviderConfig {

    @Bean
    @Primary
    ProductImageSearchProvider productImageSearchProvider(
            CommerceProperties properties,
            BraveImageSearchProvider braveProvider,
            DisabledProductImageSearchProvider disabledProvider) {
        if (properties.getImageSearch().isEnabled()
                && "brave".equalsIgnoreCase(properties.getImageSearch().getProvider())
                && braveProvider.isConfigured()) {
            return braveProvider;
        }
        return disabledProvider;
    }

    @Bean
    @Primary
    ProductImageCandidateRanker productImageCandidateRanker(
            CommerceProperties properties,
            DeepSeekProductImageCandidateRanker deepSeekRanker,
            RuleBasedProductImageCandidateRanker ruleBasedRanker) {
        if ("deepseek".equalsIgnoreCase(properties.getImageRanker().getProvider())
                && deepSeekRanker.isApiConfigured()) {
            return deepSeekRanker;
        }
        return ruleBasedRanker;
    }

    @Bean
    @Primary
    BackgroundRemovalProvider backgroundRemovalProvider(
            CommerceProperties properties,
            RembgBackgroundRemovalProvider rembgProvider,
            DisabledBackgroundRemovalProvider disabledProvider) {
        if ("rembg".equalsIgnoreCase(properties.getImageNormalization().getBackgroundRemoval().getProvider())) {
            return rembgProvider;
        }
        return disabledProvider;
    }
}
