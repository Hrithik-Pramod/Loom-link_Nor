package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.EmissionClassification;
import com.loomlink.edge.domain.model.EmissionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Prescriptive Remediation Engine for Challenge 02 — Emission Surveillance.
 *
 * <p>Generates equipment-specific, context-aware remediation recommendations
 * based on the emission classification, sensor data, SAP context, and trend analysis.
 * This directly addresses the Nordic Energy Matchbox "Solution Suggestion" requirement
 * under the MITIGATE pillar.</p>
 *
 * <p>Remediation recommendations are deterministic (rule-based) rather than LLM-generated
 * to ensure safety-critical actions are predictable and auditable. The rules encode
 * domain knowledge from ISO 14224, EU 2024/1787 response protocols, and Equinor
 * maintenance best practices.</p>
 *
 * <p>Urgency levels map to EU 2024/1787 response timelines:</p>
 * <ul>
 *   <li>IMMEDIATE — Safety-critical, initiate response within 1 hour</li>
 *   <li>WITHIN_24H — High priority, schedule repair within 24 hours</li>
 *   <li>WITHIN_72H — Standard EU 2024/1787 compliance window</li>
 *   <li>ROUTINE — Schedule during next planned maintenance window</li>
 *   <li>MONITOR_ONLY — Continue monitoring, no physical action required</li>
 * </ul>
 */
@Service
public class EmissionRemediationService {

    private static final Logger log = LoggerFactory.getLogger(EmissionRemediationService.class);

    /**
     * Generate a prescriptive remediation recommendation for a classified emission event.
     *
     * @param event the fully classified and enriched emission event
     * @return a RemediationResult with action, urgency, and estimated hours
     */
    public RemediationResult recommend(EmissionEvent event) {
        EmissionClassification classification = event.getClassification();
        if (classification == null) {
            return new RemediationResult(
                    "Await classification before determining remediation action.",
                    "MONITOR_ONLY", null);
        }

        return switch (classification) {
            case FUGITIVE_EMISSION -> recommendForFugitive(event);
            case PLANNED_VENTING -> recommendForPlannedVenting(event);
            case MAINTENANCE_ACTIVITY -> recommendForMaintenance(event);
            case SENSOR_ARTIFACT -> recommendForSensorArtifact(event);
            case NORMAL_PROCESS -> recommendForNormalProcess(event);
            case UNKNOWN -> recommendForUnknown(event);
        };
    }

