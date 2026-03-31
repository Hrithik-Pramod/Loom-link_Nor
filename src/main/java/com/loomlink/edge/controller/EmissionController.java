package com.loomlink.edge.controller;

import com.loomlink.edge.domain.enums.EmissionClassification;
import com.loomlink.edge.domain.enums.SensorModality;
import com.loomlink.edge.domain.model.EmissionEvent;
import com.loomlink.edge.repository.EmissionEventRepository;
import com.loomlink.edge.service.EmissionPipelineOrchestrator;
import com.loomlink.edge.service.RbacService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for Challenge 02 — Contextual Emission Surveillance.
 *
 * <p>Provides endpoints for ingesting robot sensor data, querying emission events,
 * and managing the compliance lifecycle of fugitive emission detections.</p>
 *
 * <p>Analogous to {@link NotificationController} in Challenge 01, but tailored for
 * SARA-compatible robot sensor readings rather than free-text notifications.</p>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>POST /api/v1/emissions/ingest — Accept new sensor reading</li>
 *   <li>GET /api/v1/emissions — List all emission events (latest first)</li>
 *   <li>GET /api/v1/emissions/mission/{missionId} — Events for a specific mission</li>
 *   <li>GET /api/v1/emissions/equipment/{equipmentTag} — Events for specific equipment</li>
 *   <li>GET /api/v1/emissions/stats — Dashboard KPI statistics</li>
 *   <li>GET /api/v1/emissions/trends/{equipmentTag} — Trend data (7-day history)</li>
 *   <li>PUT /api/v1/emissions/{id}/reclassify — Reclassify an event (SENIOR_ENGINEER only)</li>
 *   <li>PUT /api/v1/emissions/{id}/confirm — Confirm classification</li>
 *   <li>PUT /api/v1/emissions/{id}/dismiss — Dismiss as false positive</li>
 *   <li>GET /api/v1/emissions/compliance/pending — Events needing compliance action</li>
 * </ul>
 *
 * <p>RBAC enforcement: write operations (reclassify, confirm, dismiss) may require
 * the SENIOR_ENGINEER or ADMIN role for compliance audit trail.</p>
 *
 * @see com.loomlink.edge.domain.model.EmissionEvent
 * @see com.loomlink.edge.service.EmissionPipelineOrchestrator
 */
@RestController
@RequestMapping("/api/v1/emissions")
@CrossOrigin(origins = "*", maxAge = 3600)
public class EmissionController {

    private static final Logger log = LoggerFactory.getLogger(EmissionController.class);

    private final EmissionPipelineOrchestrator pipeline;
    private final EmissionEventRepository repository;
    private final RbacService rbac;

    public EmissionController(
            EmissionPipelineOrchestrator pipeline,
            EmissionEventRepository repository,
            RbacService rbac) {
        this.pipeline = pipeline;
        this.repository = repository;
        this.rbac = rbac;
    }

    // ── Ingest Endpoint ─────────────────────────────────────────────────────

