package com.loomlink.edge.domain.enums;

/**
 * Emission event classification taxonomy for Challenge 02.
 *
 * <p>When a robot sensor detects a gas anomaly, Loom Link classifies it
 * into one of these categories using SAP operational context + multi-modal
 * sensor fusion + LLM reasoning (Mistral 7B on HM90).</p>
 *
 * <p>This taxonomy maps directly to operational decisions and
 * EU 2024/1787 regulatory requirements.</p>
 */
public enum EmissionClassification {

    FUGITIVE_EMISSION("Real unintended gas leak requiring immediate response"),
    PLANNED_VENTING("Known, authorized process emission during scheduled operations"),
    MAINTENANCE_ACTIVITY("Emission during authorized maintenance work"),
    SENSOR_ARTIFACT("False positive from environmental conditions or sensor drift"),
    NORMAL_PROCESS("Within expected operational baseline for this location"),
    UNKNOWN("Cannot determine classification with available context");

    private final String description;

    EmissionClassification(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Whether this classification represents a real emission event
     * that requires regulatory documentation under EU 2024/1787.
     */
    public boolean requiresComplianceRecord() {
        return this == FUGITIVE_EMISSION;
    }

    /**
     * Whether this classification should trigger a control room alarm.
     */
    public boolean triggersAlarm() {
        return this == FUGITIVE_EMISSION;
    }

    /**
     * Whether this classification should generate a SAP work order.
     */
    public boolean triggersWorkOrder() {
        return this == FUGITIVE_EMISSION;
    }

    /**
     * Whether this emission should be included in facility emission inventory
     * (even planned venting counts toward total emissions reporting).
     */
    public boolean countsTowardInventory() {
        return this == FUGITIVE_EMISSION || this == PLANNED_VENTING;
    }
}
