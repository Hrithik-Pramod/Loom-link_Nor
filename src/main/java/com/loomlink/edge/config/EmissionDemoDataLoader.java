package com.loomlink.edge.config;

import com.loomlink.edge.domain.enums.ComplianceStatus;
import com.loomlink.edge.domain.enums.EmissionClassification;
import com.loomlink.edge.domain.enums.SensorModality;
import com.loomlink.edge.domain.model.EmissionEvent;
import com.loomlink.edge.repository.EmissionEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Seeds the emission_events table with realistic demo data for the Stavanger demo.
 *
 * <p>Creates four demo scenario events from the Challenge 02 story:</p>
 * <ol>
 *   <li>VLV-2203: MAINTENANCE_ACTIVITY — 450 ppm during active valve work order</li>
 *   <li>PST-1105: SENSOR_ARTIFACT — 180 ppm transient spike, high wind</li>
 *   <li>PRV-3302: PLANNED_VENTING — 2100 ppm authorized blowdown</li>
 *   <li>FLG-4401: FUGITIVE_EMISSION — 850 ppm with thermal + acoustic confirmation</li>
 * </ol>
 *
 * <p>Additionally, two historical events at FLG-4401 demonstrate the escalating
 * trend (200 ppm → 500 ppm → 850 ppm) that led to the fugitive emission detection.</p>
 *
 * <p>Only runs when {@code loomlink.emission.seed-demo-data=true} (default)
 * and the emission_events table is empty.</p>
 *
 * @see EmissionEvent#fromSensorReading
 */
