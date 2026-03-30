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
 * <p>The embedding column uses pgvector's {@code vector(384)} type, matching
 * the all-MiniLM-L6-v2 sentence-transformer dimension.</p>
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
     *
     * <p>Stored as a float array — mapped to pgvector via native SQL.</p>
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
        return entry;
    }

    /**
     * Factory: promote a human-corrected classification from the Experience Bank Feedback Loop.
     *
     * <p>Used when a Senior Engineer reclassifies or approves a rejected notification.
     * The correction is promoted to the cache so future similar text matches correctly.</p>
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
        // Compute a simple hash for the feedback entry
        entry.textEmbeddingHash = entry.normalizedText.hashCode();
        entry.failureModeCode = failureModeCode;
        entry.causeCode = causeCode;
        entry.confidence = confidence;
        entry.reasoning = reasoning;
        entry.modelId = modelId;
        entry.hitCount = 0;
        entry.createdAt = Instant.now();
        entry.sourceNotificationNumber = sourceNotificationNumber;
        return entry;
    }

    /** Record a cache hit. */
    public void recordHit() {
        this.hitCount++;
        this.lastHitAt = Instant.now();
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
     * Update this cache entry from a human correction (reclassification).
     * This is the core of the Experience Bank learning loop — when an engineer
     * corrects a classification, the cache entry is OVERWRITTEN so that future
     * lookups return the corrected code, not the original LLM suggestion.
     */
    public void updateFromHumanCorrection(FailureModeCode correctedCode, String causeCode,
                                           double confidence, String reasoning, String modelId) {
        this.failureModeCode = correctedCode;
        this.causeCode = causeCode;
        this.confidence = confidence;
        this.reasoning = reasoning;
        this.modelId = modelId;
    }
}
