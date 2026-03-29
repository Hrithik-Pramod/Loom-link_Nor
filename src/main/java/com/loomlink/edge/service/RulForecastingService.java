package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.FailureModeCode;
import com.loomlink.edge.domain.model.Asset;
import com.loomlink.edge.domain.model.FailureHistory;
import com.loomlink.edge.domain.model.VibrationReading;
import com.loomlink.edge.repository.AssetRepository;
import com.loomlink.edge.repository.FailureHistoryRepository;
import com.loomlink.edge.repository.VibrationReadingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Cross-Asset Survival Engine — computes the 72-hour RUL (Remaining Useful Life)
 * forecast by combining real-time vibration trends with fleet sibling failure histories.
 *
 * <p>This is NOT a general probabilistic prediction. It is a deterministic,
 * confidence-bounded forecast based on:</p>
 * <ol>
 *   <li>Current vibration trend (velocity, acceleration, temperature)</li>
 *   <li>Fleet sibling historical failure data (Weibull-style survival analysis)</li>
 *   <li>Operating hours vs. known failure thresholds from Experience Bank</li>
 * </ol>
 */
@Service
public class RulForecastingService {

    private static final Logger log = LoggerFactory.getLogger(RulForecastingService.class);

    private final AssetRepository assetRepo;
    private final FailureHistoryRepository failureHistoryRepo;
    private final VibrationReadingRepository vibrationRepo;

    /** ISO 10816 Zone D threshold for Class III machines (mm/s). */
    private static final double DANGER_THRESHOLD_MMS = 11.2;
    /** ISO 10816 Zone C threshold (mm/s). */
    private static final double ALERT_THRESHOLD_MMS = 7.1;

    public RulForecastingService(AssetRepository assetRepo,
                                  FailureHistoryRepository failureHistoryRepo,
                                  VibrationReadingRepository vibrationRepo) {
        this.assetRepo = assetRepo;
        this.failureHistoryRepo = failureHistoryRepo;
        this.vibrationRepo = vibrationRepo;
    }

