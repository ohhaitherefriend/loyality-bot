package com.plstk.loyaltybot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Stage 10 operability (docs/DECISIONS.md ADR-016): tags every request with a correlation id,
 * put in MDC as {@code correlationId} (see {@code logging.pattern.console} in application.yml)
 * and echoed back as a response header, so a single request's log lines can be grepped together
 * across the whole call - including into the async {@code @Scheduled} pipeline stages that pick
 * up work a request enqueued, as long as they propagate the same id themselves (see
 * {@code MailboxPollingJob}/{@code ImportBatch*Job} logging the batch/job id they're processing,
 * which plays the same "how do I find every log line about this one thing" role for background
 * work that has no HTTP request to carry a header on).
 *
 * <p>Reuses the caller's {@code X-Correlation-Id} if present (lets an API gateway/mobile client/
 * admin-panel request tracer supply its own id that ties client-side and server-side logs
 * together), otherwise generates a fresh UUID. Never trusts the header for anything but log
 * correlation - it is not parsed, not used in any authorization/business decision, and bounded to
 * a sane length so a malicious/broken caller can't blow up log line size.</p>
 *
 * <p>Ordered first ({@code Ordered.HIGHEST_PRECEDENCE}) so the id is present in MDC for every log
 * line the request produces, including ones from {@code JwtAuthenticationFilter} and Spring
 * Security itself.</p>
 */
@Component
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final int MAX_INCOMING_LENGTH = 100;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request);
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String incoming = request.getHeader(HEADER_NAME);
        if (StringUtils.hasText(incoming) && incoming.length() <= MAX_INCOMING_LENGTH && isSafe(incoming)) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }

    /** Restricts an externally-supplied id to characters safe to embed directly in log lines/headers. */
    private boolean isSafe(String value) {
        return value.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.');
    }
}