    /**
     * Accept a new SARA-compatible sensor reading and process it through
     * the complete Loom Link emission surveillance pipeline.
     *
     * <p>This endpoint simulates receiving a sensor alert from the robot patrol,
     * enriches it with SAP context, multi-modal fusion, and semantic cache lookup,
     * then runs Mistral 7B inference for contextual classification.</p>
     *
     * @param request the ingest request containing sensor reading and metadata
     * @return the pipeline result with classification, gate status, and compliance actions
     */
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingestSensorReading(
            @Valid @RequestBody EmissionIngestRequest request) {

        log.info("Ingest endpoint received: mission {} / robot {} / equipment {}",
                request.missionId(), request.robotId(), request.equipmentTag());

        // Parse sensor modality
        SensorModality modality;
        try {
            modality = SensorModality.valueOf(request.sensorModality().toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid sensor modality: " + request.sensorModality()));
        }

        // Parse detection timestamp
        Instant detectedAt = Instant.parse(request.detectedAt());

        // Create the domain entity from SARA request
        EmissionEvent event = EmissionEvent.fromSensorReading(
                request.inspectionId(),
                request.missionId(),
                request.robotId(),
                request.equipmentTag(),
                request.locationArea(),
                modality,
                request.rawReading(),
                request.readingUnit(),
                detectedAt);

        // Set coordinates if provided
        if (request.coordinates() != null) {
            event.setCoordinates(request.coordinates());
        }

        // Enrich with environmental data
        event.enrichWithEnvironmentalData(
                request.windSpeedKmh(),
                request.windDirection(),
                request.ambientTempCelsius());

        // Enrich with multi-modal data (thermal and acoustic)
        event.enrichWithMultiModalData(
                request.thermalReadingCelsius(),
                request.thermalDeltaCelsius(),
                request.acousticReadingDb(),
                request.acousticLeakSignature(),
                1);  // Will be updated by orchestrator if additional sensors corroborate

        // Set sensor distance if provided
        if (request.sensorDistanceMeters() != null) {
            // Store this temporarily; will be used in quantification
        }

        // Run the full emission pipeline
        EmissionPipelineOrchestrator.EmissionPipelineResult result = pipeline.process(event);

        // Build response mirroring what a monitoring dashboard would consume
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("eventId", event.getId());
        response.put("missionId", event.getMissionId());
        response.put("robotId", event.getRobotId());
        response.put("equipmentTag", event.getEquipmentTag());
        response.put("locationArea", event.getLocationArea());
        response.put("sensorModality", event.getSensorModality().name());
        response.put("rawReading", event.getRawReading());
        response.put("readingUnit", event.getReadingUnit());
        response.put("detectedAt", event.getDetectedAt());
        response.put("classification", result.classification().name());
        response.put("confidence", event.getConfidence());
        response.put("reasoning", event.getReasoning());
        response.put("gatePassed", result.gatePassed());
        response.put("gateThreshold", event.getGateThreshold());
        response.put("gateReasoning", event.getGateReasoning());
        response.put("trendDirection", result.trendDirection());
        response.put("complianceGenerated", result.complianceGenerated());
        response.put("workOrderNumber", result.workOrderNumber());
        response.put("estimatedLeakRateKgHr", event.getEstimatedLeakRateKgHr());
        response.put("corroboratingSensors", event.getCorroboratingSensors());
        response.put("inferenceLatencyMs", event.getInferenceLatencyMs());
        response.put("totalPipelineLatencyMs", result.totalLatencyMs());
        response.put("modelId", event.getModelId());
        response.put("complianceStatus", event.getComplianceStatus().name());
        response.put("remediationAction", result.remediationAction());
        response.put("remediationUrgency", result.remediationUrgency());
        response.put("remediationEstimatedHours", result.remediationEstimatedHours());

        return ResponseEntity.ok(response);
    }

    // ── Query Endpoints ─────────────────────────────────────────────────────

    /**
     * List all emission events, ordered by detection time (latest first).
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listAllEmissions() {
        List<EmissionEvent> events = repository.findAllByOrderByDetectedAtDesc();
        return ResponseEntity.ok(events.stream().map(this::toMap).toList());
    }

    /**
     * Get all emission events for a specific robot mission.
     *
     * @param missionId the SARA mission identifier
     * @return list of emission events from that mission
     */
    @GetMapping("/mission/{missionId}")
    public ResponseEntity<List<Map<String, Object>>> getEmissionsByMission(
            @PathVariable String missionId) {
        List<EmissionEvent> events = repository.findByMissionId(missionId);
        return ResponseEntity.ok(events.stream().map(this::toMap).toList());
    }

    /**
     * Get all emission events for a specific equipment tag.
     *
     * @param equipmentTag the SAP equipment identifier (e.g., "FLG-4401", "VLV-2203")
     * @return list of emission events for that equipment
     */
    @GetMapping("/equipment/{equipmentTag}")
    public ResponseEntity<List<Map<String, Object>>> getEmissionsByEquipment(
            @PathVariable String equipmentTag) {
        List<EmissionEvent> events = repository.findByEquipmentTag(equipmentTag);
        return ResponseEntity.ok(events.stream().map(this::toMap).toList());
    }

    /**
     * Get dashboard KPI statistics for emission events.
     *
     * <p>Returns aggregated metrics for monitoring and compliance reporting.</p>
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getEmissionStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        // Total events
        long totalEvents = repository.count();
        stats.put("totalEvents", totalEvents);

        // Fugitive emissions (real leaks requiring compliance)
        long fugitiveEmissions = repository.countFugitiveEmissions();
        stats.put("fugitiveEmissions", fugitiveEmissions);

        // False positives suppressed (gate passed but not real emissions)
        long falsePositives = repository.countFalsePositivesSuppressed();
        stats.put("falsePositivesSuppressed", falsePositives);

        // Average confidence score
        Double avgConfidence = repository.averageConfidence();
        stats.put("averageConfidence", avgConfidence != null ? avgConfidence : 0.0);

        // Events by classification
        Map<String, Long> eventsByClassification = new LinkedHashMap<>();
        repository.countByClassification().forEach(row -> {
            EmissionClassification classification = (EmissionClassification) row[0];
            long count = (long) row[1];
            eventsByClassification.put(classification.name(), count);
        });
        stats.put("eventsByClassification", eventsByClassification);

        // Events by location
        Map<String, Long> eventsByLocation = new LinkedHashMap<>();
        repository.countByLocationArea().forEach(row -> {
            String location = (String) row[0];
            long count = (long) row[1];
            eventsByLocation.put(location, count);
        });
        stats.put("eventsByLocation", eventsByLocation);

        return ResponseEntity.ok(stats);
    }

    /**
     * Get trend data for a specific equipment (7-day history).
     *
     * <p>Returns all emission events detected at this equipment in the last 7 days,
     * useful for trend analysis and escalation detection.</p>
     *
     * @param equipmentTag the SAP equipment identifier
     * @return list of emission events for that equipment in the last 7 days
     */
    @GetMapping("/trends/{equipmentTag}")
    public ResponseEntity<List<Map<String, Object>>> getTrendData(
            @PathVariable String equipmentTag) {
        Instant sevenDaysAgo = Instant.now().minus(java.time.Duration.ofDays(7));
        List<EmissionEvent> events = repository.findByEquipmentTagAndDetectedAtAfter(
                equipmentTag, sevenDaysAgo);
        return ResponseEntity.ok(events.stream().map(this::toMap).toList());
    }

    /**
     * Get emission event timeline data for trend chart visualization.
     *
     * <p>Returns aggregated daily counts by classification over the last 7 days,
     * designed for a stacked bar/line chart on the dashboard.</p>
     */
    @GetMapping("/timeline")
    public ResponseEntity<Map<String, Object>> getEmissionTimeline() {
        Instant sevenDaysAgo = Instant.now().minus(java.time.Duration.ofDays(7));
        List<EmissionEvent> events = repository.findAllByDetectedAtAfterOrderByDetectedAtAsc(sevenDaysAgo);

        // Aggregate by day and classification
        Map<String, Map<String, Integer>> dailyCounts = new LinkedHashMap<>();
        Map<String, Integer> dailyTotals = new LinkedHashMap<>();

        for (EmissionEvent ev : events) {
            String day = ev.getDetectedAt().toString().substring(0, 10); // YYYY-MM-DD
            dailyCounts.computeIfAbsent(day, k -> new LinkedHashMap<>());
            String cls = ev.getClassification() != null ? ev.getClassification().name() : "UNKNOWN";
            dailyCounts.get(day).merge(cls, 1, Integer::sum);
            dailyTotals.merge(day, 1, Integer::sum);
        }

        // Build response
        Map<String, Object> timeline = new LinkedHashMap<>();
        timeline.put("days", dailyCounts);
        timeline.put("totals", dailyTotals);
        timeline.put("totalEvents", events.size());

        return ResponseEntity.ok(timeline);
    }

    // ── Review / Reclassification Endpoints ─────────────────────────────────

    /**
     * Reclassify an emission event based on human expert review.
     *
     * <p>Requires SENIOR_ENGINEER or ADMIN role for compliance audit trail.</p>
     *
     * @param id the event ID
     * @param request the reclassification request
     * @return the updated event
     */
    @PutMapping("/{id}/reclassify")
    public ResponseEntity<Map<String, Object>> reclassifyEmission(
            @PathVariable UUID id,
            @RequestBody ReclassifyRequest request) {

        // ── RBAC Check ─────────────────────────────────────────────────
        if (!rbac.isAuthorized(request.reviewedBy(), RbacService.Action.RECLASSIFY_EXCEPTION)) {
            return buildForbiddenResponse(request.reviewedBy(), "reclassify");
        }

        return repository.findById(id).map(event -> {
            // Parse the corrected classification
            EmissionClassification correctedClass;
            try {
                correctedClass = EmissionClassification.valueOf(
                        request.correctedClassification().toUpperCase().trim());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().<Map<String, Object>>body(Map.of(
                        "error", "Invalid classification: " + request.correctedClassification()));
            }

            // Apply the reclassification
            event.reclassify(request.reviewedBy(), correctedClass, request.notes());
            repository.save(event);
            log.info("Emission {} RECLASSIFIED to {} by {} (RBAC: SENIOR_ENGINEER)",
                    id, correctedClass, request.reviewedBy());

            return ResponseEntity.ok(toMap(event));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Confirm the classification of an emission event.
     *
     * <p>Marks the event as reviewed and confirmed by a human expert.</p>
     *
     * @param id the event ID
     * @param request the confirmation request
     * @return the updated event
     */
    @PutMapping("/{id}/confirm")
    public ResponseEntity<Map<String, Object>> confirmEmission(
            @PathVariable UUID id,
            @RequestBody ReviewRequest request) {

        try {
            return repository.findById(id).map(event -> {
                event.confirm(request.reviewedBy(), request.notes());
                repository.save(event);
                log.info("Emission {} CONFIRMED by {}", id, request.reviewedBy());

                return ResponseEntity.ok(toMap(event));
            }).orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Failed to confirm emission {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "CONFIRM_FAILED", "message", e.getMessage()));
        }
    }

    /**
     * Dismiss an emission event as a false positive.
     *
     * <p>Marks the event as dismissed, typically for sensor artifacts or
     * environmental anomalies that do not represent real emissions.</p>
     *
     * @param id the event ID
     * @param request the dismissal request
     * @return the updated event
     */
    @PutMapping("/{id}/dismiss")
    public ResponseEntity<Map<String, Object>> dismissEmission(
            @PathVariable UUID id,
            @RequestBody ReviewRequest request) {

        // ── RBAC Check ─────────────────────────────────────────────────
        if (!rbac.isAuthorized(request.reviewedBy(), RbacService.Action.DISMISS_EXCEPTION)) {
            return buildForbiddenResponse(request.reviewedBy(), "dismiss");
        }

        return repository.findById(id).map(event -> {
            event.dismiss(request.reviewedBy(), request.notes());
            repository.save(event);
            log.info("Emission {} DISMISSED by {} (RBAC: SENIOR_ENGINEER)", id, request.reviewedBy());

            return ResponseEntity.ok(toMap(event));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Exception Inbox Endpoints ─────────────────────────────────────────────

    /**
     * Get emission events that were gate-rejected and need human review.
     *
     * <p>This is the Ch2 equivalent of the Challenge 01 Exception Inbox.
     * Returns events with reviewStatus = "GATE_REJECTED", ordered by priority
     * (CRITICAL first, then HIGH, MEDIUM, LOW).</p>
     *
     * <p>EU 2024/1787 requires that ALL detected potential leaks receive documented
     * response within 72 hours — even if the AI gate rejected them.</p>
     */
    @GetMapping("/exception-inbox")
    public ResponseEntity<List<Map<String, Object>>> getEmissionExceptionInbox() {
        List<EmissionEvent> rejected = repository.findByReviewStatus("GATE_REJECTED");

        // Sort by priority: CRITICAL > HIGH > MEDIUM > LOW
        List<String> priorityOrder = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
        List<EmissionEvent> sorted = rejected.stream()
                .sorted((a, b) -> {
                    int pa = priorityOrder.indexOf(a.getReviewPriority());
                    int pb = priorityOrder.indexOf(b.getReviewPriority());
                    if (pa == -1) pa = 99;
                    if (pb == -1) pb = 99;
                    return Integer.compare(pa, pb);
                })
                .toList();

        return ResponseEntity.ok(sorted.stream().map(this::toMap).toList());
    }

    // ── Compliance Endpoints ────────────────────────────────────────────────

    /**
     * Get events that need compliance action (pending compliance documentation).
     *
     * <p>Returns fugitive emissions that have passed the gate but not yet been
     * documented for EU 2024/1787 compliance.</p>
     */
    @GetMapping("/compliance/pending")
    public ResponseEntity<List<Map<String, Object>>> getCompliancePendingEvents() {
        List<EmissionEvent> events = repository.findByClassification(
                EmissionClassification.FUGITIVE_EMISSION);

        // Filter to only those that passed the gate and are not yet documented
        List<EmissionEvent> pending = events.stream()
                .filter(e -> Boolean.TRUE.equals(e.getGatePassed()) &&
                        !e.isComplianceReportGenerated())
                .toList();

        return ResponseEntity.ok(pending.stream().map(this::toMap).toList());
    }

    // ── RBAC Helper ─────────────────────────────────────────────────

    /**
     * Build a forbidden response for RBAC violations.
     */
    private ResponseEntity<Map<String, Object>> buildForbiddenResponse(String user, String action) {
        RbacService.Role role = rbac.getRole(user);
        String roleName = role != null ? role.name() : "UNKNOWN";
        log.warn("RBAC FORBIDDEN: user '{}' (role: {}) attempted to {} an emission", user, roleName, action);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "ACCESS DENIED");
        body.put("message", "User '" + user + "' (role: " + roleName +
                ") does not have permission to " + action + " emissions. " +
                "Only SENIOR_ENGINEER or ADMIN roles can perform this action.");
        body.put("requiredRole", "SENIOR_ENGINEER");
        body.put("currentRole", roleName);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    // ── DTOs ────────────────────────────────────────────────────────────────

    /**
     * Request body for ingest endpoint: SARA-compatible sensor reading.
     */
    public record EmissionIngestRequest(
            String inspectionId,
            @NotBlank String missionId,
            @NotBlank String robotId,
            @NotBlank String equipmentTag,
            @NotBlank String locationArea,
            String coordinates,
            @NotBlank String sensorModality,
            @NotNull double rawReading,
            @NotBlank String readingUnit,
            @NotBlank String detectedAt,
            Double windSpeedKmh,
            String windDirection,
            Double ambientTempCelsius,
            Double thermalReadingCelsius,
            Double thermalDeltaCelsius,
            Double acousticReadingDb,
            Boolean acousticLeakSignature,
            Double sensorDistanceMeters
    ) {}

    /**
     * Request body for review/confirmation endpoints.
     */
    public record ReviewRequest(
            @NotBlank String reviewedBy,
            String notes
    ) {}

    /**
     * Request body for reclassification endpoint.
     */
    public record ReclassifyRequest(
            @NotBlank String reviewedBy,
            @NotBlank String correctedClassification,
            String notes
    ) {}

    // ── Mapper ──────────────────────────────────────────────────────────────

    /**
     * Convert an EmissionEvent to a map for JSON serialization.
     */
    private Map<String, Object> toMap(EmissionEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", event.getId());
        map.put("inspectionId", event.getInspectionId());
        map.put("missionId", event.getMissionId());
        map.put("robotId", event.getRobotId());
        map.put("equipmentTag", event.getEquipmentTag());
        map.put("locationArea", event.getLocationArea());
        map.put("coordinates", event.getCoordinates());
        map.put("sensorModality", event.getSensorModality() != null
                ? event.getSensorModality().name() : null);
        map.put("rawReading", event.getRawReading());
        map.put("readingUnit", event.getReadingUnit());
        map.put("windSpeedKmh", event.getWindSpeedKmh());
        map.put("windDirection", event.getWindDirection());
        map.put("ambientTempCelsius", event.getAmbientTempCelsius());
        map.put("thermalReadingCelsius", event.getThermalReadingCelsius());
        map.put("thermalDeltaCelsius", event.getThermalDeltaCelsius());
        map.put("acousticReadingDb", event.getAcousticReadingDb());
        map.put("acousticLeakSignature", event.getAcousticLeakSignature());
        map.put("corroboratingSensors", event.getCorroboratingSensors());
        map.put("sapActiveWorkOrders", event.getSapActiveWorkOrders());
        map.put("sapMaintenanceActive", event.isSapMaintenanceActive());
        map.put("sapTurnaroundActive", event.isSapTurnaroundActive());
        map.put("daysSinceLastMaintenance", event.getDaysSinceLastMaintenance());
        map.put("historicalBaselinePpm", event.getHistoricalBaselinePpm());
        map.put("classification", event.getClassification() != null
                ? event.getClassification().name() : null);
        map.put("confidence", event.getConfidence());
        map.put("reasoning", event.getReasoning());
        map.put("modelId", event.getModelId());
        map.put("inferenceLatencyMs", event.getInferenceLatencyMs());
        map.put("gatePassed", event.getGatePassed());
        map.put("gateThreshold", event.getGateThreshold());
        map.put("gateReasoning", event.getGateReasoning());
        map.put("estimatedLeakRateKgHr", event.getEstimatedLeakRateKgHr());
        map.put("sensorDistanceMeters", event.getSensorDistanceMeters());
        map.put("complianceStatus", event.getComplianceStatus() != null
                ? event.getComplianceStatus().name() : null);
        map.put("sapWorkOrderNumber", event.getSapWorkOrderNumber());
        map.put("iso14224FailureCode", event.getIso14224FailureCode());
        map.put("complianceReportGenerated", event.isComplianceReportGenerated());
        map.put("previousDetections7d", event.getPreviousDetections7d());
        map.put("trendDirection", event.getTrendDirection());
        map.put("reviewStatus", event.getReviewStatus());
        map.put("reviewPriority", event.getReviewPriority());
        map.put("reviewedBy", event.getReviewedBy());
        map.put("correctedClassification", event.getCorrectedClassification() != null
                ? event.getCorrectedClassification().name() : null);
        map.put("reviewNotes", event.getReviewNotes());
        map.put("reviewedAt", event.getReviewedAt());
        map.put("detectedAt", event.getDetectedAt());
        map.put("classifiedAt", event.getClassifiedAt());
        map.put("complianceDocumentedAt", event.getComplianceDocumentedAt());
        map.put("createdAt", event.getCreatedAt());
        map.put("remediationAction", event.getRemediationAction());
        map.put("remediationUrgency", event.getRemediationUrgency());
        map.put("remediationEstimatedHours", event.getRemediationEstimatedHours());
        return map;
    }
}
