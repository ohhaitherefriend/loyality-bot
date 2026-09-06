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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Mock-provider tests for {@link DeepSeekSpreadsheetLayoutDetector} using
 * {@link MockRestServiceServer} (real {@link RestTemplate}, mocked transport) rather than a
 * Mockito mock of {@code RestTemplate} itself, since inline mocking of final JDK/Spring transport
 * classes is unreliable across JVM versions. Retry/backoff must only apply to retryable
 * transport/status errors (timeout, 429, 5xx), never to malformed content — what the raw JSON
 * content actually *means* (valid layout / invented column / malformed JSON) is
 * {@link LayoutRuleValidator}'s job, covered by {@link LayoutRuleValidatorTest} and end-to-end by
 * {@link ImportBatchParsingServiceTest}.
 */
class DeepSeekSpreadsheetLayoutDetectorTest {

    private static final String URL = "https://api.deepseek.com/v1/chat/completions";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private SupplierImportProperties properties;
    private DeepSeekSpreadsheetLayoutDetector detector;

    private final LayoutDetectionRequest request = new LayoutDetectionRequest(
            "main", "SUPPLIER_ALL", List.of(new LayoutSheetSample("Косметика и уход", 10, 5, List.of())));

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        properties = new SupplierImportProperties();
        properties.getAi().getDeepseek().setApiKey("test-key");
        properties.getAi().getDeepseek().setMaxRetries(2);
        properties.getAi().getDeepseek().setRetryBackoffMs(1); // keep tests fast
        detector = new DeepSeekSpreadsheetLayoutDetector(
                properties, restTemplate, new ObjectMapper(), new SupplierImportMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void disabled_whenNoApiKeyConfigured() {
        properties.getAi().getDeepseek().setApiKey("");
        LayoutDetectionResponse response = detector.detect(request);

        assertFalse(response.success());
        assertFalse(response.retryableFailure());
    }

    @Test
    void valid_firstAttemptSucceeds_noRetry() {
        expectOne().andRespond(withSuccess(envelope("{\"headerRow\":6}"), MediaType.APPLICATION_JSON));

        LayoutDetectionResponse response = detector.detect(request);

        assertTrue(response.success());
        assertEquals("{\"headerRow\":6}", response.rawContent());
        assertEquals("deepseek", response.provider());
        server.verify();
    }

    @Test
    void malformedOrEmptyContent_isPassedThroughAsSuccess_notRetried() {
        expectOne().andRespond(withSuccess(envelope(""), MediaType.APPLICATION_JSON));

        LayoutDetectionResponse response = detector.detect(request);

        assertTrue(response.success(), "detector itself does not validate content, only transport");
        assertEquals("", response.rawContent());
        server.verify();
    }

    @Test
    void timeout_isRetried_thenSucceeds() {
        expectOne().andRespond(request -> {
            throw new IOException("Read timed out");
        });
        expectOne().andRespond(withSuccess(envelope("{\"ok\":true}"), MediaType.APPLICATION_JSON));

        LayoutDetectionResponse response = detector.detect(request);

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

        LayoutDetectionResponse response = detector.detect(request);

        assertFalse(response.success());
        assertTrue(response.retryableFailure());
        server.verify(); // maxRetries=2 -> exactly 3 total attempts were expected/consumed
    }

    @Test
    void tooManyRequests_isRetried_thenSucceeds() {
        expectOne().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        expectOne().andRespond(withSuccess(envelope("{\"ok\":true}"), MediaType.APPLICATION_JSON));

        LayoutDetectionResponse response = detector.detect(request);

        assertTrue(response.success());
        server.verify();
    }

    @Test
    void serverError5xx_isRetried_thenFailsAfterExhaustingRetries() {
        for (int i = 0; i < 3; i++) {
            expectOne().andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }

        LayoutDetectionResponse response = detector.detect(request);

        assertFalse(response.success());
        assertTrue(response.retryableFailure());
        server.verify();
    }

    @Test
    void nonRetryableClientError_failsImmediately_noRetry() {
        expectOne().andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        LayoutDetectionResponse response = detector.detect(request);

        assertFalse(response.success());
        assertFalse(response.retryableFailure());
        server.verify();
    }

    private org.springframework.test.web.client.ResponseActions expectOne() {
        return server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST));
    }

    private String envelope(String content) {
        // Minimal OpenAI-compatible chat completion envelope, content is JSON-escaped by hand here.
        String escaped = content.replace("\"", "\\\"");
        return "{\"choices\":[{\"message\":{\"content\":\"" + escaped + "\"}}]}";
    }
}
