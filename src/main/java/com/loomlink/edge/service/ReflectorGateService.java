package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.EquipmentClass;
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
 * <p>Rules (evaluated in order — first failure rejects):</p>
 * <ul>
 *   <li><b>Rule 1:</b> Confidence must be ≥ configured threshold (default: 0.85)</li>
 *   <li><b>Rule 2:</b> Failure mode code must not be UNK (Unknown)</li>
 *   <li><b>Rule 3:</b> Failure mode must be valid for the equipment class per ISO 14224.
 *       E.g., VIB (vibration) is rejected for static equipment like heat exchangers.</li>
 * </ul>
 *
 * <p>If any rule fails, the classification is REJECTED and escalated for human review
 * via the Exception Inbox. The Reflector Gate ensures that every automated decision
 * is "sane, robust, and safe" — a core requirement for offshore maintenance systems.</p>
 *
 * <p>Safety-critical equipment (PSV, ESD valves) gets a higher confidence threshold
 * because misclassification can have safety consequences per IEC 61511.</p>
 */
@Service
public class ReflectorGateService {

    private static final Logger log = LoggerFactory.getLogger(ReflectorGateService.class);

    /** Standard confidence threshold for general equipment. */
    private final double confidenceThreshold;

    /**
     * Elevated confidence threshold for safety-critical equipment (PSV, ESD).
     * Safety equipment misclassification has higher consequences per IEC 61511.
     */
    private final double safetyConfidenceThreshold;

