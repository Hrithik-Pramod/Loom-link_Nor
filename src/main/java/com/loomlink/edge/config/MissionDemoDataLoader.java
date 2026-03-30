package com.loomlink.edge.config;

import com.loomlink.edge.domain.enums.InspectionPriority;
import com.loomlink.edge.domain.enums.MissionStatus;
import com.loomlink.edge.domain.enums.RobotPlatform;
import com.loomlink.edge.domain.model.InspectionWaypoint;
import com.loomlink.edge.domain.model.RobotMission;
import com.loomlink.edge.repository.RobotMissionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds robot mission data for the Stavanger demo.
 *
 * Creates one completed demo mission (the patrol from Challenge 02 story)
 * and one planned mission ready for live dispatch during the demo.
 */
@Component
@Order(300)
public class MissionDemoDataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MissionDemoDataLoader.class);

    private final RobotMissionRepository repository;

    @Value("${loomlink.mission.seed-demo-data:true}")
    private boolean seedDemoData;

    public MissionDemoDataLoader(RobotMissionRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        if (!seedDemoData) {
            log.info("Mission demo data seeding disabled");
            return;
        }

        if (repository.count() > 0) {
            log.info("Mission data already populated ({} missions), skipping seed", repository.count());
            return;
        }

        log.info("════════════════════════════════════════════════════════════════");
        log.info("  SEEDING MISSION DEMO DATA FOR STAVANGER DEMO");
        log.info("════════════════════════════════════════════════════════════════");

        seedCompletedMission();
        seedPlannedMission();

        log.info("  Mission demo data seeded: {} missions total", repository.count());
        log.info("════════════════════════════════════════════════════════════════");
    }

    /**
     * The completed mission from the Challenge 02 story.
     * Robot Spot-01 patrolled Module B and found the 4 demo scenario events.
     */
    private void seedCompletedMission() {
        RobotMission m = RobotMission.plan(
                "Risk-Priority Patrol — Module B (SPOT)",
                "spot-001",
                RobotPlatform.SPOT,
                "Module B Deck 2",
                "AI-planned mission for Boston Dynamics Spot covering Module B Deck 2. " +
                "4 inspection waypoints selected from risk scoring. " +
                "1 CRITICAL priority item (FLG-4401, escalating emission trend 200→500→850 ppm). " +
                "Multi-modal sensor fusion enabled — CH4 + thermal + acoustic correlation active. " +
                "Route optimized by risk score descending.",
                0.72,
                45
        );

        // Waypoint 1: FLG-4401 — CRITICAL (fugitive emission)
        InspectionWaypoint wp1 = InspectionWaypoint.create(
                "FLG-4401", "Flange Connection B2-North",
                "Module B Deck 2", "62.7341°N, 6.1521°E",
                InspectionPriority.CRITICAL, 0.94,
                "CRITICAL: 2 fugitive emission(s) in last 7 days. ESCALATING emission trend detected. " +
                "Overdue: 14 days since last inspection. ",
                "gas_detection", "CH4,THERMAL,ACOUSTIC", 300
        );
        wp1.enrichWithContext(14, 150.0, null);
        wp1.markCompleted("FUGITIVE_EMISSION confirmed — 850 ppm CH4, thermal +12°C, acoustic hiss. " +
                "Estimated leak rate 2.3 kg/hr. EU 2024/1787 compliance record generated. " +
                "SAP work order WO-EMISSION-A7F3B21E created.", true, null);
        m.addWaypoint(wp1);

        // Waypoint 2: VLV-2203 — HIGH (maintenance activity)
        InspectionWaypoint wp2 = InspectionWaypoint.create(
                "VLV-2203", "Isolation Valve C3",
                "Module C Deck 3", "62.7342°N, 6.1518°E",
                InspectionPriority.HIGH, 0.65,
                "Active work order PM03-28847 for valve repacking. Process control valve. ",
                "gas_detection", "CH4,THERMAL", 180
        );
        wp2.enrichWithContext(2, 30.0, "PM03-28847 (Valve repacking)");
        wp2.markCompleted("MAINTENANCE_ACTIVITY — 450 ppm CH4 correlated with active valve repacking WO. " +
                "Alert suppressed. No operator interruption.", false, null);
        m.addWaypoint(wp2);

        // Waypoint 3: PST-1105 — MEDIUM (sensor artifact)
        InspectionWaypoint wp3 = InspectionWaypoint.create(
                "PST-1105", "Pipe Support A1-South",
                "Module A Deck 1", "62.7340°N, 6.1525°E",
                InspectionPriority.MEDIUM, 0.42,
                "3 days since last inspection. ",
                "gas_detection", "CH4,THERMAL", 120
        );
        wp3.enrichWithContext(3, 20.0, null);
        wp3.markCompleted("SENSOR_ARTIFACT — 180 ppm transient spike. High wind 28 km/h. " +
                "No thermal or acoustic corroboration. Dismissed.", false, null);
        m.addWaypoint(wp3);

        // Waypoint 4: PRV-3302 — MEDIUM (planned venting)
        InspectionWaypoint wp4 = InspectionWaypoint.create(
                "PRV-3302", "Pressure Relief Valve D2",
                "Module D Deck 2", "62.7339°N, 6.1523°E",
                InspectionPriority.MEDIUM, 0.38,
                "Scheduled turnaround depressurization active. Pressure vessel. ",
                "gas_detection", "CH4,THERMAL", 120
        );
        wp4.enrichWithContext(0, 100.0, "TURN-Q1-2026 (Q1 Turnaround depressurization)");
        wp4.markCompleted("PLANNED_VENTING — 2100 ppm during authorized Q1 depressurization. " +
                "Logged for emission inventory. No alarm.", false, null);
        m.addWaypoint(wp4);

        m.approve("lars.henriksen");
        m.dispatch();
        m.startExecution();
        m.completeWaypoint(); // wp1
        m.recordDetection(true); // FLG-4401 anomaly
        m.completeWaypoint(); // wp2
        m.recordDetection(false); // VLV-2203 suppressed
        m.completeWaypoint(); // wp3
        m.completeWaypoint(); // wp4
        m.complete();

        repository.save(m);
        log.info("  Seeded: Completed Module B patrol (4 waypoints, 1 anomaly confirmed)");
    }

    /**
     * A planned mission ready for live dispatch during the demo.
     */
    private void seedPlannedMission() {
        RobotMission m = RobotMission.plan(
                "AI Risk Patrol — Module A (ANYmal)",
                "anymal-001",
                RobotPlatform.ANYMAL,
                "Module A Deck 1",
                "AI-planned mission for ANYbotics ANYmal covering Module A Deck 1. " +
                "3 inspection waypoints selected. Focus on rotating equipment health monitoring. " +
                "Multi-modal fusion active for vibration + thermal correlation.",
                0.55,
                35
        );

        InspectionWaypoint wp1 = InspectionWaypoint.create(
                "P-1001A", "Seawater Lift Pump A",
                "Module A Deck 1", "62.7340°N, 6.1526°E",
                InspectionPriority.HIGH, 0.72,
                "High-value rotating equipment. Vibration trending upward. 21 days since last inspection. ",
                "vibration_thermal_scan", "THERMAL,ACOUSTIC,VISUAL", 180
        );
        wp1.enrichWithContext(21, null, null);
        m.addWaypoint(wp1);

        InspectionWaypoint wp2 = InspectionWaypoint.create(
                "C-2001", "Gas Compressor Stage 1",
                "Module A Deck 1", "62.7340°N, 6.1527°E",
                InspectionPriority.HIGH, 0.68,
                "High-value rotating equipment. Bearing temperature elevated. ",
                "vibration_thermal_scan", "THERMAL,ACOUSTIC", 180
        );
        wp2.enrichWithContext(14, null, null);
        m.addWaypoint(wp2);

        InspectionWaypoint wp3 = InspectionWaypoint.create(
                "HX-3001", "Process Heat Exchanger",
                "Module A Deck 1", "62.7341°N, 6.1526°E",
                InspectionPriority.MEDIUM, 0.45,
                "Routine thermal scan. 30 days since last inspection. ",
                "thermal_scan", "THERMAL,VISUAL", 120
        );
        wp3.enrichWithContext(30, null, null);
        m.addWaypoint(wp3);

        m.approve("AUTO");

        repository.save(m);
        log.info("  Seeded: Planned Module A patrol (3 waypoints, ready for dispatch)");
    }
}
