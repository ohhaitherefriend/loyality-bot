package com.plstk.loyaltybot.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 10 (docs/DECISIONS.md ADR-016): {@link CorrelationIdFilter} must reuse a caller-supplied
 * id when it's safe to log, generate a fresh one otherwise, echo it back on the response, and
 * never leak the MDC value into the next request handled by the same worker thread.
 */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void reusesCallerSuppliedCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "client-trace-abc123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] mdcDuringChain = new String[1];
        FilterChain chain = (req, res) -> mdcDuringChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY);

        filter.doFilter(request, response, chain);

        assertEquals("client-trace-abc123", response.getHeader(CorrelationIdFilter.HEADER_NAME));
        assertEquals("client-trace-abc123", mdcDuringChain[0]);
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY), "MDC must be cleared after the request completes");
    }

    @Test
    void generatesFreshIdWhenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, response, chain);

        String generated = response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertTrue(generated != null && generated.length() >= 32, "expected a UUID-like generated id");
    }

    @Test
    void rejectsUnsafeOrOverlongHeaderAndGeneratesFreshIdInstead() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "bad\nvalue\r\ninjected: header");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };

        filter.doFilter(request, response, chain);

        String result = response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertTrue(!result.contains("\n") && !result.contains("\r"));
        assertTrue(!result.equals("bad\nvalue\r\ninjected: header"));
    }

    @Test
    void clearsMdcEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            throw new RuntimeException("downstream failure");
        };

        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> filter.doFilter(request, response, chain)).getMessage().contains("downstream failure"));
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
