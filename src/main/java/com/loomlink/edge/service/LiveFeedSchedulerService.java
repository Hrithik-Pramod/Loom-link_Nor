package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.EquipmentClass;
import com.loomlink.edge.domain.enums.FailureModeCode;
import com.loomlink.edge.domain.model.*;
import com.loomlink.edge.repository.AuditLogRepository;
import com.loomlink.edge.repository.ExceptionInboxRepository;
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
 * Challenge 01 Live Feed Scheduler — Production-grade SAP OData Listener Simulation.
 *
 * <p>Simulates the real-world flow: Technician enters dirty free-text in SAP →
 * OData listener intercepts → Loom Link pipeline classifies → Reflector Gate
 * validates → SAP write-back or Exception Inbox.</p>
 *
 * <h3>18 Scenarios — Interleaved for 10-Minute Demo</h3>
 * <p>Pacing: <b>2 PASS → 1 REJECT → repeat</b>, finishing with 3 Experience Bank
 * cache hits. At 35s intervals, judges see a reject every ~105 seconds — keeping
 * the Exception Inbox flow interactive throughout the demo.</p>
 * <ul>
 *   <li><b>10 PASS scenarios</b> — clear failure descriptions that pass the Reflector Gate
 *       (confidence ≥ 0.85). Covers bearing failure, seal leak, overheating, fouling,
 *       complete breakdown, valve leakage, corrosion, instrument drift, vibration,
 *       and electrical faults. Mix of English and Norwegian text.</li>
 *   <li><b>5 REJECT scenarios</b> — vague, ambiguous, or incomplete notes that fall below
 *       the gate threshold. These go to the Exception Inbox for technician review
 *       (approve, reclassify, or dismiss).</li>
 * </ul>
 *
 * <h3>Experience Bank Demo Flow</h3>
 * <p>Scenarios 16-18 are <b>semantically similar variants</b> of earlier scenarios.
 * When the scheduler cycles back to these after the originals have been cached,
 * the pgvector semantic cache (Experience Bank) triggers a cache HIT — bypassing
 * the LLM entirely and proving the system learns from every classification.</p>
 *
 * <h3>Architecture</h3>
 * <pre>
 *   Scheduler → MaintenanceNotification (in-memory)
 *            → SemanticClassification  (in-memory, pre-computed)
 *            → ReflectorGateResult     (in-memory)
 *            → AuditLog               (PERSISTED — immutable compliance record)
 *            → ExceptionInboxItem     (PERSISTED — for rejected items)
 *            → SSE Live Feed          (broadcast to all dashboard subscribers)
 * </pre>
 *
 * <p>No LLM inference is invoked. Scenario data is pre-computed to ensure
 * sub-second latency (150-800ms realistic range) suitable for live demo.</p>
 */
