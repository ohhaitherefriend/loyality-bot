package com.plstk.loyaltybot.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Dedicated {@code RestTemplate} for supplier-import DeepSeek calls (layout detection + catalog
 * matching), separate from the app-wide shared client ({@link RestTemplateConfig}) used by
 * Telegram/payments/image search. Timeouts come from {@code supplier-import.ai.deepseek.*}
 * (config/env) so a slow/misbehaving DeepSeek account can be tuned or bounded independently,
 * without affecting or being affected by unrelated outbound integrations.
 */
@Configuration
@RequiredArgsConstructor
public class DeepSeekClientConfig {

    private final SupplierImportProperties properties;

    @Bean
    public RestTemplate deepSeekRestTemplate(RestTemplateBuilder builder) {
        SupplierImportProperties.DeepSeek cfg = properties.getAi().getDeepseek();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(cfg.getConnectTimeoutMs());
        factory.setReadTimeout(cfg.getTimeoutMs());

        return builder
                .requestFactory(() -> factory)
                .build();
    }
}
