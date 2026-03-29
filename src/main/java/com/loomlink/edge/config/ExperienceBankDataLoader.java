package com.loomlink.edge.config;

import com.loomlink.edge.domain.enums.EquipmentClass;
import com.loomlink.edge.domain.enums.FailureModeCode;
import com.loomlink.edge.domain.model.Asset;
import com.loomlink.edge.domain.model.FailureHistory;
import com.loomlink.edge.domain.model.VibrationReading;
import com.loomlink.edge.repository.AssetRepository;
import com.loomlink.edge.repository.FailureHistoryRepository;
import com.loomlink.edge.repository.VibrationReadingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Phase 0: Legacy Debt Recovery — Pre-loads the Experience Bank with
 * realistic North Sea asset data, failure histories, and vibration readings.
 *
 * <p>This gives the demo AI a "memory" of equipment behavior before the
 * live pipeline is even started — exactly the "jumpstart" described in the pitch.</p>
 *
 * <p>Data represents assets across two facilities:</p>
 * <ul>
 *   <li>Plant 1000 — Johan Sverdrup (Equinor)</li>
 *   <li>Plant 2000 — Valhall (Aker BP)</li>
 * </ul>
 */
@Component
@Profile("!test")
public class ExperienceBankDataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ExperienceBankDataLoader.class);

    private final AssetRepository assetRepo;
    private final FailureHistoryRepository failureHistoryRepo;
    private final VibrationReadingRepository vibrationRepo;
    private final Random rng = new Random(42); // Deterministic for demo reproducibility

    public ExperienceBankDataLoader(AssetRepository assetRepo,
                                     FailureHistoryRepository failureHistoryRepo,
                                     VibrationReadingRepository vibrationRepo) {
        this.assetRepo = assetRepo;
        this.failureHistoryRepo = failureHistoryRepo;
        this.vibrationRepo = vibrationRepo;
    }

    @Override
    public void run(String... args) {
        log.info("════════════════════════════════════════════════════════");
        log.info("  PHASE 0: LEGACY DEBT RECOVERY — Loading Experience Bank");
        log.info("════════════════════════════════════════════════════════");

        List<Asset> assets = createAssets();
        assetRepo.saveAll(assets);
        log.info("Loaded {} assets into Experience Bank", assets.size());

        List<FailureHistory> histories = createFailureHistories();
        failureHistoryRepo.saveAll(histories);
        log.info("Loaded {} failure history records (Legacy Debt Recovery)", histories.size());

        List<VibrationReading> readings = createVibrationReadings(assets);
        vibrationRepo.saveAll(readings);
        log.info("Loaded {} vibration readings", readings.size());

        log.info("════════════════════════════════════════════════════════");
        log.info("  Experience Bank ready. Fleet: {} assets, {} failures, {} vibration points",
                assets.size(), histories.size(), readings.size());
        log.info("════════════════════════════════════════════════════════");
    }

    private List<Asset> createAssets() {
        List<Asset> assets = new ArrayList<>();

        // ── Johan Sverdrup (Plant 1000) — Equinor ──────────────────
        Asset p1001a = Asset.create("P-1001A", "Seawater Lift Pump A", EquipmentClass.PUMP,
                "Sulzer", "MSD-80-250", "1000", "JS-PROC-001", LocalDate.of(2019, 6, 15), 25, 8000);
        p1001a.setCumulativeOperatingHours(42000);
        p1001a.setRedundancyPartnerTag("P-1001B");
        assets.add(p1001a);

        Asset p1001b = Asset.create("P-1001B", "Seawater Lift Pump B (Standby)", EquipmentClass.PUMP,
                "Sulzer", "MSD-80-250", "1000", "JS-PROC-001", LocalDate.of(2019, 6, 15), 25, 2000);
        p1001b.setCumulativeOperatingHours(8500);
        p1001b.setStandby(true);
        p1001b.setRedundancyPartnerTag("P-1001A");
        p1001b.setOperationalStatus("STANDBY");
        assets.add(p1001b);

        Asset p1002a = Asset.create("P-1002A", "Main Oil Export Pump A", EquipmentClass.PUMP,
                "Sulzer", "MSD-80-250", "1000", "JS-PROC-002", LocalDate.of(2019, 3, 1), 25, 8500);
        p1002a.setCumulativeOperatingHours(48000);
        p1002a.setRedundancyPartnerTag("P-1002B");
        assets.add(p1002a);

        Asset p1002b = Asset.create("P-1002B", "Main Oil Export Pump B (Standby)", EquipmentClass.PUMP,
                "Sulzer", "MSD-80-250", "1000", "JS-PROC-002", LocalDate.of(2019, 3, 1), 25, 2000);
        p1002b.setCumulativeOperatingHours(12000);
        p1002b.setStandby(true);
        p1002b.setRedundancyPartnerTag("P-1002A");
        p1002b.setOperationalStatus("STANDBY");
        assets.add(p1002b);

        Asset c1001 = Asset.create("C-1001", "Gas Export Compressor", EquipmentClass.COMPRESSOR,
                "Siemens", "STC-SV-12", "1000", "JS-COMP-001", LocalDate.of(2018, 11, 20), 30, 8200);
        c1001.setCumulativeOperatingHours(55000);
        assets.add(c1001);

        Asset v1001 = Asset.create("V-1001", "HP Separator Inlet Valve", EquipmentClass.VALVE,
                "Cameron", "DM-Series-8", "1000", "JS-PROC-003", LocalDate.of(2019, 1, 10), 20, 8760);
        v1001.setCumulativeOperatingHours(52000);
        assets.add(v1001);

        Asset g1001 = Asset.create("G-1001", "Emergency Generator A", EquipmentClass.GENERATOR,
                "Caterpillar", "C32-ACERT", "1000", "JS-ELEC-001", LocalDate.of(2019, 6, 1), 25, 500);
        g1001.setCumulativeOperatingHours(3200);
        assets.add(g1001);

        // ── Valhall (Plant 2000) — Aker BP ─────────────────────────
        Asset p2001a = Asset.create("P-2001A", "Produced Water Injection Pump A", EquipmentClass.PUMP,
                "Sulzer", "MSD-80-250", "2000", "VH-PROC-001", LocalDate.of(2015, 4, 10), 25, 8000);
        p2001a.setCumulativeOperatingHours(78000);
        p2001a.setRedundancyPartnerTag("P-2001B");
        assets.add(p2001a);

        Asset p2001b = Asset.create("P-2001B", "Produced Water Injection Pump B (Standby)", EquipmentClass.PUMP,
                "Sulzer", "MSD-80-250", "2000", "VH-PROC-001", LocalDate.of(2015, 4, 10), 25, 2000);
        p2001b.setCumulativeOperatingHours(22000);
        p2001b.setStandby(true);
        p2001b.setRedundancyPartnerTag("P-2001A");
        p2001b.setOperationalStatus("STANDBY");
        assets.add(p2001b);

        Asset c2001 = Asset.create("C-2001", "Gas Lift Compressor", EquipmentClass.COMPRESSOR,
                "Siemens", "STC-SV-12", "2000", "VH-COMP-001", LocalDate.of(2014, 8, 1), 30, 8500);
        c2001.setCumulativeOperatingHours(92000);
        assets.add(c2001);

        Asset p2002 = Asset.create("P-2002", "Chemical Injection Pump", EquipmentClass.PUMP,
                "Milton Roy", "MR-2500", "2000", "VH-CHEM-001", LocalDate.of(2020, 2, 15), 15, 8760);
        p2002.setCumulativeOperatingHours(45000);
        assets.add(p2002);

        Asset he2001 = Asset.create("HE-2001", "Gas/Gas Heat Exchanger", EquipmentClass.HEAT_EXCHANGER,
                "Alfa Laval", "M15-BFG", "2000", "VH-PROC-002", LocalDate.of(2012, 6, 1), 20, 8760);
        he2001.setCumulativeOperatingHours(115000);
        assets.add(he2001);

        Asset m2001 = Asset.create("M-2001", "Compressor Drive Motor", EquipmentClass.MOTOR,
                "Siemens", "1LA8-355", "2000", "VH-COMP-001", LocalDate.of(2014, 8, 1), 30, 8500);
        m2001.setCumulativeOperatingHours(92000);
        assets.add(m2001);

        return assets;
    }

    private List<FailureHistory> createFailureHistories() {
        List<FailureHistory> histories = new ArrayList<>();

        // Sulzer MSD pumps — bearing failures (fleet pattern)
        histories.add(FailureHistory.create("P-1001A", FailureModeCode.VIB, "B01",
                LocalDate.of(2022, 3, 15), 24000, 14,
                "Replace drive-end bearing assembly. Realign coupling.",
                "SKF-22312 E/C3, Loctite 641", "Hydraulic puller, Dial indicator, Alignment laser",
                12.0, true, "LEGACY_RECOVERY"));

        histories.add(FailureHistory.create("P-1002A", FailureModeCode.VIB, "B01",
                LocalDate.of(2023, 1, 20), 32000, 10,
                "Replace drive-end bearing. Check impeller balance.",
                "SKF-22312 E/C3, Mobilux EP 2", "Hydraulic puller, Balance kit",
                16.0, false, "LEGACY_RECOVERY"));

        histories.add(FailureHistory.create("P-2001A", FailureModeCode.VIB, "B01",
                LocalDate.of(2021, 8, 5), 48000, 21,
                "Replace both DE and NDE bearings. Full alignment.",
                "SKF-22312 E/C3 (x2), Coupling spider", "Hydraulic puller, Alignment laser, Torque wrench",
                24.0, true, "LEGACY_RECOVERY"));

        histories.add(FailureHistory.create("P-2001A", FailureModeCode.INL, "S02",
                LocalDate.of(2024, 2, 12), 68000, 5,
                "Replace mechanical seal. Inspect shaft sleeve.",
                "John Crane Type 4610, O-ring kit", "Seal press, Torque wrench",
                18.0, false, "LEGACY_RECOVERY"));

        histories.add(FailureHistory.create("P-1001A", FailureModeCode.OHE, "L01",
                LocalDate.of(2024, 7, 3), 38000, 7,
                "Flush lubrication system. Replace oil cooler element.",
                "Shell Turbo T68 (20L), Cooler element CE-440", "Oil flush kit, Filter wrench",
                8.0, true, "LEGACY_RECOVERY"));

        // Compressor failures
        histories.add(FailureHistory.create("C-1001", FailureModeCode.VIB, "B03",
                LocalDate.of(2023, 5, 18), 38000, 18,
                "Replace thrust bearing pad set. Inspect rotor for rub marks.",
                "Kingsbury thrust pad set KTP-400, Proximity probes (x2)", "Bearing scraper, Alignment laser, Boroscope",
                48.0, true, "LEGACY_RECOVERY"));

        histories.add(FailureHistory.create("C-2001", FailureModeCode.VIB, "B03",
                LocalDate.of(2022, 11, 2), 68000, 25,
                "Replace journal and thrust bearings. Rotor balance in-situ.",
                "Kingsbury pad set KTP-400, Journal bearing shells (x4)", "Bearing scraper, Balance kit, Crane",
                72.0, true, "LEGACY_RECOVERY"));

        histories.add(FailureHistory.create("C-1001", FailureModeCode.OHE, "L01",
                LocalDate.of(2024, 9, 22), 48000, 3,
                "Replace lube oil pump. Flush system. Replace filters.",
                "Lube oil pump LOP-200, Filter elements FE-3 (x4)", "Pipe wrench, Oil flush kit",
                16.0, false, "LEGACY_RECOVERY"));

        // Valve failures
        histories.add(FailureHistory.create("V-1001", FailureModeCode.INL, "C03",
                LocalDate.of(2023, 8, 10), 36000, 30,
                "Lap valve seats. Replace stem packing. Test to API 598.",
                "Garlock packing set GP-8200, Lapping compound", "Valve lapping tool, Torque wrench, Test pump",
                6.0, true, "LEGACY_RECOVERY"));

        // Motor failures
        histories.add(FailureHistory.create("M-2001", FailureModeCode.OHE, "E01",
                LocalDate.of(2024, 4, 15), 82000, 12,
                "Rewind stator. Replace bearings. Megger test insulation.",
                "Stator winding kit, SKF-6320 (x2)", "Megger tester, Bearing heater, Crane",
                96.0, true, "LEGACY_RECOVERY"));

        histories.add(FailureHistory.create("M-2001", FailureModeCode.VIB, "B01",
                LocalDate.of(2022, 6, 30), 62000, 8,
                "Replace motor bearings. Realign to compressor.",
                "SKF-6320 (x2), Mobilux EP 2", "Bearing heater, Alignment laser",
                24.0, true, "LEGACY_RECOVERY"));

        // Heat exchanger
        histories.add(FailureHistory.create("HE-2001", FailureModeCode.PLU, "F01",
                LocalDate.of(2023, 4, 5), 95000, 60,
                "Chemical cleaning of tube bundle. Replace gaskets.",
                "Gasket set M15-BFG, Descaling chemical (200L)", "Gasket press, Chemical pump",
                36.0, true, "LEGACY_RECOVERY"));

        return histories;
    }

    private List<VibrationReading> createVibrationReadings(List<Asset> assets) {
        List<VibrationReading> readings = new ArrayList<>();
        Instant now = Instant.now();

        for (Asset asset : assets) {
            if (asset.getEquipmentClass() != EquipmentClass.PUMP
                    && asset.getEquipmentClass() != EquipmentClass.COMPRESSOR
                    && asset.getEquipmentClass() != EquipmentClass.MOTOR) {
                continue; // Only rotating equipment gets vibration data
            }

            // Generate 48 hours of readings at 1-hour intervals
            double baseVelocity = getBaseVelocity(asset);
            double baseTemp = getBaseTemp(asset);
            int baseRpm = getBaseRpm(asset);

            for (int i = 48; i >= 0; i--) {
                Instant timestamp = now.minus(i, ChronoUnit.HOURS);
                double degradation = getDegradationCurve(asset, i);

                double velocity = baseVelocity + degradation + (rng.nextGaussian() * 0.2);
                double acceleration = (velocity / 4.0) + (rng.nextGaussian() * 0.1);
                double frequency = 120 + (rng.nextGaussian() * 5); // ~2x RPM for bearing defect
                double temp = baseTemp + (degradation * 3) + (rng.nextGaussian() * 0.5);
                int rpm = baseRpm + (int)(rng.nextGaussian() * 10);

                readings.add(VibrationReading.create(
                        asset.getEquipmentTag(),
                        Math.max(velocity, 0.5),
                        Math.max(acceleration, 0.1),
                        Math.max(frequency, 50),
                        Math.max(temp, 30),
                        Math.max(rpm, 1000),
                        timestamp,
                        "SENSOR_HISTORICAL"
                ));
            }
        }

        return readings;
    }

    /** Base vibration level depends on asset age and operating hours. */
    private double getBaseVelocity(Asset asset) {
        // P-1001A: The demo star — showing degradation toward Zone C
        if (asset.getEquipmentTag().equals("P-1001A")) return 5.8;
        // P-2001A: Old pump, high hours, already concerning
        if (asset.getEquipmentTag().equals("P-2001A")) return 8.2;
        // C-2001: Old compressor with high hours
        if (asset.getEquipmentTag().equals("C-2001")) return 6.5;
        // Standby units — low vibration (barely running)
        if (asset.isStandby()) return 1.2;
        // Default healthy baseline
        return 2.0 + (asset.getCumulativeOperatingHours() / 30000.0);
    }

    private double getBaseTemp(Asset asset) {
        if (asset.getEquipmentTag().equals("P-2001A")) return 72.0;
        if (asset.getEquipmentTag().equals("C-2001")) return 68.0;
        if (asset.isStandby()) return 35.0;
        return 55.0;
    }

    private int getBaseRpm(Asset asset) {
        if (asset.getEquipmentClass() == EquipmentClass.COMPRESSOR) return 3600;
        return 1480; // Standard 4-pole motor speed
    }

    /** Simulate degradation pattern over the 48-hour window. */
    private double getDegradationCurve(Asset asset, int hoursAgo) {
        double progress = (48.0 - hoursAgo) / 48.0; // 0.0 → 1.0 over the window

        // P-1001A: Active degradation — vibration climbing toward Zone C/D
        if (asset.getEquipmentTag().equals("P-1001A")) {
            return progress * 2.5; // +2.5 mm/s over 48h = aggressive trend
        }
        // P-2001A: Already high, slow continued climb
        if (asset.getEquipmentTag().equals("P-2001A")) {
            return progress * 1.2;
        }
        // C-2001: Moderate degradation
        if (asset.getEquipmentTag().equals("C-2001")) {
            return progress * 0.8;
        }
        // Others: stable or slight increase
        return progress * 0.1;
    }
}
