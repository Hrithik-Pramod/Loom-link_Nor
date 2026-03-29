package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.EquipmentClass;
import com.loomlink.edge.domain.enums.FailureModeCode;
import com.loomlink.edge.domain.model.MaintenanceNotification;
import com.loomlink.edge.domain.model.ReflectorGateResult;
import com.loomlink.edge.domain.model.SemanticClassification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Reflector Gate — the deterministic governance layer.
 *
 * <p>The Reflector Gate is the most critical safety boundary in Loom Link.
 * It must enforce binary pass/fail with zero ambiguity. These tests verify
 * every rejection rule and edge case.</p>
 */
@DisplayName("Reflector Gate - Deterministic Governance Tests")
class ReflectorGateServiceTest {

    private ReflectorGateService gate;
    private static final double THRESHOLD = 0.85;

    @BeforeEach
    void setUp() {
        gate = new ReflectorGateService(THRESHOLD);
    }

    // ── Helper Methods ──────────────────────────────────────────────

    private MaintenanceNotification createNotification(String sapNo, String text, String tag) {
        return MaintenanceNotification.fromODataEvent(
                sapNo, text, tag, EquipmentClass.PUMP, "1000");
    }

    private SemanticClassification createClassification(
            MaintenanceNotification notif, FailureModeCode code, double confidence) {
        return SemanticClassification.create(
                notif, code, "B01", confidence,
                "Test reasoning", "{}", "mistral:7b", 500);
    }

    // ── PASS Tests ──────────────────────────────────────────────────

    @Test
    @DisplayName("PASS: High confidence bearing failure passes the gate")
    void highConfidenceBearingFailurePasses() {
        var notif = createNotification("10001", "Grinding noise from pump DE bearing", "P-1001A");
        var classification = createClassification(notif, FailureModeCode.BRD, 0.92);

        ReflectorGateResult result = gate.evaluate(classification, notif);

        assertTrue(result.isPassed(), "High confidence classification should pass");
        assertEquals(THRESHOLD, result.getThresholdApplied());
        assertTrue(result.getGateReasoning().contains("PASSED"));
    }

    @Test
    @DisplayName("PASS: Confidence exactly at threshold passes")
    void confidenceExactlyAtThresholdPasses() {
        var notif = createNotification("10002", "Seal leak detected on pump", "P-2001A");
        var classification = createClassification(notif, FailureModeCode.ELP, 0.85);

        ReflectorGateResult result = gate.evaluate(classification, notif);

        assertTrue(result.isPassed(), "Confidence exactly at threshold should pass");
    }

    @Test
    @DisplayName("PASS: Perfect confidence (1.0) passes")
    void perfectConfidencePasses() {
        var notif = createNotification("10003", "Complete pump seizure", "P-1001A");
        var classification = createClassification(notif, FailureModeCode.BRD, 1.0);

        ReflectorGateResult result = gate.evaluate(classification, notif);

        assertTrue(result.isPassed());
    }

    // ── REJECT: Confidence Below Threshold ──────────────────────────

    @Test
    @DisplayName("REJECT: Confidence below threshold is rejected")
    void lowConfidenceRejected() {
        var notif = createNotification("10004", "Something doesn't sound right", "P-1002A");
        var classification = createClassification(notif, FailureModeCode.VIB, 0.60);

        ReflectorGateResult result = gate.evaluate(classification, notif);

        assertFalse(result.isPassed(), "Low confidence should be rejected");
        assertTrue(result.getGateReasoning().contains("REJECTED"));
        assertTrue(result.getGateReasoning().contains("below threshold"));
    }

    @Test
    @DisplayName("REJECT: Confidence just below threshold is rejected (boundary test)")
    void confidenceJustBelowThresholdRejected() {
        var notif = createNotification("10005", "Unusual noise from pump area", "P-1001A");
        var classification = createClassification(notif, FailureModeCode.VIB, 0.849);

        ReflectorGateResult result = gate.evaluate(classification, notif);

        assertFalse(result.isPassed(), "0.849 should be rejected with 0.85 threshold");
    }

    @Test
    @DisplayName("REJECT: Zero confidence is rejected")
    void zeroConfidenceRejected() {
        var notif = createNotification("10006", "Unknown issue", "P-1001A");
        var classification = createClassification(notif, FailureModeCode.VIB, 0.0);

        ReflectorGateResult result = gate.evaluate(classification, notif);

        assertFalse(result.isPassed());
    }

    // ── REJECT: Unknown Failure Mode ────────────────────────────────

    @Test
    @DisplayName("REJECT: UNK failure mode is always rejected regardless of confidence")
    void unknownFailureModeRejected() {
        var notif = createNotification("10007", "Pump making unusual noise", "P-1001A");
        var classification = createClassification(notif, FailureModeCode.UNK, 0.95);

        ReflectorGateResult result = gate.evaluate(classification, notif);

        assertFalse(result.isPassed(), "UNK failure mode must be rejected even with high confidence");
        assertTrue(result.getGateReasoning().contains("UNK"));
    }

    // ── Gate Configuration ──────────────────────────────────────────

    @Test
    @DisplayName("Gate threshold is correctly configured")
    void thresholdCorrectlyConfigured() {
        assertEquals(THRESHOLD, gate.getConfidenceThreshold());
    }

    @Test
    @DisplayName("Gate with different threshold works correctly")
    void differentThresholdWorks() {
        var strictGate = new ReflectorGateService(0.95);
        var notif = createNotification("10008", "Bearing failure", "P-1001A");
        var classification = createClassification(notif, FailureModeCode.BRD, 0.90);

        // Should pass at 0.85 but fail at 0.95
        assertTrue(gate.evaluate(classification,
                createNotification("10008", "Bearing failure", "P-1001A")).isPassed());
        assertFalse(strictGate.evaluate(classification, notif).isPassed());
    }

    // ── State Transitions ───────────────────────────────────────────

    @Test
    @DisplayName("Gate pass marks notification as VERIFIED")
    void gatePassMarksVerified() {
        var notif = createNotification("10009", "DE bearing failed", "P-1001A");
        var classification = createClassification(notif, FailureModeCode.BRD, 0.92);

        gate.evaluate(classification, notif);

        assertEquals(com.loomlink.edge.domain.enums.NotificationStatus.VERIFIED, notif.getStatus());
    }

    @Test
    @DisplayName("Gate reject marks notification as REJECTED")
    void gateRejectMarksRejected() {
        var notif = createNotification("10010", "Something off", "P-1001A");
        var classification = createClassification(notif, FailureModeCode.VIB, 0.50);

        gate.evaluate(classification, notif);

        assertEquals(com.loomlink.edge.domain.enums.NotificationStatus.REJECTED, notif.getStatus());
    }
}
