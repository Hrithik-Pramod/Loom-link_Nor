package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.EquipmentClass;
import com.loomlink.edge.domain.model.MaintenanceNotification;
import com.loomlink.edge.domain.model.SemanticCacheEntry;
import com.loomlink.edge.domain.model.SemanticClassification;
import com.loomlink.edge.repository.SemanticCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Semantic Cache — bypasses Ollama for incoming notifications that are a >95%
 * semantic match to a previously verified, gate-passed classification.
 *
 * <p>Implementation uses a two-tier lookup strategy with equipment-class-aware matching:</p>
 * <ol>
 *   <li><b>Exact match:</b> Normalized text AND equipment class match (~1ms).
 *       Cross-equipment exact matches are BLOCKED to prevent physically implausible
 *       classifications (e.g., VIB cached for a pump should not auto-apply to a heat exchanger).</li>
 *   <li><b>Similarity match:</b> N-gram Jaccard similarity on trigram shingles (>95% threshold),
 *       filtered by equipment tag. This is a Java-native fallback for the Stavanger demo.
 *       In production, this tier would use pgvector cosine similarity with all-MiniLM-L6-v2
 *       embeddings via {@code SELECT * FROM semantic_cache ORDER BY text_embedding <=> :query LIMIT 1}.</li>
 * </ol>
 *
 * <p><b>Cache TTL:</b> Entries older than the configured TTL (default: 90 days) are treated as
 * stale and trigger a fresh LLM classification. In offshore environments, equipment degrades
 * over time — a classification that was correct 6 months ago might mask a new, more serious
 * failure mode. This aligns with NORSOK Z-008 requirements for periodic review.</p>
 *
 * <p>Only gate-PASSED classifications are ever promoted to cache. Rejected or
 * ambiguous results never enter the cache — ensuring every uncertain case
 * always goes through the full LLM + Reflector Gate pipeline.</p>
 *
 * <p>Offshore impact: reduces HM90 Ollama load by 30-60% for repetitive failure reports
 * (common on aging platforms where the same pump leaks the same way every quarter).</p>
 */