    private RemediationResult recommendForFugitive(EmissionEvent event) {
        String tag = event.getEquipmentTag() != null ? event.getEquipmentTag().toUpperCase() : "";
        double leakRate = event.getEstimatedLeakRateKgHr() != null ? event.getEstimatedLeakRateKgHr() : 0.0;
        String trend = event.getTrendDirection() != null ? event.getTrendDirection() : "STABLE";
        int sensors = event.getCorroboratingSensors();
        Integer daysSinceMaint = event.getDaysSinceLastMaintenance();

        StringBuilder action = new StringBuilder();
        String urgency;
        double estimatedHours;

        // High leak rate or escalating trend = IMMEDIATE
        if (leakRate > 2.0 || "ESCALATING".equals(trend)) {
            urgency = "IMMEDIATE";

            if (tag.startsWith("FLG")) {
                action.append("CRITICAL: Isolate flange joint ").append(event.getEquipmentTag())
                      .append(". Verify bolt torque to OEM specification (typically 45-65 Nm for NPS 4\" flanges). ");
                action.append("Inspect gasket integrity — replace if degraded. ");
                if (sensors >= 3) {
                    action.append("Multi-modal confirmation (thermal + acoustic + gas) indicates active leak at joint face. ");
                }
                action.append("Deploy leak sealing clamp if gasket replacement requires process shutdown. ");
                action.append("Per EU 2024/1787 §4.2: document leak rate and initiate repair within 1 hour.");
                estimatedHours = 4.0;

            } else if (tag.startsWith("VLV")) {
                action.append("CRITICAL: Isolate valve ").append(event.getEquipmentTag())
                      .append(" and verify stem packing integrity. ");
                action.append("Tighten packing gland bolts incrementally (quarter-turn steps). ");
                action.append("If packing adjustment fails, schedule valve overhaul with stem seal replacement. ");
                action.append("Per EU 2024/1787 §4.2: initiate isolation within 1 hour.");
                estimatedHours = 6.0;

            } else if (tag.startsWith("PMP")) {
                action.append("CRITICAL: Isolate pump ").append(event.getEquipmentTag())
                      .append(" and inspect mechanical seal assembly. ");
                action.append("Check seal face wear, O-ring condition, and spring tension. ");
                action.append("If seal replacement required, prepare standby pump for switchover. ");
                action.append("Verify bearing temperature and vibration levels before restart.");
                estimatedHours = 8.0;

            } else if (tag.startsWith("CMP")) {
                action.append("CRITICAL: Initiate controlled shutdown of compressor ").append(event.getEquipmentTag())
                      .append(". Inspect rod packing, distance piece vents, and process seals. ");
                action.append("High-pressure gas systems require confined space entry protocol. ");
                action.append("Coordinate with operations for safe depressurization sequence.");
                estimatedHours = 12.0;

            } else if (tag.startsWith("PRV")) {
                action.append("CRITICAL: PRV ").append(event.getEquipmentTag())
                      .append(" showing fugitive emission — verify set pressure and reseat condition. ");
                action.append("If PRV is leaking past seat, isolate via block valve (if installed) and replace. ");
                action.append("Document per API 576 inspection requirements.");
                estimatedHours = 4.0;

            } else {
                action.append("CRITICAL: Isolate equipment ").append(event.getEquipmentTag())
                      .append(" and perform visual/OGI inspection to locate leak source. ");
                action.append("Deploy portable gas detector for pinpoint confirmation. ");
                action.append("Initiate repair per equipment-specific maintenance procedure.");
                estimatedHours = 6.0;
            }

        } else {
            // Moderate leak rate, stable trend = WITHIN_24H
            urgency = "WITHIN_24H";

            if (tag.startsWith("FLG")) {
                action.append("Schedule flange re-torque on ").append(event.getEquipmentTag())
                      .append(" within 24 hours. Verify gasket type matches service conditions (material, pressure class). ");
                if (daysSinceMaint != null && daysSinceMaint > 60) {
                    action.append("Equipment aging detected (").append(daysSinceMaint).append(" days since last maintenance) — ");
                    action.append("include full flange inspection in scope. ");
                }
                action.append("Per EU 2024/1787: repair within 72-hour compliance window.");
                estimatedHours = 3.0;

            } else if (tag.startsWith("VLV")) {
                action.append("Schedule valve packing adjustment on ").append(event.getEquipmentTag())
                      .append(" within 24 hours. Inspect stem for scoring or corrosion. ");
                action.append("If valve is >5 years old, consider full overhaul during next turnaround.");
                estimatedHours = 4.0;

            } else {
                action.append("Schedule inspection and repair of ").append(event.getEquipmentTag())
                      .append(" within 24 hours. Use OGI camera to confirm leak location before repair. ");
                action.append("Per EU 2024/1787: complete repair within 72-hour compliance window.");
                estimatedHours = 4.0;
            }
        }

        log.info("  Remediation [FUGITIVE]: {} | Urgency: {} | Est: {}h",
                event.getEquipmentTag(), urgency, estimatedHours);

        return new RemediationResult(action.toString(), urgency, estimatedHours);
    }

    private RemediationResult recommendForPlannedVenting(EmissionEvent event) {
        String action = String.format(
                "Planned venting confirmed at %s. Verify emission volume against permit allocation. " +
                "Ensure venting is logged in emission inventory per EU 2024/1787 Annex III. " +
                "Monitor duration — if venting exceeds planned window, escalate to operations supervisor. " +
                "No physical repair action required.",
                event.getEquipmentTag());

        return new RemediationResult(action, "MONITOR_ONLY", null);
    }

    private RemediationResult recommendForMaintenance(EmissionEvent event) {
        String action = String.format(
                "Emission detected during active maintenance on %s. Verify emission is contained within " +
                "maintenance boundary and work permit scope. Ensure LEL monitoring is active in work zone. " +
                "If emission exceeds expected levels, halt maintenance and investigate. " +
                "Document emission in work order completion report per ISO 14224.",
                event.getEquipmentTag());

        return new RemediationResult(action, "MONITOR_ONLY", null);
    }

    private RemediationResult recommendForSensorArtifact(EmissionEvent event) {
        StringBuilder action = new StringBuilder();
        action.append(String.format("Sensor artifact detected at %s. ", event.getEquipmentTag()));

        if (event.getWindSpeedKmh() != null && event.getWindSpeedKmh() > 15) {
            action.append(String.format("High wind speed (%.0f km/h) may cause sensor drift — ", event.getWindSpeedKmh()));
            action.append("consider wind-sheltered sensor placement for this location. ");
        }

        action.append("Schedule sensor calibration check during next routine inspection round. ");
        action.append("If artifact recurs >3 times in 7 days, replace sensor head and verify wiring.");

        return new RemediationResult(action.toString(), "ROUTINE", 1.0);
    }

