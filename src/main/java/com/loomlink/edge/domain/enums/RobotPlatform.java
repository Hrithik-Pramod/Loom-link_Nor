package com.loomlink.edge.domain.enums;

/**
 * Robot platforms deployed in the Nordic Energy ecosystem.
 * Each implements the ISAR interface for mission execution.
 *
 * @see <a href="https://github.com/equinor/isar">ISAR — Equinor</a>
 */
public enum RobotPlatform {

    SPOT("Boston Dynamics Spot", "quadruped", true, true, true, 90),
    ANYMAL("ANYbotics ANYmal", "quadruped", true, true, true, 120),
    EXR("ExRobotics ExR-2", "tracked", true, false, true, 480),
    TURTLEBOT("TurtleBot", "wheeled", false, false, false, 60);

    private final String displayName;
    private final String locomotion;
    private final boolean hasThermal;
    private final boolean hasAcoustic;
    private final boolean hasCh4;
    private final int maxMissionMinutes;

    RobotPlatform(String displayName, String locomotion,
                  boolean hasThermal, boolean hasAcoustic, boolean hasCh4,
                  int maxMissionMinutes) {
        this.displayName = displayName;
        this.locomotion = locomotion;
        this.hasThermal = hasThermal;
        this.hasAcoustic = hasAcoustic;
        this.hasCh4 = hasCh4;
        this.maxMissionMinutes = maxMissionMinutes;
    }

    public String getDisplayName() { return displayName; }
    public String getLocomotion() { return locomotion; }
    public boolean hasThermal() { return hasThermal; }
    public boolean hasAcoustic() { return hasAcoustic; }
    public boolean hasCh4() { return hasCh4; }
    public int getMaxMissionMinutes() { return maxMissionMinutes; }

    /**
     * Check if this platform can perform multi-modal fusion
     * (requires at least 2 of: thermal, acoustic, CH4).
     */
    public boolean supportsMultiModalFusion() {
        int count = 0;
        if (hasThermal) count++;
        if (hasAcoustic) count++;
        if (hasCh4) count++;
        return count >= 2;
    }
}
