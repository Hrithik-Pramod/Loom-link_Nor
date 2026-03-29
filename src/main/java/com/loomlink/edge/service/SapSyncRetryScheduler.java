package com.loomlink.edge.service;

import com.loomlink.edge.domain.model.SapSyncQueueItem;
import com.loomlink.edge.repository.SapSyncQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Scheduled retry service for the SAP Sync Dead Letter Queue.
 *
 * <p>Runs every 30 seconds (configurable). Finds all PENDING_SYNC items whose
 * retry window has passed, and attempts to re-send the BAPI call to SAP.</p>
 *
 * <p>Retry strategy: exponential backoff (30s, 60s, 120s, 240s, 480s).
 * After 5 failed attempts, the item moves to FAILED status and is surfaced
 * on the operator dashboard for manual intervention.</p>
 *
 * <p>For the Stavanger demo, the "BAPI retry" is simulated with an 85% success
 * rate to demonstrate the resilience pattern convincingly. In production, this
 * would call the real JCo BAPI gateway.</p>
 */
@Service
public class SapSyncRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(SapSyncRetryScheduler.class);

    /** Demo: simulate 85% success rate on retry to show the queue draining. */
    private static final double RETRY_SUCCESS_RATE = 0.85;

    private final SapSyncQueueRepository repository;

    public SapSyncRetryScheduler(SapSyncQueueRepository repository) {
        this.repository = repository;
    }

    /**
     * Scheduled task: retry all eligible PENDING_SYNC items.
     * Runs every 30 seconds.
     */
    @Scheduled(fixedRate = 30_000)
    public void retryPendingItems() {
        List<SapSyncQueueItem> retryable = repository.findRetryable(Instant.now());

        if (retryable.isEmpty()) return;

        log.info("SAP Sync Retry: {} items eligible for retry", retryable.size());

        for (SapSyncQueueItem item : retryable) {
            try {
                boolean success = attemptSapSync(item);

                if (success) {
                    item.markSynced();
                    repository.save(item);
                    log.info("SAP Sync SUCCESS for notification {} (attempt {})",
                            item.getSapNotificationNumber(), item.getRetryCount() + 1);
                } else {
                    boolean moreRetries = item.recordRetryAttempt(
                            "SAP BAPI call returned error: simulated offline connectivity issue");
                    repository.save(item);

                    if (moreRetries) {
                        log.warn("SAP Sync FAILED for notification {} (attempt {}/{}, next retry at {})",
                                item.getSapNotificationNumber(), item.getRetryCount(),
                                item.getMaxRetries(), item.getNextRetryAt());
                    } else {
                        log.error("SAP Sync PERMANENTLY FAILED for notification {} after {} attempts. " +
                                  "Manual intervention required.",
                                item.getSapNotificationNumber(), item.getMaxRetries());
                    }
                }
            } catch (Exception e) {
                boolean moreRetries = item.recordRetryAttempt("Exception: " + e.getMessage());
                repository.save(item);
                log.error("SAP Sync exception for notification {}: {}",
                        item.getSapNotificationNumber(), e.getMessage());
            }
        }
    }

    /**
     * Simulate a SAP BAPI call attempt.
     *
     * <p>In production, this would:</p>
     * <ol>
     *   <li>Open a JCo connection to the SAP system</li>
     *   <li>Call BAPI_ALM_NOTIF_DATA_MODIFY with the failure code</li>
     *   <li>Call BAPI_ALM_NOTIF_SAVE to commit</li>
     *   <li>Check the RETURN table for errors</li>
     * </ol>
     *
     * <p>For the demo, we simulate with a configurable success rate.</p>
     */
    private boolean attemptSapSync(SapSyncQueueItem item) {
        // Simulate network latency (200-800ms for SAP round-trip)
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(200, 800));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("Attempting SAP BAPI call: BAPI_ALM_NOTIF_DATA_MODIFY for {} | {} → {}",
                item.getSapNotificationNumber(), item.getEquipmentTag(),
                item.getFailureModeCode());

        // Simulate: 85% success rate on retries (network comes back)
        return ThreadLocalRandom.current().nextDouble() < RETRY_SUCCESS_RATE;
    }

    /**
     * DLQ statistics for the dashboard.
     */
    public Map<String, Object> getStats() {
        return Map.of(
                "pendingSync", repository.countPendingSync(),
                "synced", repository.countSynced(),
                "failed", repository.countFailed(),
                "total", repository.count()
        );
    }
}
