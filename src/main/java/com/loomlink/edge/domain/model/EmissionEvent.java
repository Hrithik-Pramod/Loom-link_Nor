package com.loomlink.edge.domain.model;

import com.loomlink.edge.domain.enums.ComplianceStatus;
import com.loomlink.edge.domain.enums.EmissionClassification;
import com.loomlink.edge.domain.enums.SensorModality;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Core entity for Challenge 02 — Contextual Emission Surveillance.
 *
 * <p>Every sensor reading from a robot patrol that triggers a gas-related alert
 * becomes an EmissionEvent. This entity tracks the complete lifecycle from
 * raw detection through classification, compliance documentation, and remediation.</p>
 *
 * <p>Analogous to {@link MaintenanceNotification} in Challenge 01, but for
 * robot-acquired emission data rather than technician-entered free text.</p>
 *
 * <p>Data format is designed to be compatible with SARA (Storage and Analysis
 * of Robot Acquired plant data) output payloads from Equinor's robotics stack.</p>
 *
 * @see <a href="https://github.com/equinor/sara">SARA — Equinor</a>
 * @see <a href="https://github.com/equinor/isar">ISAR — Equinor</a>
 */
@Entity
@Table(name = "emission_events", indexes = {
    @Index(name = "idx_emission_equipment", columnList = "equipment_tag"),
    @Index(name = "idx_emission_timestamp", columnList = "detected_at"),
    @Index(name = "idx_emission_classification", columnList = "classification"),
    @Index(name = "idx_emission_mission", columnList = "mission_id"),
    @Index(name = "idx_emission_compliance", columnList = "compliance_status"),
    @Index(name = "idx_emission_location", columnList = "location_area")
})
public class EmissionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── SARA / Robot Source Context ─────────────────────────────────

    /** SARA inspection ID linking back to the robot data pipeline. */
    @Column(name = "inspection_id")
    private String inspectionId;

    /** Flotilla mission reference for tracing back to fleet management. */
    @Column(name = "mission_id")
    private String missionId;

    /** Robot identifier (e.g., "Spot-01", "ANYmal-03"). */
    @Column(name = "robot_id", nullable = false)
    private String robotId;

    // ── Equipment & Location ───────────────────────────────────────

    /** SAP equipment tag (e.g., "FLG-4401", "VLV-2203"). */
    @Column(name = "equipment_tag", nullable = false)
    private String equipmentTag;

    /** Facility location area (e.g., "Module B / Deck 2 / Area North"). */
    @Column(name = "location_area", nullable = false)
    private String locationArea;

    /** GPS or facility coordinates from robot telemetry. */
    @Column(name = "coordinates")
    private String coordinates;

    // ── Sensor Reading ─────────────────────────────────────────────

    /** Primary sensor that triggered this event. */
    @Enumerated(EnumType.STRING)
    @Column(name = "sensor_modality", nullable = false)
    private SensorModality sensorModality;

    /** Primary reading value (ppm for CH4, degrees for thermal, dB for acoustic). */
    @Column(name = "raw_reading", nullable = false)
    private double rawReading;

    /** Unit of measurement for the raw reading. */
    @Column(name = "reading_unit", nullable = false)
    private String readingUnit;

    /** Wind speed at time of detection (km/h) — affects gas dispersion modeling. */
    @Column(name = "wind_speed_kmh")
    private Double windSpeedKmh;

    /** Wind direction at time of detection. */
    @Column(name = "wind_direction")
    private String windDirection;

    /** Ambient temperature at time of detection. */
    @Column(name = "ambient_temp_celsius")
    private Double ambientTempCelsius;

    // ── Multi-Modal Fusion Data ────────────────────────────────────

    /** Thermal reading at same location/mission (null if no thermal sensor data). */
    @Column(name = "thermal_reading_celsius")
    private Double thermalReadingCelsius;

    /** Thermal delta above ambient (positive = hotter than expected). */
    @Column(name = "thermal_delta_celsius")
    private Double thermalDeltaCelsius;

    /** Acoustic reading at same location/mission (null if no acoustic data). */
    @Column(name = "acoustic_reading_db")
    private Double acousticReadingDb;

    /** Whether acoustic analysis detected high-frequency hiss (leak signature). */
    @Column(name = "acoustic_leak_signature")
    private Boolean acousticLeakSignature;

    /** Number of sensor modalities that corroborate this detection. */
    @Column(name = "corroborating_sensors")
    private int corroboratingSensors;

    // ── SAP Context (enrichment from operational systems) ──────────

    /** JSON: active work orders on this equipment at time of detection. */
    @Column(name = "sap_active_work_orders", length = 4000)
    private String sapActiveWorkOrders;

    /** Whether maintenance was active on this equipment at detection time. */
    @Column(name = "sap_maintenance_active")
    private boolean sapMaintenanceActive;

    /** Whether this equipment is in a scheduled turnaround. */
    @Column(name = "sap_turnaround_active")
    private boolean sapTurnaroundActive;

    /** Days since last maintenance on this equipment. */
    @Column(name = "days_since_last_maintenance")
    private Integer daysSinceLastMaintenance;

    /** Historical emission baseline at this location (average ppm). */
    @Column(name = "historical_baseline_ppm")
    private Double historicalBaselinePpm;

    // ── Classification Output ──────────────────────────────────────

    /** Loom Link's contextual classification of this emission event. */
    @Enumerated(EnumType.STRING)
    @Column(name = "classification")
    private EmissionClassification classification;

    /** AI confidence score for the classification (0.0 - 1.0). */
    @Column(name = "confidence")
    private double confidence;

    /** LLM reasoning chain — preserved for audit. */
    @Column(name = "reasoning", length = 4000)
    private String reasoning;

    /** Model used for classification. */
    @Column(name = "model_id")
    private String modelId;

    /** Inference latency in milliseconds. */
    @Column(name = "inference_latency_ms")
    private long inferenceLatencyMs;

    // ── Reflector Gate ─────────────────────────────────────────────

    /** Did this event pass the Emission Reflector Gate? */
    @Column(name = "gate_passed")
    private Boolean gatePassed;

    /** Gate confidence threshold that was applied. */
    @Column(name = "gate_threshold")
    private double gateThreshold;

    /** Gate reasoning (why passed or rejected). */
    @Column(name = "gate_reasoning", length = 1000)
    private String gateReasoning;

    // ── Quantification (EU 2024/1787 requirement) ──────────────────

    /** Estimated leak rate in kg/hr (for FUGITIVE_EMISSION events). */
    @Column(name = "estimated_leak_rate_kg_hr")
    private Double estimatedLeakRateKgHr;

    /** Distance from sensor to estimated source point (meters). */
    @Column(name = "sensor_distance_meters")
    private Double sensorDistanceMeters;

    /**
     * Whether an OGI (Optical Gas Imaging) survey is required per EN 15446.
     * Set to true when the screening leak rate exceeds the LDAR threshold (0.5 kg/hr)
     * or when multi-modal sensor corroboration confirms the leak (3+ sensors).
     * Screening estimates are correlation-based (EPA Method 21); confirmed leaks
     * require OGI for accurate quantification under EU 2024/1787.
     */
    @Column(name = "ogi_survey_required")
    private Boolean ogiSurveyRequired = false;

    // ── Compliance Lifecycle ───────────────────────────────────────

    /** EU 2024/1787 compliance lifecycle status. */
    @Enumerated(EnumType.STRING)
    @Column(name = "compliance_status")
    private ComplianceStatus complianceStatus;

    /** SAP work order number (if work order was generated). */
    @Column(name = "sap_work_order_number")
    private String sapWorkOrderNumber;

    /** ISO 14224 failure code auto-generated for maintenance (ELP typically). */
    @Column(name = "iso14224_failure_code")
    private String iso14224FailureCode;

    /** Whether EU compliance report has been auto-generated. */
    @Column(name = "compliance_report_generated")
    private boolean complianceReportGenerated;

    // ── Trend Tracking ─────────────────────────────────────────────

    /** Number of previous detections at this equipment tag in last 7 days. */
    @Column(name = "previous_detections_7d")
    private int previousDetections7d;

    /** Trend direction: ESCALATING, STABLE, DECLINING, FIRST_DETECTION. */
    @Column(name = "trend_direction")
    private String trendDirection;

    // ── Remediation Recommendation ────────────────────────────────

    /** AI-generated prescriptive remediation action for this emission event. */
    @Column(name = "remediation_action", length = 2000)
    private String remediationAction;

    /** Urgency level of the recommended action: IMMEDIATE, WITHIN_24H, WITHIN_72H, ROUTINE, MONITOR_ONLY. */
    @Column(name = "remediation_urgency")
    private String remediationUrgency;

    /** Estimated time to complete remediation in hours. */
    @Column(name = "remediation_estimated_hours")
    private Double remediationEstimatedHours;

    // ── Review / Exception Inbox ──────────────────────────────────────

    /** Review status: PENDING, GATE_REJECTED, CONFIRMED, RECLASSIFIED, DISMISSED. */
    @Column(name = "review_status")
    private String reviewStatus;

    /** Priority for gate-rejected events: CRITICAL, HIGH, MEDIUM, LOW. */
    @Column(name = "review_priority")
    private String reviewPriority;

    /** Engineer who reviewed (if applicable). */
    @Column(name = "reviewed_by")
    private String reviewedBy;

    /** If reclassified, the corrected classification. */
    @Enumerated(EnumType.STRING)
    @Column(name = "corrected_classification")
    private EmissionClassification correctedClassification;

    @Column(name = "review_notes", length = 2000)
    private String reviewNotes;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    // ── Timestamps ─────────────────────────────────────────────────

    /** When the robot sensor detected the anomaly. */
    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    /** When Loom Link processed and classified this event. */
    @Column(name = "classified_at")
    private Instant classifiedAt;

    /** When the compliance record was generated. */
    @Column(name = "compliance_documented_at")
    private Instant complianceDocumentedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EmissionEvent() {}

    // ── Factory Method ─────────────────────────────────────────────

    /**
     * Create a new emission event from incoming SARA-compatible sensor data.
     */
    public static EmissionEvent fromSensorReading(
            String inspectionId, String missionId, String robotId,
            String equipmentTag, String locationArea,
            SensorModality modality, double rawReading, String readingUnit,
            Instant detectedAt) {

        EmissionEvent event = new EmissionEvent();
        event.inspectionId = inspectionId;
        event.missionId = missionId;
        event.robotId = robotId;
        event.equipmentTag = equipmentTag;
        event.locationArea = locationArea;
        event.sensorModality = modality;
        event.rawReading = rawReading;
        event.readingUnit = readingUnit;
        event.detectedAt = detectedAt;
        event.createdAt = Instant.now();
        event.complianceStatus = ComplianceStatus.DETECTED;
        event.reviewStatus = "PENDING";
        event.corroboratingSensors = 1;
        return event;
    }

    // ── Lifecycle Methods ──────────────────────────────────────────

    public void enrichWithMultiModalData(
            Double thermalCelsius, Double thermalDelta,
            Double acousticDb, Boolean acousticLeakSig,
            int corroboratingSensors) {
        this.thermalReadingCelsius = thermalCelsius;
        this.thermalDeltaCelsius = thermalDelta;
        this.acousticReadingDb = acousticDb;
        this.acousticLeakSignature = acousticLeakSig;
        this.corroboratingSensors = corroboratingSensors;
        if (corroboratingSensors > 1) {
            this.sensorModality = SensorModality.MULTI;
        }
    }

    public void enrichWithEnvironmentalData(
            Double windSpeedKmh, String windDirection, Double ambientTempCelsius) {
        this.windSpeedKmh = windSpeedKmh;
        this.windDirection = windDirection;
        this.ambientTempCelsius = ambientTempCelsius;
    }

    public void enrichWithSapContext(
            String activeWorkOrders, boolean maintenanceActive,
            boolean turnaroundActive, Integer daysSinceLastMaintenance,
            Double historicalBaselinePpm) {
        this.sapActiveWorkOrders = activeWorkOrders;
        this.sapMaintenanceActive = maintenanceActive;
        this.sapTurnaroundActive = turnaroundActive;
        this.daysSinceLastMaintenance = daysSinceLastMaintenance;
        this.historicalBaselinePpm = historicalBaselinePpm;
    }

    public void enrichWithTrendData(int previousDetections7d, String trendDirection) {
        this.previousDetections7d = previousDetections7d;
        this.trendDirection = trendDirection;
    }

    public void classify(EmissionClassification classification, double confidence,
                         String reasoning, String modelId, long inferenceLatencyMs) {
        this.classification = classification;
        this.confidence = confidence;
        this.reasoning = reasoning;
        this.modelId = modelId;
        this.inferenceLatencyMs = inferenceLatencyMs;
        this.classifiedAt = Instant.now();
        this.complianceStatus = ComplianceStatus.CLASSIFIED;
    }

    public void applyGateResult(boolean passed, double threshold, String gateReasoning) {
        this.gatePassed = passed;
        this.gateThreshold = threshold;
        this.gateReasoning = gateReasoning;
    }

    public void quantifyLeakRate(double leakRateKgHr, Double sensorDistanceMeters) {
        this.estimatedLeakRateKgHr = leakRateKgHr;
        this.sensorDistanceMeters = sensorDistanceMeters;
    }

    public void markComplianceDocumented() {
        this.complianceStatus = ComplianceStatus.DOCUMENTED;
        this.complianceReportGenerated = true;
        this.complianceDocumentedAt = Instant.now();
    }

    public void markWorkOrderCreated(String workOrderNumber, String iso14224Code) {
        this.sapWorkOrderNumber = workOrderNumber;
        this.iso14224FailureCode = iso14224Code;
        this.complianceStatus = ComplianceStatus.WORK_ORDER_CREATED;
    }

    /**
     * Route this event to the Emission Exception Inbox for human review.
     * Called when the Emission Reflector Gate rejects the classification.
     *
     * Priority logic (mirrors Challenge 01 ExceptionInboxItem):
     * - CRITICAL: safety-critical equipment (PRV, PSV, BDV) OR high reading (>2x baseline)
     * - HIGH: UNKNOWN classification OR confidence very close to threshold (gap <0.05)
     * - MEDIUM: moderate confidence gap (<0.15)
     * - LOW: everything else
     */
    public void routeToExceptionInbox(double gateThreshold) {
        this.reviewStatus = "GATE_REJECTED";
        double gap = gateThreshold - this.confidence;

        // Safety-critical equipment always gets CRITICAL priority
        String tag = this.equipmentTag != null ? this.equipmentTag.toUpperCase() : "";
        boolean isSafetyCritical = tag.startsWith("PRV") || tag.startsWith("PSV") || tag.startsWith("BDV");

        // High reading relative to baseline suggests real emission
        boolean highRelativeToBaseline = this.historicalBaselinePpm != null
                && this.historicalBaselinePpm > 0
                && this.rawReading > this.historicalBaselinePpm * 2.0;

        if (isSafetyCritical || highRelativeToBaseline) {
            this.reviewPriority = "CRITICAL";
        } else if (this.classification == EmissionClassification.UNKNOWN || gap < 0.05) {
            this.reviewPriority = "HIGH";
        } else if (gap < 0.15) {
            this.reviewPriority = "MEDIUM";
        } else {
            this.reviewPriority = "LOW";
        }
    }

    public void reclassify(String reviewedBy, EmissionClassification corrected, String notes) {
        this.reviewStatus = "RECLASSIFIED";
        this.reviewedBy = reviewedBy;
        this.correctedClassification = corrected;
        this.reviewNotes = notes;
        this.reviewedAt = Instant.now();
    }

    public void confirm(String reviewedBy, String notes) {
        this.reviewStatus = "CONFIRMED";
        this.reviewedBy = reviewedBy;
        this.reviewNotes = notes;
        this.reviewedAt = Instant.now();
    }

    public void dismiss(String reviewedBy, String notes) {
        this.reviewStatus = "DISMISSED";
        this.reviewedBy = reviewedBy;
        this.reviewNotes = notes;
        this.reviewedAt = Instant.now();
        this.complianceStatus = ComplianceStatus.FALSE_POSITIVE;
    }

    // ── Getters ────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public String getInspectionId() { return inspectionId; }
    public String getMissionId() { return missionId; }
    public String getRobotId() { return robotId; }
    public String getEquipmentTag() { return equipmentTag; }
    public String getLocationArea() { return locationArea; }
    public String getCoordinates() { return coordinates; }
    public SensorModality getSensorModality() { return sensorModality; }
    public double getRawReading() { return rawReading; }
    public String getReadingUnit() { return readingUnit; }
    public Double getWindSpeedKmh() { return windSpeedKmh; }
    public String getWindDirection() { return windDirection; }
    public Double getAmbientTempCelsius() { return ambientTempCelsius; }
    public Double getThermalReadingCelsius() { return thermalReadingCelsius; }
    public Double getThermalDeltaCelsius() { return thermalDeltaCelsius; }
    public Double getAcousticReadingDb() { return acousticReadingDb; }
    public Boolean getAcousticLeakSignature() { return acousticLeakSignature; }
    public int getCorroboratingSensors() { return corroboratingSensors; }
    public String getSapActiveWorkOrders() { return sapActiveWorkOrders; }
    public boolean isSapMaintenanceActive() { return sapMaintenanceActive; }
    public boolean isSapTurnaroundActive() { return sapTurnaroundActive; }
    public Integer getDaysSinceLastMaintenance() { return daysSinceLastMaintenance; }
    public Double getHistoricalBaselinePpm() { return historicalBaselinePpm; }
    public EmissionClassification getClassification() { return classification; }
    public double getConfidence() { return confidence; }
    public String getReasoning() { return reasoning; }
    public String getModelId() { return modelId; }
    public long getInferenceLatencyMs() { return inferenceLatencyMs; }
    public Boolean getGatePassed() { return gatePassed; }
    public double getGateThreshold() { return gateThreshold; }
    public String getGateReasoning() { return gateReasoning; }
    public Double getEstimatedLeakRateKgHr() { return estimatedLeakRateKgHr; }
    public Double getSensorDistanceMeters() { return sensorDistanceMeters; }
    public Boolean isOgiSurveyRequired() { return ogiSurveyRequired != null ? ogiSurveyRequired : false; }
    public void setOgiSurveyRequired(Boolean ogiSurveyRequired) { this.ogiSurveyRequired = ogiSurveyRequired != null ? ogiSurveyRequired : false; }
    public ComplianceStatus getComplianceStatus() { return complianceStatus; }
    public String getSapWorkOrderNumber() { return sapWorkOrderNumber; }
    public String getIso14224FailureCode() { return iso14224FailureCode; }
    public boolean isComplianceReportGenerated() { return complianceReportGenerated; }
    public int getPreviousDetections7d() { return previousDetections7d; }
    public String getTrendDirection() { return trendDirection; }
    public String getReviewStatus() { return reviewStatus; }
    public String getReviewedBy() { return reviewedBy; }
    public EmissionClassification getCorrectedClassification() { return correctedClassification; }
    public String getReviewNotes() { return reviewNotes; }
    public Instant getReviewedAt() { return reviewedAt; }
    public Instant getDetectedAt() { return detectedAt; }
    public Instant getClassifiedAt() { return classifiedAt; }
    public Instant getComplianceDocumentedAt() { return complianceDocumentedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public String getReviewPriority() { return reviewPriority; }
    public String getRemediationAction() { return remediationAction; }
    public String getRemediationUrgency() { return remediationUrgency; }
    public Double getRemediationEstimatedHours() { return remediationEstimatedHours; }

    public void setCoordinates(String coordinates) { this.coordinates = coordinates; }

    public void applyRemediation(String action, String urgency, Double estimatedHours) {
        this.remediationAction = action;
        this.remediationUrgency = urgency;
        this.remediationEstimatedHours = estimatedHours;
    }
}
