package com.loomlink.edge.domain.model;

import com.loomlink.edge.domain.enums.MissionStatus;
import com.loomlink.edge.domain.enums.RobotPlatform;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AI-planned robot inspection mission for Challenge 04.
 *
 * <p>A mission is a sequence of {@link InspectionWaypoint}s, prioritized by the
 * RiskScoringService, optimized for a specific robot platform's capabilities
 * and battery constraints. The mission definition is ISAR-compatible for
 * direct dispatch to Equinor's robotics infrastructure.</p>
 *
 * <p>Lifecycle: PLANNING → READY → DISPATCHED → IN_PROGRESS → COMPLETED</p>
 *
 * @see <a href="https://github.com/equinor/isar">ISAR — Equinor</a>
 * @see <a href="https://github.com/equinor/flotilla">Flotilla — Equinor</a>
 */
@Entity
@Table(name = "robot_missions", indexes = {
    @Index(name = "idx_mission_status", columnList = "status"),
    @Index(name = "idx_mission_robot", columnList = "robot_id"),
    @Index(name = "idx_mission_created", columnList = "created_at")
})
public class RobotMission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Human-readable mission name (e.g., "Risk-Priority Patrol — Module B"). */
    @Column(name = "mission_name", nullable = false)
    private String missionName;

    /** Flotilla-compatible mission reference ID. */
    @Column(name = "flotilla_ref")
    private String flotillaRef;

    /** Assigned robot identifier. */
    @Column(name = "robot_id", nullable = false)
    private String robotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "robot_platform", nullable = false)
    private RobotPlatform robotPlatform;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private MissionStatus status;

    /** Facility area this mission covers. */
    @Column(name = "facility_area", nullable = false)
    private String facilityArea;

    /** LLM-generated mission planning reasoning. */
    @Column(name = "planning_reasoning", columnDefinition = "TEXT")
    private String planningReasoning;

    /** Overall mission risk score (0.0 = safe, 1.0 = critical). */
    @Column(name = "mission_risk_score")
    private double missionRiskScore;

    /** Estimated mission duration in minutes. */
    @Column(name = "estimated_duration_minutes")
    private int estimatedDurationMinutes;

    /** Actual mission duration in minutes (populated after completion). */
    @Column(name = "actual_duration_minutes")
    private Integer actualDurationMinutes;

    /** Total equipment items to inspect. */
    @Column(name = "waypoint_count")
    private int waypointCount;

    /** How many waypoints have been completed. */
    @Column(name = "waypoints_completed")
    private int waypointsCompleted;

    /** Detections found during mission. */
    @Column(name = "detections_count")
    private int detectionsCount;

    /** Anomalies confirmed by Loom Link during mission. */
    @Column(name = "anomalies_confirmed")
    private int anomaliesConfirmed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Who approved this mission (operator ID or "AUTO" for autonomous dispatch). */
    @Column(name = "approved_by")
    private String approvedBy;

    @OneToMany(mappedBy = "mission", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("sequenceOrder ASC")
    private List<InspectionWaypoint> waypoints = new ArrayList<>();

    protected RobotMission() {}

    /**
     * Factory: Create a new AI-planned mission.
     */
    public static RobotMission plan(String missionName, String robotId, RobotPlatform platform,
                                     String facilityArea, String planningReasoning,
                                     double missionRiskScore, int estimatedMinutes) {
        RobotMission m = new RobotMission();
        m.missionName = missionName;
        m.robotId = robotId;
        m.robotPlatform = platform;
        m.facilityArea = facilityArea;
        m.planningReasoning = planningReasoning;
        m.missionRiskScore = missionRiskScore;
        m.estimatedDurationMinutes = estimatedMinutes;
        m.status = MissionStatus.PLANNING;
        m.createdAt = Instant.now();
        m.waypointCount = 0;
        m.waypointsCompleted = 0;
        m.detectionsCount = 0;
        m.anomaliesConfirmed = 0;
        return m;
    }

    // ── Lifecycle Methods ──────────────────────────────────────────

    public void addWaypoint(InspectionWaypoint wp) {
        wp.setMission(this);
        wp.setSequenceOrder(waypoints.size() + 1);
        waypoints.add(wp);
        waypointCount = waypoints.size();
    }

    public void approve(String operatorId) {
        this.status = MissionStatus.READY;
        this.approvedBy = operatorId;
    }

    public void dispatch() {
        this.status = MissionStatus.DISPATCHED;
        this.dispatchedAt = Instant.now();
        this.flotillaRef = "FLOTILLA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    public void startExecution() {
        this.status = MissionStatus.IN_PROGRESS;
    }

    public void completeWaypoint() {
        this.waypointsCompleted++;
    }

    public void recordDetection(boolean confirmed) {
        this.detectionsCount++;
        if (confirmed) this.anomaliesConfirmed++;
    }

    public void complete() {
        this.status = MissionStatus.COMPLETED;
        this.completedAt = Instant.now();
        if (dispatchedAt != null) {
            this.actualDurationMinutes = (int) java.time.Duration.between(dispatchedAt, completedAt).toMinutes();
        }
    }

    public void abort(String reason) {
        this.status = MissionStatus.ABORTED;
        this.completedAt = Instant.now();
        this.planningReasoning = this.planningReasoning + " | ABORTED: " + reason;
    }

    // ── Getters ────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public String getMissionName() { return missionName; }
    public String getFlotillaRef() { return flotillaRef; }
    public String getRobotId() { return robotId; }
    public RobotPlatform getRobotPlatform() { return robotPlatform; }
    public MissionStatus getStatus() { return status; }
    public String getFacilityArea() { return facilityArea; }
    public String getPlanningReasoning() { return planningReasoning; }
    public double getMissionRiskScore() { return missionRiskScore; }
    public int getEstimatedDurationMinutes() { return estimatedDurationMinutes; }
    public Integer getActualDurationMinutes() { return actualDurationMinutes; }
    public int getWaypointCount() { return waypointCount; }
    public int getWaypointsCompleted() { return waypointsCompleted; }
    public int getDetectionsCount() { return detectionsCount; }
    public int getAnomaliesConfirmed() { return anomaliesConfirmed; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getApprovedBy() { return approvedBy; }
    public List<InspectionWaypoint> getWaypoints() { return waypoints; }
}
