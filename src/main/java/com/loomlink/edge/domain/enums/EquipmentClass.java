package com.loomlink.edge.domain.enums;

/**
 * ISO 14224 Equipment Class taxonomy — top-level equipment categories
 * used across North Sea installations. Each class maps to a specific
 * set of applicable failure modes and maintenance strategies.
 */
public enum EquipmentClass {

    PUMP("Rotating - Pump"),
    COMPRESSOR("Rotating - Compressor"),
    TURBINE("Rotating - Turbine"),
    GENERATOR("Rotating - Generator"),
    MOTOR("Rotating - Electric Motor"),
    HEAT_EXCHANGER("Static - Heat Exchanger"),
    VESSEL("Static - Pressure Vessel"),
    PIPING("Static - Piping"),
    VALVE("Control - Valve"),
    INSTRUMENT("Control - Instrument"),
    SAFETY_SYSTEM("Safety - SIS Component"),
    ELECTRICAL("Electrical - Switchgear / Transformer");

    private final String description;

    EquipmentClass(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
