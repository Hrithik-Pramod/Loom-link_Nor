package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.EmissionClassification;
import com.loomlink.edge.domain.model.EmissionEvent;
import com.loomlink.edge.domain.model.SemanticCacheEntry;
import com.loomlink.edge.repository.SemanticCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Experience Bank integration for Challenge 02 emission surveillance.
 *
 * This service provides semantic cache lookup specifically for emission events,
 * enabling knowledge sharing between Challenge 01 (maintenance) and Challenge 02 (emission)
 * pipelines. Since the existing SemanticCacheService.lookup() only accepts MaintenanceNotification
 * objects, this service abstracts emission-specific behavior while leveraging the shared
 * SemanticCacheRepository.
 *
 * The service uses trigram Jaccard similarity for semantic matching of emission event
 * descriptions. Unlike maintenance notifications which rely on structured fault codes,
 * emission events are text-driven from sensor readings, requiring a slightly lower
 * similarity threshold (0.90 vs 0.95) to capture domain knowledge effectively.
 *
 * Architecture:
 * - Lookup: Converts EmissionEvent to normalized text representation, queries cache
 *   using trigram Jaccard similarity, returns classification if threshold exceeded
 * - Promote: Stores gate-passed emission classifications for future reuse
 * - PromoteHumanCorrection: Captures engineer reclassifications for model refinement
 * - Stats: Tracks cache health (size, hits, promotions) for operational visibility
 *
 * Challenge 01↔02 Knowledge Sharing:
 * The cached emission classifications can inform maintenance prediction models when
 * equipment exhibits emission-based degradation patterns. Similarly, maintenance
 * notifications can be enriched with historical emission contexts.
 */
@Service
public class EmissionExperienceBankService {

    private static final Logger logger = LoggerFactory.getLogger(EmissionExperienceBankService.class);

    private static final String LOG_DIVIDER = "════════════════════════════════════════════════════════════════";
    private static final String EMISSION_MARKER = "[EMISSION]";
    private static final double SIMILARITY_THRESHOLD = 0.90;
    private static final int TRIGRAM_LENGTH = 3;

    /**
     * Cache TTL in days — entries older than this are skipped during lookup.
     * Equipment degrades over time, so a classification from 90 days ago may no longer
     * be accurate. Stale entries remain in the DB for audit purposes but are not served.
     * This prevents the Experience Bank from returning outdated classifications for
     * equipment whose condition has changed since the original reading.
     */
    private static final long CACHE_TTL_DAYS = 90;

    private final SemanticCacheRepository semanticCacheRepository;
    private final AtomicLong promotionCounter = new AtomicLong(0);

    public EmissionExperienceBankService(SemanticCacheRepository semanticCacheRepository) {
        this.semanticCacheRepository = semanticCacheRepository;
        logger.info("{}", LOG_DIVIDER);
        logger.info("EmissionExperienceBankService initialized");
        logger.info("Similarity threshold: {}", SIMILARITY_THRESHOLD);
        logger.info("{}", LOG_DIVIDER);
    }

