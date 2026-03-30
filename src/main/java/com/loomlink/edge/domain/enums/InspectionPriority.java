package com.loomlink.edge.domain.enums;

/**
 * Risk-driven inspection priority levels for mission waypoints.
 * Computed by the RiskScoringService based on maintenance history,
 * emission trends, RUL forecasts, and time since last inspection.
 */
public enum InspectionPriority {

    CRITICAL(1, "Immediate inspection required — safety risk", 300),
    HIGH(2, "Priority inspection — developing issue detected", 180),
    MEDIUM(3, "Routine inspection — scheduled monitoring", 120),
    LOW(4, "Opportunistic — inspect if within mission range", 60),
    DEFERRED(5, "No immediate need — recently inspected", 30);

    private final int rank;
    private final String description;
    private final int recommendedDwellSeconds;

    InspectionPriority(int rank, String description, int recommendedDwellSeconds) {
        this.rank = rank;
        this.description = description;
        this.recommendedDwellSeconds = recommendedDwellSeconds;
    }

    public int getRank() { return rank; }
    public String getDescription() { return description; }
    public int getRecommendedDwellSeconds() { return recommendedDwellSeconds; }
}
