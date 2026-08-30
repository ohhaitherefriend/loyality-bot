package com.plstk.loyaltybot.service.commerce;

import com.plstk.loyaltybot.config.CommerceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class RembgBackgroundRemovalProvider implements BackgroundRemovalProvider {

    private final CommerceProperties commerceProperties;
    private final RestTemplate restTemplate;

    @Override
    public BackgroundRemovalResult removeBackground(byte[] originalImage, String filename, String contentType) {
        if (!isConfigured()) {
            return BackgroundRemovalResult.failure(providerName(), "rembg URL not configured");
        }
        try {
            String url = commerceProperties.getImageNormalization().getBackgroundRemoval().getRembg().getUrl();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            ByteArrayResource resource = new ByteArrayResource(originalImage) {
                @Override
                public String getFilename() {
                    return filename != null ? filename : "image.jpg";
                }
            };
            body.add("image", resource);

            HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<byte[]> response = restTemplate.postForEntity(url, request, byte[].class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null || response.getBody().length == 0) {
                return BackgroundRemovalResult.failure(providerName(), "rembg returned empty response");
            }
            return BackgroundRemovalResult.success(providerName(), response.getBody());
        } catch (Exception e) {
            log.warn("rembg background removal failed: {}", e.getMessage());
            return BackgroundRemovalResult.failure(providerName(), e.getMessage());
        }
    }

    @Override
    public boolean isConfigured() {
        String url = commerceProperties.getImageNormalization().getBackgroundRemoval().getRembg().getUrl();
        return url != null && !url.isBlank();
    }

    @Override
    public String providerName() {
        return "rembg";
    }

    public boolean isHealthy() {
        if (!isConfigured()) {
            return false;
        }
        try {
            String healthUrl = commerceProperties.getImageNormalization().getBackgroundRemoval().getRembg().getHealthUrl();
            ResponseEntity<String> response = restTemplate.getForEntity(healthUrl, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("rembg health check failed: {}", e.getMessage());
            return false;
        }
    }
}
