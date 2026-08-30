package com.plstk.loyaltybot.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final CommerceProperties commerceProperties;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String basePath = commerceProperties.getImageStorageBasePath();
        if (!StringUtils.hasText(basePath)) {
            return;
        }
        if (!basePath.endsWith("/")) {
            basePath = basePath + "/";
        }
        String prefix = commerceProperties.getImagePublicUrlPrefix();
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        registry.addResourceHandler(prefix + "/**")
                .addResourceLocations("file:" + basePath);
    }
}
