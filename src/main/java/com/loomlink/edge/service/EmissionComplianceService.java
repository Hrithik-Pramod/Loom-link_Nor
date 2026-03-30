package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.EmissionClassification;
import com.loomlink.edge.domain.enums.SensorModality;
import com.loomlink.edge.domain.model.EmissionEvent;
import com.loomlink.edge.repository.EmissionEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * EU 2024/1787 compliance record generation for confirmed fugitive emissions.
 *
 * <p>When the Emission Pipeline classifies a detection as FUGITIVE_EMISSION and the
 * Reflector Gate passes it, this service generates the regulatory compliance
 * documentation mandated by EU 2024/1787.</p>
 *
 * <p>Key responsibilities:</p>
 * <ul>
 *   <li>Generate structured compliance records with all EU-mandated fields</li>
 *   <li>Create formatted text reports for UI display and export</li>
 *   <li>Track 72-hour response deadline per regulation</li>
 *   <li>Calculate compliance statistics (response time, compliance rate)</li>
 * </ul>
 *
 * <p>All record generation happens locally on the HM90 node. Zero data egress.</p>
 */
@Service
public class EmissionComplianceService {

    private static final Logger log = LoggerFactory.getLogger(EmissionComplianceService.class);

    private static final String FACILITY_ID = "NCS-EQUINOR-PLATFORM-01";
    private static final String REGULATION_REF = "EU 2024/1787";
    private static final int RESPONSE_DEADLINE_HOURS = 72;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final EmissionEventRepository repository;

    public EmissionComplianceService(EmissionEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Generate a compliance record from a confirmed fugitive emission event.
     *
     * @param event the gate-passed emission event
     * @return a ComplianceRecord with all EU 2024/1787 mandated fields
     */
    public ComplianceRecord generateComplianceRecord(EmissionEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("EmissionEvent cannot be null");
        }

        Instant detectedAt = event.getDetectedAt();
        LocalDateTime detectionTime = LocalDateTime.ofInstant(detectedAt, ZoneId.of("Europe/Oslo"));
        LocalDateTime reportTime = LocalDateTime.now(ZoneId.of("Europe/Oslo"));
        LocalDateTime responseDeadline = detectionTime.plusHours(RESPONSE_DEADLINE_HOURS);

        // Build detection method description
        String detectionMethod = String.format("Robot %s with %s sensor during mission %s",
                event.getRobotId() != null ? event.getRobotId() : "UNKNOWN",
                event.getSensorModality() != null ? event.getSensorModality().getDescription() : "UNKNOWN",
                event.getMissionId() != null ? event.getMissionId() : "N/A");

        // Derive gas type from sensor modality
        String gasType = "CH4 (Methane)";
        if (event.getSensorModality() == SensorModality.VOC) {
            gasType = "VOC (Volatile Organic Compound)";
        }

        // Multi-modal corroboration summary
        String corroboration;
        int sensors = event.getCorroboratingSensors();
        if (sensors >= 3) {
            corroboration = String.format("HIGH CONFIDENCE — %d independent sensor modalities convergent " +
                    "(gas + thermal delta %.1f°C + acoustic %s)",
                    sensors,
                    event.getThermalDeltaCelsius() != null ? event.getThermalDeltaCelsius() : 0.0,
                    Boolean.TRUE.equals(event.getAcousticLeakSignature()) ? "hiss detected" : "normal");
        } else if (sensors >= 2) {
            corroboration = String.format("MODERATE — %d sensor modalities corroborate", sensors);
        } else {
            corroboration = "Single sensor detection — no multi-modal corroboration";
        }

        // Trend context
        String trend = String.format("Trend: %s (%d detections in last 7 days). Historical baseline: %.1f ppm.",
                event.getTrendDirection() != null ? event.getTrendDirection() : "UNKNOWN",
                event.getPreviousDetections7d(),
                event.getHistoricalBaselinePpm() != null ? event.getHistoricalBaselinePpm() : 0.0);

        ComplianceRecord record = new ComplianceRecord(
                UUID.randomUUID(),
                REGULATION_REF,
                detectionTime,
                reportTime,
                FACILITY_ID,
                event.getEquipmentTag(),
                event.getLocationArea(),
                event.getCoordinates(),
                detectionMethod,
                gasType,
                event.getRawReading(),
                event.getEstimatedLeakRateKgHr(),
                event.getClassification() != null ? event.getClassification().name() : "UNKNOWN",
                event.getConfidence(),
                event.getSapWorkOrderNumber(),
                responseDeadline,
                "AUTO-GENERATED",
                event.getSapActiveWorkOrders(),
                corroboration,
                trend);

        log.info("Generated EU 2024/1787 compliance record {} for {} at {}",
                record.reportId(), event.getEquipmentTag(), detectionTime);

        return record;
    }