@Component
@Order(200)
public class EmissionDemoDataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EmissionDemoDataLoader.class);

    private final EmissionEventRepository repository;

    @Value("${loomlink.emission.seed-demo-data:true}")
    private boolean seedDemoData;

    public EmissionDemoDataLoader(EmissionEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        if (!seedDemoData) {
            log.info("Emission demo data seeding disabled (loomlink.emission.seed-demo-data=false)");
            return;
        }

        if (repository.count() > 0) {
            log.info("Emission events table already populated ({} events), skipping seed", repository.count());
            return;
        }

        log.info("════════════════════════════════════════════════════════════════");
        log.info("  SEEDING EMISSION DEMO DATA FOR STAVANGER DEMO");
        log.info("════════════════════════════════════════════════════════════════");

        seedHistoricalEvents();
        seedDemoScenarios();

        log.info("  Emission demo data seeded: {} events total", repository.count());
        log.info("════════════════════════════════════════════════════════════════");
    }

    /**
     * Seed 2 historical events at FLG-4401 to show escalating trend.
     */
    private void seedHistoricalEvents() {
        Instant now = Instant.now();

        // ── Historical 1: 7 days ago — 200 ppm (NORMAL_PROCESS) ─────────
        EmissionEvent h1 = EmissionEvent.fromSensorReading(
                "INSP-HIST-001",
                "PATROL-2026-Q1-080",
                "spot-001",
                "FLG-4401",
                "Module B Deck 2",
                SensorModality.CH4,
                200.0,
                "ppm",
                now.minus(7, ChronoUnit.DAYS));
        h1.enrichWithEnvironmentalData(12.0, "NW", 8.0);
        h1.enrichWithSapContext("{\"orders\":[]}", false, false, 45, 50.0);
        h1.classify(EmissionClassification.NORMAL_PROCESS, 0.88,
                "Reading 200 ppm within baseline envelope (50-250 ppm) for flange equipment. " +
                "No thermal anomaly, no acoustic leak signature. Normal process variation.",
                "mistral:7b", 3200);
        h1.applyGateResult(true, 0.80, "Confidence 0.88 exceeds gate threshold 0.80");
        h1.enrichWithTrendData(0, "FIRST_DETECTION");
        repository.save(h1);
        log.info("  Seeded: FLG-4401 historical (7 days ago, 200 ppm, NORMAL_PROCESS)");

        // ── Historical 2: 4 days ago — 500 ppm (SENSOR_ARTIFACT, later proved wrong) ──
        EmissionEvent h2 = EmissionEvent.fromSensorReading(
                "INSP-HIST-002",
                "PATROL-2026-Q1-083",
                "spot-001",
                "FLG-4401",
                "Module B Deck 2",
                SensorModality.CH4,
                500.0,
                "ppm",
                now.minus(4, ChronoUnit.DAYS));
        h2.enrichWithEnvironmentalData(15.0, "NW", 10.0);
        h2.enrichWithMultiModalData(38.0, 8.0, 78.0, false, 2);
        h2.enrichWithSapContext("{\"orders\":[]}", false, false, 48, 50.0);
        h2.classify(EmissionClassification.SENSOR_ARTIFACT, 0.72,
                "Elevated reading 500 ppm above baseline but thermal delta only +8°C " +
                "and no acoustic leak signature. Moderate wind may cause sensor drift. " +
                "Classified as sensor artifact — confidence below gate threshold.",
                "mistral:7b", 4100);
        h2.applyGateResult(false, 0.80, "Confidence 0.72 below gate threshold 0.80");
        h2.enrichWithTrendData(1, "STABLE");
        repository.save(h2);
        log.info("  Seeded: FLG-4401 historical (4 days ago, 500 ppm, SENSOR_ARTIFACT)");
    }

    /**
     * Seed the 4 main demo scenario events from the story document.
     */
    private void seedDemoScenarios() {
        Instant now = Instant.now();

        // ── Event 1: VLV-2203 — MAINTENANCE_ACTIVITY (450 ppm) ──────────
        EmissionEvent e1 = EmissionEvent.fromSensorReading(
                "INSP-DEMO-001",
                "PATROL-2026-Q1-087",
                "spot-002",
                "VLV-2203",
                "Module C Deck 3",
                SensorModality.CH4,
                450.0,
                "ppm",
                now.minus(2, ChronoUnit.HOURS));
        e1.enrichWithEnvironmentalData(8.5, "NW", 12.0);
        e1.enrichWithSapContext(
                "{\"orders\":[{\"order_id\":\"PM03-28847\",\"description\":\"Valve repacking\",\"status\":\"ACTIVE\"}]}",
                true, false, 2, 30.0);
        e1.classify(EmissionClassification.MAINTENANCE_ACTIVITY, 0.95,
                "SAP confirms active work order PM03-28847 for valve repacking. " +
                "Elevated CH4 reading consistent with authorized maintenance activity. " +
                "No thermal anomaly, no acoustic leak signature beyond expected maintenance emissions.",
                "mistral:7b", 2800);
        e1.applyGateResult(true, 0.80, "Confidence 0.95 exceeds gate threshold 0.80");
        e1.enrichWithTrendData(0, "FIRST_DETECTION");
        repository.save(e1);
        log.info("  Seeded: VLV-2203 (MAINTENANCE_ACTIVITY, 450 ppm, SAP WO PM03-28847)");

        // ── Event 2: PST-1105 — SENSOR_ARTIFACT (180 ppm) ──────────────
        EmissionEvent e2 = EmissionEvent.fromSensorReading(
                "INSP-DEMO-002",
                "PATROL-2026-Q1-087",
                "anymal-001",
                "PST-1105",
                "Module A Deck 1",
                SensorModality.CH4,
                180.0,
                "ppm",
                now.minus(90, ChronoUnit.MINUTES));
        e2.enrichWithEnvironmentalData(28.0, "NW", 14.0);
        e2.enrichWithSapContext("{\"orders\":[]}", false, false, 3, 20.0);
        e2.classify(EmissionClassification.SENSOR_ARTIFACT, 0.82,
                "Single transient spike at 180 ppm, no thermal or acoustic corroboration. " +
                "High wind speed (28 km/h) likely caused sensor drift. Equipment recently " +
                "maintained (3 days ago). Isolated event suggests environmental artifact.",
                "mistral:7b", 3500);
        e2.applyGateResult(true, 0.80, "Confidence 0.82 exceeds gate threshold 0.80");
        e2.enrichWithTrendData(0, "FIRST_DETECTION");
        e2.dismiss("lars.henriksen", "High wind false positive — confirmed via field check");
        repository.save(e2);
        log.info("  Seeded: PST-1105 (SENSOR_ARTIFACT, 180 ppm, high wind 28 km/h)");

        // ── Event 3: PRV-3302 — PLANNED_VENTING (2100 ppm) ─────────────
        EmissionEvent e3 = EmissionEvent.fromSensorReading(
                "INSP-DEMO-003",
                "PATROL-2026-Q1-087",
                "spot-001",
                "PRV-3302",
                "Module D Deck 2",
                SensorModality.CH4,
                2100.0,
                "ppm",
                now.minus(60, ChronoUnit.MINUTES));
        e3.enrichWithEnvironmentalData(6.0, "SW", 11.0);
        e3.enrichWithMultiModalData(65.0, 2.0, 92.0, false, 2);
        e3.enrichWithSapContext(
                "{\"orders\":[{\"order_id\":\"TURN-Q1-2026\",\"description\":\"Q1 Turnaround depressurization\",\"status\":\"ACTIVE\"}]}",
                false, true, 0, 100.0);
        e3.classify(EmissionClassification.PLANNED_VENTING, 0.97,
                "SAP confirms turnaround active with Q1 depressurization work order. " +
                "Very high CH4 reading (2100 ppm) consistent with controlled blowdown operation. " +
                "No thermal anomaly (normal process temp), no acoustic leak signature. " +
                "Authorized planned venting event — logged for inventory but no alarm required.",
                "mistral:7b", 2400);
        e3.applyGateResult(true, 0.80, "Confidence 0.97 exceeds gate threshold 0.80");
        e3.enrichWithTrendData(0, "FIRST_DETECTION");
        repository.save(e3);
        log.info("  Seeded: PRV-3302 (PLANNED_VENTING, 2100 ppm, turnaround active)");

        // ── Event 4: FLG-4401 — FUGITIVE_EMISSION (850 ppm) ────────────
        EmissionEvent e4 = EmissionEvent.fromSensorReading(
                "INSP-DEMO-004",
                "PATROL-2026-Q1-087",
                "spot-001",
                "FLG-4401",
                "Module B Deck 2",
                SensorModality.CH4,
                850.0,
                "ppm",
                now.minus(30, ChronoUnit.MINUTES));
        e4.enrichWithEnvironmentalData(8.0, "NW", 12.0);
        e4.enrichWithMultiModalData(55.0, 12.0, 88.0, true, 3);
        e4.enrichWithSapContext("{\"orders\":[]}", false, false, 52, 150.0);
        e4.classify(EmissionClassification.FUGITIVE_EMISSION, 0.94,
                "CH4 concentration 850 ppm significantly above historical baseline (150 ppm). " +
                "Thermal delta +12°C confirms equipment hot-spot. Acoustic high-frequency hiss " +
                "signature detected — classic leak indicator. Three independent sensor modalities " +
                "convergent. ESCALATING 7-day trend (200→500→850 ppm). No active work orders " +
                "explain the event. High-confidence real fugitive emission.",
                "mistral:7b", 3800);
        e4.applyGateResult(true, 0.80, "Confidence 0.94 exceeds gate threshold 0.80. " +
                "Multi-modal corroboration (3 sensors) strengthens classification.");
        e4.quantifyLeakRate(2.3, null);
        e4.enrichWithTrendData(2, "ESCALATING");
        e4.markComplianceDocumented();
        e4.markWorkOrderCreated("WO-EMISSION-A7F3B21E", "ELP");
        repository.save(e4);
        log.info("  Seeded: FLG-4401 (FUGITIVE_EMISSION, 850 ppm, 2.3 kg/hr, ESCALATING, EU compliant)");
    }
}