    /**
     * Compute the RUL forecast for an asset.
     *
     * @param equipmentTag the asset to forecast
     * @return a deterministic forecast with confidence bounds
     */
    public RulForecast forecast(String equipmentTag) {
        log.info("Computing RUL forecast for {}", equipmentTag);

        Asset asset = assetRepo.findByEquipmentTag(equipmentTag)
                .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + equipmentTag));

        // Get latest vibration readings (trend window)
        List<VibrationReading> readings = vibrationRepo
                .findByEquipmentTagOrderByRecordedAtDesc(equipmentTag);

        if (readings.isEmpty()) {
            return RulForecast.noData(equipmentTag, "No vibration data available for RUL computation.");
        }

        VibrationReading latest = readings.get(0);

        // Compute vibration trend (rate of change)
        double trendRateMmSPerHour = computeTrendRate(readings);

        // Find fleet siblings and their failure histories
        List<Asset> siblings = assetRepo.findFleetSiblings(
                asset.getManufacturer(), asset.getModelNumber(),
                asset.getEquipmentClass(), equipmentTag);

        List<String> siblingTags = siblings.stream()
                .map(Asset::getEquipmentTag).collect(Collectors.toList());
        siblingTags.add(equipmentTag); // include self

        // Get sibling failure data for the most likely failure mode
        FailureModeCode likelyFailure = determineLikelyFailureMode(latest);
        List<FailureHistory> siblingFailures = siblingTags.size() > 0
                ? failureHistoryRepo.findSiblingFailures(siblingTags, likelyFailure)
                : List.of();

        // Compute RUL using combined approach
        double rulHours = computeRulHours(latest, trendRateMmSPerHour, asset,
                siblingFailures, siblingTags, likelyFailure);

        // Compute confidence based on data quality
        double confidence = computeConfidence(readings.size(), siblingFailures.size(), trendRateMmSPerHour);

        // Build the prescriptive context
        String riskLevel = categorizeRisk(rulHours);
        String actionWindow = rulHours <= 72 ? "IMMEDIATE" : rulHours <= 168 ? "THIS_WEEK" : "SCHEDULED";

        RulForecast forecast = new RulForecast(
                equipmentTag,
                asset.getAssetName(),
                rulHours,
                confidence,
                riskLevel,
                actionWindow,
                likelyFailure,
                latest.getVelocityMmS(),
                latest.getSeverityZone(),
                latest.getBearingTempCelsius(),
                trendRateMmSPerHour,
                siblings.size(),
                siblingFailures.size(),
                Instant.now(),
                null
        );

        log.info("RUL forecast for {}: {:.1f}h (confidence: {:.2f}, risk: {})",
                equipmentTag, rulHours, confidence, riskLevel);

        return forecast;
    }

    /**
     * Compute the vibration velocity trend rate (mm/s per hour).
     * A positive rate means degradation is accelerating.
     */
    private double computeTrendRate(List<VibrationReading> readings) {
        if (readings.size() < 2) return 0.0;

        // Use most recent readings for trend
        int window = Math.min(readings.size(), 20);
        VibrationReading newest = readings.get(0);
        VibrationReading oldest = readings.get(window - 1);

        double deltaVelocity = newest.getVelocityMmS() - oldest.getVelocityMmS();
        double deltaHours = Duration.between(oldest.getRecordedAt(), newest.getRecordedAt()).toHours();

        if (deltaHours == 0) return 0.0;
        return deltaVelocity / deltaHours;
    }

    /**
     * Determine the most likely failure mode based on vibration signature.
     */
    private FailureModeCode determineLikelyFailureMode(VibrationReading latest) {
        if (latest.getAccelerationG() > 3.0) return FailureModeCode.BRD; // Bearing defect
        if (latest.getBearingTempCelsius() > 85) return FailureModeCode.OHE; // Overheating
        if (latest.getVelocityMmS() > ALERT_THRESHOLD_MMS) return FailureModeCode.VIB;
        return FailureModeCode.VIB; // Default for rotating equipment
    }

    /**
     * Core RUL computation combining vibration extrapolation with fleet sibling statistics.
     */
    private double computeRulHours(VibrationReading latest, double trendRate, Asset asset,
                                    List<FailureHistory> siblingFailures,
                                    List<String> siblingTags, FailureModeCode failureMode) {

        // Method 1: Vibration extrapolation (when will velocity reach Zone D?)
        double vibrationRul = Double.MAX_VALUE;
        if (trendRate > 0) {
            double headroom = DANGER_THRESHOLD_MMS - latest.getVelocityMmS();
            if (headroom > 0) {
                vibrationRul = headroom / trendRate;
            } else {
                vibrationRul = 0; // Already in danger zone
            }
        }

        // Method 2: Fleet sibling statistical RUL
        double siblingRul = Double.MAX_VALUE;
        if (!siblingFailures.isEmpty()) {
            // Average hours at failure across siblings
            double avgHoursAtFailure = siblingFailures.stream()
                    .mapToInt(FailureHistory::getOperatingHoursAtFailure)
                    .average().orElse(0);

            if (avgHoursAtFailure > 0) {
                double remainingHours = avgHoursAtFailure - asset.getCumulativeOperatingHours();
                siblingRul = Math.max(remainingHours, 0);
            }
        }

        // Method 3: Temperature-based degradation estimate
        double tempRul = Double.MAX_VALUE;
        if (latest.getBearingTempCelsius() > 70) {
            // Arrhenius-inspired: every 10C above 70C halves remaining life
            double tempExcess = latest.getBearingTempCelsius() - 70;
            double reductionFactor = Math.pow(0.5, tempExcess / 10.0);
            tempRul = 720 * reductionFactor; // Base 720h (30 days)
        }

        // Combine: use the most conservative (smallest) estimate
        double combinedRul = Math.min(vibrationRul, Math.min(siblingRul, tempRul));

        // Clamp to reasonable bounds
        if (combinedRul == Double.MAX_VALUE) combinedRul = 8760; // 1 year default
        return Math.max(combinedRul, 0);
    }

    private double computeConfidence(int readingCount, int siblingFailureCount, double trendRate) {
        double dataConfidence = Math.min(readingCount / 30.0, 1.0);       // More data = more confidence
        double siblingConfidence = Math.min(siblingFailureCount / 5.0, 1.0); // More siblings = more confidence
        double trendConfidence = trendRate > 0 ? 0.9 : 0.5;              // Clear trend = more confidence

        return (dataConfidence * 0.4 + siblingConfidence * 0.35 + trendConfidence * 0.25);
    }

    private String categorizeRisk(double rulHours) {
        if (rulHours <= 24) return "CRITICAL";
        if (rulHours <= 72) return "HIGH";
        if (rulHours <= 168) return "MEDIUM";
        return "LOW";
    }

    /**
     * Immutable RUL forecast result.
     */
    public record RulForecast(
            String equipmentTag,
            String assetName,
            double rulHours,
            double confidence,
            String riskLevel,
            String actionWindow,
            FailureModeCode likelyFailureMode,
            double currentVelocityMmS,
            String currentSeverityZone,
            double currentBearingTempC,
            double trendRateMmSPerHour,
            int fleetSiblingCount,
            int siblingFailureRecords,
            Instant forecastTimestamp,
            String message
    ) {
        public static RulForecast noData(String tag, String message) {
            return new RulForecast(tag, null, -1, 0, "UNKNOWN", "N/A",
                    null, 0, "N/A", 0, 0, 0, 0, Instant.now(), message);
        }
    }
}
