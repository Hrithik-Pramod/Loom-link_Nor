package com.loomlink.edge.domain.model;

import com.loomlink.edge.domain.enums.EquipmentClass;
import com.loomlink.edge.domain.enums.NotificationStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Core domain entity representing a maintenance notification intercepted from the
 * SAP OData notification stream. This is the "dirty" free-text entry that a technician
 * submits under field conditions — the exact data point Loom Link exists to structure.
 *
 * <p>Lifecycle: A notification enters as {@link NotificationStatus#RECEIVED} with raw
 * free-text, flows through the Semantic Engine for classification, passes the Reflector
 * Gate for confidence verification, and ultimately gets written back to SAP with a
 * verified ISO 14224 failure code.</p>
 */
@Entity
@Table(name = "maintenance_notifications")
public class MaintenanceNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── SAP Source Fields ────────────────────────────────────────────

    /** SAP Notification Number (e.g., "10004567"). Unique within the SAP system. */
    @NotBlank(message = "SAP notification number is required")
    @Column(name = "sap_notification_number", nullable = false)
    private String sapNotificationNumber;

    /** The raw free-text description entered by the field technician. This is the problem. */
    @NotBlank(message = "Free-text description is required")
    @Column(name = "free_text_description", nullable = false, length = 2000)
    private String freeTextDescription;

    /** SAP Equipment / Functional Location tag (e.g., "P-1001A" for Pump 1001A). */
    @NotBlank(message = "Equipment tag is required")
    @Column(name = "equipment_tag", nullable = false)
    private String equipmentTag;

    /** ISO 14224 equipment class for this asset. */
    @Enumerated(EnumType.STRING)
    @Column(name = "equipment_class")
    private EquipmentClass equipmentClass;

    /** SAP Plant code (e.g., "1000" for Johan Sverdrup). */
    @Column(name = "sap_plant")
    private String sapPlant;

    // ── Pipeline State ──────────────────────────────────────────────

    /** Current position in the Loom Link processing pipeline. */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private NotificationStatus status = NotificationStatus.RECEIVED;

    // ── Timestamps ──────────────────────────────────────────────────

    /** When the notification was intercepted from the OData stream. */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /** When the Semantic Engine completed its classification. */
    @Column(name = "classified_at")
    private Instant classifiedAt;

    /** When the Reflector Gate rendered its verdict. */
    @Column(name = "gate_evaluated_at")
    private Instant gateEvaluatedAt;

    /** When the structured code was written back to SAP via BAPI. */
    @Column(name = "written_back_at")
    private Instant writtenBackAt;

    // ── Constructors ────────────────────────────────────────────────

    protected MaintenanceNotification() {
        // JPA requires a no-arg constructor
    }

    /**
     * Factory method — the canonical way to create a new notification from an OData event.
     */
    public static MaintenanceNotification fromODataEvent(
            String sapNotificationNumber,
            String freeTextDescription,
            String equipmentTag,
            EquipmentClass equipmentClass,
            String sapPlant) {

        MaintenanceNotification n = new MaintenanceNotification();
        n.sapNotificationNumber = sapNotificationNumber;
        n.freeTextDescription = freeTextDescription;
        n.equipmentTag = equipmentTag;
        n.equipmentClass = equipmentClass;
        n.sapPlant = sapPlant;
        n.status = NotificationStatus.RECEIVED;
        n.receivedAt = Instant.now();
        return n;
    }

    // ── State Transitions ───────────────────────────────────────────

    public void markAnalyzing() {
        this.status = NotificationStatus.ANALYZING;
    }

    public void markClassified() {
        this.status = NotificationStatus.CLASSIFIED;
        this.classifiedAt = Instant.now();
    }

    public void markVerified() {
        this.status = NotificationStatus.VERIFIED;
        this.gateEvaluatedAt = Instant.now();
    }

    public void markRejected() {
        this.status = NotificationStatus.REJECTED;
        this.gateEvaluatedAt = Instant.now();
    }

    public void markWrittenBack() {
        this.status = NotificationStatus.WRITTEN_BACK;
        this.writtenBackAt = Instant.now();
    }

    public void markUnclassifiable() {
        this.status = NotificationStatus.UNCLASSIFIABLE;
        this.classifiedAt = Instant.now();
    }

    // ── Getters ─────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public String getSapNotificationNumber() { return sapNotificationNumber; }
    public String getFreeTextDescription() { return freeTextDescription; }
    public String getEquipmentTag() { return equipmentTag; }
    public EquipmentClass getEquipmentClass() { return equipmentClass; }
    public String getSapPlant() { return sapPlant; }
    public NotificationStatus getStatus() { return status; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getClassifiedAt() { return classifiedAt; }
    public Instant getGateEvaluatedAt() { return gateEvaluatedAt; }
    public Instant getWrittenBackAt() { return writtenBackAt; }

    @Override
    public String toString() {
        return "MaintenanceNotification{" +
                "sapNo='" + sapNotificationNumber + '\'' +
                ", equipment='" + equipmentTag + '\'' +
                ", status=" + status +
                ", text='" + (freeTextDescription.length() > 60
                    ? freeTextDescription.substring(0, 60) + "..."
                    : freeTextDescription) + '\'' +
                '}';
    }
}
