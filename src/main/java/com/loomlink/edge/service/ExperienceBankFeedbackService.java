package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.FailureModeCode;
import com.loomlink.edge.domain.model.ExceptionInboxItem;
import com.loomlink.edge.domain.model.SemanticCacheEntry;
import com.loomlink.edge.repository.SemanticCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Experience Bank Feedback Loop — the system LEARNS from human corrections.
 *
 * <p>When a Senior Engineer reclassifies a rejected notification in the Exception Inbox,
 * the corrected classification is promoted into the Semantic Cache as a verified entry.
 * Future notifications with similar text will match this correction, improving
 * accuracy over time without retraining the LLM.</p>
 *
 * <p>This closes the learning loop:</p>
 * <pre>
 *   Free-text -> LLM -> Gate REJECT -> Exception Inbox -> Human Reclassifies
 *                                                              |
 *                                                              v
 *                                                    Semantic Cache (promoted)
 *                                                              |
 *                                                              v
 *                                              Future similar text -> Cache HIT (corrected)
 * </pre>
 *
 * <p>This is the differentiator: Loom Link doesn't just classify — it continuously
 * improves from operational feedback without any cloud-based retraining.</p>
 */
@Service
public class ExperienceBankFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(ExperienceBankFeedbackService.class);

    private final SemanticCacheRepository cacheRepository;
    private final AtomicLong feedbackCount = new AtomicLong(0);
    private final AtomicLong promotionCount = new AtomicLong(0);

    public ExperienceBankFeedbackService(SemanticCacheRepository cacheRepository) {
        this.cacheRepository = cacheRepository;
    }

    /**
     * Promote a human-corrected classification into the Experience Bank.
     *
     * <p>Called when an engineer reclassifies a notification in the Exception Inbox.
     * The correction becomes a verified cache entry so future similar notifications
     * are classified correctly without hitting the LLM.</p>
     *
     * @param item the reclassified exception inbox item
     * @param correctedCode the engineer's corrected failure mode code
     * @return true if the correction was promoted successfully
     */
    public boolean promoteHumanCorrection(ExceptionInboxItem item, FailureModeCode correctedCode) {
        feedbackCount.incrementAndGet();

        try {
            String originalText = item.getOriginalText();
            String normalizedText = normalizeText(originalText);

            // Check if this text is already cached (might be from a previous LLM classification)
            var existing = cacheRepository.findByNormalizedText(normalizedText);
            if (existing.isPresent()) {
                // OVERWRITE the cached entry with the human-corrected code
                SemanticCacheEntry cached = existing.get();
                cached.updateFromHumanCorrection(
                        correctedCode,
                        "HUMAN_CORRECTED",
                        1.0,  // human corrections are 100% confident
                        "[HUMAN CORRECTION by " + item.getReviewedBy() + "] " +
                                "Original cached as " + cached.getFailureModeCode() +
                                ", corrected to " + correctedCode +
                                ". Reason: " + item.getReviewNotes(),
                        "human-feedback-v1"
                );
                cacheRepository.save(cached);
                promotionCount.incrementAndGet();
                log.info("FEEDBACK LOOP: UPDATED existing cache entry. '{}' | Was: {} → Now: {} | By: {}",
                        truncate(originalText, 60), cached.getFailureModeCode(),
                        correctedCode, item.getReviewedBy());
                return true;
            }

            // Create a new cache entry from the human correction
            SemanticCacheEntry correctionEntry = SemanticCacheEntry.fromVerifiedClassification(
                    originalText,
                    item.getEquipmentTag(),
                    item.getSapPlant(),
                    correctedCode,
                    "HUMAN_CORRECTED",  // cause code marks it as human-corrected
                    1.0,                // human corrections are 100% confident
                    "[HUMAN CORRECTION by " + item.getReviewedBy() + "] " +
                            "Original LLM suggested " + item.getSuggestedFailureCode() +
                            " at " + String.format("%.1f%%", item.getConfidenceScore() * 100) +
                            " confidence, but was reclassified to " + correctedCode +
                            " by domain expert. Reason: " + item.getReviewNotes(),
                    "human-feedback-v1",
                    item.getSapNotificationNumber());

            cacheRepository.save(correctionEntry);
            promotionCount.incrementAndGet();

            log.info("FEEDBACK LOOP: Human correction promoted to Experience Bank. " +
                            "Text: '{}' | LLM said: {} | Engineer corrected to: {} | By: {}",
                    truncate(originalText, 60),
                    item.getSuggestedFailureCode(),
                    correctedCode,
                    item.getReviewedBy());

            return true;
        } catch (Exception e) {
            log.error("Failed to promote human correction to Experience Bank: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Promote an approved classification into the Experience Bank.
     *
     * <p>When an engineer approves a rejected classification (overriding the gate),
     * the original LLM classification was actually correct — promote it to cache
     * so similar future notifications pass through faster.</p>
     */
    public boolean promoteApproval(ExceptionInboxItem item) {
        feedbackCount.incrementAndGet();

        if (item.getSuggestedFailureCode() == null) return false;

        try {
            String normalizedText = normalizeText(item.getOriginalText());
            var existing = cacheRepository.findByNormalizedText(normalizedText);
            if (existing.isPresent()) return true;

            SemanticCacheEntry approvedEntry = SemanticCacheEntry.fromVerifiedClassification(
                    item.getOriginalText(),
                    item.getEquipmentTag(),
                    item.getSapPlant(),
                    item.getSuggestedFailureCode(),
                    item.getSuggestedCauseCode(),
                    item.getConfidenceScore(),
                    "[HUMAN APPROVED by " + item.getReviewedBy() + "] " +
                            "LLM classification was correct but below gate threshold. " +
                            "Engineer confirmed " + item.getSuggestedFailureCode() + " is accurate.",
                    "human-approved-v1",
                    item.getSapNotificationNumber());

            cacheRepository.save(approvedEntry);
            promotionCount.incrementAndGet();

            log.info("FEEDBACK LOOP: Approved classification promoted to Experience Bank. " +
                            "Code: {} | Confidence: {}% | By: {}",
                    item.getSuggestedFailureCode(),
                    String.format("%.1f", item.getConfidenceScore() * 100),
                    item.getReviewedBy());

            return true;
        } catch (Exception e) {
            log.error("Failed to promote approved classification: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Feedback loop statistics for analytics dashboard.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalFeedbackEvents", feedbackCount.get());
        stats.put("promotedToCache", promotionCount.get());
        stats.put("feedbackLoopActive", true);
        return stats;
    }

    /**
     * Must EXACTLY match SemanticCacheService.normalize() — including Norwegian keyword
     * translation. If these diverge, cache lookups and promotions normalize differently,
     * causing exact matches to miss.
     *
     * TODO: Extract shared normalization into a utility class to prevent drift.
     */
    private String normalizeText(String text) {
        if (text == null) return "";
        // Step 1: Lowercase, strip punctuation, collapse whitespace
        String normalized = text.toLowerCase().trim()
                .replaceAll("[^a-z0-9åøæöäü\\s]", "")
                .replaceAll("\\s+", " ");

        // Step 2: Translate Norwegian keywords to English (must match SemanticCacheService)
        StringBuilder translated = new StringBuilder();
        for (String word : normalized.split(" ")) {
            String english = NORWEGIAN_KEYWORDS.get(word);
            translated.append(english != null ? english : word).append(" ");
        }
        return translated.toString().trim();
    }

    // Must stay in sync with SemanticCacheService.NORWEGIAN_KEYWORDS
    private static final java.util.Map<String, String> NORWEGIAN_KEYWORDS = java.util.Map.ofEntries(
            java.util.Map.entry("pumpe", "pump"), java.util.Map.entry("kompressor", "compressor"),
            java.util.Map.entry("ventil", "valve"), java.util.Map.entry("motor", "motor"),
            java.util.Map.entry("turbin", "turbine"), java.util.Map.entry("generator", "generator"),
            java.util.Map.entry("varmeveksler", "heat exchanger"), java.util.Map.entry("rør", "pipe"),
            java.util.Map.entry("rørseksjon", "pipe section"),
            java.util.Map.entry("vibrasjon", "vibration"), java.util.Map.entry("støy", "noise"),
            java.util.Map.entry("lyd", "sound"), java.util.Map.entry("lekkasje", "leak"),
            java.util.Map.entry("lekker", "leaking"), java.util.Map.entry("drypp", "drip"),
            java.util.Map.entry("overoppheting", "overheating"), java.util.Map.entry("varm", "hot"),
            java.util.Map.entry("temperatur", "temperature"), java.util.Map.entry("trykk", "pressure"),
            java.util.Map.entry("korrosjon", "corrosion"), java.util.Map.entry("erosjon", "erosion"),
            java.util.Map.entry("sprekk", "crack"), java.util.Map.entry("brudd", "breakdown"),
            java.util.Map.entry("tett", "plugged"), java.util.Map.entry("blokkert", "blocked"),
            java.util.Map.entry("tilstoppet", "clogged"),
            java.util.Map.entry("skrapelyd", "grinding noise"), java.util.Map.entry("banking", "knocking"),
            java.util.Map.entry("rasling", "rattling"), java.util.Map.entry("slitasje", "wear"),
            java.util.Map.entry("lager", "bearing"), java.util.Map.entry("tetning", "seal"),
            java.util.Map.entry("pakning", "gasket"), java.util.Map.entry("pakningsboks", "packing box"),
            java.util.Map.entry("stoppet", "stopped"), java.util.Map.entry("starter", "starts"),
            java.util.Map.entry("fungerer", "functions"), java.util.Map.entry("svikter", "failing"),
            java.util.Map.entry("skifte", "replace"), java.util.Map.entry("byttes", "replace"),
            java.util.Map.entry("sjekket", "checked"), java.util.Map.entry("oppdaget", "detected"),
            java.util.Map.entry("observert", "observed"), java.util.Map.entry("usikker", "unsure"),
            java.util.Map.entry("ukjent", "unknown"), java.util.Map.entry("mulig", "possible"),
            java.util.Map.entry("høy", "high"), java.util.Map.entry("lav", "low"),
            java.util.Map.entry("over", "above"), java.util.Map.entry("under", "below"),
            java.util.Map.entry("øker", "increasing"), java.util.Map.entry("synker", "decreasing"),
            java.util.Map.entry("annerledes", "different"), java.util.Map.entry("uvanlig", "unusual"),
            java.util.Map.entry("noe", "something"), java.util.Map.entry("galt", "wrong"),
            java.util.Map.entry("rar", "odd")
    );

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
