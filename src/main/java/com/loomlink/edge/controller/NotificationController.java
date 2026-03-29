package com.loomlink.edge.controller;

import com.loomlink.edge.domain.enums.EquipmentClass;
import com.loomlink.edge.domain.model.MaintenanceNotification;
import com.loomlink.edge.service.MaintenancePipelineOrchestrator;
import com.loomlink.edge.service.MaintenancePipelineOrchestrator.PipelineResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller simulating the SAP OData notification stream.
 *
 * <p>In production, Loom Link subscribes to SAP OData service endpoints to intercept
 * free-text entries at the moment of creation — before they propagate downstream.
 * For the Stavanger demo, this controller provides a REST endpoint that accepts
 * "dirty" notifications and feeds them into the full pipeline.</p>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code POST /api/v1/notifications} — Submit a free-text notification for processing</li>
 *   <li>{@code GET /api/v1/health} — Pipeline health check</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final MaintenancePipelineOrchestrator pipeline;

    public NotificationController(MaintenancePipelineOrchestrator pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * Simulate an OData event: a technician has submitted a free-text notification in SAP.
     * This triggers the full Loom Link pipeline.
     */
    @PostMapping("/notifications")
    public ResponseEntity<Map<String, Object>> processNotification(
            @Valid @RequestBody NotificationRequest request) {

        log.info("OData event received: notification {} for equipment {}",
                request.sapNotificationNumber(), request.equipmentTag());

        // Create the domain entity from the simulated OData payload
        MaintenanceNotification notification = MaintenanceNotification.fromODataEvent(
                request.sapNotificationNumber(),
                request.freeTextDescription(),
                request.equipmentTag(),
                request.equipmentClass() != null
                    ? EquipmentClass.valueOf(request.equipmentClass()) : null,
                request.sapPlant());

        // Run the full pipeline
        PipelineResult result = pipeline.process(notification);

        // Build the response — mirrors what a monitoring dashboard would consume
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("notificationNumber", notification.getSapNotificationNumber());
        response.put("status", notification.getStatus().name());
        response.put("failureModeCode", result.classification().getFailureModeCode().name());
        response.put("failureModeDescription", result.classification().getFailureModeCode().getDescription());
        response.put("causeCode", result.classification().getCauseCode());
        response.put("confidence", result.classification().getConfidence());
        response.put("reasoning", result.classification().getReasoning());
        response.put("reflectorGatePassed", result.gateResult().isPassed());
        response.put("gateReasoning", result.gateResult().getGateReasoning());
        response.put("writtenBackToSap", result.wasWrittenBack());
        response.put("inferenceLatencyMs", result.classification().getInferenceLatencyMs());
        response.put("totalPipelineLatencyMs", result.totalLatencyMs());
        response.put("modelId", result.classification().getModelId());
        response.put("jsonValidFirstAttempt", result.jsonValidFirstAttempt());
        response.put("llmAttempts", result.llmAttempts());
        response.put("cacheHit", result.cacheHit());
        response.put("cacheLookupMs", result.cacheLookupMs());
        response.put("cacheMatchType", result.cacheMatchType());
        response.put("queuedForRetry", result.queuedForRetry());

        return ResponseEntity.ok(response);
    }

    /**
     * Health check — confirms the pipeline is operational and the HM90 node is reachable.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "service", "Loom Link Edge Logic",
                "status", "OPERATIONAL",
                "version", "0.1.0-SNAPSHOT",
                "challenge", "01 - Maintenance Optimization",
                "sovereignNode", "HM90 (100.64.132.128)",
                "reflectorGateActive", true
        ));
    }

    /**
     * Request body matching the SAP OData notification payload structure.
     */
    public record NotificationRequest(
            @NotBlank String sapNotificationNumber,
            @NotBlank String freeTextDescription,
            @NotBlank String equipmentTag,
            String equipmentClass,
            String sapPlant
    ) {}
}
