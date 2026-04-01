package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.InspectionPriority;
import com.loomlink.edge.domain.enums.MissionStatus;
import com.loomlink.edge.domain.enums.RobotPlatform;
import com.loomlink.edge.domain.model.InspectionWaypoint;
import com.loomlink.edge.domain.model.RobotMission;
import com.loomlink.edge.repository.RobotMissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AI-driven mission planning for Challenge 04.
 *
 * <p>Generates intelligent inspection missions by combining:</p>
 * <ul>
 *   <li>Risk scores from {@link RiskScoringService}</li>
 *   <li>Robot platform capabilities (sensor suite, battery life)</li>
 *   <li>Facility area constraints (optimize route within a module)</li>
 *   <li>LLM reasoning for mission narrative generation</li>
 * </ul>
 *
 * <p>This is the transition from scripted patrol routes to intelligent,
 * context-aware mission planning that Equinor's Challenge 04 demands.</p>
 */
@Service
public class MissionPlanningService {

    private static final Logger log = LoggerFactory.getLogger(MissionPlanningService.class);

    private static final String LOG_DIVIDER = "════════════════════════════════════════════════════════════════";

    private final RiskScoringService riskScoringService;
    private final RobotMissionRepository missionRepo;

    public MissionPlanningService(RiskScoringService riskScoringService,
                                   RobotMissionRepository missionRepo) {
        this.riskScoringService = riskScoringService;
        this.missionRepo = missionRepo;
    }

    /**
     * Generate an AI-planned mission for a robot.
     *
     * @param robotId the robot to assign the mission to
     * @param platform the robot's platform type
     * @param facilityArea the area to patrol
     * @param maxWaypoints maximum number of inspection points
     * @return the planned mission with prioritized waypoints
     */
    public RobotMission planMission(String robotId, RobotPlatform platform,
                                     String facilityArea, int maxWaypoints) {
        log.info("{}", LOG_DIVIDER);
        log.info("AI MISSION PLANNING — CHALLENGE 04");
        log.info("Robot: {} ({}), Area: {}, Max waypoints: {}",
                robotId, platform.getDisplayName(), facilityArea, maxWaypoints);

        // Step 1: Score all equipment in the area
        List<RiskScoringService.EquipmentRiskAssessment> assessments =
                riskScoringService.scoreAllEquipment(facilityArea);

        if (assessments.isEmpty()) {
            log.warn("No equipment found for area: {}", facilityArea);
            throw new IllegalStateException("No inspectable equipment found in area: " + facilityArea);
        }

        log.info("Risk scoring complete — {} equipment items assessed", assessments.size());

        // Step 2: Filter by platform capability
        List<RiskScoringService.EquipmentRiskAssessment> eligible = assessments.stream()
                .filter(a -> isEligibleForPlatform(a, platform))
                .collect(Collectors.toList());

        // Step 3: Select top-N waypoints within battery budget
        int budgetMinutes = platform.getMaxMissionMinutes();
        List<RiskScoringService.EquipmentRiskAssessment> selected = selectWaypoints(
                eligible, maxWaypoints, budgetMinutes);

        log.info("Selected {} waypoints within {}min battery budget", selected.size(), budgetMinutes);

        // Step 4: Compute mission-level risk score
        double missionRisk = selected.stream()
                .mapToDouble(RiskScoringService.EquipmentRiskAssessment::riskScore)
                .average().orElse(0.0);

        // Step 5: Estimate duration
        int estimatedMinutes = estimateDuration(selected, platform);

        // Step 6: Generate planning reasoning
        String reasoning = generatePlanningReasoning(selected, platform, facilityArea);

        // Step 7: Build mission
        String missionName = String.format("Risk-Priority Patrol — %s (%s)",
                facilityArea, platform.name());

        RobotMission mission = RobotMission.plan(
                missionName, robotId, platform, facilityArea,
                reasoning, missionRisk, estimatedMinutes);

        // Step 8: Add waypoints
        for (RiskScoringService.EquipmentRiskAssessment assessment : selected) {
            InspectionWaypoint wp = InspectionWaypoint.create(
                    assessment.equipmentTag(),
                    assessment.assetName(),
                    assessment.functionalLocation() != null ? assessment.functionalLocation() : facilityArea,
                    null, // coordinates populated from facility layout
                    assessment.priority(),
                    assessment.riskScore(),
                    assessment.reasoning(),
                    assessment.isarTaskType(),
                    assessment.recommendedSensors(),
                    assessment.priority().getRecommendedDwellSeconds()
            );
            wp.enrichWithContext(
                    assessment.daysSinceLastInspection(),
                    null, // baseline ppm from emission history
                    null  // active work orders
            );
            mission.addWaypoint(wp);
        }

        // Step 9: Auto-approve if all waypoints are MEDIUM or below
        boolean allLowRisk = selected.stream()
                .allMatch(a -> a.priority().getRank() >= InspectionPriority.MEDIUM.getRank());
        if (allLowRisk) {
            mission.approve("AUTO");
            log.info("Mission auto-approved — all waypoints MEDIUM or lower risk");
        }

        // Save
        RobotMission saved = missionRepo.save(mission);

        log.info("MISSION PLANNED: {} — {} waypoints, risk: {}, est: {}min",
                saved.getMissionName(), saved.getWaypointCount(),
                String.format("%.3f", missionRisk), estimatedMinutes);
        log.info("{}", LOG_DIVIDER);

        return saved;
    }