    /**
     * Generate a formatted text compliance report for display/export.
     */
    public String generateComplianceReport(EmissionEvent event) {
        ComplianceRecord r = generateComplianceRecord(event);

        return String.format("""
                ════════════════════════════════════════════════════════════════
                EU 2024/1787 FUGITIVE EMISSION COMPLIANCE REPORT
                ════════════════════════════════════════════════════════════════

                REPORT METADATA
                  Report ID:             %s
                  Regulation:            %s
                  Generated:             %s
                  Compliance Officer:    %s

                FACILITY & LOCATION
                  Facility:              %s
                  Equipment Tag:         %s
                  Location:              %s
                  Coordinates:           %s

                EMISSION DETECTION
                  Detected:              %s
                  Detection Method:      %s
                  Gas Type:              %s
                  Concentration:         %.2f ppm
                  Estimated Leak Rate:   %s kg/hr

                CLASSIFICATION
                  Result:                %s
                  Confidence:            %.1f%%

                REGULATORY COMPLIANCE
                  Response Deadline:     %s (72 hours from detection)
                  SAP Work Order:        %s
                  Multi-Modal:           %s

                HISTORICAL CONTEXT
                  %s

                ════════════════════════════════════════════════════════════════
                Auto-generated by Loom Link Sovereign Intelligence Layer.
                All processing performed locally on HM90 node. Zero data egress.
                ════════════════════════════════════════════════════════════════
                """,
                r.reportId(),
                r.regulationReference(),
                r.reportGeneratedAt().format(FMT),
                r.complianceOfficer(),
                r.facilityId(),
                r.equipmentTag(),
                r.locationArea(),
                r.coordinates() != null ? r.coordinates() : "N/A",
                r.detectionTimestamp().format(FMT),
                r.detectionMethod(),
                r.gasType(),
                r.concentrationPpm(),
                r.estimatedLeakRateKgHr() != null ? String.format("%.4f", r.estimatedLeakRateKgHr()) : "N/A",
                r.classificationResult(),
                r.confidenceScore() * 100,
                r.responseDeadline().format(FMT),
                r.sapWorkOrderReference() != null ? r.sapWorkOrderReference() : "PENDING",
                r.multiModalCorroboration(),
                r.trendData());
    }

    /**
     * Calculate compliance statistics for the dashboard.
     */
    public ComplianceStats getComplianceStats() {
        // Count fugitive emissions that were documented
        long totalReports = repository.countFugitiveEmissions();

        // Calculate average response time from detected_at to compliance_documented_at
        List<EmissionEvent> documented = repository.findByClassification(EmissionClassification.FUGITIVE_EMISSION);
        double totalHours = 0;
        int countWithDocs = 0;
        for (EmissionEvent e : documented) {
            if (e.getComplianceDocumentedAt() != null && e.getDetectedAt() != null) {
                Duration d = Duration.between(e.getDetectedAt(), e.getComplianceDocumentedAt());
                totalHours += d.toMinutes() / 60.0;
                countWithDocs++;
            }
        }
        double avgResponseHours = countWithDocs > 0 ? totalHours / countWithDocs : 0.0;

        // Compliance rate: how many were documented within 72 hours
        long withinDeadline = documented.stream()
                .filter(e -> e.getComplianceDocumentedAt() != null && e.getDetectedAt() != null)
                .filter(e -> Duration.between(e.getDetectedAt(), e.getComplianceDocumentedAt()).toHours() <= RESPONSE_DEADLINE_HOURS)
                .count();
        double complianceRate = countWithDocs > 0 ? (double) withinDeadline / countWithDocs : 1.0;

        return new ComplianceStats(totalReports, avgResponseHours, complianceRate);
    }

    /**
     * EU 2024/1787 compliance record with all mandated fields.
     */
    public record ComplianceRecord(
            UUID reportId,
            String regulationReference,
            LocalDateTime detectionTimestamp,
            LocalDateTime reportGeneratedAt,
            String facilityId,
            String equipmentTag,
            String locationArea,
            String coordinates,
            String detectionMethod,
            String gasType,
            double concentrationPpm,
            Double estimatedLeakRateKgHr,
            String classificationResult,
            double confidenceScore,
            String sapWorkOrderReference,
            LocalDateTime responseDeadline,
            String complianceOfficer,
            String additionalContext,
            String multiModalCorroboration,
            String trendData
    ) {}

    /**
     * Aggregate compliance statistics.
     */
    public record ComplianceStats(
            long totalReportsGenerated,
            double averageResponseTimeHours,
            double complianceRate
    ) {}
}
