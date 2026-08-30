package com.plstk.loyaltybot.service.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.config.CommerceProperties;
import com.plstk.loyaltybot.entity.commerce.ImageCandidate;
import com.plstk.loyaltybot.entity.commerce.ImageSourceType;
import com.plstk.loyaltybot.entity.commerce.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class BraveImageSearchProvider implements ProductImageSearchProvider {

    private static final String BRAVE_IMAGES_URL = "https://api.search.brave.com/res/v1/images/search";

    private final CommerceProperties commerceProperties;
    private final ProductImageQueryBuilder queryBuilder;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public List<ImageCandidate> search(Product product) {
        if (!isConfigured()) {
            return List.of();
        }

        Map<String, ImageCandidate> dedup = new LinkedHashMap<>();
        List<String> queries = queryBuilder.buildQueries(product);
        int maxResults = commerceProperties.getImageSearch().getMaxResultsPerQuery();
        long rateLimitMs = commerceProperties.getImageSearch().getRateLimitMs();

        for (String query : queries) {
            try {
                List<ImageCandidate> batch = searchQuery(query, maxResults);
                for (ImageCandidate candidate : batch) {
                    if (candidate.imageUrl() != null) {
                        dedup.putIfAbsent(candidate.imageUrl(), candidate);
                    }
                }
                if (rateLimitMs > 0) {
                    Thread.sleep(rateLimitMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Brave search failed for query '{}': {}", query, e.getMessage());
            }
        }
        return new ArrayList<>(dedup.values());
    }

    public boolean isConfigured() {
        return commerceProperties.getImageSearch().isEnabled()
                && "brave".equalsIgnoreCase(commerceProperties.getImageSearch().getProvider())
                && StringUtils.hasText(commerceProperties.getImageSearch().getBrave().getApiKey());
    }

    private List<ImageCandidate> searchQuery(String query, int count) throws Exception {
        URI uri = UriComponentsBuilder.fromHttpUrl(BRAVE_IMAGES_URL)
                .queryParam("q", query)
                .queryParam("count", count)
                .queryParam("safesearch", "strict")
                .build()
                .encode()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Subscription-Token", commerceProperties.getImageSearch().getBrave().getApiKey());
        headers.set("Accept", "application/json");

        ResponseEntity<String> response = restTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return List.of();
        }

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            return List.of();
        }

        List<ImageCandidate> candidates = new ArrayList<>();
        for (JsonNode item : results) {
            candidates.add(mapCandidate(item));
        }
        return candidates;
    }

    private ImageCandidate mapCandidate(JsonNode item) {
        String title = text(item, "title");
        String pageUrl = text(item, "url");
        String sourceDomain = extractDomain(pageUrl);
        String description = text(item, "description");

        JsonNode properties = item.path("properties");
        String imageUrl = text(properties, "url");
        String thumbnailUrl = item.path("thumbnail").path("src").asText(null);
        Integer width = intOrNull(properties.path("width"));
        Integer height = intOrNull(properties.path("height"));

        boolean usedThumbnail = false;
        if (!StringUtils.hasText(imageUrl) && StringUtils.hasText(thumbnailUrl)) {
            imageUrl = thumbnailUrl;
            usedThumbnail = true;
        }

        BigDecimal confidence = BigDecimal.valueOf(usedThumbnail ? 35 : 45);

        return ImageCandidate.builder()
                .title(title)
                .imageUrl(imageUrl)
                .thumbnailUrl(thumbnailUrl)
                .pageUrl(pageUrl)
                .sourceDomain(sourceDomain)
                .description(description)
                .sourceType(ImageSourceType.MARKETPLACE_CANDIDATE)
                .confidence(confidence)
                .matchedBy("SEARCH")
                .width(width)
                .height(height)
                .hasWatermark(false)
                .looksLikePackshot(false)
                .needsReview(true)
                .foundAt(LocalDateTime.now())
                .build();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private Integer intOrNull(JsonNode node) {
        return node != null && node.isInt() ? node.asInt() : null;
    }

    private String extractDomain(String url) {
        try {
            if (!StringUtils.hasText(url)) {
                return null;
            }
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
