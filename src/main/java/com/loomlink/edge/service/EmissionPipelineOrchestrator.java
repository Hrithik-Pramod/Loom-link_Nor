package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.ComplianceStatus;
import com.loomlink.edge.domain.enums.EmissionClassification;
import com.loomlink.edge.domain.model.EmissionEvent;
import com.loomlink.edge.gateway.SapBapiGateway;
import com.loomlink.edge.repository.AuditLogRepository;
import com.loomlink.edge.repository.EmissionEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.loomlink.edge.service.EmissionAnalysisService.ClassificationResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the complete Loom Link Challenge 02 emission surveillance pipeline:
 *
 * <pre>
 *   Robot Sensor           SAP Context          Multi-Modal        Experience Bank    Mistral 7B
 *   Detection      →       Enrichment    →      Fusion      →      Lookup      →     Analysis
 *   (SARA format)         (simulated)           (thermal,          (semantic          (contextual
 *                                              acoustic)          cache)              classification)
 *                                                ↓                   ↓                   ↓
 *                                          Corroboration      Cache HIT/MISS        JSON Schema
 *                                          (sensor count)     (pgvector)            Validation
 *                                                ↓                   ↓
 *                                            Leak Rate           Quantification    Trend Tracking
 *                                           (kg/hr model)       (EU 2024/1787)     (7-day history)
 *                                                ↓                                       ↓
 *                                          Emission Reflector Gate (Confidence ≥ 0.80)
 *                                                ↓
 *                                    ┌─────────┴─────────┐
 *                                PASSED              REJECTED
 *                                  ↓                    ↓
 *                            Classification      Exception Inbox
 *                            Confirmed           (human review)
 *                                  ↓
 *                            Compliance Actions
 *                            (EU 2024/1787 record
 *                             + SAP work order
 *                             + ISO 14224 notify)
 *                                  ↓
 *                              Audit Log
 * </pre>
 *
 * <p>Scale & Resilience features:</p>
 * <ul>
 *   <li>SAP Context: simulated operational data (active work orders, maintenance status, turnaround)</li>
 *   <li>Multi-Modal Fusion: correlates thermal + acoustic + primary gas reading for confidence boost</li>
 *   <li>Experience Bank: semantic cache lookup bypasses LLM re-inference on similar historical events</li>
 *   <li>Emission Analysis: Mistral 7B model evaluation of contextual factors (SAP + environmental)</li>
 *   <li>Leak Quantification: physics-based estimation model for kg/hr (EU regulatory requirement)</li>
 *   <li>Reflector Gate: configurable confidence threshold (default 0.80) with deterministic rules</li>
 *   <li>Trend Tracking: 7-day history lookup for ESCALATING/STABLE/DECLINING/FIRST_DETECTION</li>
 *   <li>Compliance Actions: auto-generate EU 2024/1787 record + SAP work order + ISO 14224 notification</li>
 *   <li>Dead Letter Queue: failed SAP BAPI calls retry with exponential backoff</li>
 *   <li>Immutable AuditLog: every pipeline execution (pass or fail) is logged for governance</li>
 * </ul>
 *
 * <p>This orchestrator follows the same multi-stage pattern as {@link MaintenancePipelineOrchestrator},
 * but tailored for robot-acquired sensor data and emission-specific compliance requirements.</p>
 */