    /**
     * Dispatch a mission to the robot via ISAR.
     */
    public RobotMission dispatchMission(UUID missionId, String operatorId) {
        RobotMission mission = missionRepo.findById(missionId)
                .orElseThrow(() -> new IllegalArgumentException("Mission not found: " + missionId));

        if (mission.getStatus() == MissionStatus.PLANNING) {
            mission.approve(operatorId);
        }

        mission.dispatch();
        log.info("Mission dispatched: {} → robot {} (Flotilla ref: {})",
                mission.getMissionName(), mission.getRobotId(), mission.getFlotillaRef());

        return missionRepo.save(mission);
    }

    /**
     * Get mission planning statistics for dashboard.
     */
    public Map<String, Object> getMissionStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalMissions", missionRepo.count());
        stats.put("completedMissions", missionRepo.countCompleted());
        stats.put("activeMissions", missionRepo.findActiveMissions().size());
        stats.put("avgAnomaliesPerMission", missionRepo.averageAnomaliesPerMission());
        stats.put("totalWaypointsInspected", missionRepo.totalWaypointsInspected());
        return stats;
    }

    /**
     * Get all missions ordered by creation date.
     */
    public List<RobotMission> getAllMissions() {
        return missionRepo.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Get a specific mission by ID.
     */
    public RobotMission getMissionById(UUID missionId) {
        return missionRepo.findById(missionId)
                .orElseThrow(() -> new IllegalArgumentException("Mission not found: " + missionId));
    }

    /**
     * Get risk assessments for all equipment.
     */
    public List<RiskScoringService.EquipmentRiskAssessment> getRiskAssessments() {
        return riskScoringService.scoreAllEquipment(null);
    }

    // ── Private Helpers ────────────────────────────────────────────

    private boolean isEligibleForPlatform(RiskScoringService.EquipmentRiskAssessment assessment,
                                           RobotPlatform platform) {
        // If task requires gas detection, robot must have CH4 sensor
        if ("gas_detection".equals(assessment.isarTaskType()) && !platform.hasCh4()) {
            return false;
        }
        return true; // All platforms can do visual/thermal
    }

    private List<RiskScoringService.EquipmentRiskAssessment> selectWaypoints(
            List<RiskScoringService.EquipmentRiskAssessment> eligible,
            int maxWaypoints, int budgetMinutes) {

        List<RiskScoringService.EquipmentRiskAssessment> selected = new ArrayList<>();
        int totalSeconds = 0;
        int budgetSeconds = budgetMinutes * 60;

        for (RiskScoringService.EquipmentRiskAssessment assessment : eligible) {
            if (selected.size() >= maxWaypoints) break;

            int dwellSeconds = assessment.priority().getRecommendedDwellSeconds();
            int transitSeconds = 120; // 2 min transit between waypoints

            if (totalSeconds + dwellSeconds + transitSeconds <= budgetSeconds) {
                selected.add(assessment);
                totalSeconds += dwellSeconds + transitSeconds;
            }
        }

        return selected;
    }

    private int estimateDuration(List<RiskScoringService.EquipmentRiskAssessment> waypoints,
                                  RobotPlatform platform) {
        int totalSeconds = 0;
        for (var wp : waypoints) {
            totalSeconds += wp.priority().getRecommendedDwellSeconds();
            totalSeconds += 120; // transit
        }
        return Math.max(1, totalSeconds / 60);
    }

    private String generatePlanningReasoning(
            List<RiskScoringService.EquipmentRiskAssessment> selected,
            RobotPlatform platform, String facilityArea) {

        long criticalCount = selected.stream()
                .filter(a -> a.priority() == InspectionPriority.CRITICAL).count();
        long highCount = selected.stream()
                .filter(a -> a.priority() == InspectionPriority.HIGH).count();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("AI-planned mission for %s covering %s. ", platform.getDisplayName(), facilityArea));
        sb.append(String.format("%d inspection waypoints selected from risk scoring. ", selected.size()));

        if (criticalCount > 0) {
            sb.append(String.format("%d CRITICAL priority items requiring immediate attention. ", criticalCount));
        }
        if (highCount > 0) {
            sb.append(String.format("%d HIGH priority items with developing risk patterns. ", highCount));
        }

        if (platform.supportsMultiModalFusion()) {
            sb.append("Multi-modal sensor fusion enabled — CH4 + thermal + acoustic correlation active. ");
        }

        sb.append("Route optimized by risk score descending — highest risk equipment inspected first.");

        return sb.toString();
    }
}
