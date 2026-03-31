package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.EmissionClassification;
import com.loomlink.edge.domain.enums.EquipmentClass;
import com.loomlink.edge.domain.enums.InspectionPriority;
import com.loomlink.edge.domain.model.Asset;
import com.loomlink.edge.domain.model.EmissionEvent;
import com.loomlink.edge.domain.model.FailureHistory;
import com.loomlink.edge.repository.AssetRepository;
import com.loomlink.edge.repository.EmissionEventRepository;
import com.loomlink.edge.repository.FailureHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Risk-based equipment scoring for intelligent mission planning.
 *
 * <p>This service is the core intelligence of Challenge 04. It evaluates every
 * inspectable equipment item and assigns a risk score (0.0–1.0) and priority
 * level that determines the robot's inspection route.</p>
 *
 * <p>Scoring combines six dimensions:</p>
 * <ol>
 *   <li>Emission history — recent fugitive emissions boost priority</li>
 *   <li>Emission trend — ESCALATING patterns indicate developing leaks</li>
 *   <li>RUL forecast — equipment nearing failure needs urgent inspection</li>
 *   <li>Inspection recency — longer since last inspection = higher priority</li>
 *   <li>Equipment criticality — safety systems and rotating equipment rank higher</li>
 *   <li>SAP work order context — active maintenance suppresses gas inspection need</li>
 * </ol>
 *
 * <p>This creates the circular intelligence loop: Challenge 01 (maintenance) feeds
 * RUL data → Challenge 04 (mission planning) uses it to prioritize inspections →
 * Challenge 02 (emissions) provides findings back to Challenge 01.</p>
 */
@Service
public class RiskScoringService {

    private static final Logger log = LoggerFactory.getLogger(RiskScoringService.class);

    private final AssetRepository assetRepo;
    private final EmissionEventRepository emissionRepo;
    private final FailureHistoryRepository failureHistoryRepo;
    private final RulForecastingService rulService;

    // Scoring weights (sum = 1.0)
    private static final double W_EMISSION = 0.30;
    private static final double W_TREND = 0.15;
    private static final double W_RUL = 0.20;
    private static final double W_RECENCY = 0.15;
    private static final double W_CRITICALITY = 0.15;
    private static final double W_WORKORDER = 0.05;

    public RiskScoringService(AssetRepository assetRepo,
                               EmissionEventRepository emissionRepo,
                               FailureHistoryRepository failureHistoryRepo,
                               RulForecastingService rulService) {
        this.assetRepo = assetRepo;
        this.emissionRepo = emissionRepo;
        this.failureHistoryRepo = failureHistoryRepo;
        this.rulService = rulService;
    }

    /**
     * Score all equipment in a facility area for inspection prioritization.
     *
     * @param facilityArea area to score (or null for all assets)
     * @return sorted list of equipment risk assessments (highest risk first)
     */
    public List<EquipmentRiskAssessment> scoreAllEquipment(String facilityArea) {
        log.info("Scoring equipment for mission planning — area: {}",
                facilityArea != null ? facilityArea : "ALL");

        List<Asset> assets = assetRepo.findAll();

        List<EquipmentRiskAssessment> assessments = new ArrayList<>();
        for (Asset asset : assets) {
            try {
                EquipmentRiskAssessment assessment = scoreEquipment(asset);
                assessments.add(assessment);
            } catch (Exception e) {
                log.warn("Failed to score asset {}: {}", asset.getEquipmentTag(), e.getMessage());
            }
        }

        // Sort by risk score descending
        assessments.sort(Comparator.comparingDouble(EquipmentRiskAssessment::riskScore).reversed());

        log.info("Scored {} equipment items — top risk: {} (score: {})",
                assessments.size(),
                assessments.isEmpty() ? "N/A" : assessments.get(0).equipmentTag(),
                assessments.isEmpty() ? 0.0 : assessments.get(0).riskScore());

        return assessments;
    }

