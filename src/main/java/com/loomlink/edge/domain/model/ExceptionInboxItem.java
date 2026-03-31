package com.loomlink.edge.domain.model;

import com.loomlink.edge.domain.enums.EquipmentClass;
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

    // ── SLA Tracking (Ptil audit compliance) ───────────────────────

    /** When this item was first viewed by an engineer (null = never seen). */
    @Column(name = "first_viewed_at")
    private Instant firstViewedAt;

    /** Who first viewed this item. */
    @Column(name = "first_viewed_by")
    private String firstViewedBy;

    /**
     * SLA target hours based on priority:
     * CRITICAL = 1h, HIGH = 4h, MEDIUM = 24h, LOW = 72h
     * Per NORSOK Z-008 response time requirements.
     */
    @Column(name = "sla_target_hours")
    private Integer slaTargetHours;

    /** Whether the SLA was met (review completed within target). */
    @Column(name = "sla_met")
    private Boolean slaMet;

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

        // ── Priority Logic (Scenario 7 fix) ─────────────────────────
        //
        // OLD (broken): Very uncertain = LOW priority.
        // PROBLEM: A notification the AI can't classify AT ALL might describe
        //          a genuinely novel/unusual failure that needs urgent attention.
        //          A pipe about to burst might produce symptoms the LLM has never seen.
        //
        // NEW: Priority is a combination of confidence gap AND equipment criticality.
        //
        //   CRITICAL: Safety equipment (PRV, PSV, ESD) regardless of confidence gap
        //   HIGH:     AI almost passed (gap < 0.05) — likely correct, quick review
        //             OR AI had NO idea (UNK/very low confidence) — might be novel failure
        //   MEDIUM:   Moderate uncertainty (gap 0.05-0.20)
        //   LOW:      Dismissed/noise indicators in text ("not sure", "maybe", "kanskje")
        //

        EquipmentClass equipClass = EquipmentClass.fromEquipmentTag(notification.getEquipmentTag());
        double gap = gateResult.getThresholdApplied() - classification.getConfidence();
        boolean isUnknownOrVeryLow = classification.getFailureModeCode() == FailureModeCode.UNK
                || classification.getConfidence() < 0.30;

        if (equipClass.isSafetyCritical()) {
            item.priority = "CRITICAL";  // Safety equipment always gets top priority
        } else if (gap < 0.05) {
            item.priority = "HIGH";      // Almost passed — quick engineer review
        } else if (isUnknownOrVeryLow) {
            item.priority = "HIGH";      // AI couldn't classify — might be novel/serious failure
        } else if (gap < 0.20) {
            item.priority = "MEDIUM";    // Moderate uncertainty
        } else {
            item.priority = "LOW";       // Large gap with some classification — likely noise
        }

        // Set SLA target based on priority (NORSOK Z-008 response times)
        item.slaTargetHours = switch (item.priority) {
            case "CRITICAL" -> 1;    // Safety equipment: 1 hour
            case "HIGH" -> 4;        // Near-pass or novel failure: 4 hours
            case "MEDIUM" -> 24;     // Moderate uncertainty: 24 hours
            default -> 72;           // Low priority: 72 hours
        };

        return item;
    }

    // ── View Tracking (SLA) ─────────────────────────────────────────

    /**
     * Record that an engineer has viewed this item in the Exception Inbox.
     * Only records the FIRST view — subsequent views don't overwrite.
     */
    public void recordView(String viewedBy) {
        if (this.firstViewedAt == null) {
            this.firstViewedAt = Instant.now();
            this.firstViewedBy = viewedBy;
        }
    }

    /**
     * Get the time in hours this item waited before first being viewed.
     * Returns null if never viewed.
     */
    public Double getWaitHoursBeforeView() {
        if (firstViewedAt == null) return null;
        return java.time.Duration.between(createdAt, firstViewedAt).toMinutes() / 60.0;
    }

    /**
     * Get the total time in hours from creation to review completion.
     * Returns null if not yet reviewed.
     */
    public Double getResolutionHours() {
        if (reviewedAt == null) return null;
        return java.time.Duration.between(createdAt, reviewedAt).toMinutes() / 60.0;
    }

    // ── Review Actions ──────────────────────────────────────────────

    public void approve(String reviewedBy, String notes) {
        this.reviewStatus = "APPROVED";
        this.reviewedBy = reviewedBy;
        this.reviewNotes = notes;
        this.reviewedAt = Instant.now();
        computeSlaMet();
    }

    public void reclassify(String reviewedBy, FailureModeCode correctCode, String notes) {
        this.reviewStatus = "RECLASSIFIED";
        this.reviewedBy = reviewedBy;
        this.manualFailureCode = correctCode;
        this.reviewNotes = notes;
        this.reviewedAt = Instant.now();
        computeSlaMet();
    }

    public void dismiss(String reviewedBy, String notes) {
        this.reviewStatus = "DISMISSED";
        this.reviewedBy = reviewedBy;
        this.reviewNotes = notes;
        this.reviewedAt = Instant.now();
        computeSlaMet();
    }

    /**
     * Compute whether the SLA was met based on resolution time vs target.
     */
    private void computeSlaMet() {
        if (this.slaTargetHours != null && this.reviewedAt != null && this.createdAt != null) {
            double resolutionHours = java.time.Duration.between(createdAt, reviewedAt).toMinutes() / 60.0;
            this.slaMet = resolutionHours <= this.slaTargetHours;
        }
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
    public Instant getFirstViewedAt() { return firstViewedAt; }
    public String getFirstViewedBy() { return firstViewedBy; }
    public Integer getSlaTargetHours() { return slaTargetHours; }
    public Boolean getSlaMet() { return slaMet; }
}
