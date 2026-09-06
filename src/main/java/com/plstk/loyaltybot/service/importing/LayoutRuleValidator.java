package com.plstk.loyaltybot.service.importing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates a raw AI (or manual) response against {@code supplier-import/layout-rule.schema.json}
 * and then against additional semantic rules the JSON Schema cannot express (required target
 * fields, unambiguous header aliases, header/data row ordering). Nothing here trusts the AI response
 * as a rule until every check below passes — see docs/ARCHITECTURE.md §8.
 */
@Component
@Slf4j
public class LayoutRuleValidator {

    private static final String SCHEMA_RESOURCE = "/supplier-import/layout-rule.schema.json";

    private final ObjectMapper objectMapper;
    private final JsonSchema schema;

    public LayoutRuleValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        try (InputStream schemaStream = getClass().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (schemaStream == null) {
                throw new IllegalStateException("Missing classpath resource " + SCHEMA_RESOURCE);
            }
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            this.schema = factory.getSchema(schemaStream);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load layout rule JSON Schema", e);
        }
    }

    public LayoutRuleValidationResult validate(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return LayoutRuleValidationResult.invalid("Empty AI response");
        }

        String json = extractJson(rawContent);
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            return LayoutRuleValidationResult.invalid("Malformed JSON: " + e.getMessage());
        }
        if (node == null || node.isNull() || !node.isObject()) {
            return LayoutRuleValidationResult.invalid("AI response is not a JSON object");
        }

        Set<ValidationMessage> schemaErrors = schema.validate(node);
        if (!schemaErrors.isEmpty()) {
            List<String> errors = schemaErrors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.toList());
            return LayoutRuleValidationResult.invalid(errors);
        }

        LayoutRuleDefinition rule;
        try {
            rule = objectMapper.treeToValue(node, LayoutRuleDefinition.class);
        } catch (Exception e) {
            return LayoutRuleValidationResult.invalid("Failed to map validated JSON to rule: " + e.getMessage());
        }

        List<String> semanticErrors = validateSemantics(rule);
        if (!semanticErrors.isEmpty()) {
            return LayoutRuleValidationResult.invalid(semanticErrors);
        }

        return LayoutRuleValidationResult.valid(rule);
    }

    private List<String> validateSemantics(LayoutRuleDefinition rule) {
        List<String> errors = new ArrayList<>();

        if (rule.getSheetSelectors() == null
                || rule.getSheetSelectors().isEmpty()
                || rule.getSheetSelectors().stream().anyMatch(s -> s == null || s.isBlank())) {
            errors.add("sheetSelectors must contain at least one non-blank sheet name");
        }

        if (rule.getHeaderRow() == null || rule.getFirstDataRow() == null) {
            errors.add("headerRow and firstDataRow are required");
        } else if (rule.getFirstDataRow() <= rule.getHeaderRow()) {
            errors.add("firstDataRow must be after headerRow");
        }

        Map<String, LayoutColumnMapping> columns = rule.getColumns();
        if (columns == null || columns.isEmpty()) {
            errors.add("columns mapping must not be empty");
            return errors;
        }

        for (String field : columns.keySet()) {
            if (!LayoutRuleDefinition.ALLOWED_FIELDS.contains(field)) {
                errors.add("Unknown/invented target field: " + field);
            }
        }

        if (!columns.containsKey(LayoutRuleDefinition.FIELD_RAW_NAME)) {
            errors.add("Required target field 'rawName' is not mapped");
        }
        if (!columns.containsKey(LayoutRuleDefinition.FIELD_SUPPLIER_PRICE)) {
            errors.add("Required target field 'supplierPrice' is not mapped");
        }
        boolean hasIdentifier = LayoutRuleDefinition.IDENTIFIER_FIELDS.stream().anyMatch(columns::containsKey);
        if (!hasIdentifier) {
            errors.add("At least one stable identifier field (externalSku or barcode) must be mapped");
        }

        // Uniqueness of mapping: the same header alias must not be claimed by two different target
        // fields, otherwise the mapping is ambiguous.
        Map<String, String> aliasOwner = new HashMap<>();
        for (Map.Entry<String, LayoutColumnMapping> entry : columns.entrySet()) {
            LayoutColumnMapping mapping = entry.getValue();
            if (mapping == null || mapping.getHeaderAliases() == null) {
                continue;
            }
            for (String alias : mapping.getHeaderAliases()) {
                if (alias == null || alias.isBlank()) {
                    continue;
                }
                String normalized = normalizeHeader(alias);
                String owner = aliasOwner.get(normalized);
                if (owner != null && !owner.equals(entry.getKey())) {
                    errors.add("Header alias '" + alias + "' is mapped to both '" + owner
                            + "' and '" + entry.getKey() + "'");
                } else {
                    aliasOwner.put(normalized, entry.getKey());
                }
            }
        }

        if (rule.getSkipRules() != null) {
            for (LayoutSkipRule skipRule : rule.getSkipRules()) {
                if (skipRule.getColumn() != null && !LayoutRuleDefinition.ALLOWED_FIELDS.contains(skipRule.getColumn())) {
                    errors.add("skipRules references unknown column: " + skipRule.getColumn());
                }
                try {
                    if (skipRule.getMatches() != null) {
                        java.util.regex.Pattern.compile(skipRule.getMatches());
                    }
                } catch (Exception e) {
                    errors.add("skipRules has invalid regex '" + skipRule.getMatches() + "': " + e.getMessage());
                }
            }
        }

        return errors;
    }

    private String normalizeHeader(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
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
