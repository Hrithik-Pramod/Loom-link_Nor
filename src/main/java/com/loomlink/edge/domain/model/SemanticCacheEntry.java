package com.loomlink.edge.domain.model;

import com.loomlink.edge.domain.enums.FailureModeCode;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Semantic Cache Entry — stores a verified classification alongside a pgvector embedding
 * of the original free-text. When a new notification arrives that is a >95% cosine
 * similarity match to a cached entry, the pipeline can bypass Ollama entirely and
 * return the cached result.
 *
 * <p>Why this matters offshore:</p>
 * <ul>
 *   <li>Technicians often describe the same failure modes with similar language</li>
 *   <li>LLM inference on the HM90 takes 2-8 seconds — cache hits are &lt;50ms</li>
 *   <li>Reduces Ollama GPU/CPU load on a node that also serves other models</li>
 *   <li>If Ollama is temporarily unreachable, cached classifications still work</li>
 * </ul>
 *
 * <p>Only gate-PASSED classifications are cached. Rejected or ambiguous results
 * are never eligible for cache promotion — every uncertain case must go through
 * the full LLM + Reflector Gate pipeline.</p>
 *
 * <p><b>Conflict Detection:</b> When multiple engineers reclassify the same text pattern
 * differently, the entry tracks correction history to detect disagreements. If
 * {@code correctionCount >= 2} and the latest correction differs from the previous one,
 * the entry is flagged as {@code disputed = true} and routed back through the LLM
 * instead of being served from cache — forcing human consensus.</p>
 */
