package com.loomlink.edge.controller;

import com.loomlink.edge.domain.model.Asset;
import com.loomlink.edge.domain.model.FailureHistory;
import com.loomlink.edge.domain.model.VibrationReading;
import com.loomlink.edge.repository.AssetRepository;
import com.loomlink.edge.repository.FailureHistoryRepository;
import com.loomlink.edge.repository.VibrationReadingRepository;
import com.loomlink.edge.service.PrescriptiveActionService;
import com.loomlink.edge.service.RulForecastingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Dashboard API — serves all data for the Loom Link demo dashboard.
 * These endpoints power the real-time visualization at the Stavanger demo.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@CrossOrigin(origins = "*")  // Allow dashboard access from any origin during demo
public class DashboardApiController {

    private final AssetRepository assetRepo;
    private final FailureHistoryRepository failureHistoryRepo;
    private final VibrationReadingRepository vibrationRepo;
    private final RulForecastingService rulService;
    private final PrescriptiveActionService prescriptiveService;

    public DashboardApiController(AssetRepository assetRepo,
                                   FailureHistoryRepository failureHistoryRepo,
                                   VibrationReadingRepository vibrationRepo,
                                   RulForecastingService rulService,
                                   PrescriptiveActionService prescriptiveService) {
        this.assetRepo = assetRepo;
        this.failureHistoryRepo = failureHistoryRepo;
        this.vibrationRepo = vibrationRepo;
        this.rulService = rulService;
        this.prescriptiveService = prescriptiveService;
    }

    /** Get all assets in the fleet. */
    @GetMapping("/assets")
    public ResponseEntity<List<Map<String, Object>>> getAssets() {
        List<Asset> assets = assetRepo.findAll();
        List<Map<String, Object>> result = assets.stream().map(a -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("equipmentTag", a.getEquipmentTag());
            map.put("assetName", a.getAssetName());
            map.put("equipmentClass", a.getEquipmentClass().name());
            map.put("manufacturer", a.getManufacturer());
            map.put("modelNumber", a.getModelNumber());
            map.put("sapPlant", a.getSapPlant());
            map.put("functionalLocation", a.getFunctionalLocation());
            map.put("installationDate", a.getInstallationDate());
            map.put("operationalStatus", a.getOperationalStatus());
            map.put("isStandby", a.isStandby());
            map.put("redundancyPartner", a.getRedundancyPartnerTag());
            map.put("cumulativeHours", a.getCumulativeOperatingHours());
            map.put("designLifeYears", a.getDesignLifeYears());
            return map;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /** Get RUL forecast for a specific asset. */
    @GetMapping("/rul/{equipmentTag}")
    public ResponseEntity<RulForecastingService.RulForecast> getRulForecast(
            @PathVariable String equipmentTag) {
        return ResponseEntity.ok(rulService.forecast(equipmentTag));
    }

    /** Get RUL forecasts for ALL active rotating assets. */
    @GetMapping("/rul")
    public ResponseEntity<List<RulForecastingService.RulForecast>> getAllRulForecasts() {
        List<Asset> assets = assetRepo.findAll();
        List<RulForecastingService.RulForecast> forecasts = new ArrayList<>();
        for (Asset asset : assets) {
            try {
                forecasts.add(rulService.forecast(asset.getEquipmentTag()));
            } catch (Exception e) {
                // Skip assets without vibration data
            }
        }
        forecasts.sort(Comparator.comparingDouble(RulForecastingService.RulForecast::rulHours));
        return ResponseEntity.ok(forecasts);
    }

    /** Get prescriptive action for an asset. */
    @GetMapping("/prescriptive/{equipmentTag}")
    public ResponseEntity<PrescriptiveActionService.PrescriptiveAction> getPrescriptiveAction(
            @PathVariable String equipmentTag) {
        return ResponseEntity.ok(prescriptiveService.prescribe(equipmentTag));
    }

    /** Get vibration trend data for an asset (for charts). */
    @GetMapping("/vibration/{equipmentTag}")
    public ResponseEntity<List<Map<String, Object>>> getVibrationTrend(
            @PathVariable String equipmentTag) {
        List<VibrationReading> readings =
                vibrationRepo.findByEquipmentTagOrderByRecordedAtDesc(equipmentTag);
        Collections.reverse(readings); // Chronological order for charts
        List<Map<String, Object>> result = readings.stream().map(vr -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("timestamp", vr.getRecordedAt());
            map.put("velocityMmS", vr.getVelocityMmS());
            map.put("accelerationG", vr.getAccelerationG());
            map.put("bearingTempC", vr.getBearingTempCelsius());
            map.put("rpm", vr.getRpm());
            map.put("severityZone", vr.getSeverityZone());
            return map;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /** Get failure history for an asset. */
    @GetMapping("/failures/{equipmentTag}")
    public ResponseEntity<List<FailureHistory>> getFailureHistory(
            @PathVariable String equipmentTag) {
        return ResponseEntity.ok(failureHistoryRepo.findByEquipmentTag(equipmentTag));
    }

    /** Experience Bank statistics. */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalAssets", assetRepo.count());
        stats.put("totalFailureRecords", failureHistoryRepo.count());
        stats.put("legacyRecoveredRecords", failureHistoryRepo.countByDataSource("LEGACY_RECOVERY"));
        stats.put("liveClassifiedRecords", failureHistoryRepo.countByDataSource("LIVE_CLASSIFICATION"));
        stats.put("totalVibrationReadings", vibrationRepo.count());
        stats.put("reflectorGateThreshold", 0.85);
        stats.put("sovereignNode", "HM90 (100.64.132.128)");
        stats.put("model", "mistral:7b");
        return ResponseEntity.ok(stats);
    }

    /** Ingest a real sensor reading from HM90. */
    @PostMapping("/sensor/vibration")
    public ResponseEntity<Map<String, Object>> ingestSensorReading(
            @RequestBody Map<String, Object> payload) {
        String tag = (String) payload.get("equipmentTag");
        double velocity = ((Number) payload.get("velocityMmS")).doubleValue();
        double acceleration = ((Number) payload.getOrDefault("accelerationG", 0.0)).doubleValue();
        double frequency = ((Number) payload.getOrDefault("dominantFrequencyHz", 0.0)).doubleValue();
        double temp = ((Number) payload.getOrDefault("bearingTempC", 0.0)).doubleValue();
        int rpm = ((Number) payload.getOrDefault("rpm", 0)).intValue();

        VibrationReading reading = VibrationReading.create(
                tag, velocity, acceleration, frequency, temp, rpm,
                java.time.Instant.now(), "SENSOR_LIVE");
        vibrationRepo.save(reading);

        return ResponseEntity.ok(Map.of(
                "status", "INGESTED",
                "equipmentTag", tag,
                "severityZone", reading.getSeverityZone(),
                "velocityMmS", velocity
        ));
    }
}
