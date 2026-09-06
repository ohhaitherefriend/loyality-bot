package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates a raw {@link AiCatalogMatcher} response against
 * {@code supplier-import/catalog-match-response.schema.json} and then against additional checks the
 * JSON Schema cannot express: the response must actually refer to the row it was asked about, and a
 * {@code MATCH} decision's {@code candidate_id} must be one of the real ids the caller offered - an
 * invented id is treated identically to malformed JSON, never trusted (D-009,
 * docs/ARCHITECTURE.md §10). Nothing here decides auto-approval; that is
 * {@code ImportBatchMatchingService}'s job, using this result plus the backend's own critical
 * attribute conflict check on the chosen candidate.
 */
@Component
public class CatalogMatchResponseValidator {

    private static final String SCHEMA_RESOURCE = "/supplier-import/catalog-match-response.schema.json";

    private final ObjectMapper objectMapper;
    private final JsonSchema schema;

    public CatalogMatchResponseValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        try (InputStream schemaStream = getClass().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (schemaStream == null) {
                throw new IllegalStateException("Missing classpath resource " + SCHEMA_RESOURCE);
            }
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            this.schema = factory.getSchema(schemaStream);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load catalog match response JSON Schema", e);
        }
    }

    public CatalogMatchResult validate(String rawContent, String expectedRowId, Set<Long> allowedCandidateIds) {
        if (rawContent == null || rawContent.isBlank()) {
            return CatalogMatchResult.invalid(List.of("Empty AI response"));
        }

        String json = extractJson(rawContent);
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            return CatalogMatchResult.invalid(List.of("Malformed JSON: " + e.getMessage()));
        }
        if (node == null || node.isNull() || !node.isObject()) {
            return CatalogMatchResult.invalid(List.of("AI response is not a JSON object"));
        }

        Set<ValidationMessage> schemaErrors = schema.validate(node);
        if (!schemaErrors.isEmpty()) {
            List<String> errors = schemaErrors.stream().map(ValidationMessage::getMessage).collect(Collectors.toList());
            return CatalogMatchResult.invalid(errors);
        }

        List<String> errors = new ArrayList<>();

        String rowId = node.path("row_id").asText();
        if (!rowId.equals(expectedRowId)) {
            errors.add("row_id mismatch: expected " + expectedRowId + " but got " + rowId);
        }

        String decision = node.path("decision").asText();
        BigDecimal confidence = node.path("confidence").decimalValue();
        List<String> matchedAttributes = readStringArray(node.path("matched_attributes"));
        List<String> conflicts = readStringArray(node.path("conflicts"));
        String reason = node.path("reason").asText(null);

        Long candidateId = null;
        if ("MATCH".equals(decision)) {
            String candidateIdText = node.path("candidate_id").asText();
            try {
                candidateId = Long.parseLong(candidateIdText.trim());
            } catch (NumberFormatException e) {
                errors.add("candidate_id is not a valid numeric product id: " + candidateIdText);
            }
            if (candidateId != null && (allowedCandidateIds == null || !allowedCandidateIds.contains(candidateId))) {
                errors.add("AI selected candidate_id " + candidateId + " which was not in the offered candidate list");
            }
        }

        if (!errors.isEmpty()) {
            return CatalogMatchResult.invalid(errors);
        }

        return "MATCH".equals(decision)
                ? CatalogMatchResult.match(candidateId, confidence, matchedAttributes, conflicts, reason)
                : CatalogMatchResult.noMatch(confidence, matchedAttributes, conflicts, reason);
    }

    private List<String> readStringArray(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            arrayNode.forEach(n -> values.add(n.asText()));
        }
        return values;
    }

    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
    }
}
