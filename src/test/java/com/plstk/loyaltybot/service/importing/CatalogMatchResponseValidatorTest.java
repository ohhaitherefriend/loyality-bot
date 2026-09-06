package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers every explicit case from prompt 05: valid MATCH/NO_MATCH, an invented candidate id,
 * malformed JSON, a row_id mismatch and schema violations (missing field, out-of-range confidence,
 * decision/candidate_id inconsistency) - none of these must ever be trusted as a real decision.
 */
class CatalogMatchResponseValidatorTest {

    private final CatalogMatchResponseValidator validator = new CatalogMatchResponseValidator(new ObjectMapper());

    @Test
    void validMatch_isParsedAndCandidateIdAccepted() {
        CatalogMatchResult result = validator.validate(
                json("row-1", "MATCH", "42", 0.91, "[\"brand\",\"volume\"]", "[]", "Same product"),
                "row-1", Set.of(42L, 7L));

        assertTrue(result.valid());
        assertTrue(result.matched());
        assertEquals(42L, result.candidateProductId());
        assertEquals(0.91, result.confidence().doubleValue(), 0.0001);
        assertEquals("Same product", result.reason());
    }

    @Test
    void validNoMatch_isParsedWithNullCandidate() {
        CatalogMatchResult result = validator.validate(
                json("row-1", "NO_MATCH", null, 0.2, "[]", "[]", "Nothing close enough"),
                "row-1", Set.of(42L, 7L));

        assertTrue(result.valid());
        assertFalse(result.matched());
        assertEquals(null, result.candidateProductId());
    }

    @Test
    void inventedCandidateId_isRejected() {
        CatalogMatchResult result = validator.validate(
                json("row-1", "MATCH", "999999", 0.95, "[]", "[]", "Looks right"),
                "row-1", Set.of(42L, 7L));

        assertFalse(result.valid());
        assertTrue(result.validationErrors().stream().anyMatch(e -> e.contains("999999")));
    }

    @Test
    void nonNumericCandidateId_isRejected() {
        CatalogMatchResult result = validator.validate(
                json("row-1", "MATCH", "not-a-number", 0.95, "[]", "[]", "Looks right"),
                "row-1", Set.of(42L));

        assertFalse(result.valid());
    }

    @Test
    void malformedJson_isRejected() {
        CatalogMatchResult result = validator.validate("{not json at all", "row-1", Set.of(42L));

        assertFalse(result.valid());
    }

    @Test
    void emptyContent_isRejected() {
        CatalogMatchResult result = validator.validate("", "row-1", Set.of(42L));

        assertFalse(result.valid());
    }

    @Test
    void rowIdMismatch_isRejected() {
        CatalogMatchResult result = validator.validate(
                json("row-999", "NO_MATCH", null, 0.2, "[]", "[]", "Nothing close enough"),
                "row-1", Set.of(42L));

        assertFalse(result.valid());
    }

    @Test
    void missingRequiredField_isRejected() {
        String contentMissingReason = """
                {"row_id":"row-1","decision":"NO_MATCH","candidate_id":null,"confidence":0.2,
                 "matched_attributes":[],"conflicts":[]}
                """;
        CatalogMatchResult result = validator.validate(contentMissingReason, "row-1", Set.of(42L));

        assertFalse(result.valid());
    }

    @Test
    void confidenceOutOfRange_isRejected() {
        CatalogMatchResult result = validator.validate(
                json("row-1", "MATCH", "42", 1.5, "[]", "[]", "Overconfident"),
                "row-1", Set.of(42L));

        assertFalse(result.valid());
    }

    @Test
    void matchDecisionWithNullCandidateId_isRejectedBySchema() {
        String content = """
                {"row_id":"row-1","decision":"MATCH","candidate_id":null,"confidence":0.9,
                 "matched_attributes":[],"conflicts":[],"reason":"inconsistent"}
                """;
        CatalogMatchResult result = validator.validate(content, "row-1", Set.of(42L));

        assertFalse(result.valid());
    }

    @Test
    void extractsJsonFromSurroundingText() {
        String wrapped = "Here is my answer: " + json("row-1", "NO_MATCH", null, 0.1, "[]", "[]", "none") + " done.";
        CatalogMatchResult result = validator.validate(wrapped, "row-1", Set.of(42L));

        assertTrue(result.valid());
    }

    private String json(
            String rowId, String decision, String candidateId, double confidence,
            String matchedAttributes, String conflicts, String reason) {
        String candidateIdJson = candidateId == null ? "null" : "\"" + candidateId + "\"";
        return String.format(
                "{\"row_id\":\"%s\",\"decision\":\"%s\",\"candidate_id\":%s,\"confidence\":%s,"
                        + "\"matched_attributes\":%s,\"conflicts\":%s,\"reason\":\"%s\"}",
                rowId, decision, candidateIdJson, confidence, matchedAttributes, conflicts, reason);
    }
}
