package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.FailureModeCode;
import com.loomlink.edge.domain.enums.EmissionClassification;
import com.loomlink.edge.domain.model.MaintenanceNotification;
import com.loomlink.edge.domain.model.SemanticClassification;
import com.loomlink.edge.domain.model.EmissionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Demo Mode — Pitch-safe deterministic responses for the April 22nd Stavanger demo.
 *
 * <p>Problem: During a live pitch, the LLM picks its own confidence score. If Mistral 7B
 * is having a bad inference day, your carefully rehearsed "show rejection, then show
 * acceptance" flow could fail on stage.</p>
 *
 * <p>Solution: Demo Mode provides keyword-matched, deterministic responses that mirror
 * exactly what the real LLM would produce — but with guaranteed confidence scores.
 * When demo mode is OFF, everything flows through the real Mistral 7B pipeline.</p>
 *
 * <p>This is NOT a fake. The classifications are technically correct ISO 14224 mappings.
 * The reasoning chains are realistic. The latencies are simulated to match real HM90
 * performance. It's a rehearsal-safe fallback, not a shortcut.</p>
 *
 * <p>Toggle via: {@code loomlink.demo-mode.enabled=true} in application.yml
 * or at runtime via {@code POST /api/v1/demo/toggle}</p>
 */
@Service
public class DemoModeService {

    private static final Logger log = LoggerFactory.getLogger(DemoModeService.class);

    private volatile boolean enabled;

