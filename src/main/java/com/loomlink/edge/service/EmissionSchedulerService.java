package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.ComplianceStatus;
import com.loomlink.edge.domain.enums.EmissionClassification;
import com.loomlink.edge.domain.enums.SensorModality;
import com.loomlink.edge.domain.model.EmissionEvent;
import com.loomlink.edge.repository.EmissionEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Challenge 02 Live Feed Scheduler — Robot Emission Surveillance Simulation.
 *
 * <p>Simulates the real-world flow: Robot patrol detects gas anomaly (SARA format) →
 * Loom Link enriches with SAP context + multi-modal fusion → AI classifies emission →
 * Emission Reflector Gate validates → Compliance record or Exception Inbox.</p>
 *
 * <h3>15 Scenarios — Interleaved for 7.5-Minute Demo</h3>
 * <p>Pacing: <b>2 PASS → 1 REJECT → repeat</b>, finishing with 3 Experience Bank
 * cache hits. At 30s intervals, judges see a reject every ~90 seconds.</p>
 * <ul>
 *   <li><b>8 PASS scenarios</b> — clear emission detections with multi-modal corroboration
 *       that pass the Emission Reflector Gate (confidence ≥ 0.80). Covers fugitive leaks,
 *       planned venting, maintenance activity, sensor artifacts, and normal process.</li>
 *   <li><b>4 REJECT scenarios</b> — ambiguous or borderline readings that fall below
 *       the gate threshold. These go to the Emission Exception Inbox for engineer review.</li>
 *   <li><b>3 CACHE scenarios</b> — semantic variants that trigger Experience Bank hits.</li>
 * </ul>
 *
 * <h3>EU 2024/1787 Compliance</h3>
 * <p>FUGITIVE_EMISSION classifications auto-generate compliance records with:
 * leak rate quantification (kg/hr), 72-hour response timeline, ISO 14224 failure codes,
 * and SAP work order creation — demonstrating full regulatory coverage.</p>
 *
 * <h3>Architecture</h3>
 * <pre>
 *   Scheduler → EmissionEvent (PERSISTED — full lifecycle tracking)
 *            → SSE Live Feed (broadcast with source="SARA", type="EMISSION")
 * </pre>
 *
 * <p>No LLM inference is invoked. Scenario data is pre-computed to ensure
 * sub-second latency suitable for live demo.</p>
 */
