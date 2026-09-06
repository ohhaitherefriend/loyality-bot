package com.plstk.loyaltybot.service.importing;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Deterministic {@link AiSpreadsheetLayoutDetector} test double: queue up one response per
 * expected call (or a single response reused for every call) and record every request received, so
 * orchestration tests can assert AI was (or was not) called at all — the "known layout reuse" path
 * must never call this.
 */
public class FakeAiSpreadsheetLayoutDetector implements AiSpreadsheetLayoutDetector {

    private final Queue<LayoutDetectionResponse> responses = new LinkedList<>();
    private LayoutDetectionResponse defaultResponse;
    private final List<LayoutDetectionRequest> requests = new ArrayList<>();

    public void enqueue(LayoutDetectionResponse response) {
        responses.add(response);
    }

    /**
     * Must be called from each test's {@code @BeforeEach}: this bean is a singleton in the
     * {@code @DataJpaTest} Spring context, which is cached and reused across every test method in
     * the class (only the DB transaction is rolled back per method), so queued responses and the
     * request log would otherwise silently leak between tests.
     */
    public void reset() {
        responses.clear();
        defaultResponse = null;
        requests.clear();
    }

    public void alwaysReturn(LayoutDetectionResponse response) {
        this.defaultResponse = response;
    }

    public int callCount() {
        return requests.size();
    }

    public List<LayoutDetectionRequest> requests() {
        return requests;
    }

    @Override
    public LayoutDetectionResponse detect(LayoutDetectionRequest request) {
        requests.add(request);
        if (!responses.isEmpty()) {
            return responses.poll();
        }
        if (defaultResponse != null) {
            return defaultResponse;
        }
        throw new IllegalStateException("FakeAiSpreadsheetLayoutDetector has no queued/default response configured");
    }
}
