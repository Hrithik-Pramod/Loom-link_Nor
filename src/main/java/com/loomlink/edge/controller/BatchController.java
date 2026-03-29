package com.loomlink.edge.controller;

import com.loomlink.edge.domain.enums.EquipmentClass;
import com.loomlink.edge.domain.model.MaintenanceNotification;
import com.loomlink.edge.service.MaintenancePipelineOrchestrator;
import com.loomlink.edge.service.MaintenancePipelineOrchestrator.PipelineResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch Processing API — offshore platforms generate hundreds of notifications per shift.
 *
 * <p>A single-notification API is fine for demos, but production offshore deployment
 * needs bulk ingestion. This endpoint processes a batch of SAP notifications through
 * the full pipeline and returns aggregate results with per-notification details.</p>
 */
@RestController
@RequestMapping("/api/v1/pipeline")
@CrossOrigin(origins = "*")
public class BatchController {

    private static final Logger log = LoggerFactory.getLogger(BatchController.class);

    private final MaintenancePipelineOrchestrator pipeline;

    public BatchController(MaintenancePipelineOrchestrator pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * Process a batch of SAP notifications through the full Loom Link pipeline.
     * Each notification is processed sequentially (LLM is the bottleneck — parallel
     * requests would starve the HM90 GPU).
     */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> processBatch(
            @Valid @RequestBody BatchRequest request) {

        log.info("Batch processing: {} notifications received", request.notifications().size());
        long batchStart = System.currentTimeMillis();

        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0, rejected = 0, cacheHits = 0, dlqQueued = 0;
        long totalInferenceMs = 0;

        for (BatchNotification item : request.notifications()) {
            try {
                MaintenanceNotification notification = MaintenanceNotification.fromODataEvent(
                        item.sapNotificationNumber(),
                        item.freeTextDescription(),
                        item.equipmentTag(),
                        item.equipmentClass() != null
                                ? EquipmentClass.valueOf(item.equipmentClass()) : null,
                        item.sapPlant());

                PipelineResult result = pipeline.process(notification);

                Map<String, Object> itemResult = new LinkedHashMap<>();
                itemResult.put("sapNotificationNumber", item.sapNotificationNumber());
                itemResult.put("status", "PROCESSED");
                itemResult.put("failureModeCode", result.classification().getFailureModeCode().name());
                itemResult.put("confidence", result.classification().getConfidence());
                itemResult.put("gatePassed", result.gateResult().isPassed());
                itemResult.put("writtenToSap", result.wasWrittenBack());
                itemResult.put("cacheHit", result.cacheHit());
                itemResult.put("pipelineLatencyMs", result.totalLatencyMs());
                results.add(itemResult);

                if (result.gateResult().isPassed()) passed++;
                else rejected++;
                if (result.cacheHit()) cacheHits++;
                if (result.queuedForRetry()) dlqQueued++;
                totalInferenceMs += result.totalLatencyMs();
            } catch (Exception e) {
                log.error("Batch item {} failed: {}", item.sapNotificationNumber(), e.getMessage());
                Map<String, Object> errorResult = new LinkedHashMap<>();
                errorResult.put("sapNotificationNumber", item.sapNotificationNumber());
                errorResult.put("status", "ERROR");
                errorResult.put("error", e.getMessage());
                results.add(errorResult);
            }
        }

        long batchDuration = System.currentTimeMillis() - batchStart;

        // Build aggregate response
        Map<String, Object> response = new LinkedHashMap<>();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalSubmitted", request.notifications().size());
        summary.put("totalProcessed", passed + rejected);
        summary.put("gatePassed", passed);
        summary.put("gateRejected", rejected);
        summary.put("cacheHits", cacheHits);
        summary.put("dlqQueued", dlqQueued);
        summary.put("batchDurationMs", batchDuration);
        summary.put("avgLatencyMs", results.isEmpty() ? 0 : totalInferenceMs / results.size());
        summary.put("throughputPerMinute", batchDuration > 0
                ? Math.round((double) results.size() / batchDuration * 60000)
                : 0);

        response.put("summary", summary);
        response.put("results", results);

        log.info("Batch complete: {}/{} passed, {} cache hits, {}ms total",
                passed, passed + rejected, cacheHits, batchDuration);

        return ResponseEntity.ok(response);
    }

    public record BatchRequest(
            @NotEmpty List<@Valid BatchNotification> notifications
    ) {}

    public record BatchNotification(
            @NotBlank String sapNotificationNumber,
            @NotBlank String freeTextDescription,
            @NotBlank String equipmentTag,
            String equipmentClass,
            String sapPlant
    ) {}
}
