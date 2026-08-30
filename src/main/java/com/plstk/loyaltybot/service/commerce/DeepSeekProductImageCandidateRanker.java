package com.plstk.loyaltybot.service.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.config.CommerceProperties;
import com.plstk.loyaltybot.entity.commerce.ImageCandidate;
import com.plstk.loyaltybot.entity.commerce.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeepSeekProductImageCandidateRanker implements ProductImageCandidateRanker {

    private static final String SYSTEM_PROMPT = """
            You are a product image matching assistant for a cosmetics ecommerce catalog.
            Your task is to select the best real product image candidate for a catalog item.
            Rules:
            - Use only the provided candidate list.
            - Do not invent image URLs.
            - Do not generate product packaging.
            - Do not approve anything automatically.
            - If none of the candidates clearly match the product, return NO_MATCH.
            - Prefer exact barcode match over name match.
            - Prefer exact article/SKU match over generic name match.
            - Prefer official brand/supplier/product pages over marketplaces.
            - Prefer single-product packshots over collages, lifestyle photos, shelves, hands, bundles, sets, or promo images.
            - Reject candidates that look like analogs, replicas, refills, old packaging, different volume, different shade, or a different product variant.
            - Return strict JSON only.
            """;

    private final CommerceProperties commerceProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final RuleBasedProductImageCandidateRanker fallbackRanker;

    @Override
    public CandidateRankResult rank(Product product, List<ImageCandidate> candidates) {
        if (!isApiConfigured()) {
            return fallbackRanker.rank(product, candidates);
        }
        try {
            String userPrompt = buildUserPrompt(product, candidates);
            String content = callDeepSeek(userPrompt);
            return parseResponse(content, candidates, product);
        } catch (Exception e) {
            log.warn("DeepSeek ranker failed, falling back to rule-based: {}", e.getMessage());
            return fallbackRanker.rank(product, candidates);
        }
    }

    public boolean isApiConfigured() {
        String apiKey = commerceProperties.getImageRanker().getDeepseek().getApiKey();
        return StringUtils.hasText(apiKey);
    }

    private String buildUserPrompt(Product product, List<ImageCandidate> candidates) throws Exception {
        List<Map<String, Object>> candidateJson = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            ImageCandidate c = candidates.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", i);
            item.put("title", c.title());
            item.put("imageUrl", c.imageUrl());
            item.put("pageUrl", c.pageUrl());
            item.put("sourceDomain", c.sourceDomain());
            item.put("description", c.description());
            item.put("width", c.width());
            item.put("height", c.height());
            candidateJson.add(item);
        }

        return """
                Product:
                - id: %d
                - brand: %s
                - supplierArticle: %s
                - barcode: %s
                - name: %s
                - categoryPath: %s

                Candidates JSON:
                %s

                Return strict JSON only:
                {
                  "decision": "MATCH" | "NO_MATCH",
                  "bestCandidateIndex": number | null,
                  "confidence": number,
                  "matchedBy": "BARCODE" | "ARTICLE" | "BRAND_NAME" | "WEAK_TEXT_MATCH" | "NO_MATCH",
                  "reason": "short explanation in Russian",
                  "warnings": ["warning 1", "warning 2"]
                }
                """.formatted(
                product.getId(),
                nullToEmpty(product.getBrand()),
                nullToEmpty(product.getSupplierArticle()),
                nullToEmpty(product.getBarcode()),
                nullToEmpty(product.getName()),
                nullToEmpty(product.getCategoryPath()),
                objectMapper.writeValueAsString(candidateJson));
    }

    private String callDeepSeek(String userPrompt) {
        CommerceProperties.DeepSeek cfg = commerceProperties.getImageRanker().getDeepseek();
        String url = cfg.getBaseUrl();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        url = url + "/v1/chat/completions";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModel());
        body.put("temperature", 0.1);
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userPrompt)
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(cfg.getApiKey());

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("DeepSeek API error");
        }

        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            return root.path("choices").path(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse DeepSeek response", e);
        }
    }

    private CandidateRankResult parseResponse(String content, List<ImageCandidate> candidates, Product product) {
        try {
            String json = extractJson(content);
            JsonNode node = objectMapper.readTree(json);
            String decision = node.path("decision").asText("NO_MATCH");
            int index = node.path("bestCandidateIndex").isNull() ? -1 : node.path("bestCandidateIndex").asInt(-1);
            int confidence = node.path("confidence").asInt(0);
            String matchedBy = node.path("matchedBy").asText("NO_MATCH");
            String reason = node.path("reason").asText("");
            List<String> warnings = new ArrayList<>();
            if (node.has("warnings") && node.get("warnings").isArray()) {
                node.get("warnings").forEach(w -> warnings.add(w.asText()));
            }

            if (!"MATCH".equalsIgnoreCase(decision) || index < 0 || index >= candidates.size()) {
                log.info("DeepSeek NO_MATCH for product {}: {}", product.getId(), reason);
                return fallbackRanker.rank(product, candidates);
            }

            int minConfidence = commerceProperties.getImageSearch().getMinConfidenceToDownload();
            if (confidence < minConfidence) {
                log.info("DeepSeek confidence {} below {} for product {}, using rule-based fallback",
                        confidence, minConfidence, product.getId());
                return fallbackRanker.rank(product, candidates);
            }

            ImageCandidate original = candidates.get(index);
            ImageCandidate best = ImageCandidate.builder()
                    .title(original.title())
                    .imageUrl(original.imageUrl())
                    .thumbnailUrl(original.thumbnailUrl())
                    .pageUrl(original.pageUrl())
                    .sourceDomain(original.sourceDomain())
                    .description(original.description())
                    .sourceType(original.sourceType())
                    .confidence(BigDecimal.valueOf(confidence))
                    .matchedBy(matchedBy)
                    .width(original.width())
                    .height(original.height())
                    .hasWatermark(original.hasWatermark())
                    .looksLikePackshot(original.looksLikePackshot())
                    .needsReview(original.needsReview())
                    .foundAt(original.foundAt())
                    .build();

            return CandidateRankResult.builder()
                    .matched(true)
                    .bestCandidate(best)
                    .bestCandidateIndex(index)
                    .confidence(BigDecimal.valueOf(confidence))
                    .matchedBy(matchedBy)
                    .reason(reason)
                    .warnings(warnings)
                    .build();
        } catch (Exception e) {
            log.warn("Invalid DeepSeek JSON, fallback to rule-based");
            return fallbackRanker.rank(product, candidates);
        }
    }

    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
    }

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
