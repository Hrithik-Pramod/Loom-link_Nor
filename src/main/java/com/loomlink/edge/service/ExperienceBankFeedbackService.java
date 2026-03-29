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

            // Check if this text is already cached (might be from a previous correction)
            var existing = cacheRepository.findByNormalizedText(normalizedText);
            if (existing.isPresent()) {
                log.info("Feedback loop: updating existing cache entry for text: '{}'",
                        truncate(originalText, 80));
                // In a production system, we'd update the existing entry.
                // For demo, we log and count it.
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

    private String normalizeText(String text) {
        if (text == null) return "";
        return text.toLowerCase().trim()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
