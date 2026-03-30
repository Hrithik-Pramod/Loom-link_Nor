package com.loomlink.edge.controller;

import com.loomlink.edge.domain.enums.RobotPlatform;
import com.loomlink.edge.domain.model.RobotMission;
import com.loomlink.edge.service.MissionPlanningService;
import com.loomlink.edge.service.RiskScoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Challenge 04 — AI-Enhanced Operational Robotics REST API.
 *
 * <p>Endpoints for mission planning, dispatch, monitoring, and risk assessment.
 * Designed for integration with Flotilla (fleet management) and ISAR (robot control).</p>
 */
@RestController
@RequestMapping("/api/v1/missions")
@Tag(name = "Challenge 04 — Mission Planning", description = "AI-driven robot inspection mission planning")
public class MissionController {

    private static final Logger log = LoggerFactory.getLogger(MissionController.class);

    private final MissionPlanningService planningService;

    public MissionController(MissionPlanningService planningService) {
        this.planningService = planningService;
    }

    // ── Mission Planning ───────────────────────────────────────────

    @PostMapping("/plan")
    @Operation(summary = "Generate AI-planned inspection mission",
            description = "Uses risk scoring to generate an optimized inspection route for a robot")
    public ResponseEntity<RobotMission> planMission(@RequestBody PlanMissionRequest request) {
        log.info("Mission plan request — robot: {}, platform: {}, area: {}",
                request.robotId(), request.platform(), request.facilityArea());

        RobotPlatform platform = RobotPlatform.valueOf(request.platform().toUpperCase());
        int maxWaypoints = request.maxWaypoints() != null ? request.maxWaypoints() : 15;

        RobotMission mission = planningService.planMission(
                request.robotId(), platform, request.facilityArea(), maxWaypoints);

        return ResponseEntity.ok(mission);
    }

    // ── Mission Dispatch ───────────────────────────────────────────

    @PostMapping("/{missionId}/dispatch")
    @Operation(summary = "Dispatch mission to robot via ISAR")
    public ResponseEntity<RobotMission> dispatchMission(
            @PathVariable UUID missionId,
            @RequestParam(defaultValue = "operator") String operatorId) {
        RobotMission mission = planningService.dispatchMission(missionId, operatorId);
        return ResponseEntity.ok(mission);
    }

    // ── Mission Queries ────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "List all missions")
    public ResponseEntity<List<RobotMission>> getAllMissions() {
        return ResponseEntity.ok(planningService.getAllMissions());
    }

    @GetMapping("/stats")
    @Operation(summary = "Mission planning dashboard statistics")
    public ResponseEntity<Map<String, Object>> getMissionStats() {
        return ResponseEntity.ok(planningService.getMissionStats());
    }

    // ── Risk Assessment ────────────────────────────────────────────

    @GetMapping("/risk-assessment")
    @Operation(summary = "Get risk scores for all equipment",
            description = "Returns prioritized list of equipment with risk scores for mission planning")
    public ResponseEntity<List<RiskScoringService.EquipmentRiskAssessment>> getRiskAssessments() {
        return ResponseEntity.ok(planningService.getRiskAssessments());
    }

    // ── ISAR Integration ───────────────────────────────────────────

    @GetMapping("/{missionId}/isar-definition")
    @Operation(summary = "Get ISAR-compatible mission definition",
            description = "Returns mission in ISAR format for direct robot dispatch")
    public ResponseEntity<Map<String, Object>> getIsarDefinition(@PathVariable UUID missionId) {
        List<RobotMission> missions = planningService.getAllMissions();
        RobotMission mission = missions.stream()
                .filter(m -> m.getId().equals(missionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Mission not found: " + missionId));

        return ResponseEntity.ok(buildIsarDefinition(mission));
    }

    // ── Robot Fleet ────────────────────────────────────────────────

    @GetMapping("/fleet")
    @Operation(summary = "Get available robot platforms and their capabilities")
    public ResponseEntity<List<Map<String, Object>>> getFleet() {
        List<Map<String, Object>> fleet = new java.util.ArrayList<>();
        for (RobotPlatform p : RobotPlatform.values()) {
            Map<String, Object> robot = new java.util.LinkedHashMap<>();
            robot.put("platform", p.name());
            robot.put("displayName", p.getDisplayName());
            robot.put("locomotion", p.getLocomotion());
            robot.put("hasThermal", p.hasThermal());
            robot.put("hasAcoustic", p.hasAcoustic());
            robot.put("hasCh4", p.hasCh4());
            robot.put("maxMissionMinutes", p.getMaxMissionMinutes());
            robot.put("supportsMultiModalFusion", p.supportsMultiModalFusion());
            fleet.add(robot);
        }
        return ResponseEntity.ok(fleet);
    }

    // ── Private Helpers ────────────────────────────────────────────

    private Map<String, Object> buildIsarDefinition(RobotMission mission) {
        Map<String, Object> isar = new java.util.LinkedHashMap<>();
        isar.put("id", mission.getId().toString());
        isar.put("name", mission.getMissionName());
        isar.put("robot_id", mission.getRobotId());
        isar.put("status", mission.getStatus().name());

        List<Map<String, Object>> tasks = new java.util.ArrayList<>();
        for (var wp : mission.getWaypoints()) {
            Map<String, Object> task = new java.util.LinkedHashMap<>();
            task.put("id", wp.getId().toString());
            task.put("tag", wp.getEquipmentTag());
            task.put("type", wp.getIsarTaskType());
            task.put("pose", Map.of(
                    "position", Map.of("x", 0.0, "y", 0.0, "z", 0.0),
                    "orientation", Map.of("x", 0.0, "y", 0.0, "z", 0.0, "w", 1.0)
            ));
            task.put("inspection_target", Map.of(
                    "tag", wp.getEquipmentTag(),
                    "sensors", wp.getRecommendedSensors().split(","),
                    "dwell_time_seconds", wp.getDwellSeconds()
            ));
            tasks.add(task);
        }
        isar.put("tasks", tasks);
        return isar;
    }

    // ── Request DTOs ───────────────────────────────────────────────

    public record PlanMissionRequest(
            String robotId,
            String platform,
            String facilityArea,
            Integer maxWaypoints
    ) {}
}
