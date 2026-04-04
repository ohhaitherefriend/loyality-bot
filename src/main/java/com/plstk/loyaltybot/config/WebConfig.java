package com.plstk.loyaltybot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web configuration for Admin Panel.
 * Note: CORS is configured in SecurityConfig for Spring Security integration.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    // CORS configuration is handled by SecurityConfig.corsConfigurationSource()
}

