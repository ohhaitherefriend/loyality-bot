package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.config.SupplierImportProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Mock-provider tests for {@link DeepSeekCatalogMatcher}, same {@link MockRestServiceServer}
 * approach as {@code DeepSeekSpreadsheetLayoutDetectorTest}: retry/backoff must only apply to
 * retryable transport/status errors (timeout, 429, 5xx), never to malformed content - what the raw
 * JSON content actually *means* is {@link CatalogMatchResponseValidator}'s job.
 */
class DeepSeekCatalogMatcherTest {

    private static final String URL = "https://api.deepseek.com/v1/chat/completions";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private SupplierImportProperties properties;
    private DeepSeekCatalogMatcher matcher;

    private final AiMatchRequest request = new AiMatchRequest(
            "row-1",
            new NormalizedRowData("Chanel", "No 5", null, new BigDecimal("100"), "ml", null, null, false, false,
                    "SKU-1", null, new BigDecimal("100.00"), null, "chanel no 5 100 ml", "chanel|no 5|100|ml||"),
            List.of());

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        properties = new SupplierImportProperties();
        properties.getAi().getDeepseek().setApiKey("test-key");
        properties.getAi().getDeepseek().setMaxRetries(2);
        properties.getAi().getDeepseek().setRetryBackoffMs(1); // keep tests fast
        SupplierImportMetrics metrics = new SupplierImportMetrics(new SimpleMeterRegistry());
        DeepSeekHttpClient httpClient = new DeepSeekHttpClient(
                properties, restTemplate, new ObjectMapper(), metrics, new DeepSeekCircuitBreaker());
        matcher = new DeepSeekCatalogMatcher(properties, httpClient, new ObjectMapper(), metrics);
    }

    @Test
    void disabled_whenNoApiKeyConfigured() {
        properties.getAi().getDeepseek().setApiKey("");
        AiMatchResponse response = matcher.match(request);

        assertFalse(response.success());
        assertFalse(response.retryableFailure());
    }

    @Test
    void valid_firstAttemptSucceeds_noRetry_tokensAndPromptVersionPropagated() {
        expectOne().andRespond(withSuccess(envelope("{\"decision\":\"NO_MATCH\"}", 120, 40), MediaType.APPLICATION_JSON));

        AiMatchResponse response = matcher.match(request);

        assertTrue(response.success());
        assertEquals("{\"decision\":\"NO_MATCH\"}", response.rawContent());
        assertEquals("deepseek", response.provider());
        assertEquals(DeepSeekCatalogMatcher.PROMPT_VERSION, response.promptVersion());
        assertEquals(120, response.promptTokens());
        assertEquals(40, response.completionTokens());
        server.verify();
    }

    @Test
    void malformedOrEmptyContent_isPassedThroughAsSuccess_notRetried() {
        expectOne().andRespond(withSuccess(envelope("", null, null), MediaType.APPLICATION_JSON));

        AiMatchResponse response = matcher.match(request);

        assertTrue(response.success(), "matcher itself does not validate content, only transport");
        assertEquals("", response.rawContent());
        server.verify();
    }

    @Test
    void timeout_isRetried_thenSucceeds() {
        expectOne().andRespond(req -> {
            throw new IOException("Read timed out");
        });
        expectOne().andRespond(withSuccess(envelope("{\"ok\":true}", null, null), MediaType.APPLICATION_JSON));

        AiMatchResponse response = matcher.match(request);

        assertTrue(response.success());
        server.verify();
    }

    @Test
    void timeout_exhaustsRetries_thenFailsRetryable() {
        for (int i = 0; i < 3; i++) {
            expectOne().andRespond(req -> {
                throw new IOException("Read timed out");
            });
        }

        AiMatchResponse response = matcher.match(request);

        assertFalse(response.success());
        assertTrue(response.retryableFailure());
        server.verify(); // maxRetries=2 -> exactly 3 total attempts were expected/consumed
    }

    @Test
    void tooManyRequests_isRetried_thenSucceeds() {
        expectOne().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        expectOne().andRespond(withSuccess(envelope("{\"ok\":true}", null, null), MediaType.APPLICATION_JSON));

        AiMatchResponse response = matcher.match(request);

        assertTrue(response.success());
        server.verify();
    }

    @Test
    void serverError5xx_isRetried_thenFailsAfterExhaustingRetries() {
        for (int i = 0; i < 3; i++) {
            expectOne().andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }

        AiMatchResponse response = matcher.match(request);

        assertFalse(response.success());
        assertTrue(response.retryableFailure());
        server.verify();
    }

    @Test
    void nonRetryableClientError_failsImmediately_noRetry() {
        expectOne().andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        AiMatchResponse response = matcher.match(request);

        assertFalse(response.success());
        assertFalse(response.retryableFailure());
        server.verify();
    }

    private org.springframework.test.web.client.ResponseActions expectOne() {
        return server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST));
    }

    private String envelope(String content, Integer promptTokens, Integer completionTokens) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> message = Map.of("content", content);
            Map<String, Object> choice = Map.of("message", message);
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("choices", List.of(choice));
            if (promptTokens != null || completionTokens != null) {
                java.util.Map<String, Object> usage = new java.util.LinkedHashMap<>();
                if (promptTokens != null) {
                    usage.put("prompt_tokens", promptTokens);
                }
                if (completionTokens != null) {
                    usage.put("completion_tokens", completionTokens);
                }
                body.put("usage", usage);
            }
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
