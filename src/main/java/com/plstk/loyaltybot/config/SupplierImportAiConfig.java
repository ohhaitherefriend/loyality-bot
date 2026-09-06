package com.plstk.loyaltybot.config;

import com.plstk.loyaltybot.service.importing.AiCatalogMatcher;
import com.plstk.loyaltybot.service.importing.AiSpreadsheetLayoutDetector;
import com.plstk.loyaltybot.service.importing.DeepSeekCatalogMatcher;
import com.plstk.loyaltybot.service.importing.DeepSeekSpreadsheetLayoutDetector;
import com.plstk.loyaltybot.service.importing.DisabledCatalogMatcher;
import com.plstk.loyaltybot.service.importing.DisabledSpreadsheetLayoutDetector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Selects the {@link AiSpreadsheetLayoutDetector}/{@link AiCatalogMatcher} implementations the same
 * way {@code CommerceImageProviderConfig} selects the image ranker: DeepSeek only when an API key is
 * actually configured, otherwise a safe disabled no-op so the app always starts.
 */
@Configuration
public class SupplierImportAiConfig {

    @Bean
    @Primary
    AiSpreadsheetLayoutDetector aiSpreadsheetLayoutDetector(
            DeepSeekSpreadsheetLayoutDetector deepSeekDetector,
            DisabledSpreadsheetLayoutDetector disabledDetector) {
        return deepSeekDetector.isConfigured() ? deepSeekDetector : disabledDetector;
    }

    @Bean
    @Primary
    AiCatalogMatcher aiCatalogMatcher(
            DeepSeekCatalogMatcher deepSeekMatcher,
            DisabledCatalogMatcher disabledMatcher) {
        return deepSeekMatcher.isConfigured() ? deepSeekMatcher : disabledMatcher;
    }
}
