package com.loomlink.edge.domain.enums;

/**
 * Sensor modalities available on Equinor's robotic inspection platforms.
 *
 * <p>Corresponds to the actual sensor payloads on Spot, ANYmal, and
 * other platforms deployed via ISAR/Flotilla across the Nordic Energy ecosystem.</p>
 *
 * <p>Multi-modal fusion correlates readings from multiple modalities at the
 * same equipment tag within the same mission window for higher-confidence
 * classification.</p>
 */
public enum SensorModality {

    CH4("Methane sensor (electrochemical/optical)", "ppm"),
    VOC("Volatile organic compound sensor", "ppm"),
    THERMAL("Thermal imaging camera", "\u00b0C"),
    ACOUSTIC("Microphone array / ultrasonic", "dB"),
    VISUAL("4K RGB camera", "anomaly_score"),
    MULTI("Multi-modal fusion (combined)", "composite");

    private final String description;
    private final String defaultUnit;

    SensorModality(String description, String defaultUnit) {
        this.description = description;
        this.defaultUnit = defaultUnit;
    }

    public String getDescription() { return description; }
    public String getDefaultUnit() { return defaultUnit; }

    /**
     * Whether this modality directly measures gas concentration.
     */
    public boolean isGasSensor() {
        return this == CH4 || this == VOC;
    }
}