    /**
     * Score a single equipment item.
     */
    public EquipmentRiskAssessment scoreEquipment(Asset asset) {
        String tag = asset.getEquipmentTag();
        StringBuilder reasoning = new StringBuilder();

        // 1. Emission history score
        double emissionScore = scoreEmissionHistory(tag, reasoning);

        // 2. Emission trend score
        double trendScore = scoreEmissionTrend(tag, reasoning);

        // 3. RUL forecast score
        double rulScore = scoreRul(tag, reasoning);

        // 4. Inspection recency score
        int daysSinceInspection = computeDaysSinceLastInspection(tag);
        double recencyScore = scoreRecency(daysSinceInspection, reasoning);

        // 5. Equipment criticality score
        double criticalityScore = scoreCriticality(asset.getEquipmentClass(), reasoning);

        // 6. Work order context (suppress if maintenance active)
        double workOrderScore = scoreWorkOrderContext(tag, reasoning);

        // Weighted combination
        double totalScore = (emissionScore * W_EMISSION)
                + (trendScore * W_TREND)
                + (rulScore * W_RUL)
                + (recencyScore * W_RECENCY)
                + (criticalityScore * W_CRITICALITY)
                + (workOrderScore * W_WORKORDER);

        // Clamp to 0-1
        totalScore = Math.max(0.0, Math.min(1.0, totalScore));

        InspectionPriority priority = derivePriority(totalScore);

        // Determine recommended inspection type
        String isarTaskType = deriveIsarTaskType(asset, emissionScore, trendScore);
        String recommendedSensors = deriveRecommendedSensors(asset, emissionScore);

        return new EquipmentRiskAssessment(
                tag,
                asset.getAssetName(),
                asset.getEquipmentClass(),
                asset.getFunctionalLocation(),
                totalScore,
                priority,
                reasoning.toString(),
                isarTaskType,
                recommendedSensors,
                daysSinceInspection,
                emissionScore,
                trendScore,
                rulScore,
                recencyScore,
                criticalityScore
        );
    }

    private double scoreEmissionHistory(String tag, StringBuilder reasoning) {
        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        List<EmissionEvent> recentEmissions = emissionRepo.findByEquipmentTagAndDetectedAtAfter(tag, sevenDaysAgo);

        if (recentEmissions.isEmpty()) {
            return 0.1; // Baseline — no recent emissions
        }

        long fugitiveCount = recentEmissions.stream()
                .filter(e -> e.getClassification() == EmissionClassification.FUGITIVE_EMISSION)
                .count();

        if (fugitiveCount > 0) {
            reasoning.append("CRITICAL: ").append(fugitiveCount).append(" fugitive emission(s) in last 7 days. ");
            return 1.0;
        }

        // Some emissions but not fugitive
        reasoning.append(recentEmissions.size()).append(" emission event(s) in last 7 days. ");
        return Math.min(recentEmissions.size() * 0.25, 0.7);
    }

    private double scoreEmissionTrend(String tag, StringBuilder reasoning) {
        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        List<EmissionEvent> recentEmissions = emissionRepo.findByEquipmentTagAndDetectedAtAfter(tag, sevenDaysAgo);

        if (recentEmissions.isEmpty()) return 0.0;

        boolean hasEscalating = recentEmissions.stream()
                .anyMatch(e -> "ESCALATING".equals(e.getTrendDirection()));

        if (hasEscalating) {
            reasoning.append("ESCALATING emission trend detected. ");
            return 1.0;
        }

        boolean hasStable = recentEmissions.stream()
                .anyMatch(e -> "STABLE".equals(e.getTrendDirection()));

        if (hasStable) {
            reasoning.append("Stable emission pattern — monitoring needed. ");
            return 0.4;
        }

        return 0.1;
    }

    private double scoreRul(String tag, StringBuilder reasoning) {
        try {
            var forecast = rulService.forecast(tag);
            if (forecast.rulHours() < 0) return 0.2; // No data

            if (forecast.rulHours() <= 24) {
                reasoning.append("CRITICAL RUL: ").append(String.format("%.0f", forecast.rulHours())).append("h remaining. ");
                return 1.0;
            } else if (forecast.rulHours() <= 72) {
                reasoning.append("High RUL risk: ").append(String.format("%.0f", forecast.rulHours())).append("h remaining. ");
                return 0.8;
            } else if (forecast.rulHours() <= 168) {
                reasoning.append("Medium RUL: ").append(String.format("%.0f", forecast.rulHours())).append("h remaining. ");
                return 0.5;
            }
            return 0.1;
        } catch (Exception e) {
            // Asset may not have vibration data — that's normal for static equipment
            return 0.2;
        }
    }

    private int computeDaysSinceLastInspection(String tag) {
        // Check latest emission event (robot inspection) for this equipment
        List<EmissionEvent> events = emissionRepo.findByEquipmentTag(tag);
        if (events.isEmpty()) return 365; // No record = treat as very overdue

        Instant latestInspection = events.stream()
                .map(EmissionEvent::getDetectedAt)
                .max(Instant::compareTo)
                .orElse(Instant.now().minus(365, ChronoUnit.DAYS));

        return (int) Duration.between(latestInspection, Instant.now()).toDays();
    }

