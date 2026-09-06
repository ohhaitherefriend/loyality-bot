package com.plstk.loyaltybot.service.importing;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Deterministic {@link AiCatalogMatcher} test double, same shape as
 * {@code FakeAiSpreadsheetLayoutDetector}: queue up one response per expected call (or a single
 * response reused for every call) and record every request received, so orchestration tests can
 * assert exactly what was sent to "AI" and how many times.
 */
public class FakeAiCatalogMatcher implements AiCatalogMatcher {

    private final Queue<AiMatchResponse> responses = new LinkedList<>();
    private AiMatchResponse defaultResponse;
    private boolean alwaysNoMatch;
    private final List<AiMatchRequest> requests = new ArrayList<>();

    public void enqueue(AiMatchResponse response) {
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
        alwaysNoMatch = false;
        requests.clear();
    }

    public void alwaysReturn(AiMatchResponse response) {
        this.defaultResponse = response;
    }

    /**
     * Fallback for tests that don't care about exact AI wording for every incidental weak fuzzy
     * candidate a fixture happens to produce (e.g. an unrelated product sharing a few trigrams with
     * something already in the catalog): every unqueued call gets a synthesized {@code NO_MATCH}
     * response with {@code row_id} echoed from the actual request, so it always passes
     * {@link CatalogMatchResponseValidator}'s row_id check regardless of which row triggers it.
     */
    public void alwaysNoMatch() {
        this.alwaysNoMatch = true;
    }

    public int callCount() {
        return requests.size();
    }

    public List<AiMatchRequest> requests() {
        return requests;
    }

    @Override
    public AiMatchResponse match(AiMatchRequest request) {
        requests.add(request);
        if (!responses.isEmpty()) {
            return responses.poll();
        }
        if (defaultResponse != null) {
            return defaultResponse;
        }
        if (alwaysNoMatch) {
            String json = """
                    {"row_id": "%s", "decision": "NO_MATCH", "candidate_id": null, "confidence": 0.1, \
                    "matched_attributes": [], "conflicts": [], "reason": "fake: no real fuzzy match"}
                    """.formatted(request.rowId());
            return AiMatchResponse.success(json, "fake", "fake-model", "catalog-matcher-v2", 0, 0, 1L);
        }
        throw new IllegalStateException("FakeAiCatalogMatcher has no queued/default response configured");
    }
}