    /**
     * Lookup emission classification in cache using semantic similarity.
     *
     * @param emissionEvent the emission event to classify
     * @return Optional containing cache hit result if similarity exceeds threshold
     */
    public Optional<EmissionCacheHitResult> lookup(EmissionEvent emissionEvent) {
        long startTime = System.currentTimeMillis();

        logger.debug("{}", LOG_DIVIDER);
        logger.debug("EMISSION EXPERIENCE BANK LOOKUP");
        logger.debug("Equipment: {}, Sensor: {}, Reading: {} {}, Area: {}",
                emissionEvent.getEquipmentTag(),
                emissionEvent.getSensorModality(),
                emissionEvent.getRawReading(),
                emissionEvent.getReadingUnit(),
                emissionEvent.getLocationArea());

        String emissionText = buildEmissionText(emissionEvent);
        String normalizedText = normalize(emissionText);

        logger.debug("Normalized query text: {}", normalizedText);

        Set<String> queryTrigrams = extractTrigrams(normalizedText);
        logger.debug("Query trigrams extracted: {} patterns", queryTrigrams.size());

        // Query cache by equipment tag for efficiency, then compute similarity
        var cachedEntries = semanticCacheRepository.findByEquipmentTag(emissionEvent.getEquipmentTag());

        logger.debug("Cache entries found for equipment: {}", cachedEntries.size());

        Optional<EmissionCacheHitResult> bestMatch = Optional.empty();
        double bestSimilarity = 0.0;

        Instant ttlCutoff = Instant.now().minus(java.time.Duration.ofDays(CACHE_TTL_DAYS));

        for (SemanticCacheEntry entry : cachedEntries) {
            // Skip stale entries — equipment condition changes over time, so old
            // classifications may no longer reflect reality. Stale entries remain
            // in DB for audit trail but are not served from cache.
            if (entry.getCreatedAt() != null && entry.getCreatedAt().isBefore(ttlCutoff)) {
                logger.trace("Skipping stale cache entry (created: {}, TTL cutoff: {})",
                        entry.getCreatedAt(), ttlCutoff);
                continue;
            }

            Set<String> cachedTrigrams = extractTrigrams(entry.getNormalizedText());
            double similarity = computeJaccardSimilarity(queryTrigrams, cachedTrigrams);

            logger.trace("Entry comparison - Similarity: {}", similarity);

            if (similarity >= SIMILARITY_THRESHOLD && similarity > bestSimilarity) {
                bestSimilarity = similarity;

                // Parse classification from cache entry source (emission origin)
                EmissionClassification cachedClassification = parseEmissionClassification(entry);

                bestMatch = Optional.of(new EmissionCacheHitResult(
                        emissionEvent.getEquipmentTag(),
                        cachedClassification,
                        entry.getConfidence(),
                        similarity,
                        System.currentTimeMillis() - startTime,
                        "TRIGRAM_JACCARD"
                ));

                logger.debug("Semantic match found - Classification: {}, Confidence: {}, Similarity: {}",
                        cachedClassification, entry.getConfidence(), similarity);
            }
        }

        long elapsedMs = System.currentTimeMillis() - startTime;

        if (bestMatch.isPresent()) {
            logger.info("EMISSION CACHE HIT - Equipment: {}, Classification: {}, Similarity: {}, Time: {}ms",
                    emissionEvent.getEquipmentTag(),
                    bestMatch.get().cachedClassification,
                    String.format("%.3f", bestMatch.get().similarity),
                    elapsedMs);
        } else {
            logger.debug("EMISSION CACHE MISS - Equipment: {}, Time: {}ms",
                    emissionEvent.getEquipmentTag(),
                    elapsedMs);
        }

        logger.debug("{}", LOG_DIVIDER);

        return bestMatch;
    }

    /**
     * Promote a gate-passed emission classification into the cache.
     *
     * Gate-passing emission events represent verified classifications from the
     * Challenge 02 pipeline. These are stored for future pattern matching and
     * cross-challenge knowledge sharing.
     *
     * @param emissionEvent the emission event that passed gate validation
     * @param classification the verified emission classification
     * @param confidence confidence score (0.0 to 1.0)
     * @param reasoning explanation of the classification decision
     * @param modelId identifier of the classifying model
     */
    public void promote(EmissionEvent emissionEvent,
                       EmissionClassification classification,
                       double confidence,
                       String reasoning,
                       String modelId) {
        logger.info("{}", LOG_DIVIDER);
        logger.info("PROMOTING EMISSION TO EXPERIENCE BANK");
        logger.info("Equipment: {}, Classification: {}, Confidence: {}",
                emissionEvent.getEquipmentTag(),
                classification,
                confidence);

        String emissionText = buildEmissionText(emissionEvent);
        String normalizedText = normalize(emissionText);

        try {
            // (Scenario 6 fix) Store classification in sourceNotificationNumber using a
            // parseable format: "EMCLS:{CLASSIFICATION}:{eventId}"
            // This replaces the broken "EMISSION_" prefix convention where split("_")[0]
            // would truncate multi-word enum names like FUGITIVE_EMISSION → FUGITIVE.
            String sourceRef = "EMCLS:" + classification.name() + ":" + emissionEvent.getId();
            SemanticCacheEntry cacheEntry = SemanticCacheEntry.fromVerifiedClassification(
                    emissionText,
                    emissionEvent.getEquipmentTag(),
                    null, // emission events don't have SAP plant
                    null, // emission events don't use FailureModeCode
                    "EMISSION_" + classification.name(), // causeCode field
                    confidence,
                    reasoning,
                    modelId,
                    sourceRef // parseable source reference
            );
            semanticCacheRepository.save(cacheEntry);

            long promoCount = promotionCounter.incrementAndGet();

            logger.info("EMISSION PROMOTED - Promotion #: {}, Stored in cache", promoCount);
            logger.info("{}", LOG_DIVIDER);
        } catch (Exception e) {
            logger.error("Failed to promote emission to cache - Equipment: {}, Classification: {}",
                    emissionEvent.getEquipmentTag(),
                    classification,
                    e);
            throw new RuntimeException("Emission promotion failed", e);
        }
    }

