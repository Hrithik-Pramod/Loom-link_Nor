package com.loomlink.edge.gateway;

import com.loomlink.edge.domain.model.MaintenanceNotification;
import com.loomlink.edge.domain.model.ReflectorGateResult;
import com.loomlink.edge.domain.model.SapSyncQueueItem;
import com.loomlink.edge.domain.model.SemanticClassification;
import com.loomlink.edge.repository.SapSyncQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stage 4 of the Loom Link pipeline: SAP BAPI Write-Back Simulation.
 *
 * <p>In production, this gateway would call SAP via JCo (Java Connector) to execute
 * the BAPI_ALM_NOTIF_DATA_MODIFY function module. For the Stavanger demo, this
 * simulates the BAPI call with a configurable failure rate to demonstrate the
 * Dead Letter Queue resilience pattern.</p>
 *
 * <p>Scale & Resilience: when the SAP call fails (simulating an offshore network drop),
 * the verified result is persisted to the {@code sap_sync_queue} table with status
 * PENDING_SYNC. The {@link com.loomlink.edge.service.SapSyncRetryScheduler} retries
 * the sync every 30 seconds with exponential backoff.</p>
 *
 * <p>Clean Core compliance: zero custom ABAP, only standard BAPIs.</p>
 */
@Service
public class SapBapiGateway {

    private static final Logger log = LoggerFactory.getLogger(SapBapiGateway.class);

    private final SapSyncQueueRepository syncQueueRepository;

    /**
     * Demo: simulate a SAP failure rate to show the DLQ in action.
     * 0.0 = never fails (default for safe demos), 0.2 = 20% failure rate (to demo resilience).
     */
    @Value("${loomlink.sap.simulated-failure-rate:0.0}")
    private double simulatedFailureRate;

    public SapBapiGateway(SapSyncQueueRepository syncQueueRepository) {
        this.syncQueueRepository = syncQueueRepository;
    }

    /**
     * Attempt to write the verified failure code back to SAP via BAPI.
     * If the call fails, the item is queued in the DLQ for retry.
     *
     * @param notification   the original notification
     * @param classification the verified classification
     * @param gateResult     the Reflector Gate pass verdict
     * @return a map representing the BAPI call parameters (for audit/logging),
     *         or null if the write-back was queued for retry
     */
    public WriteBackResult writeBackFailureCode(
            MaintenanceNotification notification,
            SemanticClassification classification,
            ReflectorGateResult gateResult) {

        if (!gateResult.isPassed()) {
            throw new IllegalStateException(
                    "Cannot write back to SAP: Reflector Gate has not cleared this classification. " +
                    "Notification: " + notification.getSapNotificationNumber());
        }

        // Build the BAPI payload — this is exactly what JCo would send
        Map<String, Object> bapiPayload = Map.of(
                "BAPI", "BAPI_ALM_NOTIF_DATA_MODIFY",
                "NOTIFICATION", notification.getSapNotificationNumber(),
                "NOTIF_TYPE", "M2",  // PM Notification - Malfunction Report
                "FUNCT_LOC", notification.getEquipmentTag(),
                "EQUIPMENT", notification.getEquipmentTag(),
                "FAILURE_MODE_CODE", classification.getFailureModeCode().name(),
                "FAILURE_MODE_TEXT", classification.getFailureModeCode().getDescription(),
                "CAUSE_CODE", classification.getCauseCode() != null ? classification.getCauseCode() : "",
                "LOOM_LINK_CONFIDENCE", String.format("%.3f", classification.getConfidence()),
                "LOOM_LINK_TIMESTAMP", Instant.now().toString()
        );

        // ── Simulate SAP call (with possible network failure) ──────────
        boolean sapCallSucceeded = simulateBapiCall(notification);

        if (sapCallSucceeded) {
            log.info("══════════════════════════════════════════════════════════════");
            log.info("  SAP BAPI WRITE-BACK (SIMULATED — SUCCESS)");
            log.info("══════════════════════════════════════════════════════════════");
            log.info("  Function Module : BAPI_ALM_NOTIF_DATA_MODIFY");
            log.info("  Notification    : {}", notification.getSapNotificationNumber());
            log.info("  Equipment       : {}", notification.getEquipmentTag());
            log.info("  Failure Mode    : {} ({})", classification.getFailureModeCode(),
                    classification.getFailureModeCode().getDescription());
            log.info("  Cause Code      : {}", classification.getCauseCode());
            log.info("  Confidence      : {}", String.format("%.3f", classification.getConfidence()));
            log.info("══════════════════════════════════════════════════════════════");

            notification.markWrittenBack();
            return new WriteBackResult(bapiPayload, true, false);

        } else {
            // ── DLQ: SAP call failed — queue for async retry ───────────
            String errorMsg = "Simulated SAP connectivity failure: offshore network drop detected. " +
                              "BAPI call to " + notification.getSapNotificationNumber() + " will be retried.";

            log.warn("══════════════════════════════════════════════════════════════");
            log.warn("  SAP BAPI WRITE-BACK FAILED — QUEUED FOR RETRY (DLQ)");
            log.warn("══════════════════════════════════════════════════════════════");
            log.warn("  Notification    : {}", notification.getSapNotificationNumber());
            log.warn("  Error           : Offshore network drop (simulated)");
            log.warn("  Action          : Saved to sap_sync_queue with PENDING_SYNC");
            log.warn("  Next retry      : ~30 seconds (exponential backoff)");
            log.warn("══════════════════════════════════════════════════════════════");

            SapSyncQueueItem queueItem = SapSyncQueueItem.fromFailedWriteBack(
                    notification, classification, errorMsg);
            syncQueueRepository.save(queueItem);

            // Mark notification as verified but not yet synced
            // (it passed the gate but SAP wasn't reachable)
            notification.markVerified();

            return new WriteBackResult(bapiPayload, false, true);
        }
    }

