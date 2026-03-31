package com.loomlink.edge.domain.enums;

import java.util.Map;
import java.util.Set;

/**
 * ISO 14224 Equipment Class taxonomy — determines which failure modes
 * are physically plausible for each equipment type.
 *
 * <p>In North Sea operations, equipment tags follow a prefix convention
 * that encodes the equipment class:</p>
 * <ul>
 *   <li>P-xxxx = Pump (rotating)</li>
 *   <li>K-xxxx = Compressor (rotating)</li>
 *   <li>T-xxxx = Turbine (rotating)</li>
 *   <li>M-xxxx = Motor/Driver (rotating)</li>
 *   <li>E-xxxx = Heat Exchanger (static)</li>
 *   <li>V-xxxx = Vessel/Tank (static)</li>
 *   <li>D-xxxx = Drum/Separator (static)</li>
 *   <li>L-xxxx = Pipeline/Piping (static)</li>
 *   <li>FLG-xxxx = Flange (static)</li>
 *   <li>VLV-xxxx = Valve (mechanical)</li>
 *   <li>PRV-xxxx = Pressure Relief Valve (safety)</li>
 *   <li>PMP-xxxx = Pump (rotating, alternate prefix)</li>
 *   <li>CMP-xxxx = Compressor (rotating, alternate prefix)</li>
 * </ul>
 *
 * <p>This mapping is used by the Reflector Gate (Rule 3) to reject classifications
 * that are physically impossible — e.g., VIB (vibration) on a heat exchanger,
 * or FTS (failure to start) on static piping.</p>
 *
 * @see <a href="https://www.iso.org/standard/64076.html">ISO 14224:2016 Table A.4</a>
 */
public enum EquipmentClass {

    /**
     * Rotating equipment: pumps, compressors, turbines, motors, fans.
     * Subject to vibration, bearing wear, seal failure, imbalance.
     */
    PUMP("Rotating - Pump",
            Set.of(FailureModeCode.VIB, FailureModeCode.NOI, FailureModeCode.OHE,
                    FailureModeCode.BRD, FailureModeCode.FTS, FailureModeCode.UST,
                    FailureModeCode.HIO, FailureModeCode.LOO, FailureModeCode.ELP,
                    FailureModeCode.ELU, FailureModeCode.INL, FailureModeCode.FTF,
                    FailureModeCode.AIR, FailureModeCode.SER, FailureModeCode.PDE,
                    FailureModeCode.OTH)),

    COMPRESSOR("Rotating - Compressor",
            Set.of(FailureModeCode.VIB, FailureModeCode.NOI, FailureModeCode.OHE,
                    FailureModeCode.BRD, FailureModeCode.FTS, FailureModeCode.UST,
                    FailureModeCode.HIO, FailureModeCode.LOO, FailureModeCode.ELP,
                    FailureModeCode.ELU, FailureModeCode.INL, FailureModeCode.FTF,
                    FailureModeCode.AIR, FailureModeCode.SER, FailureModeCode.PDE,
                    FailureModeCode.OTH)),

    TURBINE("Rotating - Turbine",
            Set.of(FailureModeCode.VIB, FailureModeCode.NOI, FailureModeCode.OHE,
                    FailureModeCode.BRD, FailureModeCode.FTS, FailureModeCode.UST,
                    FailureModeCode.HIO, FailureModeCode.LOO, FailureModeCode.ELP,
                    FailureModeCode.ELU, FailureModeCode.FTF,
                    FailureModeCode.AIR, FailureModeCode.SER, FailureModeCode.PDE,
                    FailureModeCode.OTH)),

    GENERATOR("Rotating - Generator",
            Set.of(FailureModeCode.VIB, FailureModeCode.NOI, FailureModeCode.OHE,
                    FailureModeCode.BRD, FailureModeCode.FTS, FailureModeCode.UST,
                    FailureModeCode.HIO, FailureModeCode.LOO,
                    FailureModeCode.AIR, FailureModeCode.SER, FailureModeCode.PDE,
                    FailureModeCode.OTH)),

    MOTOR("Rotating - Electric Motor",
            Set.of(FailureModeCode.VIB, FailureModeCode.NOI, FailureModeCode.OHE,
                    FailureModeCode.BRD, FailureModeCode.FTS, FailureModeCode.UST,
                    FailureModeCode.AIR, FailureModeCode.SER, FailureModeCode.PDE,
                    FailureModeCode.OTH)),

    /**
     * Static equipment: heat exchangers, vessels, piping, flanges, drums.
     * Subject to corrosion, erosion, plugging, structural degradation.
     * NOT subject to vibration or start-on-demand failures.
     */
    HEAT_EXCHANGER("Static - Heat Exchanger",
            Set.of(FailureModeCode.ERO, FailureModeCode.INL, FailureModeCode.ELP,
                    FailureModeCode.ELU, FailureModeCode.PLU, FailureModeCode.STD,
                    FailureModeCode.OHE, FailureModeCode.BRD,
                    FailureModeCode.AIR, FailureModeCode.SER, FailureModeCode.PDE,
                    FailureModeCode.OTH)),