    /**
     * Promote a human-corrected emission classification into the cache.
     *
     * When engineers reclassify an emission event (e.g., false positive correction),
     * this method captures the corrected classification for model refinement and
     * pattern learning. Human corrections receive special handling for traceability.
     *
     * @param emissionEvent the emission event that was reclassified
     * @param originalClassification the initial (incorrect) classification
     * @param correctedClassification the engineer-verified correct classification
     * @param confidence confidence in the correction
     * @param engineerReason explanation from the engineer
     * @param engineerId identifier of the correcting engineer
     */
    public void promoteHumanCorrection(EmissionEvent emissionEvent,
                                       EmissionClassification originalClassification,
                                       EmissionClassification correctedClassification,
                                       double confidence,
                                       String engineerReason,
                                       String engineerId) {
        logger.warn("{}", LOG_DIVIDER);
        logger.warn("HUMAN CORRECTION TO EXPERIENCE BANK");
        logger.warn("Equipment: {}, Original: {} → Corrected: {}, Engineer: {}",
                emissionEvent.getEquipmentTag(),
                originalClassification,
                correctedClassification,
                engineerId);

        String emissionText = buildEmissionText(emissionEvent);
        String normalizedText = normalize(emissionText);

        String correctionReasoning = String.format(
                "Human correction by %s: %s (Original: %s)",
                engineerId,
                engineerReason,
                originalClassification.name()
        );

        try {
            // (Scenario 6 fix) Use the same parseable format for human corrections:
            // "EMCLS:{CORRECTED_CLASSIFICATION}:{eventId}" — so parseEmissionClassification()
            // can extract the correct enum name regardless of underscores in the enum value.
            String sourceRef = "EMCLS:" + correctedClassification.name() + ":" + emissionEvent.getId();
            SemanticCacheEntry cacheEntry = SemanticCacheEntry.fromVerifiedClassification(
                    emissionText,
                    emissionEvent.getEquipmentTag(),
                    null, // emission events don't have SAP plant
                    null,
                    "EMISSION_" + correctedClassification.name(),
                    confidence,
                    correctionReasoning,
                    "HUMAN_CORRECTION",
                    sourceRef
            );
            semanticCacheRepository.save(cacheEntry);

            long promoCount = promotionCounter.incrementAndGet();

            logger.warn("CORRECTION PROMOTED - Promotion #: {}, Will refine future classifications", promoCount);
            logger.warn("{}", LOG_DIVIDER);
        } catch (Exception e) {
            logger.error("Failed to promote human correction - Equipment: {}, Correction: {} → {}",
                    emissionEvent.getEquipmentTag(),
                    originalClassification,
                    correctedClassification,
                    e);
            throw new RuntimeException("Correction promotion failed", e);
        }
    }

    /**
     * Get cache statistics for operational visibility.
     *
     * @return Map containing:
     *         - cacheSize: total entries in cache
     *         - totalHits: cumulative cache hit count
     *         - promotionCount: total emissions promoted
     *         - similarityThreshold: current threshold for semantic matches
     */
    public Map<String, Object> getStats() {
        long cacheSize = semanticCacheRepository.cacheSize();
        long totalHits = semanticCacheRepository.totalHits();
        long promoCount = promotionCounter.get();

        Map<String, Object> stats = new HashMap<>();
        stats.put("cacheSize", cacheSize);
        stats.put("totalHits", totalHits);
        stats.put("promotionCount", promoCount);
        stats.put("similarityThreshold", SIMILARITY_THRESHOLD);

        logger.debug("EMISSION EXPERIENCE BANK STATS - Size: {}, Hits: {}, Promotions: {}",
                cacheSize, totalHits, promoCount);

        return stats;
    }

    /**
     * Build normalized emission text representation for similarity matching.
     *
     * Format: "[EMISSION] equipmentTag | sensorModality rawReading readingUnit | locationArea"
     *
     * @param emissionEvent the emission event
     * @return formatted emission text
     */
    private String buildEmissionText(EmissionEvent emissionEvent) {
        return String.format("%s %s | %s %s %s | %s",
                EMISSION_MARKER,
                emissionEvent.getEquipmentTag(),
                emissionEvent.getSensorModality(),
                emissionEvent.getRawReading(),
                emissionEvent.getReadingUnit(),
                emissionEvent.getLocationArea()
        );
    }

