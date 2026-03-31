package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.EquipmentClass;
import com.loomlink.edge.domain.model.*;
import com.loomlink.edge.gateway.SapBapiGateway;
import com.loomlink.edge.repository.AuditLogRepository;
import com.loomlink.edge.repository.ExceptionInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the complete Loom Link Challenge 01 pipeline:
 *
 * <pre>
 *   Free-Text Entry  →  Semantic Cache Check  →  Semantic Analysis  →  Reflector Gate  →  SAP Write-Back
 *   (OData mock)        (pgvector >95%)           (LLM on HM90)        (Deterministic)    (BAPI + DLQ)
 *                              ↓                        ↓                     ↓
 *                         Cache HIT              Schema Validator        Exception Inbox
 *                       (bypass Ollama)          (1-retry fallback)      (rejected items)
 *                              ↓                        ↓                     ↓
 *                       Audit Log  ←─────────── every execution ──────→  Audit Log
 * </pre>
 *
 * <p>Scale & Resilience features:</p>
 * <ul>
 *   <li>Semantic Cache: >95% similarity match bypasses Ollama, returns cached result (~50ms)</li>
 *   <li>Dead Letter Queue: failed SAP BAPI calls are queued with exponential backoff retry</li>
 *   <li>JSON Schema Validation with 1-retry on LLM output</li>
 *   <li>Immutable AuditLog for every pipeline execution (pass or fail)</li>
 *   <li>Exception Inbox for rejected classifications awaiting human review</li>
 * </ul>
 */