@Service
public class EmissionSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(EmissionSchedulerService.class);

    private final LiveFeedEventBus eventBus;
    private final EmissionEventRepository emissionRepository;

    private final AtomicBoolean paused = new AtomicBoolean(false);
    private Instant pauseExpiresAt = null;
    private final AtomicInteger emissionCycleCounter = new AtomicInteger(0);

    @Value("${loomlink.live-feed.emission-enabled:true}")
    private boolean feedEnabled;

    @Value("${loomlink.emission.gate-threshold:0.80}")
    private double gateThreshold;

    // ══════════════════════════════════════════════════════════════════
    // 15 Emission Scenarios — Realistic North Sea Robot Patrol Data
    //
    // Equipment tags follow offshore naming conventions:
    //   FLG-xxxx = Flange       VLV-xxxx = Valve
    //   PMP-xxxx = Pump         CMP-xxxx = Compressor
    //   HX-xxxx  = Heat Exchanger  PRV-xxxx = Pressure Relief Valve
    //   SEP-xxxx = Separator
    //
    // Robot IDs: Spot-01, ANYmal-02, EXR-03 (Equinor fleet)
    // Mission IDs: MSN-2026-xxxx
    // Sensor modalities: CH4, THERMAL, ACOUSTIC, MULTI
    // ══════════════════════════════════════════════════════════════════

    private static final List<EmissionScenario> SCENARIOS = List.of(

        // ══════════════════════════════════════════════════════════════
        // DEMO PACING: 2 PASS → 1 REJECT → repeat (7.5-minute demo)
        //
        // At 30s intervals × 15 scenarios = ~7.5 minutes total.
        //
        //   #1-2   PASS     Fugitive emission + Planned venting
        //   #3     REJECT   Ambiguous single-sensor spike
        //   #4-5   PASS     Maintenance activity + Sensor artifact
        //   #6     REJECT   Borderline CH4 reading
        //   #7-8   PASS     Fugitive (thermal+acoustic) + Normal process
        //   #9     REJECT   Norwegian vague robot report
        //   #10-11 PASS     Fugitive (escalating) + Planned venting (PRV test)
        //   #12    REJECT   Incomplete multi-sensor data
        //   #13-15 CACHE    Experience Bank semantic hits
        // ══════════════════════════════════════════════════════════════

        // ── WAVE 1: 2× PASS ─────────────────────────────────────────

        // #1: FUGITIVE_EMISSION — Flange leak confirmed by 3 sensors
        new EmissionScenario("Spot-01", "MSN-2026-0401", "INS-001",
            "FLG-4401", "Module B / Deck 2 / Area North",
            SensorModality.CH4, 850.0, "ppm",
            12.0, "NW", 8.5,
            78.0, 32.0, 95.0, true, 3,
            false, false, false, 45, 80.0,
            EmissionClassification.FUGITIVE_EMISSION, 0.94, true,
            "CH4 at 850 ppm (10.6x baseline 80 ppm). Thermal delta +32°C confirms equipment hot-spot. Acoustic high-frequency hiss detected. 3 corroborating sensors. No active work orders. CONFIRMED FUGITIVE EMISSION requiring immediate response.",
            2.55, "WO-EM-2026-0001", "ELP", ComplianceStatus.WORK_ORDER_CREATED,
            0, "FIRST_DETECTION", false),

        // #2: PLANNED_VENTING — Valve during turnaround
        new EmissionScenario("ANYmal-02", "MSN-2026-0402", "INS-002",
            "VLV-2203", "Module C / Deck 3 / Area South",
            SensorModality.CH4, 420.0, "ppm",
            8.0, "SE", 12.0,
            65.0, 2.0, 78.0, false, 2,
            true, false, true, 15, 25.0,
            EmissionClassification.PLANNED_VENTING, 0.91, true,
            "CH4 elevated at 420 ppm but SAP confirms turnaround active with work order for controlled depressurization. Thermal normal, no acoustic leak signature. Consistent with authorized venting operations during Q1 turnaround.",
            0.0, null, null, ComplianceStatus.CLASSIFIED,
            0, "FIRST_DETECTION", false),

        // ── WAVE 1: 1× REJECT ───────────────────────────────────────

        // #3: REJECT — Ambiguous single-sensor reading
        new EmissionScenario("EXR-03", "MSN-2026-0403", "INS-003",
            "PMP-1105", "Module A / Deck 1 / Area East",
            SensorModality.CH4, 35.0, "ppm",
            22.0, "W", 15.0,
            null, null, null, null, 1,
            false, false, false, 90, 28.0,
            EmissionClassification.UNKNOWN, 0.42, false,
            "Single CH4 reading 35 ppm (1.25x baseline). No thermal or acoustic corroboration. High wind speed (22 km/h) may cause sensor drift. Insufficient multi-modal data for reliable classification.",
            0.0, null, null, ComplianceStatus.DETECTED,
            0, "FIRST_DETECTION", false),

        // ── WAVE 2: 2× PASS ─────────────────────────────────────────

        // #4: MAINTENANCE_ACTIVITY — Pump with active work order
        new EmissionScenario("Spot-01", "MSN-2026-0404", "INS-004",
            "PMP-3301", "Module D / Deck 2 / Area West",
            SensorModality.CH4, 180.0, "ppm",
            6.0, "N", 10.0,
            52.0, 8.0, 72.0, false, 2,
            true, true, false, 5, 45.0,
            EmissionClassification.MAINTENANCE_ACTIVITY, 0.88, true,
            "CH4 at 180 ppm during active pump seal replacement (SAP WO confirmed). Maintenance crew on-site. Moderate thermal delta (+8°C) consistent with open equipment. Expected emission during authorized work.",
            0.0, null, null, ComplianceStatus.CLASSIFIED,
            0, "FIRST_DETECTION", false),

        // #5: SENSOR_ARTIFACT — Environmental interference
        new EmissionScenario("ANYmal-02", "MSN-2026-0405", "INS-005",
            "CMP-3302", "Module D / Deck 3 / Area North",
            SensorModality.CH4, 18.0, "ppm",
            28.0, "S", 4.0,
            null, null, null, null, 1,
            false, false, false, 3, 15.0,
            EmissionClassification.SENSOR_ARTIFACT, 0.82, true,
            "Isolated CH4 spike to 18 ppm (1.2x baseline) with no thermal or acoustic corroboration. Extreme wind (28 km/h) and low ambient temperature suggest environmental interference. Equipment recently maintained (3 days ago). Single transient event.",
            0.0, null, null, ComplianceStatus.CLASSIFIED,
            0, "FIRST_DETECTION", false),

        // ── WAVE 2: 1× REJECT ───────────────────────────────────────

        // #6: REJECT — Borderline reading, conflicting signals
        new EmissionScenario("Spot-01", "MSN-2026-0406", "INS-006",
            "SEP-1201", "Module A / Deck 2 / Area South",
            SensorModality.CH4, 65.0, "ppm",
            10.0, "NE", 11.0,
            42.0, 4.0, 68.0, false, 2,
            false, false, false, 60, 50.0,
            EmissionClassification.UNKNOWN, 0.58, false,
            "CH4 at 65 ppm (1.3x baseline). Minor thermal elevation but no acoustic leak signature. Conflicting signals — could be minor process variation or early-stage leak. Insufficient confidence for automated classification.",
            0.0, null, null, ComplianceStatus.DETECTED,
            0, "STABLE", false),

        // ── WAVE 3: 2× PASS ─────────────────────────────────────────

        // #7: FUGITIVE_EMISSION — Valve leak confirmed by thermal + acoustic
        new EmissionScenario("EXR-03", "MSN-2026-0407", "INS-007",
            "VLV-1102", "Module B / Deck 1 / Area West",
            SensorModality.MULTI, 620.0, "ppm",
            7.0, "E", 9.5,
            72.0, 25.0, 88.0, true, 3,
            false, false, false, 75, 60.0,
            EmissionClassification.FUGITIVE_EMISSION, 0.92, true,
            "Multi-modal detection: CH4 at 620 ppm (10.3x baseline). Thermal delta +25°C and acoustic hiss signature confirmed. 3 corroborating sensors converge on valve body leak. No active maintenance. 75 days since last service increases failure probability.",
            1.86, "WO-EM-2026-0002", "ELP", ComplianceStatus.WORK_ORDER_CREATED,
            0, "FIRST_DETECTION", false),

        // #8: NORMAL_PROCESS — Within expected baseline
        new EmissionScenario("ANYmal-02", "MSN-2026-0408", "INS-008",
            "HX-2001", "Module C / Deck 2 / Area East",
            SensorModality.CH4, 12.0, "ppm",
            5.0, "SW", 13.0,
            48.0, 1.0, 55.0, false, 2,
            false, false, false, 20, 10.0,
            EmissionClassification.NORMAL_PROCESS, 0.87, true,
            "CH4 at 12 ppm within 1.2x baseline (10 ppm) for heat exchanger area. Thermal and acoustic readings nominal. Stable 7-day pattern. Natural process variation within expected operational envelope.",
            0.0, null, null, ComplianceStatus.CLASSIFIED,
            0, "STABLE", false),

        // ── WAVE 3: 1× REJECT ───────────────────────────────────────

        // #9: REJECT — Norwegian vague robot report
        new EmissionScenario("Spot-01", "MSN-2026-0409", "INS-009",
            "FLG-5502", "Modul E / Dekk 1 / Område Nord",
            SensorModality.CH4, 45.0, "ppm",
            14.0, "N", 7.0,
            38.0, 3.0, null, null, 1,
            false, false, false, 40, 35.0,
            EmissionClassification.UNKNOWN, 0.48, false,
            "Norwegian location data. CH4 at 45 ppm (1.3x baseline). Minor thermal elevation but incomplete acoustic data — sensor malfunction during patrol. Insufficient multi-modal corroboration for reliable emission classification.",
            0.0, null, null, ComplianceStatus.DETECTED,
            0, "FIRST_DETECTION", false),

        // ── WAVE 4: 2× PASS ─────────────────────────────────────────

        // #10: FUGITIVE_EMISSION — Escalating trend over 7 days
        new EmissionScenario("EXR-03", "MSN-2026-0410", "INS-010",
            "FLG-6601", "Module F / Deck 2 / Area South",
            SensorModality.CH4, 380.0, "ppm",
            9.0, "W", 10.0,
            58.0, 18.0, 82.0, true, 3,
            false, false, false, 120, 55.0,
            EmissionClassification.FUGITIVE_EMISSION, 0.93, true,
            "ESCALATING trend: CH4 readings climbed from 95 ppm to 380 ppm over 7 days (6.9x baseline). Multi-modal confirmation: thermal delta +18°C, acoustic leak signature positive. Equipment aged (120 days since maintenance). Developing flange leak requiring urgent response.",
            1.14, "WO-EM-2026-0003", "ELP", ComplianceStatus.WORK_ORDER_CREATED,
            4, "ESCALATING", false),

        // #11: PLANNED_VENTING — Pressure relief valve test
        new EmissionScenario("ANYmal-02", "MSN-2026-0411", "INS-011",
            "PRV-3301", "Module D / Deck 3 / Area East",
            SensorModality.CH4, 950.0, "ppm",
            3.0, "SE", 14.0,
            95.0, 45.0, 105.0, false, 2,
            true, false, false, 10, 30.0,
            EmissionClassification.PLANNED_VENTING, 0.89, true,
            "High CH4 (950 ppm) but SAP confirms active work order for scheduled PRV pop-test. Extreme thermal reading expected during pressure relief. No acoustic leak signature (burst, not leak). Authorized safety valve testing.",
            0.0, null, null, ComplianceStatus.CLASSIFIED,
            0, "FIRST_DETECTION", false),

        // ── WAVE 4: 1× REJECT ───────────────────────────────────────

        // #12: REJECT — Incomplete multi-sensor data, robot sensor failure
        new EmissionScenario("Spot-01", "MSN-2026-0412", "INS-012",
            "CMP-4401", "Module E / Deck 2 / Area West",
            SensorModality.CH4, 88.0, "ppm",
            16.0, "NW", 6.0,
            null, null, null, null, 1,
            false, false, false, 55, 40.0,
            EmissionClassification.UNKNOWN, 0.51, false,
            "CH4 at 88 ppm (2.2x baseline) but thermal and acoustic sensors offline during this patrol segment (robot sensor fault). Cannot corroborate with multi-modal data. Classification unreliable without full sensor suite.",
            0.0, null, null, ComplianceStatus.DETECTED,
            0, "FIRST_DETECTION", false),

        // ── FINALE: EXPERIENCE BANK CACHE HITS (3) ──────────────────

        // #13: Semantic variant of #1 (FLG-4401 fugitive emission)
        new EmissionScenario("Spot-01", "MSN-2026-0413", "INS-013",
            "FLG-4401", "Module B / Deck 2 / Area North",
            SensorModality.CH4, 920.0, "ppm",
            10.0, "NW", 9.0,
            82.0, 35.0, 98.0, true, 3,
            false, false, false, 46, 80.0,
            EmissionClassification.FUGITIVE_EMISSION, 0.94, true,
            "[EXPERIENCE BANK HIT — similarity 96.8%] Same equipment FLG-4401, same failure pattern. Semantic cache bypassed LLM inference. CH4 elevated with multi-modal confirmation. Previous classification reused.",
            2.76, "WO-EM-2026-0004", "ELP", ComplianceStatus.WORK_ORDER_CREATED,
            1, "ESCALATING", true),

        // #14: Semantic variant of #4 (PMP-3301 maintenance activity)
        new EmissionScenario("ANYmal-02", "MSN-2026-0414", "INS-014",
            "PMP-3301", "Module D / Deck 2 / Area West",
            SensorModality.CH4, 165.0, "ppm",
            7.0, "N", 11.0,
            50.0, 7.0, 70.0, false, 2,
            true, true, false, 6, 45.0,
            EmissionClassification.MAINTENANCE_ACTIVITY, 0.88, true,
            "[EXPERIENCE BANK HIT — similarity 95.4%] Same equipment PMP-3301 during ongoing maintenance. Semantic cache matched previous classification. Active work order confirmed.",
            0.0, null, null, ComplianceStatus.CLASSIFIED,
            0, "STABLE", true),

        // #15: Semantic variant of #7 (VLV-1102 fugitive emission)
        new EmissionScenario("EXR-03", "MSN-2026-0415", "INS-015",
            "VLV-1102", "Module B / Deck 1 / Area West",
            SensorModality.MULTI, 680.0, "ppm",
            8.0, "E", 10.0,
            75.0, 28.0, 90.0, true, 3,
            false, false, false, 76, 60.0,
            EmissionClassification.FUGITIVE_EMISSION, 0.92, true,
            "[EXPERIENCE BANK HIT — similarity 97.1%] Same valve VLV-1102, same multi-modal leak pattern. pgvector semantic cache matched previous classification with high similarity. Cross-challenge knowledge sharing active.",
            2.04, "WO-EM-2026-0005", "ELP", ComplianceStatus.WORK_ORDER_CREATED,
            1, "ESCALATING", true)
    );

    // ══════════════════════════════════════════════════════════════════

    public EmissionSchedulerService(
            LiveFeedEventBus eventBus,
            EmissionEventRepository emissionRepository) {
        this.eventBus = eventBus;
        this.emissionRepository = emissionRepository;
    }

    // ── Pause / Resume Control ────────────────────────────────────

    public void pause(int minutes) {
        paused.set(true);
        pauseExpiresAt = Instant.now().plus(minutes, ChronoUnit.MINUTES);
        log.info("Emission Feed PAUSED for {} minutes (expires at {})", minutes, pauseExpiresAt);
        eventBus.publish("SARA", "EMISSION_FEED_PAUSED", "SARA Feed Paused",
                "SARA emission surveillance paused for " + minutes + " minutes.",
                Map.of("pauseMinutes", minutes, "expiresAt", pauseExpiresAt.toString()));
    }

    public void resume() {
        paused.set(false);
        pauseExpiresAt = null;
        log.info("Emission Feed RESUMED");
        eventBus.publish("SARA", "EMISSION_FEED_RESUMED", "SARA Feed Resumed",
                "SARA robot emission surveillance resumed. Patrol simulation active.", null);
    }

    public boolean isPaused() {
        if (paused.get() && pauseExpiresAt != null && Instant.now().isAfter(pauseExpiresAt)) {
            resume();
            return false;
        }
        return paused.get();
    }

    public void resetDemo() {
        log.info("════════════════════════════════════════════════════════════════");
        log.info("  EMISSION DEMO RESET — Clearing emission surveillance data");
        log.info("════════════════════════════════════════════════════════════════");
        emissionRepository.deleteAll();
        emissionCycleCounter.set(0);
        log.info("Emission demo reset complete. Scheduler will re-process all {} scenarios.", SCENARIOS.size());
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", feedEnabled);
        status.put("paused", isPaused());
        status.put("pauseExpiresAt", pauseExpiresAt != null ? pauseExpiresAt.toString() : null);
        status.put("emissionCycleCount", emissionCycleCounter.get());
        status.put("totalScenarios", SCENARIOS.size());
        status.put("currentScenarioIndex", emissionCycleCounter.get() % SCENARIOS.size());
        status.put("activeSubscribers", eventBus.getSubscriberCount());
        return status;
    }

    // ── Utility ──────────────────────────────────────────────────

    private static long demoLatencyFresh() {
        return ThreadLocalRandom.current().nextLong(400, 900);
    }

    private static long demoLatencyCache() {
        return ThreadLocalRandom.current().nextLong(30, 95);
    }

    // ══════════════════════════════════════════════════════════════
    // Robot Patrol Loop — Every 30 seconds
    //
    // Flow: Scenario → EmissionEvent (DB) → SSE Live Feed
    // ══════════════════════════════════════════════════════════════

    @Scheduled(fixedDelayString = "${loomlink.live-feed.emission-interval-ms:30000}", initialDelay = 5000)
    public void emissionPatrolLoop() {
        if (!feedEnabled || isPaused()) return;

        int index = emissionCycleCounter.getAndIncrement() % SCENARIOS.size();
        EmissionScenario s = SCENARIOS.get(index);

        // Skip already-processed scenarios (ddl-auto: update preserves data)
        List<EmissionEvent> existing = emissionRepository.findByEquipmentTag(s.equipmentTag);
        boolean alreadyProcessed = existing.stream()
                .anyMatch(e -> s.inspectionId.equals(e.getInspectionId()));
        if (alreadyProcessed) {
            log.debug("Emission #{}: {} already processed, skipping", index + 1, s.inspectionId);
            return;
        }

        long pipelineStart = System.currentTimeMillis();

        try {
            // ── 1. Create EmissionEvent from scenario ──────────────────
            EmissionEvent event = EmissionEvent.fromSensorReading(
                    s.inspectionId, s.missionId, s.robotId,
                    s.equipmentTag, s.locationArea,
                    s.sensorModality, s.rawReading, s.readingUnit,
                    Instant.now());

            // ── 2. Enrich with environmental data ──────────────────────
            event.enrichWithEnvironmentalData(s.windSpeedKmh, s.windDirection, s.ambientTempCelsius);

            // ── 3. Enrich with multi-modal fusion ──────────────────────
            if (s.thermalReadingCelsius != null) {
                event.enrichWithMultiModalData(
                        s.thermalReadingCelsius, s.thermalDeltaCelsius,
                        s.acousticReadingDb, s.acousticLeakSignature,
                        s.corroboratingSensors);
            }

            // ── 4. Enrich with SAP context ─────────────────────────────
            String workOrderJson = s.sapMaintenanceActive
                    ? "{\"orders\":[{\"order_id\":\"WO-MAINT-" + s.equipmentTag + "\",\"status\":\"ACTIVE\"}]}"
                    : "{\"orders\":[]}";
            event.enrichWithSapContext(workOrderJson, s.sapMaintenanceActive,
                    s.sapTurnaroundActive, s.daysSinceLastMaintenance, s.historicalBaselinePpm);

            // ── 5. Enrich with trend data ──────────────────────────────
            event.enrichWithTrendData(s.previousDetections7d, s.trendDirection);

            // ── 6. Apply classification ────────────────────────────────
            long inferenceLatency = s.experienceBankHit ? demoLatencyCache() : demoLatencyFresh();
            event.classify(s.classification, s.confidence, s.reasoning,
                    s.experienceBankHit ? "mistral:7b (cached)" : "mistral:7b",
                    inferenceLatency);

            // ── 7. Apply Reflector Gate ────────────────────────────────
            String gateReasoning;
            if (s.gatePassed) {
                gateReasoning = String.format(
                        "EMISSION GATE PASSED — Confidence %.1f%% meets threshold %.0f%%. Classification accepted.",
                        s.confidence * 100, gateThreshold * 100);
            } else {
                gateReasoning = String.format(
                        "EMISSION GATE REJECTED — Confidence %.1f%% below threshold %.0f%%. Routed to Emission Exception Inbox.",
                        s.confidence * 100, gateThreshold * 100);
            }
            event.applyGateResult(s.gatePassed, gateThreshold, gateReasoning);

            // ── 8. Leak quantification (FUGITIVE_EMISSION only) ───────
            if (s.estimatedLeakRateKgHr > 0) {
                event.quantifyLeakRate(s.estimatedLeakRateKgHr, null);
            }

            // ── 9. Compliance actions ──────────────────────────────────
            if (s.gatePassed && s.classification.requiresComplianceRecord()) {
                event.markComplianceDocumented();
                if (s.workOrderNumber != null) {
                    event.markWorkOrderCreated(s.workOrderNumber, s.iso14224FailureCode);
                }
            }

            // ── 10. Set review status based on gate result ─────────────
            // gatePassed events stay as "PENDING" but are auto-confirmed in production
            // gate rejected events need human review

            long totalLatency = System.currentTimeMillis() - pipelineStart + inferenceLatency;

            // ── 11. PERSIST ────────────────────────────────────────────
            emissionRepository.save(event);

            // ── 12. Publish to SSE Live Feed ───────────────────────────
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("eventId", event.getId() != null ? event.getId().toString() : null);
            metadata.put("robotId", s.robotId);
            metadata.put("missionId", s.missionId);
            metadata.put("inspectionId", s.inspectionId);
            metadata.put("equipmentTag", s.equipmentTag);
            metadata.put("locationArea", s.locationArea);
            metadata.put("sensorModality", s.sensorModality.name());
            metadata.put("rawReading", s.rawReading);
            metadata.put("readingUnit", s.readingUnit);
            metadata.put("classification", s.classification.name());
            metadata.put("confidence", s.confidence);
            metadata.put("gatePassed", s.gatePassed);
            metadata.put("gateReasoning", gateReasoning);
            metadata.put("reasoning", s.reasoning);
            metadata.put("estimatedLeakRateKgHr", s.estimatedLeakRateKgHr);
            metadata.put("corroboratingSensors", s.corroboratingSensors);
            metadata.put("trendDirection", s.trendDirection);
            metadata.put("complianceStatus", event.getComplianceStatus().name());
            metadata.put("workOrderNumber", s.workOrderNumber);
            metadata.put("pipelineLatencyMs", totalLatency);
            metadata.put("inferenceLatencyMs", inferenceLatency);
            metadata.put("cacheHit", s.experienceBankHit);
            metadata.put("scenarioIndex", index + 1);
            metadata.put("totalScenarios", SCENARIOS.size());

            String cacheTag = s.experienceBankHit ? " [CACHE]" : "";
            String title;
            if (s.gatePassed) {
                title = String.format("SARA %s → %s (%s) — EMISSION GATE PASSED %.0f%%%s [%dms]",
                        s.robotId, s.equipmentTag, s.classification.name(),
                        s.confidence * 100, cacheTag, totalLatency);
            } else {
                title = String.format("SARA %s → %s — EMISSION GATE REJECTED %.0f%%%s [%dms]",
                        s.robotId, s.equipmentTag,
                        s.confidence * 100, cacheTag, totalLatency);
            }

            eventBus.publish("SARA", "EMISSION", title, s.reasoning, metadata);

            log.info("Emission #{}/{}: {} → {} [{}] ({}) — {} in {}ms",
                    index + 1, SCENARIOS.size(),
                    s.robotId, s.equipmentTag,
                    s.sensorModality, s.classification.name(),
                    s.gatePassed ? "PASS → COMPLIANCE" : "REJECT → EMISSION INBOX",
                    totalLatency);

        } catch (Exception e) {
            log.error("Emission #{}: error for {} — {}", index + 1, s.inspectionId, e.getMessage(), e);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("equipmentTag", s.equipmentTag);
            metadata.put("inspectionId", s.inspectionId);
            metadata.put("confidence", s.confidence);
            metadata.put("gatePassed", s.gatePassed);
            metadata.put("error", e.getMessage());

            eventBus.publish("SARA", "EMISSION",
                    String.format("SARA %s → %s — PIPELINE ERROR", s.robotId, s.equipmentTag),
                    "Emission pipeline error: " + e.getMessage(), metadata);
        }
    }

    // ── Scenario Record ──────────────────────────────────────────

    record EmissionScenario(
            String robotId,
            String missionId,
            String inspectionId,
            String equipmentTag,
            String locationArea,
            SensorModality sensorModality,
            double rawReading,
            String readingUnit,
            Double windSpeedKmh,
            String windDirection,
            Double ambientTempCelsius,
            Double thermalReadingCelsius,
            Double thermalDeltaCelsius,
            Double acousticReadingDb,
            Boolean acousticLeakSignature,
            int corroboratingSensors,
            boolean sapActiveWorkOrder,
            boolean sapMaintenanceActive,
            boolean sapTurnaroundActive,
            int daysSinceLastMaintenance,
            Double historicalBaselinePpm,
            EmissionClassification classification,
            double confidence,
            boolean gatePassed,
            String reasoning,
            double estimatedLeakRateKgHr,
            String workOrderNumber,
            String iso14224FailureCode,
            ComplianceStatus complianceStatus,
            int previousDetections7d,
            String trendDirection,
            boolean experienceBankHit) {}
}
