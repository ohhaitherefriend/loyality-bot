package com.plstk.loyaltybot.config;

import com.plstk.loyaltybot.service.importing.ProductCandidateFetcher;
import com.plstk.loyaltybot.service.importing.SimpleProductCandidateFetcher;
import com.plstk.loyaltybot.service.importing.TrigramProductCandidateFetcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Selects the {@link ProductCandidateFetcher} implementation the same way
 * {@code SupplierImportAiConfig} selects the layout detector: an explicit opt-in config flag, not
 * the {@code prod} Spring profile, because Flyway staying disabled means a production database
 * cannot be assumed to already have the {@code pg_trgm} extension/index from {@code V19} applied.
 */
@Configuration
public class CandidateFetcherConfig {

    @Bean
    @Primary
    ProductCandidateFetcher productCandidateFetcher(
            SimpleProductCandidateFetcher simpleFetcher,
            TrigramProductCandidateFetcher trigramFetcher,
            SupplierImportProperties properties) {
        return properties.getMatching().isPgTrgmEnabled() ? trigramFetcher : simpleFetcher;
    }
}
