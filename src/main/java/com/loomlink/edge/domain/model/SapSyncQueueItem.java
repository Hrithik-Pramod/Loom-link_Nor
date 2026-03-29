package com.loomlink.edge.domain.model;

import com.loomlink.edge.domain.enums.FailureModeCode;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Dead Letter Queue item for SAP write-back retries.
 *
 * <p>Offshore reality: the network link between the HM90 node and the SAP system
 * can drop at any time — satellite latency, weather windows, maintenance windows,
 * or simply a bad cable on a 30-year-old platform. The pipeline must not lose
 * verified classifications when this happens.</p>
 *
 * <p>When the SAP BAPI call fails, the verified result is persisted here with
 * status PENDING_SYNC. A {@code @Scheduled} task runs every 30 seconds to
 * retry the sync. After 5 failed attempts, the item moves to FAILED status
 * and is surfaced to the operator on the dashboard.</p>
 *
 * <p>This guarantees eventual consistency between the Loom Link pipeline
 * and SAP — even across network outages.</p>
 */
@Entity
@Table(name = "sap_sync_queue", indexes = {
    @Index(name = "idx_sync_status", columnList = "sync_status"),
    @Index(name = "idx_sync_created", columnList = "created_at"),
    @Index(name = "idx_sync_next_retry", columnList = "next_retry_at")
})
public class SapSyncQueueItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── Notification Context ───────────────────────────────────────

    @Column(name = "sap_notification_number", nullable = false)
    private String sapNotificationNumber;

    @Column(name = "equipment_tag", nullable = false)
    private String equipmentTag;

    @Column(name = "original_text", nullable = false, length = 2000)
    private String originalText;

    @Column(name = "sap_plant")
    private String sapPlant;

    // ── Verified Classification (ready for SAP write-back) ─────────

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_mode_code", nullable = false)
    private FailureModeCode failureModeCode;

    @Column(name = "cause_code")
    private String causeCode;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @Column(name = "reasoning", length = 4000)
    private String reasoning;

    // ── Sync State ─────────────────────────────────────────────────

    /** PENDING_SYNC, SYNCED, FAILED */
    @Column(name = "sync_status", nullable = false)
    private String syncStatus;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 5;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "synced_at")
    private Instant syncedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SapSyncQueueItem() {}

    /**
     * Factory: create a new DLQ item from a failed SAP write-back.
     */
    public static SapSyncQueueItem fromFailedWriteBack(
            MaintenanceNotification notification,
            SemanticClassification classification,
            String errorMessage) {

        SapSyncQueueItem item = new SapSyncQueueItem();
        item.sapNotificationNumber = notification.getSapNotificationNumber();
        item.equipmentTag = notification.getEquipmentTag();
        item.originalText = notification.getFreeTextDescription();
        item.sapPlant = notification.getSapPlant();
        item.failureModeCode = classification.getFailureModeCode();
        item.causeCode = classification.getCauseCode();
        item.confidence = classification.getConfidence();
        item.reasoning = classification.getReasoning();
        item.syncStatus = "PENDING_SYNC";
        item.retryCount = 0;
        item.lastError = errorMessage;
        item.createdAt = Instant.now();
        // First retry in 30 seconds
        item.nextRetryAt = Instant.now().plusSeconds(30);
        return item;
    }

    /** Record a retry attempt. Returns true if more retries are allowed. */
    public boolean recordRetryAttempt(String error) {
        this.retryCount++;
        this.lastError = error;
        if (this.retryCount >= this.maxRetries) {
            this.syncStatus = "FAILED";
            return false;
        }
        // Exponential backoff: 30s, 60s, 120s, 240s, 480s
        long backoffSeconds = 30L * (long) Math.pow(2, this.retryCount - 1);
        this.nextRetryAt = Instant.now().plusSeconds(backoffSeconds);
        return true;
    }

    /** Mark as successfully synced to SAP. */
    public void markSynced() {
        this.syncStatus = "SYNCED";
        this.syncedAt = Instant.now();
        this.nextRetryAt = null;
    }

    /** Mark as permanently failed after exhausting retries. */
    public void markFailed(String finalError) {
        this.syncStatus = "FAILED";
        this.lastError = finalError;
        this.nextRetryAt = null;
    }

    // Getters
    public UUID getId() { return id; }
    public String getSapNotificationNumber() { return sapNotificationNumber; }
    public String getEquipmentTag() { return equipmentTag; }
    public String getOriginalText() { return originalText; }
    public String getSapPlant() { return sapPlant; }
    public FailureModeCode getFailureModeCode() { return failureModeCode; }
    public String getCauseCode() { return causeCode; }
    public double getConfidence() { return confidence; }
    public String getReasoning() { return reasoning; }
    public String getSyncStatus() { return syncStatus; }
    public int getRetryCount() { return retryCount; }
    public int getMaxRetries() { return maxRetries; }
    public String getLastError() { return lastError; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public Instant getSyncedAt() { return syncedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