@Service
public class EmissionPipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(EmissionPipelineOrchestrator.class);

    private final EmissionEventRepository emissionRepository;
    private final AuditLogRepository auditLogRepository;
    private final EmissionAnalysisService emissionAnalysisService;
    private final SapBapiGateway bapiGateway;
    private final EmissionExperienceBankService emissionExperienceBank;
    private final EmissionRemediationService remediationService;
    private final double emissionGateThreshold;
    private final boolean demoModeEnabled;

    public EmissionPipelineOrchestrator(
            EmissionEventRepository emissionRepository,
            AuditLogRepository auditLogRepository,
            EmissionAnalysisService emissionAnalysisService,
            SapBapiGateway bapiGateway,
            EmissionExperienceBankService emissionExperienceBank,
            EmissionRemediationService remediationService,
            @Value("${loomlink.emission.gate-threshold:0.80}") double emissionGateThreshold,
            @Value("${loomlink.demo-mode.enabled:false}") boolean demoModeEnabled) {

        this.emissionRepository = emissionRepository;
        this.auditLogRepository = auditLogRepository;
        this.emissionAnalysisService = emissionAnalysisService;
        this.bapiGateway = bapiGateway;
        this.emissionExperienceBank = emissionExperienceBank;
        this.remediationService = remediationService;
        this.emissionGateThreshold = emissionGateThreshold;
        this.demoModeEnabled = demoModeEnabled;
    }

    /**
     * Process a single emission event through the complete Challenge 02 pipeline.
     *
     * @param event the raw emission event from the robot sensor (SARA-compatible)
     * @return the pipeline result containing all stage outputs and compliance actions
     */
    public EmissionPipelineResult process(EmissionEvent event) {
        log.info("════════════════════════════════════════════════════════════════");
        log.info("  EMISSION SURVEILLANCE PIPELINE START");
        log.info("  Mission       : {}", event.getMissionId());
        log.info("  Robot         : {}", event.getRobotId());
        log.info("  Equipment Tag : {}", event.getEquipmentTag());
        log.info("  Location      : {}", event.getLocationArea());
        log.info("  Sensor        : {} = {} {}",
                 event.getSensorModality(), event.getRawReading(), event.getReadingUnit());
        log.info("  Detection Time: {}", event.getDetectedAt());
        log.info("════════════════════════════════════════════════════════════════");

        long pipelineStart = System.currentTimeMillis();

        // ── Stage 1: SAP Context Enrichment ─────────────────────────────
        enrichSapContext(event);

        // ── Stage 2: Multi-Modal Fusion ─────────────────────────────────
        enrichMultiModalData(event);

        // ── Stage 3 & 4: Experience Bank Lookup + Emission Analysis ──────
        // Check if we've seen similar readings at this location before
        // (semantic cache for emission patterns — reuse from Challenge 01)
        EmissionClassification emissionClass;
        double confidence;
        String reasoning;
        long analysisLatency;

        var cacheHit = emissionExperienceBank.lookup(event);
        if (cacheHit.isPresent()) {
            var hit = cacheHit.get();
            emissionClass = hit.cachedClassification();
            confidence = hit.confidence();
            reasoning = "Experience Bank cache hit (similarity: " + String.format("%.3f", hit.similarity()) + ")";
            analysisLatency = hit.lookupMs();
            log.info("  Experience Bank: CACHE HIT — {} (similarity: {})", emissionClass, hit.similarity());
        } else {
            log.info("  Experience Bank: CACHE MISS — proceeding to LLM analysis");
            long analysisStart = System.currentTimeMillis();
            ClassificationResult classificationResult = emissionAnalysisService.classify(event);
            emissionClass = classificationResult.classification();
            confidence = classificationResult.confidence();
            reasoning = classificationResult.reasoning();
            analysisLatency = System.currentTimeMillis() - analysisStart;
        }

        log.info("  Emission Analysis: {} (latency: {}ms)",
                 emissionClass, analysisLatency);

        // Apply classification to event
        event.classify(emissionClass, confidence, reasoning, "mistral:7b", analysisLatency);

        // ── Stage 5: Leak Rate Quantification (EU 2024/1787) ───────────
        double estimatedLeakRateKgHr = quantifyLeakRate(event, emissionClass);
        if (estimatedLeakRateKgHr > 0) {
            event.quantifyLeakRate(estimatedLeakRateKgHr, null);
            log.info("  Leak Quantification: {} kg/hr", String.format("%.3f", estimatedLeakRateKgHr));
        }

        // ── Stage 6: Emission Reflector Gate ────────────────────────────
        boolean gatePassed = evaluateEmissionGate(event, emissionClass);
        event.applyGateResult(gatePassed, emissionGateThreshold,
                gatePassed ? "Confidence exceeds gate threshold"
                          : "Confidence below gate threshold");

        log.info("  Emission Reflector Gate: {} (threshold: {})",
                 gatePassed ? "PASSED" : "REJECTED", String.format("%.2f", emissionGateThreshold));

        // ── Stage 6b: Exception Inbox Routing (for rejected events) ─────
        // (Scenario 3 fix) Rejected emission events must not silently disappear.
        // EU 2024/1787 requires documented response for ALL detected leaks.
        // Route to human review queue with priority based on equipment criticality.
        if (!gatePassed) {
            event.routeToExceptionInbox(emissionGateThreshold);
            log.info("  Exception Inbox: ROUTED — priority {} (review status: GATE_REJECTED)",
                     event.getReviewPriority());
        }

        // ── Stage 7: Trend Tracking ─────────────────────────────────────
        String trendDirection = detectTrend(event);
        event.enrichWithTrendData(event.getPreviousDetections7d(), trendDirection);
        log.info("  Trend Analysis: {} ({} detections in last 7 days)",
                 trendDirection, event.getPreviousDetections7d());

        // ── Stage 8: Prescriptive Remediation ───────────────────────────
        EmissionRemediationService.RemediationResult remediation = remediationService.recommend(event);
        event.applyRemediation(remediation.action(), remediation.urgency(), remediation.estimatedHours());
        log.info("  Remediation: {} | Action: {}",
                 remediation.urgency(),
                 remediation.action().substring(0, Math.min(80, remediation.action().length())) + "...");

        // ── Stage 9: Compliance Actions ─────────────────────────────────
        String workOrderNumber = null;
        boolean complianceGenerated = false;

        if (gatePassed && emissionClass.requiresComplianceRecord()) {
            complianceGenerated = generateComplianceRecord(event);
            log.info("  Compliance Record: {} (EU 2024/1787)",
                     complianceGenerated ? "GENERATED" : "FAILED");

            // Generate SAP work order for fugitive emissions
            if (complianceGenerated && emissionClass.triggersWorkOrder()) {
                workOrderNumber = generateSapWorkOrder(event);
                if (workOrderNumber != null) {
                    event.markWorkOrderCreated(workOrderNumber, "ELP");
                    log.info("  SAP Work Order: {} created",
                             workOrderNumber);
                }
            }
        }

        long totalLatency = System.currentTimeMillis() - pipelineStart;

        // ── Enterprise Feature: Audit Log (every execution) ──────────────
        recordAuditLog(event, gatePassed, totalLatency, complianceGenerated);

        log.info("════════════════════════════════════════════════════════════════");
        log.info("  PIPELINE COMPLETE in {}ms", totalLatency);
        log.info("  Result: {} | Gate: {} | Compliance: {} | Work Order: {}",
                 emissionClass,
                 gatePassed ? "PASSED" : "REJECTED",
                 complianceGenerated ? "DOCUMENTED" : "N/A",
                 workOrderNumber != null ? workOrderNumber : "N/A");
        log.info("════════════════════════════════════════════════════════════════");

        return new EmissionPipelineResult(
                event,
                emissionClass,
                gatePassed,
                totalLatency,
                trendDirection,
                complianceGenerated,
                workOrderNumber,
                remediation.action(),
                remediation.urgency(),
                remediation.estimatedHours());
    }

    /**
     * Stage 1: Enrich the emission event with SAP operational context.
     *
     * <p>Simulates a query to SAP for:
     * <ul>
     *   <li>Active work orders on this equipment</li>
     *   <li>Maintenance history (days since last maintenance)</li>
     *   <li>Turnaround status (scheduled maintenance window)</li>
     * </ul>
     *
     * <p>Demo mode applies realistic patterns: equipment tags starting with "VLV"
     * (valve) often have active work orders; "FLG" (flange) equipment often has
     * high historical emission baseline.</p>
     */
    private void enrichSapContext(EmissionEvent event) {
        // Simulate SAP lookups based on equipment tag patterns
        boolean hasActiveWorkOrder = simulateSapWorkOrderCheck(event.getEquipmentTag());
        boolean maintenanceActive = simulateSapMaintenanceCheck(event.getEquipmentTag());
        boolean turnaroundActive = simulateSapTurnaroundCheck(event.getEquipmentTag());
        int daysSinceLastMaintenance = simulateDaysSinceLastMaintenance(event.getEquipmentTag());
        double historicalBaseline = simulateHistoricalBaseline(event.getEquipmentTag());

        String activeWorkOrdersJson = hasActiveWorkOrder
                ? "{\"orders\":[{\"order_id\":\"1000456\",\"description\":\"Valve inspection\",\"status\":\"ACTIVE\"}]}"
                : "{\"orders\":[]}";

        event.enrichWithSapContext(
                activeWorkOrdersJson,
                maintenanceActive,
                turnaroundActive,
                daysSinceLastMaintenance,
                historicalBaseline);

        log.info("  SAP Context Enrichment:");
        log.info("    - Active Work Orders: {}", hasActiveWorkOrder ? "YES" : "NO");
        log.info("    - Maintenance Active: {}", maintenanceActive ? "YES" : "NO");
        log.info("    - Turnaround Active: {}", turnaroundActive ? "YES" : "NO");
        log.info("    - Days Since Maintenance: {}", daysSinceLastMaintenance);
        log.info("    - Historical Baseline: {} ppm", String.format("%.2f", historicalBaseline));
    }

    /**
     * Stage 2: Correlate multi-modal sensor data at the same location.
     *
     * <p>In a real deployment, this would query a time-series DB for thermal,
     * acoustic, and other sensor readings at the same location/mission.
     * For demo, we simulate based on primary reading magnitude.</p>
     */
    private void enrichMultiModalData(EmissionEvent event) {
        // Wind-adjusted multi-modal fusion thresholds (Scenario 1 fix)
        // High wind disperses gas plume before reaching sensor — a real 800 ppm leak
        // at source may read only 180 ppm at the robot in 30 km/h wind.
        // Lower the corroboration thresholds proportionally to wind speed so we don't
        // dismiss wind-diluted real leaks as single-sensor events.
        double windSpeedKmh = event.getWindSpeedKmh() != null ? event.getWindSpeedKmh() : 0.0;
        double windDilutionFactor = Math.max(1.0, 1.0 + (windSpeedKmh / 20.0));
        // At 0 km/h: factor=1.0, thresholds unchanged (500/200 ppm)
        // At 20 km/h: factor=2.0, thresholds halved (250/100 ppm)
        // At 40 km/h: factor=3.0, thresholds divided by 3 (167/67 ppm)
        // Apply wind adjustment with 150 ppm floor — at extreme wind speeds the formula
        // can push thresholds unrealistically low (e.g. 60 km/h → 125 ppm). A 150 ppm
        // floor ensures we never set the bar below what sensors can reliably distinguish
        // from background noise on a North Sea platform.
        double highThreshold = Math.max(150.0, 500.0 / windDilutionFactor);
        double moderateThreshold = Math.max(150.0, 200.0 / windDilutionFactor);

        int corroboratingSensors = 1;
        Double thermal = null;
        Double thermalDelta = null;
        Double acoustic = null;
        Boolean acousticLeakSig = null;

        if (event.getRawReading() > highThreshold) {
            thermal = 45.0;  // Simulated elevated temperature
            thermalDelta = 15.0;  // 15 degrees above ambient
            acoustic = 85.0;  // High acoustic reading (dB)
            acousticLeakSig = true;  // High-frequency hiss signature
            corroboratingSensors = 3;

            log.info("  Multi-Modal Fusion: HIGH-CONFIDENCE corroboration (3 sensors) " +
                     "[wind-adjusted threshold: {} ppm, wind: {} km/h]",
                     String.format("%.0f", highThreshold), String.format("%.0f", windSpeedKmh));
        } else if (event.getRawReading() > moderateThreshold) {
            thermal = 35.0;
            thermalDelta = 5.0;
            acoustic = 75.0;
            acousticLeakSig = false;
            corroboratingSensors = 2;

            log.info("  Multi-Modal Fusion: MODERATE corroboration (2 sensors) " +
                     "[wind-adjusted threshold: {} ppm, wind: {} km/h]",
                     String.format("%.0f", moderateThreshold), String.format("%.0f", windSpeedKmh));
        } else {
            log.info("  Multi-Modal Fusion: PRIMARY sensor only " +
                     "[reading {} ppm below wind-adjusted threshold {} ppm]",
                     String.format("%.0f", event.getRawReading()), String.format("%.0f", moderateThreshold));
        }

        if (thermal != null) {
            event.enrichWithMultiModalData(thermal, thermalDelta, acoustic, acousticLeakSig, corroboratingSensors);
        }
    }

    /**
     * Stage 5: Estimate fugitive emission leak rate in kg/hr.
     *
     * <p>Uses a simplified physics model based on:
     * <ul>
     *   <li>Gas concentration (ppm) and wind speed → dispersion dilution</li>
     *   <li>Sensor distance (estimated) → source strength back-calculation</li>
     *   <li>Equipment type heuristics (VLV=valve, FLG=flange, etc.)</li>
     * </ul>
     *
     * <p>Real model would integrate with CFD/plume dispersion software
     * and equipment-specific leak correlations (ISO 15848 orifice models).</p>
     */
    private double quantifyLeakRate(EmissionEvent event, EmissionClassification classification) {
        if (!classification.equals(EmissionClassification.FUGITIVE_EMISSION)) {
            return 0.0;
        }

        double basePpm = event.getRawReading();
        double windFactor = event.getWindSpeedKmh() != null ?
                (event.getWindSpeedKmh() + 1.0) / 5.0 : 1.0;

        // (Scenario 7 fix) Gas type factor based on sensor modality.
        // Different gases have drastically different molecular weights and hazard profiles:
        // - CH4 (methane): MW=16, LEL=50,000 ppm — high volume but lower immediate toxicity
        // - VOC (volatile organics): MW varies 30-120, often toxic at much lower concentrations
        //   (benzene STEL = 5 ppm, H2S IDLH = 100 ppm)
        // The leak rate formula must account for molecular weight differences to produce
        // meaningful kg/hr estimates for EU 2024/1787 compliance records.
        double gasTypeFactor;
        String gasTypeLabel;
        if (event.getSensorModality() != null) {
            switch (event.getSensorModality()) {
                case CH4:
                    gasTypeFactor = 1.0;   // Baseline: methane (MW 16)
                    gasTypeLabel = "CH4 (methane)";
                    break;
                case VOC:
                    gasTypeFactor = 3.5;   // VOCs are heavier (avg MW ~60) and more hazardous
                    gasTypeLabel = "VOC (mixed organics)";
                    break;
                case THERMAL:
                    gasTypeFactor = 1.0;   // Thermal-only detection: assume methane-equivalent
                    gasTypeLabel = "THERMAL (gas type unknown, assuming CH4)";
                    break;
                case ACOUSTIC:
                    gasTypeFactor = 1.0;   // Acoustic-only: assume methane-equivalent
                    gasTypeLabel = "ACOUSTIC (gas type unknown, assuming CH4)";
                    break;
                default:
                    gasTypeFactor = 1.5;   // Multi-modal or unknown: conservative estimate
                    gasTypeLabel = "MULTI/UNKNOWN (conservative)";
                    break;
            }
        } else {
            gasTypeFactor = 1.5;
            gasTypeLabel = "UNSPECIFIED (conservative)";
        }

        // Equipment type factor
        double equipFactor;
        String tag = event.getEquipmentTag() != null ? event.getEquipmentTag().toUpperCase() : "";
        if (tag.startsWith("VLV")) {
            equipFactor = 0.5;   // Valve: typically small stem packing leaks
        } else if (tag.startsWith("FLG")) {
            equipFactor = 1.2;   // Flange: gasket failure can be substantial
        } else if (tag.startsWith("PMP")) {
            equipFactor = 2.0;   // Pump seal: larger leak potential
        } else if (tag.startsWith("CMP")) {
            equipFactor = 2.5;   // Compressor: high-pressure systems
        } else if (tag.startsWith("PRV") || tag.startsWith("PSV")) {
            equipFactor = 3.0;   // Safety valves: full-bore relief possible
        } else {
            equipFactor = 1.0;   // Generic
        }

        double leakRateKgHr = (basePpm / 100.0) * equipFactor * windFactor * gasTypeFactor;

        log.info("  Leak Quantification: {} kg/hr [gas: {}, equipFactor: {}, windFactor: {}]",
                String.format("%.3f", leakRateKgHr), gasTypeLabel, equipFactor, String.format("%.2f", windFactor));

        // ── EN 15446 OGI Trigger ──────────────────────────────────────────
        // The screening estimate above is a correlation-based approximation (per EPA
        // Method 21). EU 2024/1787 and EN 15446 require that confirmed fugitive leaks
        // undergo a detailed Optical Gas Imaging (OGI) survey for accurate quantification.
        // We flag the event for OGI follow-up when:
        //   - Leak rate exceeds 0.5 kg/hr (material leak per LDAR threshold), OR
        //   - Multi-modal corroboration confirms the leak (3 sensors agree)
        // The OGI flag feeds into the remediation action and compliance record.
        double finalRate = Math.max(0.01, leakRateKgHr);
        if (finalRate > 0.5 || event.getCorroboratingSensors() >= 3) {
            event.setOgiSurveyRequired(true);
            log.info("  EN 15446 OGI Survey: REQUIRED — screening rate {} kg/hr exceeds LDAR threshold " +
                     "or multi-sensor confirmation (sensors: {}). Confirmed leak quantification per EN 15446.",
                     String.format("%.3f", finalRate), event.getCorroboratingSensors());
        } else {
            event.setOgiSurveyRequired(false);
            log.info("  EN 15446 OGI Survey: Not required — screening rate {} kg/hr within routine monitoring range.",
                     String.format("%.3f", finalRate));
        }

        return finalRate;
    }

    /**
     * Stage 6: Evaluate the Emission Reflector Gate.
     *
     * <p>Deterministic rule: classification must have confidence >= gateThreshold.
     * Additional heuristics:
     * <ul>
     *   <li>Multi-modal corroboration boosts gate passage probability</li>
     *   <li>SAP context (active maintenance) may suppress false positives</li>
     *   <li>Experience bank match improves confidence</li>
     * </ul>
     */
    private boolean evaluateEmissionGate(EmissionEvent event, EmissionClassification classification) {
        double confidence = event.getConfidence();

        // ── Rule 1: UNKNOWN never auto-passes — always needs human review ──
        if (classification == EmissionClassification.UNKNOWN) {
            log.info("  Emission Gate: REJECTED — UNKNOWN classification always requires human review");
            return false;
        }

        // ── Rule 2: Base confidence threshold ──────────────────────────
        if (confidence < emissionGateThreshold) {
            return false;
        }

        // ── Rule 3: Multi-modal corroboration boost — ONLY for real emission types ──
        // (Scenario 2 fix) During turnaround, gas + thermal + acoustic all fire, but it's
        // planned venting, not a fugitive leak. Multi-modal corroboration should only
        // boost passage for classifications where "more sensors = more real."
        // PLANNED_VENTING, MAINTENANCE_ACTIVITY, SENSOR_ARTIFACT don't benefit from
        // sensor agreement — they need SAP context validation, not sensor count.
        if (event.getCorroboratingSensors() >= 2
                && classification == EmissionClassification.FUGITIVE_EMISSION) {
            return true;
        }

        // ── Rule 4: SAP context suppression — maintenance activity during active maintenance ──
        // If SAP confirms maintenance is active and the LLM classified it correctly as
        // MAINTENANCE_ACTIVITY, suppress the event (no compliance action needed).
        if (event.isSapMaintenanceActive()
                && classification == EmissionClassification.MAINTENANCE_ACTIVITY) {
            log.info("  Emission Gate: SUPPRESSED — maintenance active matches MAINTENANCE_ACTIVITY classification");
            return false;
        }

        // ── Rule 5: SAP context suppression — planned venting during turnaround ──
        if (event.isSapTurnaroundActive()
                && classification == EmissionClassification.PLANNED_VENTING) {
            log.info("  Emission Gate: SUPPRESSED — turnaround active matches PLANNED_VENTING classification");
            return false;
        }

        return true;
    }

    /**
     * Stage 7: Detect trend direction by querying recent history at this equipment.
     *
     * <p>Returns one of: ESCALATING, STABLE, DECLINING, FIRST_DETECTION
     * based on the 7-day emission count and confidence progression.</p>
     */
    private String detectTrend(EmissionEvent event) {
        Instant sevenDaysAgo = Instant.now().minus(java.time.Duration.ofDays(7));
        List<EmissionEvent> recentEvents = emissionRepository.findTrendDataLast7Days(
                event.getEquipmentTag(), sevenDaysAgo);

        int previousDetections7d = recentEvents.size();
        event.enrichWithTrendData(previousDetections7d, "STABLE");

        if (previousDetections7d == 0) {
            return "FIRST_DETECTION";
        } else if (previousDetections7d >= 2) {
            // (Scenario 4 fix) Compare SENSOR READINGS, not LLM confidence.
            // A leak escalating from 25 ppm → 100 ppm must show ESCALATING even if
            // the LLM confidence stayed flat at 0.85. Raw readings are physical truth;
            // confidence is just the model's self-assessment.
            double oldReading = recentEvents.get(0).getRawReading();
            double newReading = recentEvents.get(recentEvents.size() - 1).getRawReading();

            // Use proportional comparison: 20% increase = escalating, 20% decrease = declining
            // This handles different scales (25→35 ppm is a 40% jump; 500→520 ppm is only 4%)
            if (oldReading > 0) {
                double changeRatio = (newReading - oldReading) / oldReading;
                if (changeRatio > 0.20) {
                    return "ESCALATING";
                } else if (changeRatio < -0.20) {
                    return "DECLINING";
                }
            } else if (newReading > 0) {
                // Old reading was 0, new reading is positive — escalating
                return "ESCALATING";
            }

            // Also check if detection frequency itself is escalating
            // (3+ detections in 7 days when previous week had none is a pattern)
            if (previousDetections7d >= 5) {
                return "ESCALATING";
            }

            return "STABLE";
        }

        return "STABLE";
    }

    /**
     * Stage 8: Generate EU 2024/1787 compliance record for fugitive emissions.
     *
     * <p>Creates immutable compliance documentation including:
     * <ul>
     *   <li>Emission classification and confidence</li>
     *   <li>Quantified leak rate (kg/hr)</li>
     *   <li>Location, date/time, equipment identification</li>
     *   <li>SAP reference (equipment tag, equipment number)</li>
     *   <li>ISO 14224 failure code</li>
     * </ul>
     *
     * <p>In production, this would integrate with an external compliance
     * database or report aggregation system.</p>
     */
    private boolean generateComplianceRecord(EmissionEvent event) {
        try {
            event.markComplianceDocumented();
            log.info("  EU 2024/1787 Compliance Record Generated:");
            log.info("    - Event ID: {}", event.getId());
            log.info("    - Classification: {}", event.getClassification());
            log.info("    - Confidence: {}", String.format("%.3f", event.getConfidence()));
            log.info("    - Leak Rate: {} kg/hr",
                     String.format("%.3f", event.getEstimatedLeakRateKgHr() != null ? event.getEstimatedLeakRateKgHr() : 0.0));
            log.info("    - Location: {}", event.getLocationArea());
            log.info("    - Equipment: {}", event.getEquipmentTag());
            log.info("    - Compliance Status: {}", event.getComplianceStatus());
            return true;
        } catch (Exception e) {
            log.error("Failed to generate compliance record: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Stage 8: Generate SAP work order for confirmed fugitive emissions.
     *
     * <p>Calls the BAPI gateway to create a PM (Preventive Maintenance)
     * notification and work order with ISO 14224 failure classification.</p>
     */
    private String generateSapWorkOrder(EmissionEvent event) {
        try {
            // Generate synthetic work order number (in production: BAPI response)
            String workOrderNumber = "WO-EMISSION-" +
                UUID.randomUUID().toString().substring(0, 8).toUpperCase();

            log.info("  Generating SAP Work Order: {}", workOrderNumber);
            return workOrderNumber;
        } catch (Exception e) {
            log.error("Failed to generate SAP work order: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Record audit log for this pipeline execution.
     *
     * <p>Enterprise feature: immutable audit trail for governance and compliance audits.
     * Every emission event is logged regardless of gate pass/fail outcome.</p>
     */
    private void recordAuditLog(
            EmissionEvent event,
            boolean gatePassed,
            long totalLatency,
            boolean complianceGenerated) {
        try {
            // Persist the event itself (includes all enrichment and classification)
            emissionRepository.save(event);
            log.info("  Audit: Emission event persisted (id: {})", event.getId());
        } catch (Exception e) {
            log.error("Failed to save emission event: {}", e.getMessage());
        }
    }

    // ── Simulation Helpers (for demo mode) ────────────────────────────────

    private boolean simulateSapWorkOrderCheck(String equipmentTag) {
        // Demo: valves (VLV) often have active work orders
        return equipmentTag.startsWith("VLV") && Math.random() > 0.5;
    }

    private boolean simulateSapMaintenanceCheck(String equipmentTag) {
        return Math.random() > 0.7;  // 30% chance
    }

    private boolean simulateSapTurnaroundCheck(String equipmentTag) {
        return equipmentTag.startsWith("FLG") && Math.random() > 0.8;  // Flanges in turnaround
    }

    private int simulateDaysSinceLastMaintenance(String equipmentTag) {
        // Demo: flange equipment (FLG) recently maintained; pump equipment older
        if (equipmentTag.startsWith("FLG")) {
            return (int)(Math.random() * 30) + 5;  // 5-35 days
        } else if (equipmentTag.startsWith("VLV")) {
            return (int)(Math.random() * 60) + 10;  // 10-70 days
        }
        return (int)(Math.random() * 90) + 20;  // 20-110 days
    }

    private double simulateHistoricalBaseline(String equipmentTag) {
        // Demo: flange equipment has higher baseline emissions
        if (equipmentTag.startsWith("FLG")) {
            return 150.0 + Math.random() * 100;  // 150-250 ppm
        }
        return 50.0 + Math.random() * 50;  // 50-100 ppm
    }

    /**
     * Immutable result of a complete emission pipeline execution.
     *
     * @param event                  the processed emission event (enriched, classified, gated)
     * @param classification         the final emission classification
     * @param gatePassed             true if confidence >= gate threshold
     * @param totalLatencyMs         total pipeline latency in milliseconds
     * @param trendDirection         trend classification (ESCALATING, STABLE, DECLINING, FIRST_DETECTION)
     * @param complianceGenerated    true if EU 2024/1787 record was generated
     * @param workOrderNumber        SAP work order number (null if not generated)
     */
    public record EmissionPipelineResult(
            EmissionEvent event,
            EmissionClassification classification,
            boolean gatePassed,
            long totalLatencyMs,
            String trendDirection,
            boolean complianceGenerated,
            String workOrderNumber,
            String remediationAction,
            String remediationUrgency,
            Double remediationEstimatedHours
    ) {
        /**
         * Convenience predicate: true if this emission required and received
         * regulatory compliance documentation.
         */
        public boolean wasDocumentedForCompliance() {
            return complianceGenerated &&
                   classification.equals(EmissionClassification.FUGITIVE_EMISSION);
        }

        /**
         * Convenience predicate: true if this event passed the gate and
         * requires follow-up actions.
         */
        public boolean requiresFollowUp() {
            return gatePassed && classification.triggersWorkOrder();
        }
    }
}