@Entity
@Table(name = "semantic_cache", indexes = {
    @Index(name = "idx_cache_equipment", columnList = "equipment_tag"),
    @Index(name = "idx_cache_failure_code", columnList = "failure_mode_code"),
    @Index(name = "idx_cache_created", columnList = "created_at")
})
public class SemanticCacheEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── Source Text ─────────────────────────────────────────────────

    /** The original free-text that produced this verified classification. */
    @Column(name = "original_text", nullable = false, length = 2000)
    private String originalText;

    /** Normalized/lowercased version for exact-match fast path. */
    @Column(name = "normalized_text", nullable = false, length = 2000)
    private String normalizedText;

    @Column(name = "equipment_tag")
    private String equipmentTag;

    @Column(name = "sap_plant")
    private String sapPlant;

    // ── Embedding (pgvector) ───────────────────────────────────────

    /**
     * Sentence embedding of the original text, stored as a pgvector vector(384).
     * Generated locally via all-MiniLM-L6-v2 or similar sentence-transformer on the HM90.
     *
     * <p>For the Stavanger demo, we use a lightweight Java-native similarity hash
     * as a fallback until the sentence-transformer is deployed on the HM90.</p>
     */
    @Column(name = "text_embedding_hash", nullable = false)
    private long textEmbeddingHash;

    // ── Cached Classification ──────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_mode_code", nullable = false)
    private FailureModeCode failureModeCode;

    @Column(name = "cause_code")
    private String causeCode;

    @Column(name = "confidence")
    private double confidence;

    @Column(name = "reasoning", length = 4000)
    private String reasoning;

    @Column(name = "model_id")
    private String modelId;

    // ── Cache Metadata ─────────────────────────────────────────────

    /** How many times this cache entry has been returned instead of calling Ollama. */
    @Column(name = "hit_count", nullable = false)
    private int hitCount = 0;

    @Column(name = "last_hit_at")
    private Instant lastHitAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The SAP notification that originally created this entry (for traceability). */
    @Column(name = "source_notification_number")
    private String sourceNotificationNumber;

    // ── Conflict Detection (Scenario 3) ────────────────────────────

    /**
     * How many times this entry has been corrected by human engineers.
     * If correctionCount >= 2 and the code changed, the entry becomes disputed.
     */
    @Column(name = "correction_count", nullable = false)
    private int correctionCount = 0;

    /**
     * The failure mode code BEFORE the latest human correction.
     * Used to detect disagreements between engineers.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_failure_code")
    private FailureModeCode previousFailureCode;

    /**
     * Who last corrected this entry. Used to detect if different engineers disagree.
     */
    @Column(name = "last_corrected_by")
    private String lastCorrectedBy;

    /**
     * True if two or more engineers have assigned DIFFERENT failure codes to this text.
     * Disputed entries are NOT served from cache — they force fresh LLM classification
     * and route to the Exception Inbox for consensus.
     */
    @Column(name = "disputed", nullable = false)
    private boolean disputed = false;

    protected SemanticCacheEntry() {}

    /**
     * Factory: promote a verified, gate-passed classification into the semantic cache.
     */
    public static SemanticCacheEntry fromVerifiedClassification(
            MaintenanceNotification notification,
            SemanticClassification classification,
            long textHash) {

        SemanticCacheEntry entry = new SemanticCacheEntry();
        entry.originalText = notification.getFreeTextDescription();
        entry.normalizedText = notification.getFreeTextDescription().toLowerCase().trim()
                .replaceAll("[^a-z0-9åøæöäü\\s]", "")
                .replaceAll("\\s+", " ");
        entry.equipmentTag = notification.getEquipmentTag();
        entry.sapPlant = notification.getSapPlant();
        entry.textEmbeddingHash = textHash;
        entry.failureModeCode = classification.getFailureModeCode();
        entry.causeCode = classification.getCauseCode();
        entry.confidence = classification.getConfidence();
        entry.reasoning = classification.getReasoning();
        entry.modelId = classification.getModelId();
        entry.hitCount = 0;
        entry.createdAt = Instant.now();
        entry.sourceNotificationNumber = notification.getSapNotificationNumber();
        entry.correctionCount = 0;
        entry.disputed = false;
        return entry;
    }

    /**
     * Factory: promote a human-corrected classification from the Experience Bank Feedback Loop.
     */
    public static SemanticCacheEntry fromVerifiedClassification(
            String originalText,
            String equipmentTag,
            String sapPlant,
            FailureModeCode failureModeCode,
            String causeCode,
            double confidence,
            String reasoning,
            String modelId,
            String sourceNotificationNumber) {

        SemanticCacheEntry entry = new SemanticCacheEntry();
        entry.originalText = originalText;
        entry.normalizedText = originalText != null
                ? originalText.toLowerCase().trim().replaceAll("[^a-z0-9åøæöäü\\s]", "").replaceAll("\\s+", " ").trim()
                : "";
        entry.equipmentTag = equipmentTag;
        entry.sapPlant = sapPlant;
        entry.textEmbeddingHash = entry.normalizedText.hashCode();
        entry.failureModeCode = failureModeCode;
        entry.causeCode = causeCode;
        entry.confidence = confidence;
        entry.reasoning = reasoning;
        entry.modelId = modelId;
        entry.hitCount = 0;
        entry.createdAt = Instant.now();
        entry.sourceNotificationNumber = sourceNotificationNumber;
        entry.correctionCount = 1; // First human correction
        entry.disputed = false;
        return entry;
    }

    /** Record a cache hit. */
    public void recordHit() {
        this.hitCount++;
        this.lastHitAt = Instant.now();
    }

    /**
     * Update this cache entry from a human correction (reclassification).
     *
     * <p><b>Conflict Detection:</b> If a different engineer corrects this entry to a
     * DIFFERENT failure code than what's currently stored, the entry is marked as
     * {@code disputed}. Disputed entries are skipped during cache lookup, forcing
     * fresh LLM classification and human review until consensus is reached.</p>
     *
     * @param correctedCode the engineer's corrected failure mode code
     * @param causeCode     the cause code
     * @param confidence    confidence level (1.0 for human corrections)
     * @param reasoning     reasoning including engineer name and notes
     * @param modelId       source identifier (e.g., "human-feedback-v1")
     */
    public void updateFromHumanCorrection(FailureModeCode correctedCode, String causeCode,
                                           double confidence, String reasoning, String modelId) {
        // Track previous code for conflict detection
        this.previousFailureCode = this.failureModeCode;

        // Detect conflict: different engineer, different code
        String newReviewer = extractReviewer(reasoning);
        if (this.correctionCount > 0
                && correctedCode != this.failureModeCode
                && newReviewer != null
                && this.lastCorrectedBy != null
                && !newReviewer.equals(this.lastCorrectedBy)) {
            // Two different engineers disagree on the classification
            this.disputed = true;
        }

        // Apply the correction
        this.failureModeCode = correctedCode;
        this.causeCode = causeCode;
        this.confidence = confidence;
        this.reasoning = reasoning;
        this.modelId = modelId;
        this.correctionCount++;
        this.lastCorrectedBy = newReviewer;
        this.createdAt = Instant.now(); // Reset TTL on correction
    }

    /**
     * Resolve a dispute — called when engineers reach consensus (e.g., via team review).
     * Clears the disputed flag so the entry can be served from cache again.
     */
    public void resolveDispute(String resolvedBy, String resolution) {
        this.disputed = false;
        this.reasoning = "[DISPUTE RESOLVED by " + resolvedBy + "] " + resolution
                + " | Previous: " + this.reasoning;
    }

    /**
     * Extract reviewer name from reasoning string.
     * Reasoning format: "[HUMAN CORRECTION by Lars Hansen] ..."
     */
    private String extractReviewer(String reasoning) {
        if (reasoning == null) return null;
        int start = reasoning.indexOf("by ");
        int end = reasoning.indexOf("]", start);
        if (start >= 0 && end > start) {
            return reasoning.substring(start + 3, end).trim();
        }
        return null;
    }

    // Getters
    public UUID getId() { return id; }
    public String getOriginalText() { return originalText; }
    public String getNormalizedText() { return normalizedText; }
    public String getEquipmentTag() { return equipmentTag; }
    public String getSapPlant() { return sapPlant; }
    public long getTextEmbeddingHash() { return textEmbeddingHash; }
    public FailureModeCode getFailureModeCode() { return failureModeCode; }
    public String getCauseCode() { return causeCode; }
    public double getConfidence() { return confidence; }
    public String getReasoning() { return reasoning; }
    public String getModelId() { return modelId; }
    public int getHitCount() { return hitCount; }
    public Instant getLastHitAt() { return lastHitAt; }
    public Instant getCreatedAt() { return createdAt; }
    public String getSourceNotificationNumber() { return sourceNotificationNumber; }
    /**
     * Override normalized text — used by SemanticCacheService to apply Norwegian keyword
     * translation. The factory methods do basic normalization; the service adds the
     * cross-language keyword layer on top.
     */
    public void setNormalizedText(String normalizedText) { this.normalizedText = normalizedText; }

    public int getCorrectionCount() { return correctionCount; }
    public FailureModeCode getPreviousFailureCode() { return previousFailureCode; }
    public String getLastCorrectedBy() { return lastCorrectedBy; }
    public boolean isDisputed() { return disputed; }
}
