package com.loomlink.edge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loomlink.edge.domain.enums.FailureModeCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Enterprise Feature #1: Strict JSON Schema Validator with 1-retry fallback.
 *
 * <p>Ensures the LLM output is structurally valid before the Reflector Gate
 * evaluates it. This catches hallucinated formatting, missing fields,
 * invalid types, and out-of-range values.</p>
 *
 * <p>Validation rules:</p>
 * <ul>
 *   <li>Must be valid JSON (parseable)</li>
 *   <li>Must contain exactly: failureModeCode, causeCode, confidence, reasoning</li>
 *   <li>failureModeCode must be a recognized ISO 14224 enum value</li>
 *   <li>confidence must be a number in [0.0, 1.0]</li>
 *   <li>reasoning must be a non-empty string</li>
 * </ul>
 *
 * <p>If validation fails on first attempt, the caller retries once. If it fails
 * again, the response is marked as schema-invalid and routed to the Exception Inbox.</p>
 */
@Service
public class LlmResponseValidator {

    private static final Logger log = LoggerFactory.getLogger(LlmResponseValidator.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Validate and parse the raw LLM response string.
     *
     * @param rawResponse the raw string from Ollama
     * @return a validated result, or a list of validation errors
     */
    public ValidationResult validate(String rawResponse) {
        List<String> errors = new ArrayList<>();

        if (rawResponse == null || rawResponse.isBlank()) {
            return ValidationResult.invalid(List.of("LLM returned empty response"));
        }

        // Step 1: Extract JSON from response (LLM may wrap it in markdown or text)
        String jsonStr = extractJson(rawResponse);
        if (jsonStr == null) {
            return ValidationResult.invalid(List.of(
                "No valid JSON object found in LLM response. Raw: " +
                rawResponse.substring(0, Math.min(rawResponse.length(), 200))));
        }

        // Step 2: Parse as JSON
        JsonNode root;
        try {
            root = objectMapper.readTree(jsonStr);
        } catch (Exception e) {
            return ValidationResult.invalid(List.of("JSON parse error: " + e.getMessage()));
        }

        if (!root.isObject()) {
            errors.add("Response is not a JSON object");
            return ValidationResult.invalid(errors);
        }

        // Step 3: Validate required fields
        String failureCode = validateStringField(root, "failureModeCode", errors);
        String causeCode = validateStringField(root, "causeCode", errors);
        Double confidence = validateNumberField(root, "confidence", 0.0, 1.0, errors);
        String reasoning = validateStringField(root, "reasoning", errors);

        // Step 4: Validate failure code is a known enum
        FailureModeCode validatedCode = null;
        if (failureCode != null) {
            try {
                validatedCode = FailureModeCode.valueOf(failureCode.toUpperCase().trim());
            } catch (IllegalArgumentException e) {
                errors.add("Unknown failure mode code: '" + failureCode +
                          "'. Must be one of: " + java.util.Arrays.toString(FailureModeCode.values()));
            }
        }

        if (!errors.isEmpty()) {
            return ValidationResult.invalid(errors);
        }

        return ValidationResult.valid(validatedCode, causeCode, confidence, reasoning, jsonStr);
    }

    /**
     * Extract a JSON object from a potentially noisy LLM response.
     * Handles cases where the model wraps JSON in markdown code blocks,
     * prefixes it with text, or adds trailing commentary.
     */
    private String extractJson(String raw) {
        String trimmed = raw.trim();

        // Try direct parse first
        if (trimmed.startsWith("{")) {
            int braceCount = 0;
            for (int i = 0; i < trimmed.length(); i++) {
                if (trimmed.charAt(i) == '{') braceCount++;
                else if (trimmed.charAt(i) == '}') braceCount--;
                if (braceCount == 0) {
                    return trimmed.substring(0, i + 1);
                }
            }
        }

        // Try extracting from markdown code block: ```json ... ```
        int jsonBlockStart = trimmed.indexOf("```json");
        if (jsonBlockStart != -1) {
            int contentStart = trimmed.indexOf('\n', jsonBlockStart) + 1;
            int contentEnd = trimmed.indexOf("```", contentStart);
            if (contentEnd != -1) {
                return trimmed.substring(contentStart, contentEnd).trim();
            }
        }

        // Try extracting from generic code block: ``` ... ```
        int blockStart = trimmed.indexOf("```");
        if (blockStart != -1) {
            int contentStart = trimmed.indexOf('\n', blockStart) + 1;
            int contentEnd = trimmed.indexOf("```", contentStart);
            if (contentEnd != -1) {
                String content = trimmed.substring(contentStart, contentEnd).trim();
                if (content.startsWith("{")) return content;
            }
        }

        // Try finding first { and matching last }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }

        return null;
    }

    private String validateStringField(JsonNode root, String field, List<String> errors) {
        if (!root.has(field) || root.get(field).isNull()) {
            errors.add("Missing required field: " + field);
            return null;
        }
        String value = root.get(field).asText().trim();
        if (value.isEmpty()) {
            errors.add("Field '" + field + "' is empty");
            return null;
        }
        return value;
    }

    private Double validateNumberField(JsonNode root, String field,
                                        double min, double max, List<String> errors) {
        if (!root.has(field) || root.get(field).isNull()) {
            errors.add("Missing required field: " + field);
            return null;
        }
        if (!root.get(field).isNumber()) {
            errors.add("Field '" + field + "' must be a number, got: " + root.get(field).asText());
            return null;
        }
        double value = root.get(field).asDouble();
        if (value < min || value > max) {
            errors.add("Field '" + field + "' value " + value + " is out of range [" + min + ", " + max + "]");
            return null;
        }
        return value;
    }

    /**
     * Validated LLM response — either valid with parsed fields, or invalid with error list.
     */
    public record ValidationResult(
            boolean valid,
            FailureModeCode failureModeCode,
            String causeCode,
            Double confidence,
            String reasoning,
            String cleanJson,
            List<String> errors
    ) {
        public static ValidationResult valid(FailureModeCode code, String causeCode,
                                              double confidence, String reasoning, String json) {
            return new ValidationResult(true, code, causeCode, confidence, reasoning, json, List.of());
        }

        public static ValidationResult invalid(List<String> errors) {
            return new ValidationResult(false, null, null, null, null, null, errors);
        }
    }
}