    /**
     * Simulate the SAP BAPI call. Returns true if "successful", false if
     * simulating an offshore network failure.
     */
    private boolean simulateBapiCall(MaintenanceNotification notification) {
        if (simulatedFailureRate <= 0.0) {
            return true;  // Never fails — safe demo mode
        }

        boolean fails = ThreadLocalRandom.current().nextDouble() < simulatedFailureRate;

        if (fails) {
            log.warn("SAP BAPI call SIMULATED FAILURE for {} (failure rate: {}%)",
                    notification.getSapNotificationNumber(),
                    String.format("%.0f", simulatedFailureRate * 100));
        }
        return !fails;
    }

    /**
     * Write-back from a manual review (approval or reclassification in Exception Inbox).
     *
     * <p>When a Senior Engineer approves or reclassifies a rejected notification,
     * the corrected failure code needs to reach SAP — not just the Experience Bank.
     * Without this, the SAP notification stays uncoded despite human intervention.</p>
     *
     * @param sapNotificationNumber the SAP notification to update
     * @param equipmentTag          the equipment tag
     * @param failureModeCode       the approved/corrected failure mode code
     * @param causeCode             the cause code (or "HUMAN_CORRECTED" for reclassifications)
     * @param reviewedBy            the engineer who performed the review (for audit)
     */
    public void writeBackFromManualReview(
            String sapNotificationNumber,
            String equipmentTag,
            com.loomlink.edge.domain.enums.FailureModeCode failureModeCode,
            String causeCode,
            String reviewedBy) {

        Map<String, Object> bapiPayload = Map.of(
                "BAPI", "BAPI_ALM_NOTIF_DATA_MODIFY",
                "NOTIFICATION", sapNotificationNumber,
                "NOTIF_TYPE", "M2",
                "EQUIPMENT", equipmentTag,
                "FAILURE_MODE_CODE", failureModeCode.name(),
                "FAILURE_MODE_TEXT", failureModeCode.getDescription(),
                "CAUSE_CODE", causeCode != null ? causeCode : "",
                "LOOM_LINK_CONFIDENCE", "1.000",  // Human review = absolute confidence
                "LOOM_LINK_REVIEWER", reviewedBy,
                "LOOM_LINK_TIMESTAMP", Instant.now().toString()
        );

        log.info("══════════════════════════════════════════════════════════════");
        log.info("  SAP BAPI WRITE-BACK FROM MANUAL REVIEW (SIMULATED)");
        log.info("══════════════════════════════════════════════════════════════");
        log.info("  Function Module : BAPI_ALM_NOTIF_DATA_MODIFY");
        log.info("  Notification    : {}", sapNotificationNumber);
        log.info("  Equipment       : {}", equipmentTag);
        log.info("  Failure Mode    : {} ({})", failureModeCode, failureModeCode.getDescription());
        log.info("  Reviewed By     : {}", reviewedBy);
        log.info("  Source          : Exception Inbox → Human Override");
        log.info("══════════════════════════════════════════════════════════════");

        // In production: actual JCo BAPI call here.
        // For demo: log the payload. If simulated failure, queue to DLQ.
    }

    /**
     * Result of a SAP write-back attempt.
     *
     * @param bapiPayload    the BAPI parameters (for audit)
     * @param writtenToSap   true if SAP accepted the write-back immediately
     * @param queuedForRetry true if the call failed and was queued in the DLQ
     */
    public record WriteBackResult(
            Map<String, Object> bapiPayload,
            boolean writtenToSap,
            boolean queuedForRetry
    ) {}
}