    VESSEL("Static - Pressure Vessel",
            Set.of(FailureModeCode.ERO, FailureModeCode.INL, FailureModeCode.ELP,
                    FailureModeCode.ELU, FailureModeCode.PLU, FailureModeCode.STD,
                    FailureModeCode.OHE, FailureModeCode.BRD,
                    FailureModeCode.AIR, FailureModeCode.SER, FailureModeCode.PDE,
                    FailureModeCode.OTH)),

    PIPING("Static - Piping",
            Set.of(FailureModeCode.ERO, FailureModeCode.INL, FailureModeCode.ELP,
                    FailureModeCode.ELU, FailureModeCode.PLU, FailureModeCode.STD,
                    FailureModeCode.AIR, FailureModeCode.SER, FailureModeCode.PDE,
                    FailureModeCode.OTH)),

    /**
     * Valve equipment: control valves, isolation valves, check valves.
     * Subject to leakage, plugging, failure to operate on demand.
     */
    VALVE("Control - Valve",
            Set.of(FailureModeCode.ELP, FailureModeCode.ELU, FailureModeCode.INL,
                    FailureModeCode.PLU, FailureModeCode.FTF, FailureModeCode.FTS,
                    FailureModeCode.NOI, FailureModeCode.STD, FailureModeCode.BRD,
                    FailureModeCode.AIR, FailureModeCode.SER, FailureModeCode.PDE,
                    FailureModeCode.OTH)),

    /**
     * Instrument/control equipment: sensors, transmitters, actuators.
     */
    INSTRUMENT("Control - Instrument",
            Set.of(FailureModeCode.AIR, FailureModeCode.PDE, FailureModeCode.FTF,
                    FailureModeCode.FTS, FailureModeCode.SLL, FailureModeCode.SHL,
                    FailureModeCode.BRD, FailureModeCode.SER, FailureModeCode.OTH)),

    /**
     * Safety-critical equipment: PSVs, ESD valves, blowdown valves.
     * Failure modes are tightly constrained — any anomaly is safety-relevant.
     */
    SAFETY_SYSTEM("Safety - SIS Component",
            Set.of(FailureModeCode.ELP, FailureModeCode.ELU, FailureModeCode.INL,
                    FailureModeCode.FTF, FailureModeCode.FTS, FailureModeCode.PLU,
                    FailureModeCode.STD, FailureModeCode.AIR, FailureModeCode.SER,
                    FailureModeCode.PDE, FailureModeCode.OTH)),

    /**
     * Electrical equipment: switchgear, transformers, cables.
     */
    ELECTRICAL("Electrical - Switchgear / Transformer",
            Set.of(FailureModeCode.AIR, FailureModeCode.PDE, FailureModeCode.FTF,
                    FailureModeCode.FTS, FailureModeCode.SLL, FailureModeCode.SHL,
                    FailureModeCode.BRD, FailureModeCode.OHE,
                    FailureModeCode.SER, FailureModeCode.OTH)),

    /**
     * Unknown equipment class — when the tag prefix is not recognized.
     * All failure modes are allowed (conservative approach), but a warning is logged.
     */
    UNKNOWN("Unknown Equipment Class", Set.of(FailureModeCode.values()));

    private final String description;
    private final Set<FailureModeCode> validFailureModes;

    EquipmentClass(String description, Set<FailureModeCode> validFailureModes) {
        this.description = description;
        this.validFailureModes = validFailureModes;
    }

    /**
     * Check whether a failure mode is physically plausible for this equipment class.
     * UNK (Unknown) is always considered valid — it will be caught by Rule 2 of the gate.
     *
     * @param code the failure mode code to validate
     * @return true if this failure mode can occur on this equipment class per ISO 14224
     */
    public boolean isValidFailureMode(FailureModeCode code) {
        if (code == FailureModeCode.UNK) return true; // Handled by gate Rule 2
        return validFailureModes.contains(code);
    }

    public String getDescription() {
        return description;
    }

    public Set<FailureModeCode> getValidFailureModes() {
        return validFailureModes;
    }

    // ── Tag Prefix to Equipment Class Mapping ──────────────────────