    private RemediationResult recommendForNormalProcess(EmissionEvent event) {
        String action = String.format(
                "Reading at %s is within normal operational baseline (%.0f ppm vs %.0f ppm baseline). " +
                "No remediation required. Continue routine monitoring schedule.",
                event.getEquipmentTag(),
                event.getRawReading(),
                event.getHistoricalBaselinePpm() != null ? event.getHistoricalBaselinePpm() : 0.0);

        return new RemediationResult(action, "MONITOR_ONLY", null);
    }

    private RemediationResult recommendForUnknown(EmissionEvent event) {
        // (Scenario 5 fix) UNKNOWN classification can't always wait 24 hours.
        // If the system can't classify the emission, the urgency depends on:
        // 1. Equipment criticality — safety-critical equipment (PRV, PSV, BDV) = IMMEDIATE
        // 2. Reading magnitude — high readings suggest real hazard regardless of classification
        // 3. Multi-modal corroboration — multiple sensors seeing something = likely real
        String tag = event.getEquipmentTag() != null ? event.getEquipmentTag().toUpperCase() : "";
        double reading = event.getRawReading();
        int sensors = event.getCorroboratingSensors();
        boolean isSafetyCritical = tag.startsWith("PRV") || tag.startsWith("PSV") || tag.startsWith("BDV");
        Double baseline = event.getHistoricalBaselinePpm();
        boolean highRelativeToBaseline = baseline != null && baseline > 0 && reading > baseline * 3.0;

        StringBuilder action = new StringBuilder();
        String urgency;
        double estimatedHours;

        if (isSafetyCritical || reading > 500 || (highRelativeToBaseline && sensors >= 2)) {
            // Safety-critical equipment OR high reading OR strong multi-modal + high baseline ratio
            urgency = "IMMEDIATE";
            estimatedHours = 2.0;
            action.append(String.format(
                    "CRITICAL UNKNOWN: Classification uncertain for %s but conditions indicate potential hazard. ",
                    event.getEquipmentTag()));
            if (isSafetyCritical) {
                action.append("Safety-critical equipment — treat as real emission until proven otherwise. ");
                action.append("Initiate area evacuation protocol per facility emergency plan. ");
            }
            if (reading > 500) {
                action.append(String.format("High reading (%.0f ppm) exceeds safe investigation threshold. ", reading));
                action.append("Deploy technician with SCBA (Self-Contained Breathing Apparatus) for approach. ");
            }
            action.append("Use portable multi-gas detector (PID/FID + H2S/LEL) for manual verification. ");
            action.append("Do NOT approach without confirmed atmospheric monitoring. ");
            action.append("Cross-reference SAP equipment master data to confirm equipment identity. ");
            action.append("If confirmed as real emission, reclassify and initiate immediate response protocol.");

        } else if (sensors >= 2 || highRelativeToBaseline) {
            // Moderate concern — multiple sensors or elevated baseline
            urgency = "WITHIN_24H";
            estimatedHours = 2.0;
            action.append(String.format(
                    "Classification uncertain for %s — requires human expert review within 24 hours. ",
                    event.getEquipmentTag()));
            action.append("Deploy technician with portable gas detector (PID/FID) for manual verification. ");
            action.append("Cross-reference with SAP equipment master data to confirm equipment identity. ");
            action.append("If confirmed as real emission, reclassify and initiate appropriate response protocol.");

        } else {
            // Low concern — single sensor, moderate reading, non-critical equipment
            urgency = "WITHIN_24H";
            estimatedHours = 2.0;
            action.append(String.format(
                    "Classification uncertain for %s — requires human expert review. ",
                    event.getEquipmentTag()));
            action.append("Deploy technician with portable gas detector (PID/FID) for manual verification. ");
            action.append("Cross-reference with SAP equipment master data to confirm equipment identity. ");
            action.append("If confirmed as real emission, reclassify and initiate appropriate response protocol.");
        }

        log.info("  Remediation [UNKNOWN]: {} | Urgency: {} | Safety-critical: {} | Reading: {:.0f} ppm",
                event.getEquipmentTag(), urgency, isSafetyCritical, reading);

        return new RemediationResult(action.toString(), urgency, estimatedHours);
    }

    /**
     * Prescriptive remediation recommendation result.
     *
     * @param action          detailed, equipment-specific remediation instruction
     * @param urgency         urgency level (IMMEDIATE, WITHIN_24H, WITHIN_72H, ROUTINE, MONITOR_ONLY)
     * @param estimatedHours  estimated hours to complete (null if monitoring only)
     */
    public record RemediationResult(
            String action,
            String urgency,
            Double estimatedHours
    ) {}
}