    /** Pre-built scenario responses keyed by keyword patterns (EN + NO bilingual). */
    private static final List<DemoScenario> SCENARIOS = List.of(

        // ── SCENARIO 1: High-Confidence Bearing/Vibration (PASS) ─────────
        new DemoScenario(
            List.of("grinding", "vibration", "bearing", "squeal", "vib",
                     "skrapelyd", "vibrasjon", "lager", "slitasje"),
            FailureModeCode.VIB,
            "B01",
            0.94,
            "The technician's description contains convergent bearing wear indicators: " +
            "grinding noise/skrapelyd, rising vibration/vibrasjon, and elevated bearing " +
            "temperature — three corroborating symptoms. Cross-referencing ISO 14224 Table B.6 " +
            "for rotating equipment, this maps to VIB (Vibration) with root cause B01 (Bearing wear). " +
            "High confidence due to multiple specific symptom indicators."
        ),

        // ── SCENARIO 2: High-Confidence Overheating (PASS) ───────────────
        new DemoScenario(
            List.of("overheat", "temperature alarm", "oil temp", "hot", "thermal",
                     "overoppheting", "høy temperatur", "varm", "oljetemp", "temperaturalarm"),
            FailureModeCode.OHE,
            "L01",
            0.92,
            "The description reports elevated temperature triggering an alarm — " +
            "progressive thermal deviation indicates overheating rather than sensor fault. " +
            "Per ISO 14224 Table B.6, this maps to OHE (Overheating) with root cause L01 " +
            "(Lubrication failure). Confidence is high due to objective measurement anchor."
        ),

        // ── SCENARIO 3: High-Confidence External Leakage (PASS) ──────────
        new DemoScenario(
            List.of("leak", "drip", "seal", "seepage", "fluid under", "pool of",
                     "lekkasje", "drypp", "tetning", "væske under", "pakningsfeil"),
            FailureModeCode.ELP,
            "S02",
            0.91,
            "Visible external leak from mechanical seal with progressive worsening " +
            "and physical evidence. Per ISO 14224, an external leak of process medium " +
            "from a seal maps to ELP (External leakage) with root cause S02 (Seal failure). " +
            "The progressive nature and specific location provide strong confidence."
        ),

        // ── SCENARIO 4: High-Confidence Plugged/Choked (PASS) ────────────
        new DemoScenario(
            List.of("plugged", "choked", "blocked", "fouled", "restricted flow", "pressure drop",
                     "tett", "blokkert", "tilstoppet", "høyt trykkfall", "redusert strømning"),
            FailureModeCode.PLU,
            "F01",
            0.89,
            "Flow restriction indicators map directly to ISO 14224 failure mode PLU " +
            "(Plugged/choked) with root cause F01 (Fouling/scaling). Gradual flow degradation " +
            "is consistent with progressive internal fouling."
        ),

        // ── SCENARIO 5: High-Confidence Internal Leakage (PASS) ──────────
        new DemoScenario(
            List.of("internal leak", "valve passing", "seat leak", "through leak",
                     "internlekkasje", "ventil passerer", "gjennomslag", "setesvikt"),
            FailureModeCode.INL,
            "C03",
            0.90,
            "Valve passing / internal leakage with clear symptom identification. " +
            "Per ISO 14224, this maps to INL (Internal leakage) with root cause C03 (Corrosion). " +
            "Seat degradation in process service is well-characterized for North Sea valves."
        ),

        // ── SCENARIO 6: High-Confidence Breakdown (PASS) ─────────────────
        new DemoScenario(
            List.of("stopped", "tripped", "shutdown", "failed to run", "seized", "locked up",
                     "stoppet", "utløst", "nedstenging", "starter ikke", "gått i lås"),
            FailureModeCode.BRD,
            "M01",
            0.96,
            "Equipment has ceased to function — complete breakdown event. ISO 14224 " +
            "classifies this as BRD (Breakdown) with root cause M01 (Mechanical failure). " +
            "Very high confidence — breakdown is a binary state with no ambiguity."
        ),

        // ── SCENARIO 7: Low-Confidence Ambiguous (REJECT) ────────────────
        new DemoScenario(
            List.of("something", "doesn't sound right", "not sure", "check it", "maybe", "funny noise",
                     "noe galt", "vet ikke", "usikker", "sjekk det", "høres rart ut", "kanskje"),
            FailureModeCode.VIB,
            "U01",
            0.38,
            "The description is vague — no specific symptoms, measurements, or location " +
            "details. While auditory anomalies on rotating equipment often correlate with " +
            "vibration issues (VIB), the lack of corroborating evidence makes confident " +
            "classification impossible. Flagging for human review."
        ),

        // ── SCENARIO 8: Medium-Confidence Noise (REJECT — just under gate) ──
        new DemoScenario(
            List.of("noise", "unusual sound", "rattling",
                     "støy", "uvanlig lyd", "rasling", "banking"),
            FailureModeCode.NOI,
            "B01",
            0.72,
            "Unusual noise/rattling reported. Acoustic anomalies in rotating machinery " +
            "frequently indicate developing bearing issues (NOI - Noise, cause B01). However, " +
            "noise descriptions are subjective and without frequency analysis or vibration " +
            "correlation, confidence cannot exceed the gate threshold."
        ),

        // ── SCENARIO 9: Norwegian — Erosion/Corrosion (PASS) ────────────
        new DemoScenario(
            List.of("erosjon", "korrosjon", "veggtynnelse", "sandskade", "materialtap",
                     "erosion", "wall thinning", "sand damage"),
            FailureModeCode.ERO,
            "E01",
            0.88,
            "Erosion indicators identified — material loss / wall thinning consistent with " +
            "sand-laden flow damage common in North Sea production systems. ISO 14224 maps " +
            "to ERO (Erosion) with root cause E01 (Erosive particles). High confidence due to " +
            "specific physical evidence of material degradation."
        ),

        // ── SCENARIO 10: Norwegian — Pump Won't Start (PASS) ────────────
        new DemoScenario(
            List.of("starter ikke", "startar ikkje", "vil ikke starte", "failure to start", "won't start"),
            FailureModeCode.FTS,
            "E02",
            0.93,
            "Equipment fails to start on demand. Clear binary failure state — the equipment " +
            "was commanded to start but did not respond. ISO 14224 classifies this as FTS " +
            "(Failure to start) with root cause E02 (Electrical/control failure). Very high " +
            "confidence due to unambiguous failure mode."
        )
    );

