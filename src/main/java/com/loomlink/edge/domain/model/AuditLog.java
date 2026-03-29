package com.loomlink.edge.domain.model;

import com.loomlink.edge.domain.enums.FailureModeCode;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit trail for every SAP write-back. This is the compliance record
 * that proves to Equinor/Aker BP enterprise architects exactly what was written,
 * when, why, and with what confidence.
 *
 * <p>Regulatory context: North Sea operators must demonstrate traceability for
 * every modification to maintenance records. This entity provides the complete
 * chain-of-custody from free-text input to structured SAP output.</p>
 *
 * <p>This table is append-only. No updates. No deletes. Ever.</p>
 */
@Entity
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_audit_sap_notif", columnList = "sap_notification_number"),
    @Index(name = "idx_audit_timestamp", columnList = "created_at"),
    @Index(name = "idx_audit_equipment", columnList = "equipment_tag")
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── Source Context ───────────────────────────────────────────────

    @Column(name = "sap_notification_number", nullable = false)
    private String sapNotificationNumber;

    @Column(name = "equipment_tag", nullable = false)
    private String equipmentTag;

    /** The raw free-text exactly as the technician entered it. */
    @Column(name = "original_text", nullable = false, length = 2000)
    private String originalText;

    // ── Classification Output ───────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_mode_code", nullable = false)
    private FailureModeCode failureModeCode;

    @Column(name = "cause_code")
    private String causeCode;

    @Column(name = "confidence_score", nullable = false)
    private double confidenceScore;

    /** The LLM's reasoning chain — preserved verbatim for audit. */
    @Column(name = "llm_reasoning", length = 4000)
    private String llmReasoning;

    // ── Model & Infrastructure ──────────────────────────────────────

    @Column(name = "model_name", nullable = false)
    private String modelName;

    @Column(name = "inference_latency_ms")
    private long inferenceLatencyMs;

    // ── Gate Verdict ────────────────────────────────────────────────

    @Column(name = "gate_threshold", nullable = false)
    private double gateThreshold;

    @Column(name = "gate_passed", nullable = false)
    private boolean gatePassed;

    @Column(name = "gate_reasoning", length = 1000)
    private String gateReasoning;

    // ── BAPI Write-Back ─────────────────────────────────────────────

    /** Was the code actually written back to SAP? */
    @Column(name = "written_to_sap", nullable = false)
    private boolean writtenToSap;

    @Column(name = "bapi_function")
    private String bapiFunction;

    // ── Pipeline Metadata ───────────────────────────────────────────

    @Column(name = "total_pipeline_latency_ms")
    private long totalPipelineLatencyMs;

    /** Was demo mode active for this classification? */
    @Column(name = "demo_mode")
    private boolean demoMode;

    /** JSON schema validation: did the LLM output pass on first attempt? */
    @Column(name = "json_valid_first_attempt")
    private boolean jsonValidFirstAttempt;

    /** How many LLM attempts were needed (1 = clean, 2 = retry succeeded). */
    @Column(name = "llm_attempts")
    private int llmAttempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLog() {}

    public static AuditLog record(
            MaintenanceNotification notification,
            SemanticClassification classification,
            ReflectorGateResult gateResult,
            boolean writtenToSap,
            long totalPipelineLatencyMs,
            boolean demoMode,
            boolean jsonValidFirstAttempt,
            int llmAttempts) {

        AuditLog log = new AuditLog();
        log.sapNotificationNumber = notification.getSapNotificationNumber();
        log.equipmentTag = notification.getEquipmentTag();
        log.originalText = notification.getFreeTextDescription();
        log.failureModeCode = classification.getFailureModeCode();
        log.causeCode = classification.getCauseCode();
        log.confidenceScore = classification.getConfidence();
        log.llmReasoning = classification.getReasoning();
        log.modelName = classification.getModelId();
        log.inferenceLatencyMs = classification.getInferenceLatencyMs();
        log.gateThreshold = gateResult.getThresholdApplied();
        log.gatePassed = gateResult.isPassed();
        log.gateReasoning = gateResult.getGateReasoning();
        log.writtenToSap = writtenToSap;
        log.bapiFunction = writtenToSap ? "BAPI_ALM_NOTIF_DATA_MODIFY" : null;
        log.totalPipelineLatencyMs = totalPipelineLatencyMs;
        log.demoMode = demoMode;
        log.jsonValidFirstAttempt = jsonValidFirstAttempt;
        log.llmAttempts = llmAttempts;
        log.createdAt = Instant.now();
        return log;
    }

    /**
     * Record a manual review action (approve, reclassify, dismiss) from the Exception Inbox.
     * This creates a separate audit entry tracking the human decision for compliance.
     */
    public static AuditLog recordManualReview(
            ExceptionInboxItem item,
            String action,
            String reviewedBy,
            FailureModeCode finalCode,
            String reviewNotes) {

        AuditLog log = new AuditLog();
        log.sapNotificationNumber = item.getSapNotificationNumber();
        log.equipmentTag = item.getEquipmentTag();
        log.originalText = item.getOriginalText();
        log.failureModeCode = finalCode != null ? finalCode
                : (item.getSuggestedFailureCode() != null ? item.getSuggestedFailureCode() : FailureModeCode.UNK);
        log.causeCode = item.getSuggestedCauseCode();
        log.confidenceScore = item.getConfidenceScore();
        log.llmReasoning = item.getLlmReasoning();
        log.modelName = "HUMAN_REVIEW";
        log.inferenceLatencyMs = 0;
        log.gateThreshold = item.getGateThreshold();
        log.gatePassed = false; // original was rejected by gate
        log.gateReasoning = String.format("Manual %s by %s. %s",
                action.toUpperCase(), reviewedBy,
                reviewNotes != null ? "Notes: " + reviewNotes : "");
        log.writtenToSap = "APPROVE".equalsIgnoreCase(action) || "RECLASSIFY".equalsIgnoreCase(action);
        log.bapiFunction = log.writtenToSap ? "BAPI_ALM_NOTIF_DATA_MODIFY (Manual Override)" : null;
        log.totalPipelineLatencyMs = 0;
        log.demoMode = false;
        log.jsonValidFirstAttempt = true;
        log.llmAttempts = 0;
        log.createdAt = Instant.now();
        return log;
    }

    // Getters
    public UUID getId() { return id; }
    public String getSapNotificationNumber() { return sapNotificationNumber; }
    public String getEquipmentTag() { return equipmentTag; }
    public String getOriginalText() { return originalText; }
    public FailureModeCode getFailureModeCode() { return failureModeCode; }
    public String getCauseCode() { return causeCode; }
    public double getConfidenceScore() { return confidenceScore; }
    public String getLlmReasoning() { return llmReasoning; }
    public String getModelName() { return modelName; }
    public long getInferenceLatencyMs() { return inferenceLatencyMs; }
    public double getGateThreshold() { return gateThreshold; }
    public boolean isGatePassed() { return gatePassed; }
    public String getGateReasoning() { return gateReasoning; }
    public boolean isWrittenToSap() { return writtenToSap; }
    public String getBapiFunction() { return bapiFunction; }
    public long getTotalPipelineLatencyMs() { return totalPipelineLatencyMs; }
    public boolean isDemoMode() { return demoMode; }
    public boolean isJsonValidFirstAttempt() { return jsonValidFirstAttempt; }
    public int getLlmAttempts() { return llmAttempts; }
    public Instant getCreatedAt() { return createdAt; }
}
