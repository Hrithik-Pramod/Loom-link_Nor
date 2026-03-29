package com.loomlink.edge.domain.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * The Reflector Gate verdict — the deterministic validation layer between
 * the LLM's semantic reasoning and any SAP write-back operation.
 *
 * <p>Every classification must pass through the Reflector Gate. There is no
 * bypass, no override, no probabilistic middle ground. The gate enforces a
 * binary pass/fail based on configurable thresholds set by the facility's
 * maintenance engineering team.</p>
 *
 * <p>Philosophy: "sane, robust, and safe" — or it is not made at all.</p>
 */
@Entity
@Table(name = "reflector_gate_results")
public class ReflectorGateResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "classification_id", nullable = false, unique = true)
    private SemanticClassification classification;

    // ── Gate Verdict ────────────────────────────────────────────────

    /** Did the classification pass the Reflector Gate? True = cleared for SAP write-back. */
    @Column(name = "passed", nullable = false)
    private boolean passed;

    /** The confidence threshold that was applied (e.g., 0.85). */
    @Column(name = "threshold_applied", nullable = false)
    private double thresholdApplied;

    /** The actual confidence score that was evaluated. */
    @Column(name = "confidence_evaluated", nullable = false)
    private double confidenceEvaluated;

    /** Human-readable explanation of why the gate passed or rejected. */
    @Column(name = "gate_reasoning", length = 1000)
    private String gateReasoning;

    /**
     * Whether the failure mode code exists in the ISO 14224 taxonomy
     * for the equipment class on this notification. A structural sanity check
     * beyond just confidence scoring.
     */
    @Column(name = "taxonomy_valid")
    private boolean taxonomyValid;

    /** If rejected, was this escalated to a human reviewer? */
    @Column(name = "escalated_for_review")
    private boolean escalatedForReview;

    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    // ── Constructors ────────────────────────────────────────────────

    protected ReflectorGateResult() {
        // JPA
    }

    /**
     * Factory: create a PASSED gate result.
     */
    public static ReflectorGateResult passed(
            SemanticClassification classification,
            double threshold,
            String reasoning) {

        ReflectorGateResult r = new ReflectorGateResult();
        r.classification = classification;
        r.passed = true;
        r.thresholdApplied = threshold;
        r.confidenceEvaluated = classification.getConfidence();
        r.gateReasoning = reasoning;
        r.taxonomyValid = true;
        r.escalatedForReview = false;
        r.evaluatedAt = Instant.now();
        return r;
    }

    /**
     * Factory: create a REJECTED gate result — flagged for human review.
     */
    public static ReflectorGateResult rejected(
            SemanticClassification classification,
            double threshold,
            String reasoning) {

        ReflectorGateResult r = new ReflectorGateResult();
        r.classification = classification;
        r.passed = false;
        r.thresholdApplied = threshold;
        r.confidenceEvaluated = classification.getConfidence();
        r.gateReasoning = reasoning;
        r.taxonomyValid = true;
        r.escalatedForReview = true;
        r.evaluatedAt = Instant.now();
        return r;
    }

    // ── Getters ─────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public SemanticClassification getClassification() { return classification; }
    public boolean isPassed() { return passed; }
    public double getThresholdApplied() { return thresholdApplied; }
    public double getConfidenceEvaluated() { return confidenceEvaluated; }
    public String getGateReasoning() { return gateReasoning; }
    public boolean isTaxonomyValid() { return taxonomyValid; }
    public boolean isEscalatedForReview() { return escalatedForReview; }
    public Instant getEvaluatedAt() { return evaluatedAt; }

    @Override
    public String toString() {
        return "ReflectorGate{" +
                (passed ? "PASSED" : "REJECTED") +
                ", confidence=" + String.format("%.3f", confidenceEvaluated) +
                ", threshold=" + thresholdApplied +
                '}';
    }
}
