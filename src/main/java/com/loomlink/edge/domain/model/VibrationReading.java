package com.loomlink.edge.domain.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A vibration sensor reading from a rotating asset. These readings form
 * the basis for RUL (Remaining Useful Life) forecasting.
 *
 * In production, these come from real sensors via the HM90 node.
 * For the demo, we support both real sensor endpoints and pre-loaded data.
 */
@Entity
@Table(name = "vibration_readings")
public class VibrationReading {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "equipment_tag", nullable = false)
    private String equipmentTag;

    /** Overall vibration velocity in mm/s (ISO 10816 standard). */
    @Column(name = "velocity_mm_s", nullable = false)
    private double velocityMmS;

    /** Vibration acceleration in g (bearing defect indicator). */
    @Column(name = "acceleration_g")
    private double accelerationG;

    /** Dominant frequency in Hz (identifies specific fault type). */
    @Column(name = "dominant_frequency_hz")
    private double dominantFrequencyHz;

    /** Bearing temperature in Celsius. */
    @Column(name = "bearing_temp_celsius")
    private double bearingTempCelsius;

    /** Operating speed in RPM at time of reading. */
    @Column(name = "rpm")
    private int rpm;

    /** ISO 10816 severity zone: A (good), B (acceptable), C (alert), D (danger). */
    @Column(name = "severity_zone")
    private String severityZone;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    /** Source: SENSOR_LIVE, SENSOR_HISTORICAL, or SIMULATED. */
    @Column(name = "data_source")
    private String dataSource;

    protected VibrationReading() {}

    public static VibrationReading create(String equipmentTag, double velocityMmS,
                                           double accelerationG, double dominantFrequencyHz,
                                           double bearingTempCelsius, int rpm,
                                           Instant recordedAt, String dataSource) {
        VibrationReading vr = new VibrationReading();
        vr.equipmentTag = equipmentTag;
        vr.velocityMmS = velocityMmS;
        vr.accelerationG = accelerationG;
        vr.dominantFrequencyHz = dominantFrequencyHz;
        vr.bearingTempCelsius = bearingTempCelsius;
        vr.rpm = rpm;
        vr.recordedAt = recordedAt;
        vr.dataSource = dataSource;
        // Classify per ISO 10816 for Class III machines (pumps, medium motors)
        if (velocityMmS <= 2.8) vr.severityZone = "A";
        else if (velocityMmS <= 7.1) vr.severityZone = "B";
        else if (velocityMmS <= 11.2) vr.severityZone = "C";
        else vr.severityZone = "D";
        return vr;
    }

    public UUID getId() { return id; }
    public String getEquipmentTag() { return equipmentTag; }
    public double getVelocityMmS() { return velocityMmS; }
    public double getAccelerationG() { return accelerationG; }
    public double getDominantFrequencyHz() { return dominantFrequencyHz; }
    public double getBearingTempCelsius() { return bearingTempCelsius; }
    public int getRpm() { return rpm; }
    public String getSeverityZone() { return severityZone; }
    public Instant getRecordedAt() { return recordedAt; }
    public String getDataSource() { return dataSource; }
}