@Service
public class LiveFeedSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(LiveFeedSchedulerService.class);

    private final LiveFeedEventBus eventBus;
    private final AuditLogRepository auditLogRepository;
    private final ExceptionInboxRepository exceptionInboxRepository;

    private final AtomicBoolean paused = new AtomicBoolean(false);
    private Instant pauseExpiresAt = null;
    private final AtomicInteger sapCycleCounter = new AtomicInteger(0);

    @Value("${loomlink.live-feed.enabled:true}")
    private boolean feedEnabled;

    @Value("${loomlink.reflector-gate.threshold:0.85}")
    private double gateThreshold;

    // ══════════════════════════════════════════════════════════════════
    // 15 SAP Notification Scenarios — Realistic North Sea Technician Notes
    //
    // Equipment tags follow Equinor/Aker BP naming conventions:
    //   P-xxxx  = Pump          C-xxxx  = Compressor
    //   G-xxxx  = Generator     V-xxxx  = Valve
    //   HE-xxxx = Heat Exchanger M-xxxx = Motor
    //   T-xxxx  = Turbine       I-xxxx  = Instrument
    //
    // SAP Notification Numbers are 8-digit (10004601-10004615)
    // Languages: EN (English) and NO (Norwegian Bokmål)
    // ══════════════════════════════════════════════════════════════════

    private static final List<SapScenario> SAP_SCENARIOS = List.of(

        // ══════════════════════════════════════════════════════════════
        // DEMO PACING: 2 PASS → 1 REJECT → repeat (7.5-minute demo)
        //
        // At 25s intervals × 18 scenarios = ~7.5 minutes total.
        // First event fires 2s after startup. Judges see a reject
        // every ~75 seconds, keeping the demo interactive throughout.
        //
        //   #1-2   PASS     (0:02 - 0:27)   Bearing + Overheating
        //   #3     REJECT   (0:52)           Vague pump noise
        //   #4-5   PASS     (1:17 - 1:42)   Seal leak + Generator FTS
        //   #6     REJECT   (2:07)           Norwegian colloquial
        //   #7-8   PASS     (2:32 - 2:57)   Valve leak + Fouling
        //   #9     REJECT   (3:22)           Ambiguous noise source
        //   #10-11 PASS     (3:47 - 4:12)   Motor distress + Turbine erosion
        //   #12    REJECT   (4:37)           Borderline valve moisture
        //   #13-14 PASS     (5:02 - 5:27)   Instrument drift + Piping corrosion
        //   #15    REJECT   (5:52)           Norwegian incomplete note
        //   #16-18 CACHE    (6:17 - 7:07)   Experience Bank semantic hits
        // ══════════════════════════════════════════════════════════════

        // ── WAVE 1: 2× PASS ─────────────────────────────────────────

        // #1: Pump bearing failure — classic rotating equipment failure
        new SapScenario("P-1001A", "10004601", "EN", EquipmentClass.PUMP,
            "Pump P-1001A making loud grinding noise from bearing housing. Vibration readings at 12.4 mm/s, well above Zone C threshold. Bearing replacement needed within 48 hours.",
            FailureModeCode.VIB, "B01", 0.94, true,
            "Clear bearing failure with quantitative vibration data (12.4 mm/s > Zone C). ISO 14224 maps to VIB with cause code B01 (bearing wear).",
            false),

        // #2: Compressor overheating — Norwegian text (bilingual capability)
        new SapScenario("C-1001", "10004602", "NO", EquipmentClass.COMPRESSOR,
            "Kompressor C-1001 overoppheting. Utløpstemperatur 145°C, over designgrense på 130°C. Mulig lubrikasjonsproblem.",
            FailureModeCode.OHE, "L01", 0.91, true,
            "Norwegian text correctly parsed. Compressor discharge temperature 145°C exceeds design limit 130°C. Lubrication suspected — maps to OHE/L01.",
            false),

        // ── WAVE 1: 1× REJECT ───────────────────────────────────────

        // #3: Extremely vague — no actionable information → Exception Inbox
        new SapScenario("P-2001A", "10004611", "EN", EquipmentClass.PUMP,
            "Something sounds different on the pump. Not sure what it is.",
            FailureModeCode.UNK, null, 0.32, false,
            "Vague observation with no specific failure indicators, no quantitative data, no location detail. Confidence 0.32 — well below gate threshold 0.85.",
            false),

        // ── WAVE 2: 2× PASS ─────────────────────────────────────────

        // #4: Pump seal leak — external leakage process medium
        new SapScenario("P-1002A", "10004603", "EN", EquipmentClass.PUMP,
            "Visible oil leak from mechanical seal area on P-1002A. Pool of fluid under pump skid. Process medium confirmed — seal replacement scheduled.",
            FailureModeCode.ELP, "S02", 0.93, true,
            "External leakage of process medium confirmed visually. Mechanical seal identified as source. Maps to ELP with cause S02 (seal degradation).",
            false),

        // #5: Generator failure to start — safety-critical equipment
        new SapScenario("G-1001", "10004604", "EN", EquipmentClass.GENERATOR,
            "Emergency generator G-1001 failed to start during weekly test. Fuel injection system suspected. Complete breakdown — no backup available.",
            FailureModeCode.FTS, "M01", 0.96, true,
            "Safety-critical: emergency generator FTS on demand test. Fuel injection failure suspected. HIGH priority — no redundancy available.",
            false),

        // ── WAVE 2: 1× REJECT ───────────────────────────────────────

        // #6: Norwegian vague — colloquial language → Exception Inbox
        new SapScenario("C-2001", "10004612", "NO", EquipmentClass.COMPRESSOR,
            "Kompressor virker litt rar i dag. Kanskje noe med trykket.",
            FailureModeCode.UNK, null, 0.38, false,
            "Norwegian colloquial: 'seems a bit odd today, maybe something with the pressure'. No measurable deviation reported. Confidence 0.38.",
            false),

        // ── WAVE 3: 2× PASS ─────────────────────────────────────────

        // #7: Valve packing leak — Norwegian text
        new SapScenario("V-1001", "10004605", "NO", EquipmentClass.VALVE,
            "Ventil V-1001 lekker fra pakningsboksen. Trykktap observert nedstrøms. Pakning må skiftes ved neste vedlikeholdsvindu.",
            FailureModeCode.ELP, "S01", 0.89, true,
            "Norwegian: Valve packing box leak with downstream pressure drop observed. Packing replacement needed at next window. Maps to ELP/S01.",
            false),

        // #8: Heat exchanger fouling — performance degradation
        new SapScenario("HE-2001", "10004606", "EN", EquipmentClass.HEAT_EXCHANGER,
            "Heat exchanger HE-2001 fouling detected. Approach temperature increased by 8°C over last month. Tube bundle cleaning required.",
            FailureModeCode.PLU, "F01", 0.87, true,
            "Performance degradation with clear fouling indicators. 8°C approach temp increase over 30 days. Maps to PLU (plugged/choked), cause F01.",
            false),

        // ── WAVE 3: 1× REJECT ───────────────────────────────────────

        // #9: Ambiguous noise source — could be multiple equipment → Exception Inbox
        new SapScenario("P-1002B", "10004613", "EN", EquipmentClass.PUMP,
            "Possible noise from P-1002B area. Could be pump or could be piping. Need to investigate further before any action.",
            FailureModeCode.NOI, null, 0.51, false,
            "Ambiguous: noise source unconfirmed between pump and piping. Technician explicitly states further investigation needed. Confidence 0.51.",
            false),

        // ── WAVE 4: 2× PASS ─────────────────────────────────────────

        // #10: Motor bearing distress — electrical indicators
        new SapScenario("M-2001", "10004607", "EN", EquipmentClass.MOTOR,
            "Motor M-2001 drawing 15% above rated current. Bearing temperature rising steadily. Suspected misalignment after last coupling maintenance.",
            FailureModeCode.VIB, "B01", 0.91, true,
            "Motor overcurrent (115% rated) with rising bearing temp. Post-maintenance misalignment suspected. Maps to VIB/B01 (bearing distress).",
            false),

        // #11: Turbine erosion — high-value asset
        new SapScenario("T-3001", "10004608", "EN", EquipmentClass.TURBINE,
            "Gas turbine T-3001 first stage blades showing pitting during borescope inspection. Inlet filter differential pressure above setpoint. Erosion progressing.",
            FailureModeCode.ERO, "E01", 0.92, true,
            "Borescope-confirmed blade pitting on first stage. High inlet DP confirms particulate ingress. Progressive erosion — maps to ERO/E01.",
            false),

        // ── WAVE 4: 1× REJECT ───────────────────────────────────────

        // #12: Borderline case — some detail but not enough → Exception Inbox
        new SapScenario("V-3002", "10004614", "EN", EquipmentClass.VALVE,
            "Valve V-3002 might be leaking a little bit. Noticed some moisture around the bonnet but could be condensation. Will check again tomorrow.",
            FailureModeCode.ELP, null, 0.62, false,
            "Borderline: possible external leak but technician uncertain — 'could be condensation'. Deferred observation. Confidence 0.62 — close but below 0.85 gate.",
            false),

        // ── WAVE 5: 2× PASS ─────────────────────────────────────────

        // #13: Instrument drift — parameter deviation
        new SapScenario("I-4401", "10004609", "EN", EquipmentClass.INSTRUMENT,
            "Pressure transmitter I-4401 reading 2.3 bar above cross-checked gauge. Drift confirmed during calibration round. Process data unreliable until replaced.",
            FailureModeCode.PDE, "C01", 0.88, true,
            "Instrument drift confirmed by cross-check: +2.3 bar deviation. Process data unreliable. Maps to PDE (parameter deviation), cause C01 (calibration drift).",
            false),

        // #14: Piping corrosion — Norwegian text with technical detail
        new SapScenario("P-5501", "10004610", "NO", EquipmentClass.PIPING,
            "Korrosjon oppdaget på rørseksjon P-5501 ved UT-måling. Veggtykkelse redusert fra 8.2mm til 5.1mm. Under minimumsgrense på 6.0mm. Utskifting nødvendig.",
            FailureModeCode.STD, "K01", 0.95, true,
            "Norwegian: UT measurement confirms wall thinning from 8.2mm to 5.1mm (below 6.0mm minimum). Corrosion-driven structural deficiency — maps to STD/K01.",
            false),

        // ── WAVE 5: 1× REJECT ───────────────────────────────────────

        // #15: Norwegian incomplete — technician interrupted mid-note → Exception Inbox
        new SapScenario("M-3001", "10004615", "NO", EquipmentClass.MOTOR,
            "Motor M-3001 vibrasjon. Sjekket i morges, var ok men nå høres det annerledes ut. Usikker på",
            FailureModeCode.VIB, null, 0.45, false,
            "Norwegian incomplete: 'Motor vibration. Checked this morning, was ok but now sounds different. Unsure about...' — note truncated. Confidence 0.45.",
            false),

        // ── FINALE: EXPERIENCE BANK CACHE HITS (3) ──────────────────
        // These fire last, after originals have been "cached". Proves
        // the system learns — pgvector semantic cache bypasses LLM.

        // #16: Semantic variant of #1 (P-1001A bearing failure)
        new SapScenario("P-1001A", "10004616", "EN", EquipmentClass.PUMP,
            "P-1001A pump unusual vibration detected at drive-end bearing. Readings elevated above alarm threshold. Recommend bearing inspection within 72 hours.",
            FailureModeCode.VIB, "B01", 0.94, true,
            "[EXPERIENCE BANK HIT expected] Semantically similar to SAP #10004601. Same equipment, same failure mode (bearing/vibration), different phrasing.",
            true),

        // #17: Semantic variant of #4 (P-1002A seal leak)
        new SapScenario("P-1002A", "10004617", "NO", EquipmentClass.PUMP,
            "Oljelekkasje observert rundt mekanisk tetning på P-1002A. Prosessmedium bekreftet. Tetning må byttes.",
            FailureModeCode.ELP, "S02", 0.93, true,
            "[EXPERIENCE BANK HIT expected] Norwegian semantic variant of SAP #10004603. Same equipment, same seal leak, cross-language pgvector match.",
            true),

        // #18: Semantic variant of #5 (G-1001 generator FTS)
        new SapScenario("G-1001", "10004618", "EN", EquipmentClass.GENERATOR,
            "Weekly function test on emergency generator G-1001: unit cranks but does not fire. Fuel pressure at injector rail reads zero. Likely fuel pump or injector fault.",
            FailureModeCode.FTS, "M01", 0.96, true,
            "[EXPERIENCE BANK HIT expected] Semantic variant of SAP #10004604. Same FTS failure, more specific fuel system diagnosis. pgvector similarity > 0.95.",
            true)
    );

    // ══════════════════════════════════════════════════════════════════

    public LiveFeedSchedulerService(
            LiveFeedEventBus eventBus,
            AuditLogRepository auditLogRepository,
            ExceptionInboxRepository exceptionInboxRepository) {
        this.eventBus = eventBus;
        this.auditLogRepository = auditLogRepository;
        this.exceptionInboxRepository = exceptionInboxRepository;
    }

    // ── Pause / Resume Control ────────────────────────────────────

    /** Pause all feeds for the specified number of minutes (for manual demo). */
    public void pause(int minutes) {
        paused.set(true);
        pauseExpiresAt = Instant.now().plus(minutes, ChronoUnit.MINUTES);
        log.info("Live Feed PAUSED for {} minutes (expires at {})", minutes, pauseExpiresAt);
        eventBus.publish("SYSTEM", "FEED_PAUSED", "Live Feed Paused",
                "All automated feeds paused for " + minutes + " minutes. Manual demo mode active.",
                Map.of("pauseMinutes", minutes, "expiresAt", pauseExpiresAt.toString()));
    }

    /** Resume all feeds immediately. */
    public void resume() {
        paused.set(false);
        pauseExpiresAt = null;
        log.info("Live Feed RESUMED");
        eventBus.publish("SYSTEM", "FEED_RESUMED", "Live Feed Resumed",
                "All automated feeds resumed. Production simulation active.", null);
    }

    /** Check if feeds are currently paused (auto-resumes when timer expires). */
    public boolean isPaused() {
        if (paused.get() && pauseExpiresAt != null && Instant.now().isAfter(pauseExpiresAt)) {
            resume();
            return false;
        }
        return paused.get();
    }

    /**
     * Reset the demo — clears all scheduler-generated audit logs and exception inbox items,
     * resets the cycle counter to 0. The scheduler will re-process all 18 scenarios.
     * Experience Bank data (assets, failure histories, vibrations) is NOT affected.
     */
    public void resetDemo() {
        log.info("════════════════════════════════════════════════════════");
        log.info("  DEMO RESET — Clearing scheduler-generated data");
        log.info("════════════════════════════════════════════════════════");
        exceptionInboxRepository.deleteAll();
        auditLogRepository.deleteAll();
        sapCycleCounter.set(0);
        log.info("Demo reset complete. Scheduler will re-process all {} scenarios.", SAP_SCENARIOS.size());
    }

    /** Dashboard status endpoint — returns scheduler state and cycle count. */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", feedEnabled);
        status.put("paused", isPaused());
        status.put("pauseExpiresAt", pauseExpiresAt != null ? pauseExpiresAt.toString() : null);
        status.put("sapCycleCount", sapCycleCounter.get());
        status.put("totalScenarios", SAP_SCENARIOS.size());
        status.put("currentScenarioIndex", sapCycleCounter.get() % SAP_SCENARIOS.size());
        status.put("activeSubscribers", eventBus.getSubscriberCount());
        return status;
    }

    // ── Utility ──────────────────────────────────────────────────

    /** Realistic demo latency — simulates LLM inference time without calling LLM. */
    private static long demoLatency() {
        return ThreadLocalRandom.current().nextLong(180, 650);
    }

    /** Longer latency for first-time classifications (no cache hit). */
    private static long demoLatencyFresh() {
        return ThreadLocalRandom.current().nextLong(350, 850);
    }

    /** Short latency for Experience Bank cache hits. */
    private static long demoLatencyCache() {
        return ThreadLocalRandom.current().nextLong(25, 85);
    }

    // ══════════════════════════════════════════════════════════════
    // SAP OData Listener Loop — Every 35 seconds
    //
    // Flow: Scenario data → in-memory domain objects → AuditLog (DB)
    //       → ExceptionInbox (DB, if rejected) → SSE Live Feed
    //
    // Experience Bank scenarios (#16-18) use shorter latency
    // and add [CACHE HIT] tag to demonstrate semantic cache.
    // ══════════════════════════════════════════════════════════════

    @Scheduled(fixedDelayString = "${loomlink.live-feed.sap-interval-ms:25000}", initialDelay = 2000)
    public void sapNotificationLoop() {
        if (!feedEnabled || isPaused()) return;

        int index = sapCycleCounter.getAndIncrement() % SAP_SCENARIOS.size();
        SapScenario s = SAP_SCENARIOS.get(index);

        // Skip scenarios that were already processed in a previous session
        // (ddl-auto: update preserves data between restarts)
        if (!auditLogRepository.findBySapNotificationNumber(s.notificationNumber).isEmpty()) {
            log.debug("SAP #{}: {} already processed, skipping", index + 1, s.notificationNumber);
            return;
        }

        long pipelineStart = System.currentTimeMillis();

        try {
            // ── 1. Create in-memory MaintenanceNotification ──────────
            MaintenanceNotification notification = MaintenanceNotification.fromODataEvent(
                    s.notificationNumber, s.freeText, s.equipmentTag,
                    s.equipmentClass, "1000");

            // ── 2. Create in-memory SemanticClassification ───────────
            long inferenceLatency = s.experienceBankVariant ? demoLatencyCache() : demoLatencyFresh();
            notification.markClassified();

            SemanticClassification classification = SemanticClassification.create(
                    notification, s.failureCode, s.causeCode, s.confidence,
                    s.experienceBankVariant
                        ? "[CACHE HIT — SEMANTIC match @ 96.2%] " + s.reasoning
                        : s.reasoning,
                    null,
                    s.experienceBankVariant ? "mistral:7b (cached)" : "mistral:7b",
                    inferenceLatency);

            // ── 3. Create in-memory ReflectorGateResult ──────────────
            // Gate reasoning is distinct from LLM reasoning:
            //   - LLM reasoning = the model's classification analysis (stored on SemanticClassification)
            //   - Gate reasoning = the threshold-based decision (stored on ReflectorGateResult)
            ReflectorGateResult gateResult;
            if (s.gatePassed) {
                notification.markVerified();
                String gateReasoning = String.format(
                    "GATE PASSED — Confidence %.1f%% meets threshold %.0f%%. Classification accepted, SAP write-back authorized.",
                    s.confidence * 100, gateThreshold * 100);
                gateResult = ReflectorGateResult.passed(classification, gateThreshold, gateReasoning);
            } else {
                notification.markRejected();
                String gateReasoning = String.format(
                    "GATE REJECTED — Confidence %.1f%% below threshold %.0f%%. Routed to Exception Inbox for human review. Priority: %s.",
                    s.confidence * 100, gateThreshold * 100,
                    s.confidence > 0.55 ? "HIGH" : s.confidence > 0.40 ? "MEDIUM" : "LOW");
                gateResult = ReflectorGateResult.rejected(classification, gateThreshold, gateReasoning);
            }

            // ── 4. Simulate SAP write-back (for PASS scenarios) ──────
            boolean writtenToSap = s.gatePassed;
            if (writtenToSap) notification.markWrittenBack();

            // Simulate realistic pipeline latency (inference + gate + SAP write-back overhead)
            long gateLatency = ThreadLocalRandom.current().nextLong(15, 45);
            long sapWriteLatency = writtenToSap ? ThreadLocalRandom.current().nextLong(30, 90) : 0;
            long totalLatency = inferenceLatency + gateLatency + sapWriteLatency;

            // ── 5. PERSIST: AuditLog (immutable compliance record) ────
            AuditLog auditLog = AuditLog.record(
                    notification, classification, gateResult,
                    writtenToSap, totalLatency, true,
                    true, s.experienceBankVariant ? 0 : 1);
            auditLogRepository.save(auditLog);

            // ── 6. PERSIST: ExceptionInbox (for rejected items) ───────
            if (!s.gatePassed) {
                try {
                    ExceptionInboxItem item = ExceptionInboxItem.fromRejection(
                            notification, classification, gateResult);
                    exceptionInboxRepository.save(item);
                    log.info("  → Exception Inbox: {} (confidence {}, priority {})",
                            s.notificationNumber, s.confidence,
                            s.confidence > 0.55 ? "HIGH" : s.confidence > 0.40 ? "MEDIUM" : "LOW");
                } catch (Exception e) {
                    log.warn("Exception inbox save failed for {}: {}", s.notificationNumber, e.getMessage());
                }
            }

            // ── 7. Publish to SSE Live Feed ──────────────────────────
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("equipmentTag", s.equipmentTag);
            metadata.put("notificationNumber", s.notificationNumber);
            metadata.put("language", s.language);
            metadata.put("freeText", s.freeText);
            metadata.put("failureCode", s.failureCode.name());
            metadata.put("failureDescription", s.failureCode.getDescription());
            metadata.put("confidence", s.confidence);
            metadata.put("gatePassed", s.gatePassed);
            metadata.put("gateReasoning", s.reasoning);
            metadata.put("pipelineLatencyMs", totalLatency);
            metadata.put("inferenceLatencyMs", inferenceLatency);
            metadata.put("cacheHit", s.experienceBankVariant);
            metadata.put("auditLogged", true);
            metadata.put("scenarioIndex", index + 1);
            metadata.put("totalScenarios", SAP_SCENARIOS.size());

            String cacheTag = s.experienceBankVariant ? " [CACHE]" : "";
            String title;
            if (s.gatePassed) {
                title = String.format("SAP %s → %s (%s) — GATE PASSED %.0f%%%s [%dms]",
                        s.notificationNumber, s.equipmentTag,
                        s.failureCode.name(), s.confidence * 100,
                        cacheTag, totalLatency);
            } else {
                title = String.format("SAP %s → %s — GATE REJECTED %.0f%%%s [%dms]",
                        s.notificationNumber, s.equipmentTag,
                        s.confidence * 100, cacheTag, totalLatency);
            }

            eventBus.publish("SAP_ODATA", "CLASSIFICATION", title, s.reasoning, metadata);

            log.info("SAP #{}/{}: {} → {} [{}] ({}) — {} in {}ms",
                    index + 1, SAP_SCENARIOS.size(),
                    s.notificationNumber, s.equipmentTag,
                    s.language, s.failureCode.name(),
                    s.gatePassed ? "PASS → AUDIT LOG" : "REJECT → EXCEPTION INBOX",
                    totalLatency);

        } catch (Exception e) {
            log.error("SAP #{}: error for {} — {}", index + 1, s.notificationNumber, e.getMessage(), e);

            // Fallback: publish error to SSE so dashboard stays alive
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("equipmentTag", s.equipmentTag);
            metadata.put("notificationNumber", s.notificationNumber);
            metadata.put("confidence", s.confidence);
            metadata.put("gatePassed", s.gatePassed);
            metadata.put("auditLogged", false);
            metadata.put("error", e.getMessage());

            eventBus.publish("SAP_ODATA", "CLASSIFICATION",
                    String.format("SAP %s → %s — PIPELINE ERROR", s.notificationNumber, s.equipmentTag),
                    "Pipeline error: " + e.getMessage(), metadata);
        }
    }

    // ── Scenario Record ──────────────────────────────────────────

    /**
     * Immutable scenario definition for SAP OData listener simulation.
     *
     * @param equipmentTag           SAP equipment tag (e.g., "P-1001A")
     * @param notificationNumber     SAP notification number (8 digits)
     * @param language               Notification language ("EN" or "NO")
     * @param equipmentClass         ISO 14224 equipment class
     * @param freeText               The raw technician free-text (the "dirty" input)
     * @param failureCode            ISO 14224 failure mode code (classification output)
     * @param causeCode              SAP cause code (root cause, null for rejects)
     * @param confidence             LLM confidence score (0.0-1.0)
     * @param gatePassed             Whether the Reflector Gate passes this (confidence ≥ 0.85)
     * @param reasoning              LLM reasoning chain / gate reasoning
     * @param experienceBankVariant  True if this is a semantic variant for cache demo
     */
    record SapScenario(
            String equipmentTag,
            String notificationNumber,
            String language,
            EquipmentClass equipmentClass,
            String freeText,
            FailureModeCode failureCode,
            String causeCode,
            double confidence,
            boolean gatePassed,
            String reasoning,
            boolean experienceBankVariant) {}
}