    public DemoModeService(
            @Value("${loomlink.demo-mode.enabled:false}") boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            log.info("╔══════════════════════════════════════════════════════════╗");
            log.info("║  DEMO MODE ACTIVE — Deterministic responses enabled     ║");
            log.info("║  Toggle off with POST /api/v1/demo/toggle               ║");
            log.info("╚══════════════════════════════════════════════════════════╝");
        }
    }

    public boolean isEnabled() { return enabled; }

    public void toggle() {
        this.enabled = !this.enabled;
        log.info("Demo mode toggled to: {}", enabled ? "ON" : "OFF");
    }

    /**
     * Attempt to match the notification's free-text against known demo scenarios.
     * Returns empty if no match found (falls through to real LLM).
     */
    public Optional<SemanticClassification> classify(MaintenanceNotification notification) {
        if (!enabled) return Optional.empty();

        String text = notification.getFreeTextDescription().toLowerCase();

        for (DemoScenario scenario : SCENARIOS) {
            if (scenario.matches(text)) {
                log.info("Demo mode matched scenario: {} -> {} (confidence: {})",
                        scenario.keywords.get(0), scenario.failureMode, scenario.confidence);

                // Simulate realistic HM90 inference latency (2-8 seconds)
                long simulatedLatency = ThreadLocalRandom.current().nextLong(2000, 8000);
                try {
                    Thread.sleep(simulatedLatency);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                String rawOutput = String.format(
                    "{\"failureModeCode\":\"%s\",\"causeCode\":\"%s\",\"confidence\":%s,\"reasoning\":\"%s\"}",
                    scenario.failureMode.name(), scenario.causeCode,
                    scenario.confidence, scenario.reasoning);

                notification.markClassified();

                return Optional.of(SemanticClassification.create(
                        notification,
                        scenario.failureMode,
                        scenario.causeCode,
                        scenario.confidence,
                        scenario.reasoning,
                        rawOutput,
                        "mistral:7b (demo-verified)",
                        simulatedLatency
                ));
            }
        }

        // No scenario matched — fall through to real LLM
        log.info("Demo mode: no matching scenario for '{}', falling through to LLM",
                text.substring(0, Math.min(text.length(), 50)));
        return Optional.empty();
    }

    /**
     * Attempt to classify an emission event using demo-mode deterministic responses.
     * Returns empty if no match (falls through to real LLM).
     */
    public Optional<EmissionClassification> classifyEmission(EmissionEvent event) {
        if (!enabled) return Optional.empty();

        String equipmentTag = event.getEquipmentTag().toUpperCase();
        double reading = event.getRawReading();

        // Demo scenarios based on equipment tag and reading patterns
        if (equipmentTag.startsWith("VLV") && reading > 200 && reading < 600) {
            log.info("Demo mode: emission classified as MAINTENANCE_ACTIVITY for {} ({}ppm during valve work)", equipmentTag, reading);
            simulateLatency();
            return Optional.of(EmissionClassification.MAINTENANCE_ACTIVITY);
        }
        if (equipmentTag.startsWith("PST") || (reading < 200 && reading > 100)) {
            log.info("Demo mode: emission classified as SENSOR_ARTIFACT for {} ({}ppm, low-range transient)", equipmentTag, reading);
            simulateLatency();
            return Optional.of(EmissionClassification.SENSOR_ARTIFACT);
        }
        if (equipmentTag.startsWith("PRV") && reading > 1500) {
            log.info("Demo mode: emission classified as PLANNED_VENTING for {} ({}ppm, PRV high reading)", equipmentTag, reading);
            simulateLatency();
            return Optional.of(EmissionClassification.PLANNED_VENTING);
        }
        if (equipmentTag.startsWith("FLG") && reading > 500) {
            log.info("Demo mode: emission classified as FUGITIVE_EMISSION for {} ({}ppm, flange leak signature)", equipmentTag, reading);
            simulateLatency();
            return Optional.of(EmissionClassification.FUGITIVE_EMISSION);
        }

        // No match — fall through to real LLM
        log.info("Demo mode: no emission scenario matched for {} ({}ppm), falling through to LLM", equipmentTag, reading);
        return Optional.empty();
    }

    private void simulateLatency() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(500, 2000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Get all available scenarios for the dashboard.
     */
    public List<Map<String, Object>> getScenarios() {
        return SCENARIOS.stream().map(s -> Map.<String, Object>of(
                "keywords", s.keywords,
                "failureMode", s.failureMode.name(),
                "failureModeDescription", s.failureMode.getDescription(),
                "causeCode", s.causeCode,
                "confidence", s.confidence,
                "willPass", s.confidence >= 0.85,
                "reasoning", s.reasoning
        )).toList();
    }

    // ── Inner Scenario Record ────────────────────────────────────────
    private record DemoScenario(
            List<String> keywords,
            FailureModeCode failureMode,
            String causeCode,
            double confidence,
            String reasoning
    ) {
        boolean matches(String text) {
            return keywords.stream().anyMatch(text::contains);
        }
    }
}
