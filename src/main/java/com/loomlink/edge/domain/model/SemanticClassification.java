package com.loomlink.edge.domain.model;

import com.loomlink.edge.domain.enums.FailureModeCode;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * The output of the Semantic Engine — a structured ISO 14224 classification
 * derived from the technician's free-text description via Mistral 7B on the HM90 node.
 *
 * <p>This entity captures not just the classification itself, but the full audit trail:
 * what the LLM reasoned, how confident it was, and what the raw model output contained.
 * The Reflector Gate evaluates the {@code confidence} field to make its deterministic
 * pass/fail decision.</p>
 */
@Entity
@Table(name = "semantic_classifications")
public class SemanticClassification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The notification this classification belongs to. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_id", nullable = false, unique = true)
    private MaintenanceNotification notification;

    // ── Classification Output ───────────────────────────────────────

    /** The ISO 14224 failure mode code the LLM mapped the free-text onto. */
    @Enumerated(EnumType.STRING)
    @Column(name = "failure_mode_code", nullable = false)
    private FailureModeCode failureModeCode;

    /** SAP Cause Code — root cause category (e.g., "B01" for bearing wear). */
    @Column(name = "cause_code")
    private String causeCode;

    /**
     * Confidence score from the Semantic Engine. Range: [0.0, 1.0].
     * The Reflector Gate requires this to be ≥ 0.85 for auto-write-back.
     */
    @Column(name = "confidence", nullable = false)
    private double confidence;

    /** Human-readable reasoning chain from the LLM — for audit purposes. */
    @Column(name = "reasoning", length = 4000)
    private String reasoning;

    /** Raw model output (JSON) — preserved for debugging and Experience Bank training. */
    @Column(name = "raw_model_output", length = 8000)
    private String rawModelOutput;

    /** Which model produced this classification (e.g., "mistral:7b"). */
    @Column(name = "model_id")
    private String modelId;

    /** Inference latency in milliseconds — critical for demo performance metrics. */
    @Column(name = "inference_latency_ms")
    private long inferenceLatencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // ── Constructors ────────────────────────────────────────────────

    protected SemanticClassification() {
        // JPA
    }

    public static SemanticClassification create(
            MaintenanceNotification notification,
            FailureModeCode failureModeCode,
            String causeCode,
            double confidence,
            String reasoning,
            String rawModelOutput,
            String modelId,
            long inferenceLatencyMs) {

        SemanticClassification sc = new SemanticClassification();
        sc.notification = notification;
        sc.failureModeCode = failureModeCode;
        sc.causeCode = causeCode;
        sc.confidence = confidence;
        sc.reasoning = reasoning;
        sc.rawModelOutput = rawModelOutput;
        sc.modelId = modelId;
        sc.inferenceLatencyMs = inferenceLatencyMs;
        sc.createdAt = Instant.now();
        return sc;
    }

    // ── Getters ─────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public MaintenanceNotification getNotification() { return notification; }
    public FailureModeCode getFailureModeCode() { return failureModeCode; }
    public String getCauseCode() { return causeCode; }
    public double getConfidence() { return confidence; }
    public String getReasoning() { return reasoning; }
    public String getRawModelOutput() { return rawModelOutput; }
    public String getModelId() { return modelId; }
    public long getInferenceLatencyMs() { return inferenceLatencyMs; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "SemanticClassification{" +
                "failureMode=" + failureModeCode +
                ", confidence=" + String.format("%.3f", confidence) +
                ", model='" + modelId + '\'' +
                ", latency=" + inferenceLatencyMs + "ms" +
                '}';
    }
}