    public ReflectorGateService(
            @Value("${loomlink.reflector-gate.confidence-threshold:0.85}") double confidenceThreshold,
            @Value("${loomlink.reflector-gate.safety-confidence-threshold:0.92}") double safetyConfidenceThreshold) {
        this.confidenceThreshold = confidenceThreshold;
        this.safetyConfidenceThreshold = safetyConfidenceThreshold;
        log.info("Reflector Gate initialized: standard threshold={}, safety threshold={}",
                confidenceThreshold, safetyConfidenceThreshold);
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

        // Derive equipment class from tag prefix (ISO 14224 taxonomy)
        EquipmentClass equipmentClass = EquipmentClass.fromEquipmentTag(
                notification.getEquipmentTag());

        // (Gap 4 fix) Equipment-class-specific confidence thresholds.
        // Use the HIGHEST of: configured default, safety override, or equipment-specific minimum.
        // This ensures turbines (complex) can pass at 0.82 while flanges (simple) need 0.87.
        double equipmentMinThreshold = equipmentClass.getMinConfidenceThreshold();
        double applicableThreshold = Math.max(
                equipmentClass.isSafetyCritical() ? safetyConfidenceThreshold : confidenceThreshold,
                equipmentMinThreshold);

        // ── Rule 1: Confidence Threshold ────────────────────────────
        if (classification.getConfidence() < applicableThreshold) {
            String reason = String.format(
                    "REJECTED: Confidence %.3f is below %s threshold %.3f%s. " +
                    "Classification is not deterministic enough for automated SAP write-back.",
                    classification.getConfidence(),
                    equipmentClass.isSafetyCritical() ? "SAFETY" : "standard",
                    applicableThreshold,
                    equipmentClass.isSafetyCritical()
                            ? " (elevated for " + equipmentClass.getDescription() + " per IEC 61511)"
                            : "");

            log.warn("Gate REJECTED {}: {}", notification.getSapNotificationNumber(), reason);
            notification.markRejected();
            return ReflectorGateResult.rejected(classification, applicableThreshold, reason);
        }

        // ── Rule 2: No Unknown Classifications ──────────────────────
        if (classification.getFailureModeCode() == FailureModeCode.UNK) {
            String reason = "REJECTED: Failure mode code is UNK (Unknown). " +
                    "Cannot write an unresolved classification back to SAP.";

            log.warn("Gate REJECTED {}: unknown failure mode", notification.getSapNotificationNumber());
            notification.markRejected();
            return ReflectorGateResult.rejected(classification, applicableThreshold, reason);
        }

        // ── Rule 3: ISO 14224 Equipment-Class Validation ────────────
        // Validates that the failure mode is physically plausible for this
        // equipment class. E.g., VIB is invalid for static equipment.
        if (equipmentClass == EquipmentClass.UNKNOWN) {
            // (Gap 2 fix) UNKNOWN equipment class = we can't validate the failure mode.
            // This is a safety gap — the plausibility check is bypassed entirely.
            // Conservative approach: require HIGHER confidence for unrecognized equipment
            // because we can't verify the classification makes physical sense.
            double unknownEquipThreshold = Math.max(applicableThreshold, 0.90);
            if (classification.getConfidence() < unknownEquipThreshold) {
                String reason = String.format(
                        "REJECTED: Equipment tag '%s' could not be resolved to a known ISO 14224 " +
                        "equipment class. Confidence %.3f is below the elevated threshold %.3f " +
                        "required for unrecognized equipment (cannot verify physical plausibility). " +
                        "Requires human review to confirm equipment identity and failure mode validity.",
                        notification.getEquipmentTag(),
                        classification.getConfidence(),
                        unknownEquipThreshold);

                log.warn("Gate REJECTED {} (Rule 3 — UNKNOWN equipment, elevated threshold): {} on '{}'",
                        notification.getSapNotificationNumber(),
                        classification.getFailureModeCode(),
                        notification.getEquipmentTag());
                notification.markRejected();
                return ReflectorGateResult.rejected(classification, unknownEquipThreshold, reason);
            }
            // If confidence is very high (>= 0.90), allow it but log a warning
            log.warn("Gate ALLOWED {} with UNKNOWN equipment class '{}' — " +
                     "confidence {} >= elevated threshold {}. " +
                     "Equipment tag should be reconciled with SAP master data.",
                    notification.getSapNotificationNumber(),
                    notification.getEquipmentTag(),
                    classification.getConfidence(),
                    unknownEquipThreshold);

        } else if (!equipmentClass.isValidFailureMode(classification.getFailureModeCode())) {

            String reason = String.format(
                    "REJECTED: Failure mode %s (%s) is not a valid ISO 14224 failure mode " +
                    "for equipment class %s (%s). Equipment tag: %s. " +
                    "This classification is physically implausible and requires human review.",
                    classification.getFailureModeCode(),
                    classification.getFailureModeCode().getDescription(),
                    equipmentClass.name(),
                    equipmentClass.getDescription(),
                    notification.getEquipmentTag());

            log.warn("Gate REJECTED {} (Rule 3 — equipment mismatch): {} on {}",
                    notification.getSapNotificationNumber(),
                    classification.getFailureModeCode(),
                    equipmentClass.getDescription());
            notification.markRejected();
            return ReflectorGateResult.rejected(classification, applicableThreshold, reason);
        }

        // ── All Rules Passed ────────────────────────────────────────
        String reason = String.format(
                "PASSED: Confidence %.3f ≥ threshold %.3f. " +
                "Failure mode %s (%s) is valid for equipment %s [%s]. " +
                "Classification cleared for SAP write-back via BAPI.",
                classification.getConfidence(),
                applicableThreshold,
                classification.getFailureModeCode(),
                classification.getFailureModeCode().getDescription(),
                notification.getEquipmentTag(),
                equipmentClass.getDescription());

        log.info("Gate PASSED for {}: {} with confidence {} [equipment: {}]",
                notification.getSapNotificationNumber(),
                classification.getFailureModeCode(),
                classification.getConfidence(),
                equipmentClass.getDescription());

        notification.markVerified();
        return ReflectorGateResult.passed(classification, applicableThreshold, reason);
    }

    public double getConfidenceThreshold() {
        return confidenceThreshold;
    }

    public double getSafetyConfidenceThreshold() {
        return safetyConfidenceThreshold;
    }
}
