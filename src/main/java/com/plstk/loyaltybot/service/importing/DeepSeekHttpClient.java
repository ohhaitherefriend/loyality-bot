package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plstk.loyaltybot.config.SupplierImportProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Hardened, provider-neutral-in-shape (but DeepSeek-specific today) HTTP client shared by
 * {@link DeepSeekCatalogMatcher} and {@link DeepSeekSpreadsheetLayoutDetector} (Stage 5). Both
 * callers previously duplicated near-identical retry/backoff/parsing code against the app-wide
 * shared {@code RestTemplate}; this class centralizes:
 *
 * <ul>
 *   <li>a dedicated {@code RestTemplate} ({@code DeepSeekClientConfig}) with its own connect/read
 *       timeouts, so a slow DeepSeek account cannot borrow timeout budget from (or be bound by)
 *       unrelated outbound integrations;</li>
 *   <li>exponential backoff with jitter, honoring an HTTP {@code Retry-After} header on 429s when
 *       present instead of guessing;</li>
 *   <li>a JVM-wide {@link DeepSeekCircuitBreaker} so a fully-down account fails fast instead of
 *       every row/batch burning its own full retry budget;</li>
 *   <li>a concurrency limiter ({@link Semaphore}) bounding in-flight DeepSeek calls across both
 *       call sites, so a burst of concurrent batches/rows cannot hammer one account/rate limit.</li>
 * </ul>
 *
 * Retries apply only to retryable transport/status errors (timeout, 429, 5xx) — malformed/empty
 * JSON content is a content problem for each caller's own validator, not a transport problem, and
 * is returned here as a (non-retryable) success.
 */
@Component
@Slf4j
public class DeepSeekHttpClient {

    /** Never wait longer than this for one retry, even if a Retry-After header asks for more. */
    private static final long MAX_BACKOFF_MS = 60_000L;

    private final SupplierImportProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final SupplierImportMetrics metrics;
    private final DeepSeekCircuitBreaker circuitBreaker;
    private final Semaphore concurrencyLimiter;

    public DeepSeekHttpClient(
            SupplierImportProperties properties,
            @Qualifier("deepSeekRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            SupplierImportMetrics metrics,
            DeepSeekCircuitBreaker circuitBreaker) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.circuitBreaker = circuitBreaker;
        this.concurrencyLimiter = new Semaphore(Math.max(1, cfg().getMaxConcurrentRequests()));
    }

    public boolean isConfigured() {
        return StringUtils.hasText(cfg().getApiKey());
    }

    private SupplierImportProperties.DeepSeek cfg() {
        return properties.getAi().getDeepseek();
    }

    /**
     * @param metricKind {@code SupplierImportMetrics.aiCall(kind, ...)} discriminator, e.g.
     *                   {@code "catalog_match"}/{@code "layout_detect"}.
     */
    public DeepSeekCallOutcome chatCompletion(String systemPrompt, String userPrompt, String metricKind) {
        if (!isConfigured()) {
            return DeepSeekCallOutcome.failure("DeepSeek API key not configured", false);
        }

        SupplierImportProperties.DeepSeek cfg = cfg();
        boolean acquiredSlot;
        try {
            acquiredSlot = concurrencyLimiter.tryAcquire(cfg.getConcurrencyAcquireTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return DeepSeekCallOutcome.failure("Interrupted while waiting for a DeepSeek concurrency slot", true);
        }
        if (!acquiredSlot) {
            metrics.aiCall(metricKind, "concurrency_limited");
            return DeepSeekCallOutcome.failure(
                    "DeepSeek concurrency limit reached (" + cfg.getMaxConcurrentRequests() + " in flight)", true);
        }

        try {
            if (!circuitBreaker.tryAcquirePermission(cfg.getCircuitBreakerOpenDurationMs())) {
                metrics.aiCall(metricKind, "circuit_open");
                return DeepSeekCallOutcome.failure(
                        "DeepSeek circuit breaker is open, failing fast without calling the API", true);
            }
            return callWithRetry(cfg, systemPrompt, userPrompt, metricKind);
        } finally {
            concurrencyLimiter.release();
        }
    }

    private DeepSeekCallOutcome callWithRetry(
            SupplierImportProperties.DeepSeek cfg, String systemPrompt, String userPrompt, String metricKind) {
        int maxAttempts = Math.max(1, cfg.getMaxRetries() + 1);
        String lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long start = System.currentTimeMillis();
            try {
                DeepSeekCallResult result = callDeepSeekOnce(cfg, systemPrompt, userPrompt);
                long latencyMs = System.currentTimeMillis() - start;
                metrics.aiCall(metricKind, "success");
                circuitBreaker.recordSuccess();
                return DeepSeekCallOutcome.success(
                        result.content(), result.promptTokens(), result.completionTokens(), latencyMs);
            } catch (RetryableCallException e) {
                lastError = e.getMessage();
                log.warn("DeepSeek call ({}) attempt {}/{} failed retryably: {}",
                        metricKind, attempt, maxAttempts, e.getMessage());
                metrics.aiCall(metricKind, "retryable_failure");
                if (attempt < maxAttempts) {
                    sleepBackoff(cfg, attempt, e.retryAfterMs());
                }
            } catch (Exception e) {
                log.warn("DeepSeek call ({}) failed non-retryably: {}", metricKind, e.getMessage());
                metrics.aiCall(metricKind, "failure");
                circuitBreaker.recordFailure(cfg.getCircuitBreakerFailureThreshold());
                return DeepSeekCallOutcome.failure("DeepSeek call failed: " + safeMessage(e), false);
            }
        }
        metrics.aiCall(metricKind, "failure");
        circuitBreaker.recordFailure(cfg.getCircuitBreakerFailureThreshold());
        return DeepSeekCallOutcome.failure(
                "DeepSeek call failed after " + maxAttempts + " attempt(s): " + lastError, true);
    }