@Service
public class MaintenancePipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MaintenancePipelineOrchestrator.class);

    private final SemanticAnalysisService semanticService;
    private final ReflectorGateService reflectorGate;
    private final SapBapiGateway bapiGateway;
    private final AuditLogRepository auditLogRepository;
    private final ExceptionInboxRepository exceptionInboxRepository;
    private final SemanticCacheService semanticCache;
    private final RulForecastingService rulService;
    private final boolean demoModeEnabled;

    public MaintenancePipelineOrchestrator(
            SemanticAnalysisService semanticService,
            ReflectorGateService reflectorGate,
            SapBapiGateway bapiGateway,
            AuditLogRepository auditLogRepository,
            ExceptionInboxRepository exceptionInboxRepository,
            SemanticCacheService semanticCache,
            RulForecastingService rulService,
            @Value("${loomlink.demo-mode.enabled:false}") boolean demoModeEnabled) {
        this.semanticService = semanticService;
        this.reflectorGate = reflectorGate;
        this.bapiGateway = bapiGateway;
        this.auditLogRepository = auditLogRepository;
        this.exceptionInboxRepository = exceptionInboxRepository;
        this.semanticCache = semanticCache;
        this.rulService = rulService;
        this.demoModeEnabled = demoModeEnabled;
    }

    /**
     * Process a single maintenance notification through the full pipeline.
     *
     * @param notification the raw notification intercepted from the OData stream
     * @return the pipeline result containing all stage outputs
     */
    public PipelineResult process(MaintenanceNotification notification) {
        log.info("════════════════════════════════════════════════════════");
        log.info("  LOOM LINK PIPELINE START");
        log.info("  Notification: {}", notification.getSapNotificationNumber());
        log.info("  Equipment   : {}", notification.getEquipmentTag());
        log.info("  Free-text   : \"{}\"", notification.getFreeTextDescription());
        log.info("════════════════════════════════════════════════════════");

        long pipelineStart = System.currentTimeMillis();

        // ── Stage 1.5: Semantic Cache Lookup ───────────────────────────
        var cacheHit = semanticCache.lookup(notification);
        if (cacheHit.isPresent()) {
            return handleCacheHit(notification, cacheHit.get(), pipelineStart);
        }

        // ── Stage 2: Semantic Analysis (LLM on HM90) with JSON Schema Validation + 1-retry
        SemanticAnalysisService.ClassificationResult classResult = semanticService.classify(notification);
        SemanticClassification classification = classResult.classification();

        // ── Stage 3: Reflector Gate (Deterministic Governance) ─────────
        ReflectorGateResult gateResult = reflectorGate.evaluate(classification, notification);

        // ── Stage 3.5: Promote ALL LLM results to cache (saves re-inference) ──
        // Cache stores the classification as-is; the Reflector Gate still evaluates
        // every cache hit (defense in depth), so rejected results stay rejected.
        // ASYNC: Cache promotion is fire-and-forget — it doesn't affect the pipeline
        // result, so we run it off the critical path to reduce response latency.
        CompletableFuture.runAsync(() -> {
            try {
                semanticCache.promote(notification, classification);
            } catch (Exception e) {
                log.warn("Async cache promotion failed for {}: {}",
                        notification.getSapNotificationNumber(), e.getMessage());
            }
        });

        // ── Stage 3.7: RUL Forecasting (72-hour prediction for rotating equipment) ──
        // Only run for rotating equipment where vibration data exists.
        // This answers "what's wrong?" (failure mode) AND "how much time do we have?" (RUL).
        RulForecastingService.RulForecast rulForecast = null;
        EquipmentClass equipClass = EquipmentClass.fromEquipmentTag(notification.getEquipmentTag());
        if (gateResult.isPassed() && equipClass.isRotating()) {
            try {
                rulForecast = rulService.forecast(notification.getEquipmentTag());
                if (rulForecast.rulHours() >= 0) {
                    log.info("  RUL Forecast: {} hours remaining (risk: {}, confidence: {})",
                            rulForecast.rulHours(), rulForecast.riskLevel(), rulForecast.confidence());
                    log.info("  RUL Details: {} fleet siblings, {} failure records, trend: {} mm/s/hr",
                            rulForecast.fleetSiblingCount(), rulForecast.siblingFailureRecords(),
                            rulForecast.trendRateMmSPerHour());
                } else {
                    log.info("  RUL Forecast: NO DATA — {}", rulForecast.message());
                }
            } catch (Exception e) {
                log.warn("  RUL Forecast: SKIPPED — {}", e.getMessage());
                // RUL failure doesn't block the pipeline — classification still goes to SAP
            }
        } else if (!equipClass.isRotating()) {
            log.info("  RUL Forecast: N/A — {} is {} equipment (RUL applies to rotating assets)",
                    notification.getEquipmentTag(), equipClass.getDescription());
        }

        // ── Stage 4: SAP Write-Back (only if gate passed) with DLQ ────
        SapBapiGateway.WriteBackResult writeBackResult = null;
        if (gateResult.isPassed()) {
            writeBackResult = bapiGateway.writeBackFailureCode(notification, classification, gateResult);
        }

        boolean writtenToSap = writeBackResult != null && writeBackResult.writtenToSap();
        boolean queuedForRetry = writeBackResult != null && writeBackResult.queuedForRetry();
        long totalLatency = System.currentTimeMillis() - pipelineStart;

        // ── Enterprise Feature: Audit Log (every execution) ────────────
        recordAuditLog(notification, classification, gateResult, writtenToSap,
                totalLatency, classResult.jsonValidFirstAttempt(), classResult.llmAttempts(),
                false, queuedForRetry);

        // ── Enterprise Feature: Exception Inbox (on rejection) ─────────
        if (!gateResult.isPassed()) {
            recordExceptionInbox(notification, classification, gateResult);
        }

        log.info("════════════════════════════════════════════════════════");
        log.info("  PIPELINE COMPLETE in {}ms", totalLatency);
        log.info("  Result: {}", gateResult.isPassed()
                ? (writtenToSap ? "WRITTEN BACK TO SAP" : "QUEUED FOR RETRY (DLQ)")
                : "FLAGGED FOR HUMAN REVIEW");
        log.info("  Cache: MISS | JSON valid first attempt: {} | LLM attempts: {}",
                classResult.jsonValidFirstAttempt(), classResult.llmAttempts());
        if (rulForecast != null && rulForecast.rulHours() >= 0) {
            log.info("  RUL: {}h remaining | Risk: {} | Action: {}",
                    rulForecast.rulHours(), rulForecast.riskLevel(), rulForecast.actionWindow());
        }
        log.info("════════════════════════════════════════════════════════");

        return new PipelineResult(
                notification, classification, gateResult,
                writeBackResult != null ? writeBackResult.bapiPayload() : null,
                totalLatency,
                classResult.jsonValidFirstAttempt(), classResult.llmAttempts(),
                false, 0L, null, queuedForRetry, rulForecast);
    }

    /**
     * Handle a semantic cache hit — bypass the LLM entirely.
     */
    private PipelineResult handleCacheHit(
            MaintenanceNotification notification,
            SemanticCacheService.CacheHitResult hit,
            long pipelineStart) {

        SemanticCacheEntry cached = hit.entry();

        // Build a synthetic classification from the cache entry
        notification.markClassified();
        SemanticClassification cachedClassification = SemanticClassification.create(
                notification,
                cached.getFailureModeCode(),
                cached.getCauseCode(),
                cached.getConfidence(),
                "[CACHE HIT — " + hit.matchType() + " match @ " +
                    String.format("%.1f%%", hit.similarity() * 100) + "] " + cached.getReasoning(),
                null,
                cached.getModelId() + " (cached)",
                hit.lookupMs());

        // Cache hits still go through the Reflector Gate (defense in depth)
        ReflectorGateResult gateResult = reflectorGate.evaluate(cachedClassification, notification);

        // RUL Forecasting for rotating equipment (same as LLM path)
        RulForecastingService.RulForecast rulForecast = null;
        EquipmentClass equipClass = EquipmentClass.fromEquipmentTag(notification.getEquipmentTag());
        if (gateResult.isPassed() && equipClass.isRotating()) {
            try {
                rulForecast = rulService.forecast(notification.getEquipmentTag());
            } catch (Exception e) {
                log.warn("  RUL Forecast (cache path): SKIPPED — {}", e.getMessage());
            }
        }

        SapBapiGateway.WriteBackResult writeBackResult = null;
        if (gateResult.isPassed()) {
            writeBackResult = bapiGateway.writeBackFailureCode(notification, cachedClassification, gateResult);
        }

        boolean writtenToSap = writeBackResult != null && writeBackResult.writtenToSap();
        boolean queuedForRetry = writeBackResult != null && writeBackResult.queuedForRetry();
        long totalLatency = System.currentTimeMillis() - pipelineStart;

        // Audit log for cache hits too
        recordAuditLog(notification, cachedClassification, gateResult, writtenToSap,
                totalLatency, true, 0, true, queuedForRetry);

        if (!gateResult.isPassed()) {
            recordExceptionInbox(notification, cachedClassification, gateResult);
        }

        log.info("════════════════════════════════════════════════════════");
        log.info("  PIPELINE COMPLETE in {}ms (CACHE HIT — Ollama bypassed!)", totalLatency);
        log.info("  Cache: {} match @ {}% | Lookup: {}ms",
                hit.matchType(), String.format("%.1f", hit.similarity() * 100), hit.lookupMs());
        log.info("  Result: {}", gateResult.isPassed()
                ? (writtenToSap ? "WRITTEN BACK TO SAP" : "QUEUED FOR RETRY (DLQ)")
                : "FLAGGED FOR HUMAN REVIEW");
        if (rulForecast != null && rulForecast.rulHours() >= 0) {
            log.info("  RUL: {}h remaining | Risk: {} | Action: {}",
                    rulForecast.rulHours(), rulForecast.riskLevel(), rulForecast.actionWindow());
        }
        log.info("════════════════════════════════════════════════════════");

        return new PipelineResult(
                notification, cachedClassification, gateResult,
                writeBackResult != null ? writeBackResult.bapiPayload() : null,
                totalLatency,
                true, 0,
                true, hit.lookupMs(), hit.matchType(), queuedForRetry, rulForecast);
    }

    private void recordAuditLog(
            MaintenanceNotification notification,
            SemanticClassification classification,
            ReflectorGateResult gateResult,
            boolean writtenToSap,
            long totalLatency,
            boolean jsonValidFirstAttempt,
            int llmAttempts,
            boolean cacheHit,
            boolean queuedForRetry) {
        try {
            AuditLog auditLog = AuditLog.record(
                    notification, classification, gateResult,
                    writtenToSap, totalLatency, demoModeEnabled,
                    jsonValidFirstAttempt, llmAttempts);
            auditLogRepository.save(auditLog);
        } catch (Exception e) {
            log.error("Failed to save audit log for {}: {}",
                    notification.getSapNotificationNumber(), e.getMessage());
        }
    }

    private void recordExceptionInbox(
            MaintenanceNotification notification,
            SemanticClassification classification,
            ReflectorGateResult gateResult) {
        try {
            ExceptionInboxItem item = ExceptionInboxItem.fromRejection(
                    notification, classification, gateResult);
            exceptionInboxRepository.save(item);
            log.info("Exception inbox item created for rejected notification {}",
                    notification.getSapNotificationNumber());
        } catch (Exception e) {
            log.error("Failed to save exception inbox item for {}: {}",
                    notification.getSapNotificationNumber(), e.getMessage());
        }
    }

    /**
     * Immutable result of a complete pipeline execution.
     */
    /**
     * Immutable result of a complete pipeline execution.
     *
     * @param rulForecast 72-hour Remaining Useful Life forecast for rotating equipment
     *                    (null if equipment is static, no vibration data, or gate rejected)
     */
    public record PipelineResult(
            MaintenanceNotification notification,
            SemanticClassification classification,
            ReflectorGateResult gateResult,
            Map<String, Object> bapiPayload,
            long totalLatencyMs,
            boolean jsonValidFirstAttempt,
            int llmAttempts,
            boolean cacheHit,
            long cacheLookupMs,
            String cacheMatchType,
            boolean queuedForRetry,
            RulForecastingService.RulForecast rulForecast
    ) {
        public boolean wasWrittenBack() {
            return gateResult.isPassed() && bapiPayload != null && !queuedForRetry;
        }

        /**
         * Whether this result includes a valid RUL forecast.
         * Useful for dashboard display — only show RUL panel for rotating equipment with data.
         */
        public boolean hasRulForecast() {
            return rulForecast != null && rulForecast.rulHours() >= 0;
        }
    }
}
