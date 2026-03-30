package com.loomlink.edge.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.loomlink.edge.domain.enums.InspectionPriority;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A single inspection point within a {@link RobotMission}.
 *
 * <p>Each waypoint represents one equipment item the robot must inspect.
 * The priority is computed by the RiskScoringService based on maintenance
 * history, emission trends, RUL forecasts, and time since last inspection.</p>
 *
 * <p>Waypoints are ordered by sequence within the mission and include
 * ISAR-compatible task definitions for the robot to execute.</p>
 */
@Entity
@Table(name = "inspection_waypoints", indexes = {
    @Index(name = "idx_wp_mission", columnList = "mission_id"),
    @Index(name = "idx_wp_equipment", columnList = "equipment_tag"),
    @Index(name = "idx_wp_priority", columnList = "priority")
})
public class InspectionWaypoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mission_id", nullable = false)
    private RobotMission mission;

    /** Position in the mission sequence. */
    @Column(name = "sequence_order", nullable = false)
    private int sequenceOrder;

    /** SAP equipment tag to inspect. */
    @Column(name = "equipment_tag", nullable = false)
    private String equipmentTag;

    /** Human-readable equipment name. */
    @Column(name = "equipment_name")
    private String equipmentName;

    /** Facility location for navigation. */
    @Column(name = "location_area", nullable = false)
    private String locationArea;

    /** Coordinates for robot navigation. */
    @Column(name = "coordinates")
    private String coordinates;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false)
    private InspectionPriority priority;

    /** Risk score from RiskScoringService (0.0-1.0). */
    @Column(name = "risk_score")
    private double riskScore;

    /** Why this waypoint was prioritized — human-readable explanation. */
    @Column(name = "risk_reasoning", columnDefinition = "TEXT")
    private String riskReasoning;

    /** ISAR task type (e.g., "gas_detection", "thermal_scan", "visual_inspection"). */
    @Column(name = "isar_task_type", nullable = false)
    private String isarTaskType;

    /** Recommended sensor modalities to use at this waypoint. */
    @Column(name = "recommended_sensors")
    private String recommendedSensors;

    /** How long the robot should dwell at this point (seconds). */
    @Column(name = "dwell_seconds")
    private int dwellSeconds;

    /** Days since this equipment was last inspected. */
    @Column(name = "days_since_last_inspection")
    private Integer daysSinceLastInspection;

    /** Historical emission baseline at this location (ppm). */
    @Column(name = "historical_baseline_ppm")
    private Double historicalBaselinePpm;

    /** Active SAP work orders on this equipment. */
    @Column(name = "active_work_orders")
    private String activeWorkOrders;

    // ── Execution Results (populated during/after mission) ─────────

    /** Was this waypoint completed during the mission? */
    @Column(name = "completed")
    private boolean completed;

    /** When was this waypoint inspected? */
    @Column(name = "inspected_at")
    private Instant inspectedAt;

    /** Was an anomaly detected at this waypoint? */
    @Column(name = "anomaly_detected")
    private boolean anomalyDetected;

    /** If detection occurred, link to the EmissionEvent ID. */
    @Column(name = "emission_event_id")
    private UUID emissionEventId;

    /** Brief summary of findings at this waypoint. */
    @Column(name = "finding_summary")
    private String findingSummary;

    protected InspectionWaypoint() {}

    /**
     * Factory: Create a new inspection waypoint.
     */
    public static InspectionWaypoint create(String equipmentTag, String equipmentName,
                                             String locationArea, String coordinates,
                                             InspectionPriority priority, double riskScore,
                                             String riskReasoning, String isarTaskType,
                                             String recommendedSensors, int dwellSeconds) {
        InspectionWaypoint wp = new InspectionWaypoint();
        wp.equipmentTag = equipmentTag;
        wp.equipmentName = equipmentName;
        wp.locationArea = locationArea;
        wp.coordinates = coordinates;
        wp.priority = priority;
        wp.riskScore = riskScore;
        wp.riskReasoning = riskReasoning;
        wp.isarTaskType = isarTaskType;
        wp.recommendedSensors = recommendedSensors;
        wp.dwellSeconds = dwellSeconds;
        wp.completed = false;
        wp.anomalyDetected = false;
        return wp;
    }

    // ── Lifecycle ──────────────────────────────────────────────────

    public void enrichWithContext(Integer daysSinceLastInspection, Double historicalBaselinePpm,
                                  String activeWorkOrders) {
        this.daysSinceLastInspection = daysSinceLastInspection;
        this.historicalBaselinePpm = historicalBaselinePpm;
        this.activeWorkOrders = activeWorkOrders;
    }

    public void markCompleted(String findingSummary, boolean anomalyDetected, UUID emissionEventId) {
        this.completed = true;
        this.inspectedAt = Instant.now();
        this.findingSummary = findingSummary;
        this.anomalyDetected = anomalyDetected;
        this.emissionEventId = emissionEventId;
    }

    // ── Getters & Setters ──────────────────────────────────────────

    public UUID getId() { return id; }
    public RobotMission getMission() { return mission; }
    public void setMission(RobotMission mission) { this.mission = mission; }
    public int getSequenceOrder() { return sequenceOrder; }
    public void setSequenceOrder(int order) { this.sequenceOrder = order; }
    public String getEquipmentTag() { return equipmentTag; }
    public String getEquipmentName() { return equipmentName; }
    public String getLocationArea() { return locationArea; }
    public String getCoordinates() { return coordinates; }
    public InspectionPriority getPriority() { return priority; }
    public double getRiskScore() { return riskScore; }
    public String getRiskReasoning() { return riskReasoning; }
    public String getIsarTaskType() { return isarTaskType; }
    public String getRecommendedSensors() { return recommendedSensors; }
    public int getDwellSeconds() { return dwellSeconds; }
    public Integer getDaysSinceLastInspection() { return daysSinceLastInspection; }
    public Double getHistoricalBaselinePpm() { return historicalBaselinePpm; }
    public String getActiveWorkOrders() { return activeWorkOrders; }
    public boolean isCompleted() { return completed; }
    public Instant getInspectedAt() { return inspectedAt; }
    public boolean isAnomalyDetected() { return anomalyDetected; }
    public UUID getEmissionEventId() { return emissionEventId; }
    public String getFindingSummary() { return findingSummary; }
}