    private double scoreRecency(int daysSinceInspection, StringBuilder reasoning) {
        if (daysSinceInspection > 90) {
            reasoning.append("Overdue: ").append(daysSinceInspection).append(" days since last inspection. ");
            return 1.0;
        } else if (daysSinceInspection > 30) {
            reasoning.append(daysSinceInspection).append(" days since last inspection. ");
            return 0.6;
        } else if (daysSinceInspection > 7) {
            return 0.3;
        }
        return 0.1; // Recently inspected
    }

    private double scoreCriticality(EquipmentClass eqClass, StringBuilder reasoning) {
        return switch (eqClass) {
            case SAFETY_SYSTEM -> { reasoning.append("Safety-critical equipment. "); yield 1.0; }
            case COMPRESSOR, TURBINE -> { reasoning.append("High-value rotating equipment. "); yield 0.85; }
            case PUMP, GENERATOR -> { reasoning.append("Rotating equipment. "); yield 0.7; }
            case VALVE -> { reasoning.append("Process control valve. "); yield 0.6; }
            case VESSEL -> { reasoning.append("Pressure vessel. "); yield 0.55; }
            case PIPING -> 0.4;
            case HEAT_EXCHANGER -> 0.35;
            case INSTRUMENT -> 0.3;
            case MOTOR -> 0.5;
            case ELECTRICAL -> 0.4;
            case UNKNOWN -> { reasoning.append("Unknown equipment class — elevated inspection priority. "); yield 0.75; }
        };
    }

    private double scoreWorkOrderContext(String tag, StringBuilder reasoning) {
        // If there's active maintenance, gas readings are expected — lower inspection priority
        Instant recentWindow = Instant.now().minus(1, ChronoUnit.DAYS);
        List<EmissionEvent> recentEvents = emissionRepo.findByEquipmentTagAndDetectedAtAfter(tag, recentWindow);

        boolean hasMaintenanceActivity = recentEvents.stream()
                .anyMatch(e -> e.getClassification() == EmissionClassification.MAINTENANCE_ACTIVITY);

        if (hasMaintenanceActivity) {
            reasoning.append("Active maintenance — gas inspection deprioritized. ");
            return 0.0; // Suppress priority during active maintenance
        }
        return 0.5; // Neutral — no active maintenance context
    }

    private InspectionPriority derivePriority(double score) {
        if (score >= 0.8) return InspectionPriority.CRITICAL;
        if (score >= 0.6) return InspectionPriority.HIGH;
        if (score >= 0.4) return InspectionPriority.MEDIUM;
        if (score >= 0.2) return InspectionPriority.LOW;
        return InspectionPriority.DEFERRED;
    }

    private String deriveIsarTaskType(Asset asset, double emissionScore, double trendScore) {
        if (emissionScore >= 0.8 || trendScore >= 0.8) return "gas_detection";
        return switch (asset.getEquipmentClass()) {
            case PUMP, COMPRESSOR, TURBINE, GENERATOR, MOTOR -> "vibration_thermal_scan";
            case VALVE, PIPING -> "gas_detection";
            case VESSEL, HEAT_EXCHANGER -> "thermal_scan";
            case SAFETY_SYSTEM -> "full_inspection";
            case INSTRUMENT, ELECTRICAL -> "visual_inspection";
            case UNKNOWN -> "full_inspection";
        };
    }

    private String deriveRecommendedSensors(Asset asset, double emissionScore) {
        if (emissionScore >= 0.5) return "CH4,THERMAL,ACOUSTIC";
        return switch (asset.getEquipmentClass()) {
            case PUMP, COMPRESSOR, TURBINE, GENERATOR, MOTOR -> "THERMAL,ACOUSTIC,VISUAL";
            case VALVE, PIPING -> "CH4,THERMAL";
            case VESSEL, HEAT_EXCHANGER -> "THERMAL,VISUAL";
            case SAFETY_SYSTEM -> "CH4,THERMAL,ACOUSTIC,VISUAL";
            default -> "VISUAL,THERMAL";
        };
    }

    /**
     * Equipment risk assessment result.
     */
    public record EquipmentRiskAssessment(
            String equipmentTag,
            String assetName,
            EquipmentClass equipmentClass,
            String functionalLocation,
            double riskScore,
            InspectionPriority priority,
            String reasoning,
            String isarTaskType,
            String recommendedSensors,
            int daysSinceLastInspection,
            double emissionScore,
            double trendScore,
            double rulScore,
            double recencyScore,
            double criticalityScore
    ) {}
}