@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    /** Minimum Jaccard similarity for a cache hit. 0.95 = 95% trigram overlap. */
    private static final double SIMILARITY_THRESHOLD = 0.95;

    /**
     * Minimum normalized text length for similarity matching (Scenario 5).
     * Short texts like "P-1001A VIB high DE brg" produce too few trigrams for
     * reliable Jaccard similarity. Below this threshold, only exact match is used.
     * Similarity matching is skipped to prevent false positives from short texts.
     */
    private static final int MIN_TEXT_LENGTH_FOR_SIMILARITY = 30;

    private final SemanticCacheRepository repository;

    /** Maximum age of a cache entry before it is considered stale. Default: 90 days. */
    private final Duration cacheTtl;

    public SemanticCacheService(
            SemanticCacheRepository repository,
            @Value("${loomlink.semantic-cache.ttl-days:90}") int ttlDays) {
        this.repository = repository;
        this.cacheTtl = Duration.ofDays(ttlDays);
        log.info("Semantic Cache initialized with TTL: {} days", ttlDays);
    }

    /**
     * Attempt to find a cached classification for the given notification.
     *
     * <p>Equipment-class-aware: Tier 1 exact match requires the same equipment class
     * (not just same text). This prevents cross-equipment misclassification —
     * e.g., VIB cached for pump P-1001A will NOT match heat exchanger E-3001A.</p>
     *
     * @return the cached classification if a >95% match is found, empty otherwise
     */
    public Optional<CacheHitResult> lookup(MaintenanceNotification notification) {
        String inputText = notification.getFreeTextDescription();
        String normalized = normalize(inputText);
        long startTime = System.nanoTime();

        // Derive equipment class of incoming notification
        EquipmentClass inputEquipmentClass = EquipmentClass.fromEquipmentTag(
                notification.getEquipmentTag());

        // ── Tier 1: Exact normalized text match ────────────────────────
        Optional<SemanticCacheEntry> exactMatch = repository.findByNormalizedText(normalized);
        if (exactMatch.isPresent()) {
            SemanticCacheEntry entry = exactMatch.get();

            // ── Safety Check: Equipment Class Compatibility ────────────
            EquipmentClass cachedEquipmentClass = EquipmentClass.fromEquipmentTag(
                    entry.getEquipmentTag());

            if (inputEquipmentClass != EquipmentClass.UNKNOWN
                    && cachedEquipmentClass != EquipmentClass.UNKNOWN
                    && inputEquipmentClass != cachedEquipmentClass) {

                long lookupMs = (System.nanoTime() - startTime) / 1_000_000;
                log.warn("CACHE BLOCKED (cross-equipment): Text matches '{}' but equipment class " +
                        "differs: incoming={} [{}] vs cached={} [{}]. Forcing fresh LLM classification. [{}ms]",
                        truncate(inputText, 50),
                        inputEquipmentClass.name(), notification.getEquipmentTag(),
                        cachedEquipmentClass.name(), entry.getEquipmentTag(),
                        lookupMs);
                // Fall through to Tier 2 (which filters by equipment tag)
            }

            // ── Conflict Check: Disputed entries skip cache (Scenario 3) ──
            else if (entry.isDisputed()) {
                long lookupMs = (System.nanoTime() - startTime) / 1_000_000;
                log.warn("CACHE BLOCKED (disputed): Entry for '{}' has conflicting corrections " +
                        "(corrected {} times, last by {}). Previous code: {}, current code: {}. " +
                        "Forcing fresh LLM classification until dispute is resolved. [{}ms]",
                        truncate(inputText, 50), entry.getCorrectionCount(),
                        entry.getLastCorrectedBy(),
                        entry.getPreviousFailureCode(), entry.getFailureModeCode(),
                        lookupMs);
                // Fall through — don't serve disputed entries from cache
            }

            // ── Staleness Check: Cache TTL ─────────────────────────────
            else if (isStale(entry)) {
                long lookupMs = (System.nanoTime() - startTime) / 1_000_000;
                long ageDays = Duration.between(entry.getCreatedAt(), Instant.now()).toDays();
                log.warn("CACHE STALE: Entry for '{}' is {} days old (TTL={} days). " +
                        "Forcing fresh LLM classification to account for equipment degradation. [{}ms]",
                        truncate(inputText, 50), ageDays, cacheTtl.toDays(), lookupMs);
                // Fall through — treat as miss to get fresh classification
            }

            // ── Valid exact match ──────────────────────────────────────
            else {
                entry.recordHit();
                repository.save(entry);
                long lookupMs = (System.nanoTime() - startTime) / 1_000_000;
                log.info("CACHE HIT (exact) for '{}' → {} (confidence {}) " +
                        "[{}ms lookup, {} total hits, equipment: {}]",
                        truncate(inputText, 50), entry.getFailureModeCode(),
                        entry.getConfidence(), lookupMs, entry.getHitCount(),
                        cachedEquipmentClass.getDescription());
                return Optional.of(new CacheHitResult(entry, 1.0, lookupMs, "EXACT"));
            }
        }

        // ── Tier 2: Trigram Jaccard similarity ─────────────────────────

        // Scenario 5: Short text guard — abbreviations like "P-1001A VIB high DE brg"
        // produce too few trigrams for reliable similarity. Only exact match is safe.
        if (normalized.length() < MIN_TEXT_LENGTH_FOR_SIMILARITY) {
            long lookupMs = (System.nanoTime() - startTime) / 1_000_000;
            log.info("CACHE MISS (short text): '{}' has {} chars (min {} for similarity). " +
                    "Only exact match is reliable for short descriptions. [{}ms]",
                    truncate(inputText, 50), normalized.length(),
                    MIN_TEXT_LENGTH_FOR_SIMILARITY, lookupMs);
            return Optional.empty();
        }

        long inputHash = computeSimHash(inputText);
        List<SemanticCacheEntry> candidates = repository.findByTextEmbeddingHash(inputHash);

        // Equipment-scoped fallback: only search entries for the SAME equipment tag
        if (candidates.isEmpty() && notification.getEquipmentTag() != null) {
            candidates = repository.findByEquipmentTag(notification.getEquipmentTag());
        }

        for (SemanticCacheEntry candidate : candidates) {
            // Skip disputed entries (Scenario 3)
            if (candidate.isDisputed()) {
                log.debug("Skipping disputed candidate: '{}'", truncate(candidate.getOriginalText(), 30));
                continue;
            }

            // Skip stale entries
            if (isStale(candidate)) {
                log.debug("Skipping stale candidate for '{}' (age: {} days)",
                        truncate(candidate.getOriginalText(), 30),
                        Duration.between(candidate.getCreatedAt(), Instant.now()).toDays());
                continue;
            }

            // Equipment class compatibility check for similarity matches too
            EquipmentClass candidateClass = EquipmentClass.fromEquipmentTag(
                    candidate.getEquipmentTag());
            if (inputEquipmentClass != EquipmentClass.UNKNOWN
                    && candidateClass != EquipmentClass.UNKNOWN
                    && inputEquipmentClass != candidateClass) {
                log.debug("Skipping candidate — equipment class mismatch: {} vs {}",
                        inputEquipmentClass, candidateClass);
                continue;
            }

            double similarity = computeJaccardSimilarity(normalized, candidate.getNormalizedText());
            if (similarity >= SIMILARITY_THRESHOLD) {
                candidate.recordHit();
                repository.save(candidate);
                long lookupMs = (System.nanoTime() - startTime) / 1_000_000;
                log.info("CACHE HIT (similarity={}) for '{}' → {} (confidence {}) " +
                        "[{}ms lookup, {} total hits, equipment: {}]",
                        String.format("%.3f", similarity), truncate(inputText, 50),
                        candidate.getFailureModeCode(), candidate.getConfidence(),
                        lookupMs, candidate.getHitCount(),
                        candidateClass.getDescription());
                return Optional.of(new CacheHitResult(candidate, similarity, lookupMs, "SIMILARITY"));
            }
        }

        long lookupMs = (System.nanoTime() - startTime) / 1_000_000;
        log.debug("CACHE MISS for '{}' [{}ms lookup, {} candidates checked]",
                truncate(inputText, 50), lookupMs, candidates.size());
        return Optional.empty();
    }

    /**
     * Check if a cache entry has exceeded its TTL.
     *
     * <p>Human-corrected entries (modelId = "human-feedback-v1" or "human-approved-v1")
     * get double TTL, since human corrections are more reliable than LLM classifications
     * and should persist longer.</p>
     */
    private boolean isStale(SemanticCacheEntry entry) {
        if (entry.getCreatedAt() == null) return false;

        Duration effectiveTtl = cacheTtl;
        // Human corrections get double TTL — they're more trustworthy
        if (entry.getModelId() != null && entry.getModelId().startsWith("human-")) {
            effectiveTtl = cacheTtl.multipliedBy(2);
        }

        return Duration.between(entry.getCreatedAt(), Instant.now()).compareTo(effectiveTtl) > 0;
    }

    /**
     * Promote a gate-passed classification into the semantic cache.
     * Called only when the Reflector Gate passes.
     */
    public void promote(MaintenanceNotification notification, SemanticClassification classification) {
        String normalized = normalize(notification.getFreeTextDescription());

        // Don't cache duplicates
        if (repository.findByNormalizedText(normalized).isPresent()) {
            log.debug("Cache entry already exists for normalized text, skipping promotion");
            return;
        }

        long textHash = computeSimHash(notification.getFreeTextDescription());
        SemanticCacheEntry entry = SemanticCacheEntry.fromVerifiedClassification(
                notification, classification, textHash);
        // Override with keyword-translated normalization (includes Norwegian → English)
        entry.setNormalizedText(normalized);
        repository.save(entry);
        log.info("CACHE PROMOTED: '{}' → {} [equipment: {}, class: {}]",
                truncate(notification.getFreeTextDescription(), 50),
                classification.getFailureModeCode(),
                notification.getEquipmentTag(),
                EquipmentClass.fromEquipmentTag(notification.getEquipmentTag()).getDescription());
    }

    /**
     * Cache statistics for the dashboard.
     */
    public Map<String, Object> getStats() {
        long size = repository.cacheSize();
        Long totalHits = repository.totalHits();
        return Map.of(
                "cacheSize", size,
                "totalHits", totalHits != null ? totalHits : 0L,
                "enabled", true,
                "ttlDays", cacheTtl.toDays()
        );
    }

    // ── Text Similarity Engine ──────────────────────────────────────

    /**
     * Norwegian-to-English keyword map for cross-language cache matching (Scenario 4).
     *
     * <p>Real North Sea technicians write in mixed Norwegian/English. "Pumpe lager støy"
     * and "Pump making noise" mean the same thing but produce completely different trigrams.
     * By translating common Norwegian maintenance terms to English before normalization,
     * we increase cross-language cache hit rates.</p>
     *
     * <p>This covers the ~50 most common maintenance terms from Equinor/Aker BP
     * notification data. In production, this would be replaced by sentence embeddings
     * (all-MiniLM-L6-v2) which are language-agnostic.</p>
     */
    private static final java.util.Map<String, String> NORWEGIAN_KEYWORDS = java.util.Map.ofEntries(
            // Equipment
            java.util.Map.entry("pumpe", "pump"), java.util.Map.entry("kompressor", "compressor"),
            java.util.Map.entry("ventil", "valve"), java.util.Map.entry("motor", "motor"),
            java.util.Map.entry("turbin", "turbine"), java.util.Map.entry("generator", "generator"),
            java.util.Map.entry("varmeveksler", "heat exchanger"), java.util.Map.entry("rør", "pipe"),
            java.util.Map.entry("rørseksjon", "pipe section"),
            // Failure modes
            java.util.Map.entry("vibrasjon", "vibration"), java.util.Map.entry("støy", "noise"),
            java.util.Map.entry("lyd", "sound"), java.util.Map.entry("lekkasje", "leak"),
            java.util.Map.entry("lekker", "leaking"), java.util.Map.entry("drypp", "drip"),
            java.util.Map.entry("overoppheting", "overheating"), java.util.Map.entry("varm", "hot"),
            java.util.Map.entry("temperatur", "temperature"), java.util.Map.entry("trykk", "pressure"),
            java.util.Map.entry("korrosjon", "corrosion"), java.util.Map.entry("erosjon", "erosion"),
            java.util.Map.entry("sprekk", "crack"), java.util.Map.entry("brudd", "breakdown"),
            java.util.Map.entry("tett", "plugged"), java.util.Map.entry("blokkert", "blocked"),
            java.util.Map.entry("tilstoppet", "clogged"),
            // Symptoms
            java.util.Map.entry("skrapelyd", "grinding noise"), java.util.Map.entry("banking", "knocking"),
            java.util.Map.entry("rasling", "rattling"), java.util.Map.entry("slitasje", "wear"),
            java.util.Map.entry("lager", "bearing"), java.util.Map.entry("tetning", "seal"),
            java.util.Map.entry("pakning", "gasket"), java.util.Map.entry("pakningsboks", "packing box"),
            // Actions / States
            java.util.Map.entry("stoppet", "stopped"), java.util.Map.entry("starter", "starts"),
            java.util.Map.entry("fungerer", "functions"), java.util.Map.entry("svikter", "failing"),
            java.util.Map.entry("skifte", "replace"), java.util.Map.entry("byttes", "replace"),
            java.util.Map.entry("sjekket", "checked"), java.util.Map.entry("oppdaget", "detected"),
            java.util.Map.entry("observert", "observed"), java.util.Map.entry("usikker", "unsure"),
            java.util.Map.entry("ukjent", "unknown"), java.util.Map.entry("mulig", "possible"),
            // Qualifiers
            java.util.Map.entry("høy", "high"), java.util.Map.entry("lav", "low"),
            java.util.Map.entry("over", "above"), java.util.Map.entry("under", "below"),
            java.util.Map.entry("øker", "increasing"), java.util.Map.entry("synker", "decreasing"),
            java.util.Map.entry("annerledes", "different"), java.util.Map.entry("uvanlig", "unusual"),
            java.util.Map.entry("noe", "something"), java.util.Map.entry("galt", "wrong"),
            java.util.Map.entry("rar", "odd")
    );

    /**
     * Normalize text: lowercase, collapse whitespace, remove punctuation noise.
     * Then translate common Norwegian maintenance terms to English for cross-language matching.
     * Preserves Norwegian/Nordic characters (å, ø, æ, ö, ä, ü) for bilingual support.
     */
    private String normalize(String text) {
        String normalized = text.toLowerCase().trim()
                .replaceAll("[^a-z0-9åøæöäü\\s]", "")
                .replaceAll("\\s+", " ");

        // Scenario 4: Translate Norwegian keywords to English equivalents
        // This ensures "pumpe lager støy" and "pump making noise" share more trigrams
        StringBuilder translated = new StringBuilder();
        for (String word : normalized.split(" ")) {
            String english = NORWEGIAN_KEYWORDS.get(word);
            translated.append(english != null ? english : word).append(" ");
        }
        return translated.toString().trim();
    }

    /**
     * Compute trigram Jaccard similarity between two texts.
     * J(A,B) = |A ∩ B| / |A ∪ B|, where A and B are trigram sets.
     * Returns 0.0 to 1.0.
     */
    private double computeJaccardSimilarity(String a, String b) {
        var trigramsA = extractTrigrams(a);
        var trigramsB = extractTrigrams(b);

        if (trigramsA.isEmpty() || trigramsB.isEmpty()) return 0.0;

        long intersection = trigramsA.stream().filter(trigramsB::contains).count();
        long union = trigramsA.size() + trigramsB.size() - intersection;

        return union == 0 ? 0.0 : (double) intersection / union;
    }

    /**
     * Extract character-level trigrams from text.
     * "pump noise" → ["pum", "ump", "mp ", "p n", " no", "noi", "ois", "ise"]
     */
    private java.util.Set<String> extractTrigrams(String text) {
        var trigrams = new java.util.HashSet<String>();
        for (int i = 0; i <= text.length() - 3; i++) {
            trigrams.add(text.substring(i, i + 3));
        }
        return trigrams;
    }

    /**
     * Compute a locality-sensitive SimHash for coarse-grained bucketing.
     * Texts with similar content tend to produce the same hash, enabling
     * fast candidate filtering before expensive similarity computation.
     */
    private long computeSimHash(String text) {
        String normalized = normalize(text);
        var trigrams = extractTrigrams(normalized);

        long[] v = new long[64];
        for (String trigram : trigrams) {
            long hash = trigram.hashCode();
            // Spread the hash bits
            hash = hash * 0x9E3779B97F4A7C15L;
            for (int i = 0; i < 64; i++) {
                if (((hash >> i) & 1) == 1) v[i]++;
                else v[i]--;
            }
        }

        long simhash = 0;
        for (int i = 0; i < 64; i++) {
            if (v[i] > 0) simhash |= (1L << i);
        }
        return simhash;
    }

    private String truncate(String text, int maxLen) {
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    /**
     * Result of a cache hit, including the similarity score and lookup time.
     */
    public record CacheHitResult(
            SemanticCacheEntry entry,
            double similarity,
            long lookupMs,
            String matchType  // "EXACT" or "SIMILARITY"
    ) {}
}
