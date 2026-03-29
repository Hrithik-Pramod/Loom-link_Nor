package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.FailureModeCode;
import com.loomlink.edge.domain.model.Asset;
import com.loomlink.edge.domain.model.FailureHistory;
import com.loomlink.edge.repository.AssetRepository;
import com.loomlink.edge.repository.FailureHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Prescriptive Action Engine — generates "Next Best Action" recommendations
 * by combining RUL forecasts with fleet sibling repair histories and
 * Smart Swap redundancy assessments.
 *
 * <p>This moves the system from predictive (telling you WHEN) to prescriptive
 * (telling you WHAT TO DO and with WHAT PARTS).</p>
 */
@Service
public class PrescriptiveActionService {

    private static final Logger log = LoggerFactory.getLogger(PrescriptiveActionService.class);

    private final AssetRepository assetRepo;
    private final FailureHistoryRepository failureHistoryRepo;
    private final RulForecastingService rulService;

    public PrescriptiveActionService(AssetRepository assetRepo,
                                      FailureHistoryRepository failureHistoryRepo,
                                      RulForecastingService rulService) {
        this.assetRepo = assetRepo;
        this.failureHistoryRepo = failureHistoryRepo;
        this.rulService = rulService;
    }

    /**
     * Generate a complete prescriptive action package for an asset.
     */
    public PrescriptiveAction prescribe(String equipmentTag) {
        log.info("Generating prescriptive action for {}", equipmentTag);

        Asset asset = assetRepo.findByEquipmentTag(equipmentTag)
                .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + equipmentTag));

        // Get the RUL forecast
        RulForecastingService.RulForecast forecast = rulService.forecast(equipmentTag);

        // Find fleet siblings and their repair histories for this failure mode
        List<Asset> siblings = assetRepo.findFleetSiblings(
                asset.getManufacturer(), asset.getModelNumber(),
                asset.getEquipmentClass(), equipmentTag);

        List<String> allTags = new ArrayList<>(siblings.stream()
                .map(Asset::getEquipmentTag).toList());
        allTags.add(equipmentTag);

        FailureModeCode failureMode = forecast.likelyFailureMode() != null
                ? forecast.likelyFailureMode() : FailureModeCode.VIB;

        List<FailureHistory> relevantHistories =
                failureHistoryRepo.findSiblingFailures(allTags, failureMode);

        // Build the Next Best Action from sibling repair data
        NextBestAction nextAction = buildNextBestAction(forecast, relevantHistories, asset);

        // Smart Swap assessment
        SmartSwapAssessment smartSwap = assessSmartSwap(asset, forecast);

        return new PrescriptiveAction(
                equipmentTag,
                asset.getAssetName(),
                forecast,
                nextAction,
                smartSwap,
                Instant.now()
        );
    }

    private NextBestAction buildNextBestAction(RulForecastingService.RulForecast forecast,
                                                List<FailureHistory> histories,
                                                Asset asset) {
        // Find the most common repair action for this failure mode from siblings
        String recommendedAction = "Schedule condition-based maintenance inspection";
        String requiredParts = "Pending inspection results";
        String requiredTools = "Standard maintenance toolkit";
        double estimatedDowntimeHours = 8.0;
        String priority = "MEDIUM";

        if (!histories.isEmpty()) {
            // Use the most recent successful repair as the template
            FailureHistory bestMatch = histories.stream()
                    .filter(FailureHistory::isPlanned)
                    .findFirst()
                    .orElse(histories.get(0));

            recommendedAction = bestMatch.getRepairAction();
            requiredParts = bestMatch.getPartsUsed();
            requiredTools = bestMatch.getToolsRequired();
            estimatedDowntimeHours = bestMatch.getDowntimeHours();
        }

        // Adjust priority based on RUL
        if (forecast.rulHours() <= 24) priority = "EMERGENCY";
        else if (forecast.rulHours() <= 72) priority = "URGENT";
        else if (forecast.rulHours() <= 168) priority = "HIGH";

        String actionSummary = String.format(
                "Based on %d sibling failure records and current vibration trend (%.2f mm/s/hr), " +
                "%s within %.0f hours. %s",
                histories.size(),
                forecast.trendRateMmSPerHour(),
                recommendedAction.toLowerCase(),
                forecast.rulHours(),
                forecast.riskLevel().equals("CRITICAL") ? "IMMEDIATE INTERVENTION REQUIRED." : ""
        );

        return new NextBestAction(
                priority,
                recommendedAction,
                requiredParts,
                requiredTools,
                estimatedDowntimeHours,
                actionSummary,
                histories.size()
        );
    }

    private SmartSwapAssessment assessSmartSwap(Asset asset,
                                                 RulForecastingService.RulForecast forecast) {
        if (asset.getRedundancyPartnerTag() == null) {
            return new SmartSwapAssessment(false, false,
                    null, null, "N/A", 0, 0,
                    "No redundancy partner configured. Single-train operation.");
        }

        Optional<Asset> partnerOpt = assetRepo.findByEquipmentTag(asset.getRedundancyPartnerTag());
        if (partnerOpt.isEmpty()) {
            return new SmartSwapAssessment(false, false,
                    asset.getRedundancyPartnerTag(), null, "UNKNOWN", 0, 0,
                    "Redundancy partner not found in Experience Bank.");
        }

        Asset partner = partnerOpt.get();

        // Assess standby readiness
        RulForecastingService.RulForecast partnerForecast;
        try {
            partnerForecast = rulService.forecast(partner.getEquipmentTag());
        } catch (Exception e) {
            return new SmartSwapAssessment(true, false,
                    partner.getEquipmentTag(), partner.getAssetName(),
                    partner.getOperationalStatus(), 0, 0,
                    "Unable to assess standby unit health: " + e.getMessage());
        }

        boolean standbyHealthy = partnerForecast.rulHours() > forecast.rulHours()
                && !partnerForecast.riskLevel().equals("CRITICAL")
                && !partnerForecast.riskLevel().equals("HIGH");

        boolean swapRecommended = forecast.rulHours() <= 72 && standbyHealthy;

        String assessment = swapRecommended
                ? String.format("SMART SWAP RECOMMENDED: Transfer load to %s (%s). " +
                        "Standby unit has %.0fh RUL vs active unit's %.0fh. " +
                        "1oo2 barrier integrity will be preserved throughout the transfer.",
                        partner.getEquipmentTag(), partner.getAssetName(),
                        partnerForecast.rulHours(), forecast.rulHours())
                : String.format("Smart Swap not required at this time. Active unit RUL: %.0fh. " +
                        "Standby %s health: %s (RUL: %.0fh).",
                        forecast.rulHours(), partner.getEquipmentTag(),
                        partnerForecast.riskLevel(), partnerForecast.rulHours());

        return new SmartSwapAssessment(
                true, swapRecommended,
                partner.getEquipmentTag(), partner.getAssetName(),
                partner.getOperationalStatus(),
                partnerForecast.rulHours(), partnerForecast.confidence(),
                assessment
        );
    }

    // ── Result Records ──────────────────────────────────────────────

    public record PrescriptiveAction(
            String equipmentTag, String assetName,
            RulForecastingService.RulForecast forecast,
            NextBestAction nextBestAction,
            SmartSwapAssessment smartSwap,
            Instant generatedAt
    ) {}

    public record NextBestAction(
            String priority, String recommendedAction,
            String requiredParts, String requiredTools,
            double estimatedDowntimeHours, String actionSummary,
            int siblingDataPoints
    ) {}

    public record SmartSwapAssessment(
            boolean hasRedundancyPartner, boolean swapRecommended,
            String partnerTag, String partnerName,
            String partnerStatus, double partnerRulHours,
            double partnerConfidence, String assessment
    ) {}
}
