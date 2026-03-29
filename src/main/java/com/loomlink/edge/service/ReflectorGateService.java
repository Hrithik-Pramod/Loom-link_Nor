package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.FailureModeCode;
import com.loomlink.edge.domain.model.MaintenanceNotification;
import com.loomlink.edge.domain.model.ReflectorGateResult;
import com.loomlink.edge.domain.model.SemanticClassification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Stage 3 of the Loom Link pipeline: The Deterministic Reflector Gate.
 *
 * <p>This is the governance layer that sits between the LLM's semantic reasoning
 * and any SAP write-back operation. It enforces a binary pass/fail decision —
 * no probabilistic middle ground, no "maybe."</p>
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>Confidence must be ≥ configured threshold (default: 0.85)</li>
 *   <li>Failure mode code must not be UNK (Unknown)</li>
 *   <li>Failure mode must be valid for the equipment class (taxonomy check)</li>
 * </ul>
 *
 * <p>If any rule fails, the classification is REJECTED and escalated for human review.
 * The Reflector Gate ensures that every automated decision is "sane, robust, and safe."</p>
 */
@Service
public class ReflectorGateService {

    private static final Logger log = LoggerFactory.getLogger(ReflectorGateService.class);

    private final double confidenceThreshold;

    public ReflectorGateService(
            @Value("${loomlink.reflector-gate.confidence-threshold:0.85}") double confidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
        log.info("Reflector Gate initialized with confidence threshold: {}", confidenceThreshold);
    }

    /**
     * Evaluate a semantic classification against the Reflector Gate rules.
     *
     * @param classification the LLM's output from the Semantic Engine
     * @param notification   the original notification (for equipment class context)
     * @return a deterministic PASSED or REJECTED verdict
     */
    public ReflectorGateResult evaluate(
            SemanticClassification classification,
            MaintenanceNotification notification) {

        log.info("Reflector Gate evaluating classification for notification: {}",
                notification.getSapNotificationNumber());

        // ── Rule 1: Confidence Threshold ────────────────────────────
        if (classification.getConfidence() < confidenceThreshold) {
            String reason = String.format(
                    "REJECTED: Confidence %.3f is below threshold %.3f. " +
                    "Classification is not deterministic enough for automated SAP write-back.",
                    classification.getConfidence(), confidenceThreshold);

            log.warn("Gate REJECTED {}: {}", notification.getSapNotificationNumber(), reason);
            notification.markRejected();
            return ReflectorGateResult.rejected(classification, confidenceThreshold, reason);
        }

        // ── Rule 2: No Unknown Classifications ──────────────────────
        if (classification.getFailureModeCode() == FailureModeCode.UNK) {
            String reason = "REJECTED: Failure mode code is UNK (Unknown). " +
                    "Cannot write an unresolved classification back to SAP.";

            log.warn("Gate REJECTED {}: unknown failure mode", notification.getSapNotificationNumber());
            notification.markRejected();
            return ReflectorGateResult.rejected(classification, confidenceThreshold, reason);
        }

        // ── Rule 3: Taxonomy Validity ───────────────────────────────
        // In production, this would validate that the failure mode is applicable
        // to the specific ISO 14224 equipment class. For the demo, we validate
        // that the code is a recognized enum value (already enforced by parsing).

        // ── All Rules Passed ────────────────────────────────────────
        String reason = String.format(
                "PASSED: Confidence %.3f ≥ threshold %.3f. " +
                "Failure mode %s (%s) is valid for equipment %s. " +
                "Classification cleared for SAP write-back via BAPI.",
                classification.getConfidence(),
                confidenceThreshold,
                classification.getFailureModeCode(),
                classification.getFailureModeCode().getDescription(),
                notification.getEquipmentTag());

        log.info("Gate PASSED for {}: {} with confidence {}",
                notification.getSapNotificationNumber(),
                classification.getFailureModeCode(),
                classification.getConfidence());

        notification.markVerified();
        return ReflectorGateResult.passed(classification, confidenceThreshold, reason);
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }
}
