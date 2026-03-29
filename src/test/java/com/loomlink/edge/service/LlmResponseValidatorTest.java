package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.FailureModeCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the LLM Response Validator — JSON schema enforcement.
 *
 * <p>The validator is the first line of defense against LLM hallucinations.
 * These tests cover every edge case: clean JSON, markdown-wrapped JSON,
 * missing fields, invalid codes, out-of-range confidence, and garbage input.</p>
 */
@DisplayName("LLM Response Validator - JSON Schema Enforcement Tests")
class LlmResponseValidatorTest {

    private LlmResponseValidator validator;

    @BeforeEach
    void setUp() {
        validator = new LlmResponseValidator();
    }

    // ── Valid Responses ─────────────────────────────────────────────

    @Test
    @DisplayName("VALID: Clean JSON with all required fields")
    void cleanJsonPasses() {
        String json = """
            {
                "failureModeCode": "BRD",
                "causeCode": "B01",
                "confidence": 0.92,
                "reasoning": "Grinding noise and elevated temperature indicate bearing degradation."
            }
            """;

        var result = validator.validate(json);

        assertTrue(result.valid());
        assertEquals(FailureModeCode.BRD, result.failureModeCode());
        assertEquals("B01", result.causeCode());
        assertEquals(0.92, result.confidence());
        assertNotNull(result.reasoning());
    }

    @Test
    @DisplayName("VALID: JSON wrapped in markdown code block")
    void markdownWrappedJsonPasses() {
        String response = """
            Here is the classification:
            ```json
            {
                "failureModeCode": "VIB",
                "causeCode": "V01",
                "confidence": 0.88,
                "reasoning": "Vibration levels indicate mechanical imbalance."
            }
            ```
            """;

        var result = validator.validate(response);

        assertTrue(result.valid());
        assertEquals(FailureModeCode.VIB, result.failureModeCode());
    }

    @Test
    @DisplayName("VALID: JSON with surrounding text (LLM prefix)")
    void jsonWithSurroundingTextPasses() {
        String response = """
            Based on my analysis of the maintenance notification:
            {"failureModeCode": "ELP", "causeCode": "S01", "confidence": 0.90, "reasoning": "External seal leak detected with visible fluid."}
            This classification is based on the described symptoms.
            """;

        var result = validator.validate(response);

        assertTrue(result.valid());
        assertEquals(FailureModeCode.ELP, result.failureModeCode());
    }

    @Test
    @DisplayName("VALID: Confidence at exact boundary values (0.0 and 1.0)")
    void boundaryConfidenceValues() {
        String jsonLow = """
            {"failureModeCode": "UNK", "causeCode": "U01", "confidence": 0.0, "reasoning": "Cannot determine failure mode."}
            """;
        String jsonHigh = """
            {"failureModeCode": "BRD", "causeCode": "B01", "confidence": 1.0, "reasoning": "Definite bearing failure."}
            """;

        assertTrue(validator.validate(jsonLow).valid());
        assertTrue(validator.validate(jsonHigh).valid());
    }

    @Test
    @DisplayName("VALID: Lowercase failure mode code is accepted")
    void lowercaseCodeAccepted() {
        String json = """
            {"failureModeCode": "brd", "causeCode": "B01", "confidence": 0.91, "reasoning": "Bearing issue."}
            """;

        var result = validator.validate(json);
        assertTrue(result.valid());
        assertEquals(FailureModeCode.BRD, result.failureModeCode());
    }

    // ── Invalid: Missing Fields ─────────────────────────────────────

    @Test
    @DisplayName("INVALID: Missing failureModeCode")
    void missingFailureCodeRejected() {
        String json = """
            {"causeCode": "B01", "confidence": 0.92, "reasoning": "Bearing degradation."}
            """;

        var result = validator.validate(json);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("failureModeCode")));
    }

    @Test
    @DisplayName("INVALID: Missing confidence")
    void missingConfidenceRejected() {
        String json = """
            {"failureModeCode": "BRD", "causeCode": "B01", "reasoning": "Bearing issue."}
            """;

        var result = validator.validate(json);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("confidence")));
    }

    @Test
    @DisplayName("INVALID: Missing reasoning")
    void missingReasoningRejected() {
        String json = """
            {"failureModeCode": "BRD", "causeCode": "B01", "confidence": 0.92}
            """;

        var result = validator.validate(json);
        assertFalse(result.valid());
    }

    // ── Invalid: Bad Values ─────────────────────────────────────────

    @Test
    @DisplayName("INVALID: Unknown failure mode code")
    void unknownFailureCodeRejected() {
        String json = """
            {"failureModeCode": "XYZABC", "causeCode": "X01", "confidence": 0.92, "reasoning": "Invalid code."}
            """;

        var result = validator.validate(json);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("Unknown failure mode")));
    }

    @Test
    @DisplayName("INVALID: Confidence above 1.0")
    void confidenceAboveOneRejected() {
        String json = """
            {"failureModeCode": "BRD", "causeCode": "B01", "confidence": 1.5, "reasoning": "Over-confident."}
            """;

        var result = validator.validate(json);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("out of range")));
    }

    @Test
    @DisplayName("INVALID: Negative confidence")
    void negativeConfidenceRejected() {
        String json = """
            {"failureModeCode": "BRD", "causeCode": "B01", "confidence": -0.5, "reasoning": "Negative."}
            """;

        var result = validator.validate(json);
        assertFalse(result.valid());
    }

    @Test
    @DisplayName("INVALID: Confidence is a string, not a number")
    void confidenceAsStringRejected() {
        String json = """
            {"failureModeCode": "BRD", "causeCode": "B01", "confidence": "high", "reasoning": "Textual confidence."}
            """;

        var result = validator.validate(json);
        assertFalse(result.valid());
    }

    // ── Invalid: Garbage Input ──────────────────────────────────────

    @Test
    @DisplayName("INVALID: Null input")
    void nullInputRejected() {
        var result = validator.validate(null);
        assertFalse(result.valid());
    }

    @Test
    @DisplayName("INVALID: Empty string")
    void emptyStringRejected() {
        var result = validator.validate("");
        assertFalse(result.valid());
    }

    @Test
    @DisplayName("INVALID: Plain text (no JSON)")
    void plainTextRejected() {
        var result = validator.validate("I think the pump has a bearing issue with high confidence.");
        assertFalse(result.valid());
    }

    @Test
    @DisplayName("INVALID: Truncated JSON")
    void truncatedJsonRejected() {
        String json = """
            {"failureModeCode": "BRD", "causeCode": "B01", "conf
            """;

        var result = validator.validate(json);
        assertFalse(result.valid());
    }
}