    /**
     * Normalize text for trigram extraction: lowercase, trim, remove punctuation.
     *
     * @param text raw text
     * @return normalized text
     */
    private String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.toLowerCase()
                .trim()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ");
    }

    /**
     * Extract trigrams from normalized text.
     *
     * Trigrams are 3-character substrings used for semantic similarity matching.
     * Padding with spaces enables matching of short terms.
     *
     * @param text normalized text
     * @return set of trigrams
     */
    private Set<String> extractTrigrams(String text) {
        Set<String> trigrams = new HashSet<>();

        if (text == null || text.length() < TRIGRAM_LENGTH) {
            return trigrams;
        }

        // Pad text with spaces for boundary detection
        String paddedText = " " + text + " ";

        for (int i = 0; i <= paddedText.length() - TRIGRAM_LENGTH; i++) {
            trigrams.add(paddedText.substring(i, i + TRIGRAM_LENGTH));
        }

        return trigrams;
    }

    /**
     * Compute Jaccard similarity between two trigram sets.
     *
     * Similarity = |intersection| / |union|
     *
     * @param set1 first trigram set
     * @param set2 second trigram set
     * @return similarity score (0.0 to 1.0)
     */
    private double computeJaccardSimilarity(Set<String> set1, Set<String> set2) {
        if (set1.isEmpty() && set2.isEmpty()) {
            return 1.0;
        }

        if (set1.isEmpty() || set2.isEmpty()) {
            return 0.0;
        }

        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        return (double) intersection.size() / union.size();
    }

    /**
     * Parse emission classification from cache entry.
     *
     * Source field format: "EMISSION_CLASSIFICATION" or "EMISSION_CORRECTION_eventId"
     *
     * @param entry cache entry
     * @return parsed emission classification
     */
    /**
     * Parse emission classification from cache entry.
     *
     * (Scenario 6 fix) Supports two formats:
     * - New format: "EMCLS:{CLASSIFICATION_NAME}:{eventId}" — colon-delimited, safe for
     *   multi-word enum names like FUGITIVE_EMISSION
     * - Legacy format: "EMISSION_{CLASSIFICATION}" — broken for multi-word enums,
     *   kept for backward compatibility with existing cache entries
     *
     * @param entry cache entry
     * @return parsed emission classification
     */
    private EmissionClassification parseEmissionClassification(SemanticCacheEntry entry) {
        String sourceNotification = entry.getSourceNotificationNumber();
        if (sourceNotification == null) {
            return EmissionClassification.UNKNOWN;
        }

        // New format: "EMCLS:FUGITIVE_EMISSION:uuid-here"
        if (sourceNotification.startsWith("EMCLS:")) {
            String[] parts = sourceNotification.split(":", 3); // limit=3 to not split UUID colons
            if (parts.length >= 2) {
                try {
                    return EmissionClassification.valueOf(parts[1]);
                } catch (IllegalArgumentException e) {
                    logger.warn("Failed to parse classification from EMCLS format: {}, defaulting to UNKNOWN",
                            sourceNotification);
                    return EmissionClassification.UNKNOWN;
                }
            }
        }

        // Legacy format: "EMISSION_FUGITIVE_EMISSION" or "EMISSION_CORRECTION_uuid"
        // Try all enum values to find a match (handles multi-word names correctly)
        if (sourceNotification.startsWith("EMISSION_")) {
            String remainder = sourceNotification.substring("EMISSION_".length());
            for (EmissionClassification ec : EmissionClassification.values()) {
                if (remainder.startsWith(ec.name())) {
                    return ec;
                }
            }
            logger.warn("Failed to parse classification from legacy format: {}, defaulting to UNKNOWN",
                    sourceNotification);
        }

        return EmissionClassification.UNKNOWN;
    }

    /**
     * Inner record representing a successful cache lookup result.
     *
     * @param equipmentTag the equipment that was queried
     * @param cachedClassification the emission classification from cache
     * @param confidence confidence score of the cached classification
     * @param similarity the trigram Jaccard similarity score
     * @param lookupMs elapsed time in milliseconds for the lookup
     * @param matchType the matching algorithm used (e.g., TRIGRAM_JACCARD)
     */
    public record EmissionCacheHitResult(
            String equipmentTag,
            EmissionClassification cachedClassification,
            double confidence,
            double similarity,
            long lookupMs,
            String matchType
    ) {
        /**
         * Validate result integrity.
         *
         * @return true if similarity and confidence are within valid ranges
         */
        public boolean isValid() {
            return similarity >= 0.0 && similarity <= 1.0 &&
                    confidence >= 0.0 && confidence <= 1.0;
        }

        /**
         * String representation for logging.
         *
         * @return formatted result string
         */
        @Override
        public String toString() {
            return String.format(
                    "EmissionCacheHit{eq=%s, class=%s, conf=%.3f, sim=%.3f, ms=%d, type=%s}",
                    equipmentTag, cachedClassification, confidence, similarity, lookupMs, matchType
            );
        }
    }
}
