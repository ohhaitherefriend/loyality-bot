package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.config.SupplierImportProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link DeepSeekHttpClient} (Stage 5 hardening): circuit breaker integration,
 * concurrency limiting, and honoring the {@code Retry-After} header on HTTP 429 responses. Retry
 * classification and content pass-through are already covered end-to-end by
 * {@link DeepSeekCatalogMatcherTest}/{@code DeepSeekSpreadsheetLayoutDetectorTest}; this suite
 * focuses on the transport-shared behavior that lives here.
 */
class DeepSeekHttpClientTest {

    private static final String URL = "https://api.deepseek.com/v1/chat/completions";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private SupplierImportProperties properties;
    private DeepSeekCircuitBreaker circuitBreaker;
    private DeepSeekHttpClient client;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        properties = new SupplierImportProperties();
        properties.getAi().getDeepseek().setApiKey("test-key");
        properties.getAi().getDeepseek().setMaxRetries(0);
        properties.getAi().getDeepseek().setRetryBackoffMs(1);
        properties.getAi().getDeepseek().setCircuitBreakerFailureThreshold(2);
        properties.getAi().getDeepseek().setCircuitBreakerOpenDurationMs(60_000L);
        properties.getAi().getDeepseek().setMaxConcurrentRequests(4);
        properties.getAi().getDeepseek().setConcurrencyAcquireTimeoutMs(2000);
        circuitBreaker = new DeepSeekCircuitBreaker();
        client = new DeepSeekHttpClient(
                properties, restTemplate, new ObjectMapper(), new SupplierImportMetrics(new SimpleMeterRegistry()),
                circuitBreaker);
    }

    @Test
    void circuitOpensAfterThreshold_thenFailsFastWithoutCallingServer() {
        properties.getAi().getDeepseek().setMaxRetries(0); // 1 attempt per call, 2 calls to reach threshold=2
        expectOne().andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expectOne().andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        DeepSeekCallOutcome first = client.chatCompletion("sys", "user", "test");
        DeepSeekCallOutcome second = client.chatCompletion("sys", "user", "test");
        assertFalse(first.success());
        assertFalse(second.success());
        assertEquals(DeepSeekCircuitBreaker.State.OPEN, circuitBreaker.getState());
        server.verify();

        // Circuit is now open: a third call must fail fast without any HTTP request being made -
        // MockRestServiceServer has no more expectations queued, so an unexpected request would throw.
        DeepSeekCallOutcome third = client.chatCompletion("sys", "user", "test");
        assertFalse(third.success());
        assertTrue(third.retryableFailure());
        assertTrue(third.errorMessage().contains("circuit breaker"));
    }

    @Test
    void successfulCall_keepsCircuitClosed() {
        expectOne().andRespond(withSuccess(envelope("{\"ok\":true}"), MediaType.APPLICATION_JSON));

        DeepSeekCallOutcome outcome = client.chatCompletion("sys", "user", "test");

        assertTrue(outcome.success());
        assertEquals(DeepSeekCircuitBreaker.State.CLOSED, circuitBreaker.getState());
        server.verify();
    }

    @Test
    void retryAfterHeaderOnTooManyRequests_isHonoredOverExponentialBackoff() {
        properties.getAi().getDeepseek().setMaxRetries(1);
        properties.getAi().getDeepseek().setRetryBackoffMs(5000); // would be slow without Retry-After
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "0");
        expectOne().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));
        expectOne().andRespond(withSuccess(envelope("{\"ok\":true}"), MediaType.APPLICATION_JSON));

        long start = System.currentTimeMillis();
        DeepSeekCallOutcome outcome = client.chatCompletion("sys", "user", "test");
        long elapsedMs = System.currentTimeMillis() - start;

        assertTrue(outcome.success());
        assertTrue(elapsedMs < 2000, "Retry-After: 0 should short-circuit the 5s exponential backoff, took " + elapsedMs + "ms");
        server.verify();
    }

    @Test
    void concurrencyLimitReached_failsFastAsRetryable_withoutCallingServer() throws Exception {
        properties.getAi().getDeepseek().setMaxConcurrentRequests(1);
        properties.getAi().getDeepseek().setConcurrencyAcquireTimeoutMs(200);
        client = new DeepSeekHttpClient(
                properties, restTemplate, new ObjectMapper(), new SupplierImportMetrics(new SimpleMeterRegistry()),
                new DeepSeekCircuitBreaker());

        CountDownLatch releaseServerResponse = new CountDownLatch(1);
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST)).andRespond(req -> {
            try {
                releaseServerResponse.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return withSuccess(envelope("{\"ok\":true}"), MediaType.APPLICATION_JSON).createResponse(req);
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<DeepSeekCallOutcome> firstCall = executor.submit(() -> client.chatCompletion("sys", "user", "test"));
            // give the first call time to acquire the single concurrency slot before the second tries
            Thread.sleep(150);

            DeepSeekCallOutcome secondCall = client.chatCompletion("sys", "user", "test");
            assertFalse(secondCall.success());
            assertTrue(secondCall.retryableFailure());
            assertTrue(secondCall.errorMessage().contains("concurrency limit"));

            releaseServerResponse.countDown();
            DeepSeekCallOutcome firstOutcome = firstCall.get(5, TimeUnit.SECONDS);
            assertTrue(firstOutcome.success());
        } finally {
            executor.shutdownNow();
        }
        server.verify();
    }

    private org.springframework.test.web.client.ResponseActions expectOne() {
        return server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST));
    }

    private String envelope(String content) {
        String escaped = content.replace("\"", "\\\"");
        return "{\"choices\":[{\"message\":{\"content\":\"" + escaped + "\"}}]}";
    }
}