    private void sleepBackoff(SupplierImportProperties.DeepSeek cfg, int attempt, Long retryAfterMs) {
        long backoffMs;
        if (retryAfterMs != null) {
            backoffMs = Math.min(retryAfterMs, MAX_BACKOFF_MS);
        } else {
            long base = cfg.getRetryBackoffMs() * (1L << (attempt - 1));
            long jitter = ThreadLocalRandom.current().nextLong(0, base / 2 + 1);
            backoffMs = Math.min(base + jitter, MAX_BACKOFF_MS);
        }
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private DeepSeekCallResult callDeepSeekOnce(
            SupplierImportProperties.DeepSeek cfg, String systemPrompt, String userPrompt) {
        String url = cfg.getBaseUrl();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        url = url + "/v1/chat/completions";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModel());
        body.put("temperature", 0.0);
        body.put("response_format", Map.of("type", "json_object"));
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(cfg.getApiKey());

        HttpEntity<Map<String, Object>> httpRequest = new HttpEntity<>(body, headers);
        ResponseEntity<String> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.POST, httpRequest, String.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new RetryableCallException("HTTP 429 rate limited", e, parseRetryAfterMs(e.getResponseHeaders()));
            }
            throw new IllegalStateException("DeepSeek API client error: " + e.getStatusCode(), e);
        } catch (HttpServerErrorException e) {
            throw new RetryableCallException("HTTP " + e.getStatusCode().value() + " server error", e);
        } catch (ResourceAccessException e) {
            throw new RetryableCallException("Transport/timeout error: " + safeMessage(e), e);
        } catch (RestClientException e) {
            throw new IllegalStateException("DeepSeek API call failed: " + safeMessage(e), e);
        }

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("DeepSeek API error: " + response.getStatusCode());
        }

        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            JsonNode usage = root.path("usage");
            Integer promptTokens = usage.has("prompt_tokens") ? usage.path("prompt_tokens").asInt() : null;
            Integer completionTokens = usage.has("completion_tokens") ? usage.path("completion_tokens").asInt() : null;
            return new DeepSeekCallResult(content, promptTokens, completionTokens);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse DeepSeek envelope response", e);
        }
    }

    /** Accepts both delay-seconds and HTTP-date forms (RFC 9110 §10.2.3); {@code null} if absent/unparseable. */
    private Long parseRetryAfterMs(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        value = value.trim();
        try {
            long seconds = Long.parseLong(value);
            return Math.max(0, seconds * 1000L);
        } catch (NumberFormatException ignored) {
            // fall through to HTTP-date parsing below
        }
        try {
            ZonedDateTime target = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
            long deltaMs = Duration.between(ZonedDateTime.now(target.getZone()), target).toMillis();
            return Math.max(0, deltaMs);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String safeMessage(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String message = t.getMessage();
        return message != null ? message : t.getClass().getSimpleName();
    }

    private record DeepSeekCallResult(String content, Integer promptTokens, Integer completionTokens) {
    }

    private static final class RetryableCallException extends RuntimeException {
        private final Long retryAfterMs;

        RetryableCallException(String message, Throwable cause) {
            this(message, cause, null);
        }

        RetryableCallException(String message, Throwable cause, Long retryAfterMs) {
            super(message, cause);
            this.retryAfterMs = retryAfterMs;
        }

        Long retryAfterMs() {
            return retryAfterMs;
        }
    }
}
