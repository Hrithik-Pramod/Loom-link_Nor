package com.loomlink.edge.domain.model;

import com.loomlink.edge.domain.enums.FailureModeCode;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Exception Inbox — stores every Reflector Gate rejection for Senior Engineer review.
 *
 * <p>When the gate rejects a classification (confidence below threshold, unknown code,
 * or taxonomy mismatch), the item lands here. A Senior Engineer can later:</p>
 * <ul>
 *   <li>APPROVE — override the gate and push the classification to SAP</li>
 *   <li>RECLASSIFY — manually assign the correct failure code</li>
 *   <li>DISMISS — mark as noise / not actionable</li>
 * </ul>
 *
 * <p>This ensures zero data loss — every notification gets resolved, either automatically
 * by the pipeline or manually by a human. Nothing falls through the cracks.</p>
 */
@Entity
@Table(name = "exception_inbox", indexes = {
    @Index(name = "idx_exception_status", columnList = "review_status"),
    @Index(name = "idx_exception_priority", columnList = "priority"),
    @Index(name = "idx_exception_created", columnList = "created_at")
})
public class ExceptionInboxItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── Original Notification Context ───────────────────────────────

    @Column(name = "sap_notification_number", nullable = false)
    private String sapNotificationNumber;

    @Column(name = "equipment_tag", nullable = false)
    private String equipmentTag;

    @Column(name = "original_text", nullable = false, length = 2000)
    private String originalText;

    @Column(name = "sap_plant")
    private String sapPlant;

    // ── LLM Classification (what the AI suggested) ──────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "suggested_failure_code")
    private FailureModeCode suggestedFailureCode;

    @Column(name = "suggested_cause_code")
    private String suggestedCauseCode;

    @Column(name = "confidence_score")
    private double confidenceScore;

    @Column(name = "llm_reasoning", length = 4000)
    private String llmReasoning;

    @Column(name = "model_name")
    private String modelName;

    // ── Rejection Context ───────────────────────────────────────────

    @Column(name = "rejection_reason", nullable = false, length = 1000)
    private String rejectionReason;

    @Column(name = "gate_threshold")
    private double gateThreshold;

    // ── Review State ────────────────────────────────────────────────

    /** PENDING, APPROVED, RECLASSIFIED, DISMISSED */
    @Column(name = "review_status", nullable = false)
    private String reviewStatus;

    /** CRITICAL, HIGH, MEDIUM, LOW — based on equipment class and confidence gap. */
    @Column(name = "priority", nullable = false)
    private String priority;

    /** Engineer who reviewed this item. */
    @Column(name = "reviewed_by")
    private String reviewedBy;

    /** If reclassified, what code did the engineer assign? */
    @Enumerated(EnumType.STRING)
    @Column(name = "manual_failure_code")
    private FailureModeCode manualFailureCode;

    @Column(name = "review_notes", length = 2000)
    private String reviewNotes;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ExceptionInboxItem() {}

    public static ExceptionInboxItem fromRejection(
            MaintenanceNotification notification,
            SemanticClassification classification,
            ReflectorGateResult gateResult) {

        ExceptionInboxItem item = new ExceptionInboxItem();
        item.sapNotificationNumber = notification.getSapNotificationNumber();
        item.equipmentTag = notification.getEquipmentTag();
        item.originalText = notification.getFreeTextDescription();
        item.sapPlant = notification.getSapPlant();
        item.suggestedFailureCode = classification.getFailureModeCode();
        item.suggestedCauseCode = classification.getCauseCode();
        item.confidenceScore = classification.getConfidence();
        item.llmReasoning = classification.getReasoning();
        item.modelName = classification.getModelId();
        item.rejectionReason = gateResult.getGateReasoning();
        item.gateThreshold = gateResult.getThresholdApplied();
        item.reviewStatus = "PENDING";
        item.createdAt = Instant.now();

        // Priority: higher confidence gap = lower priority (the AI was very unsure)
        // Low confidence on safety-critical equipment = higher priority
        double gap = gateResult.getThresholdApplied() - classification.getConfidence();
        if (gap < 0.05) item.priority = "HIGH";       // Almost passed — likely correct
        else if (gap < 0.15) item.priority = "MEDIUM"; // Moderate uncertainty
        else item.priority = "LOW";                     // Very uncertain — probably noise

        return item;
    }

    // ── Review Actions ──────────────────────────────────────────────

    public void approve(String reviewedBy, String notes) {
        this.reviewStatus = "APPROVED";
        this.reviewedBy = reviewedBy;
        this.reviewNotes = notes;
        this.reviewedAt = Instant.now();
    }

    public void reclassify(String reviewedBy, FailureModeCode correctCode, String notes) {
        this.reviewStatus = "RECLASSIFIED";
        this.reviewedBy = reviewedBy;
        this.manualFailureCode = correctCode;
        this.reviewNotes = notes;
        this.reviewedAt = Instant.now();
    }

    public void dismiss(String reviewedBy, String notes) {
        this.reviewStatus = "DISMISSED";
        this.reviewedBy = reviewedBy;
        this.reviewNotes = notes;
        this.reviewedAt = Instant.now();
    }

    // Getters
    public UUID getId() { return id; }
    public String getSapNotificationNumber() { return sapNotificationNumber; }
    public String getEquipmentTag() { return equipmentTag; }
    public String getOriginalText() { return originalText; }
    public String getSapPlant() { return sapPlant; }
    public FailureModeCode getSuggestedFailureCode() { return suggestedFailureCode; }
    public String getSuggestedCauseCode() { return suggestedCauseCode; }
    public double getConfidenceScore() { return confidenceScore; }
    public String getLlmReasoning() { return llmReasoning; }
    public String getModelName() { return modelName; }
    public String getRejectionReason() { return rejectionReason; }
    public double getGateThreshold() { return gateThreshold; }
    public String getReviewStatus() { return reviewStatus; }
    public String getPriority() { return priority; }
    public String getReviewedBy() { return reviewedBy; }
    public FailureModeCode getManualFailureCode() { return manualFailureCode; }
    public String getReviewNotes() { return reviewNotes; }
    public Instant getReviewedAt() { return reviewedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