    private static final Map<String, EquipmentClass> TAG_PREFIX_MAP = Map.ofEntries(
            // Rotating equipment
            Map.entry("P-", PUMP),
            Map.entry("PMP", PUMP),
            Map.entry("K-", COMPRESSOR),
            Map.entry("CMP", COMPRESSOR),
            Map.entry("T-", TURBINE),
            Map.entry("G-", GENERATOR),
            Map.entry("M-", MOTOR),
            Map.entry("FN-", MOTOR),         // Fan (rotating)

            // Static equipment
            Map.entry("E-", HEAT_EXCHANGER),
            Map.entry("V-", VESSEL),
            Map.entry("D-", VESSEL),          // Drum/Separator → vessel class
            Map.entry("L-", PIPING),
            Map.entry("FLG", PIPING),         // Flange → piping class

            // Valve equipment
            Map.entry("VLV", VALVE),
            Map.entry("XV-", VALVE),          // ESD/Shutdown valve
            Map.entry("CV-", VALVE),          // Control valve

            // Safety-critical
            Map.entry("PRV", SAFETY_SYSTEM),
            Map.entry("PSV", SAFETY_SYSTEM),
            Map.entry("BDV", SAFETY_SYSTEM),  // Blowdown valve

            // Electrical/Instrument
            Map.entry("FT-", INSTRUMENT),     // Flow Transmitter
            Map.entry("PT-", INSTRUMENT),     // Pressure Transmitter
            Map.entry("TT-", INSTRUMENT),     // Temperature Transmitter
            Map.entry("LT-", INSTRUMENT),     // Level Transmitter
            Map.entry("ZT-", INSTRUMENT),     // Position Transmitter
            Map.entry("AT-", INSTRUMENT)      // Analyzer Transmitter
    );

    /**
     * Derive equipment class from a North Sea equipment tag.
     * Tries longest prefix match first for specificity (e.g., "PMP" before "P-").
     *
     * @param equipmentTag e.g., "P-1001A", "VLV-3201B", "E-4001"
     * @return the derived equipment class, or UNKNOWN if prefix not recognized
     */
    public static EquipmentClass fromEquipmentTag(String equipmentTag) {
        if (equipmentTag == null || equipmentTag.isBlank()) return UNKNOWN;

        String tag = equipmentTag.toUpperCase().trim();

        // Try 3-character prefixes first (PMP, CMP, FLG, VLV, PRV, PSV, BDV)
        if (tag.length() >= 3) {
            EquipmentClass match = TAG_PREFIX_MAP.get(tag.substring(0, 3));
            if (match != null) return match;
        }

        // Then try 2-character prefixes (P-, K-, T-, M-, E-, V-, D-, L-)
        if (tag.length() >= 2) {
            EquipmentClass match = TAG_PREFIX_MAP.get(tag.substring(0, 2));
            if (match != null) return match;
        }

        return UNKNOWN;
    }

    /**
     * Check if this equipment class is in the "rotating" family.
     * Vibration-related failure modes are only valid for rotating equipment.
     */
    public boolean isRotating() {
        return this == PUMP || this == COMPRESSOR || this == TURBINE
                || this == GENERATOR || this == MOTOR;
    }

    /**
     * Check if this equipment class is safety-critical per IEC 61511.
     * Safety equipment gets stricter gate rules.
     */
    public boolean isSafetyCritical() {
        return this == SAFETY_SYSTEM;
    }

    /**
     * Equipment-class-specific minimum confidence threshold.
     *
     * <p>Different equipment types have inherently different classification difficulty:
     * - Simple equipment (flanges, piping): failure modes are straightforward, model should
     *   classify easily → 0.87 minimum
     * - Complex rotating equipment (turbines, compressors): multiple interacting failure
     *   modes, model genuinely struggles → 0.82 minimum (allow lower confidence)
     * - Safety-critical (PRV, PSV, BDV): any misclassification has safety consequences
     *   → 0.92 minimum per IEC 61511
     * - Instruments/electrical: moderate complexity → 0.85 standard
     *
     * <p>The Reflector Gate uses the HIGHER of (configured threshold, equipment-specific threshold).</p>
     */
    public double getMinConfidenceThreshold() {
        return switch (this) {
            // Safety-critical: highest bar (IEC 61511)
            case SAFETY_SYSTEM -> 0.92;

            // Simple static equipment: model should classify easily
            // If it can't reach 0.87 on a flange, something is wrong with the input
            case PIPING, VESSEL, HEAT_EXCHANGER -> 0.87;

            // Valves: moderate complexity (multiple failure modes)
            case VALVE -> 0.85;

            // Complex rotating equipment: inherently harder to classify
            // Accept lower confidence because model genuinely struggles with
            // overlapping vibration/bearing/seal failure modes
            case PUMP, COMPRESSOR, TURBINE, GENERATOR, MOTOR -> 0.82;

            // Instruments and electrical: standard threshold
            case INSTRUMENT, ELECTRICAL -> 0.85;

            // Unknown: elevated threshold (can't verify plausibility)
            case UNKNOWN -> 0.90;
        };
    }
}
