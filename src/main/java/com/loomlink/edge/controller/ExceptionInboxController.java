package com.loomlink.edge.controller;

import com.loomlink.edge.domain.enums.EquipmentClass;
import com.loomlink.edge.domain.enums.FailureModeCode;
import com.loomlink.edge.domain.model.AuditLog;
import com.loomlink.edge.domain.model.ExceptionInboxItem;
import com.loomlink.edge.gateway.SapBapiGateway;
import com.loomlink.edge.repository.AuditLogRepository;
import com.loomlink.edge.repository.ExceptionInboxRepository;
import com.loomlink.edge.service.ExperienceBankFeedbackService;
import com.loomlink.edge.service.RbacService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Exception Inbox REST API — allows Senior Engineers to review, approve,
 * reclassify, or dismiss rejected classifications.
 *
 * <p>RBAC enforcement: write operations (approve, reclassify, dismiss) require
 * the SENIOR_ENGINEER or ADMIN role. Operators and Technicians can view but not act.</p>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET  /api/v1/exceptions          — list all pending items (priority-sorted)</li>
 *   <li>GET  /api/v1/exceptions/all       — list all items regardless of status</li>
 *   <li>GET  /api/v1/exceptions/stats     — inbox statistics</li>
 *   <li>GET  /api/v1/exceptions/{id}      — get single item details</li>
 *   <li>PUT  /api/v1/exceptions/{id}/approve     — approve (SENIOR_ENGINEER only)</li>
 *   <li>PUT  /api/v1/exceptions/{id}/reclassify  — manually assign correct code (SENIOR_ENGINEER only)</li>
 *   <li>PUT  /api/v1/exceptions/{id}/dismiss      — mark as noise (SENIOR_ENGINEER only)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/exceptions")
public class ExceptionInboxController {

    private static final Logger log = LoggerFactory.getLogger(ExceptionInboxController.class);

    private final ExceptionInboxRepository repository;
    private final RbacService rbac;
    private final ExperienceBankFeedbackService feedbackService;
    private final AuditLogRepository auditLogRepository;
    private final SapBapiGateway bapiGateway;

    public ExceptionInboxController(
            ExceptionInboxRepository repository,
            RbacService rbac,
            ExperienceBankFeedbackService feedbackService,
            AuditLogRepository auditLogRepository,
            SapBapiGateway bapiGateway) {
        this.repository = repository;
        this.rbac = rbac;
        this.feedbackService = feedbackService;
        this.auditLogRepository = auditLogRepository;
        this.bapiGateway = bapiGateway;
    }

    /**
     * List all pending items, sorted by priority (CRITICAL > HIGH > MEDIUM > LOW).
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listPending() {
        List<ExceptionInboxItem> items = repository.findPendingByPriority();
        return ResponseEntity.ok(items.stream().map(this::toMap).toList());
    }

    /**
     * List all items regardless of review status.
     */
    @GetMapping("/all")
    public ResponseEntity<List<Map<String, Object>>> listAll() {
        List<ExceptionInboxItem> items = repository.findAll();
        items.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        return ResponseEntity.ok(items.stream().map(this::toMap).toList());
    }

    /**
     * Inbox statistics for the dashboard — includes operational metrics
     * for Ptil audit compliance and demo KPI reporting.
     *
     * <p>Metrics include:</p>
     * <ul>
     *   <li>Basic counts (pending, approved, reclassified, dismissed, total)</li>
     *   <li>False-positive rate (dismissed / total resolved) — measures gate accuracy</li>
     *   <li>SLA compliance rate (slaMet=true / total resolved) — NORSOK Z-008</li>
     *   <li>Average resolution hours (overall + per-priority breakdown)</li>
     *   <li>Average wait-before-view hours — measures inbox responsiveness</li>
     * </ul>
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        // ── Basic Counts ───────────────────────────────────────────────
        long pending = repository.countPending();
        long approved = repository.countApproved();
        long reclassified = repository.countReclassified();
        long dismissed = repository.countDismissed();
        long total = repository.count();
        long resolved = repository.countResolved();

        stats.put("pending", pending);
        stats.put("approved", approved);
        stats.put("reclassified", reclassified);
        stats.put("dismissed", dismissed);
        stats.put("total", total);
        stats.put("resolved", resolved);

        // ── Operational Metrics ────────────────────────────────────────

        // False-positive rate: dismissed items are "noise" the gate shouldn't have flagged
        // Lower is better — means the Reflector Gate is catching real issues
        if (resolved > 0) {
            double falsePositiveRate = (double) dismissed / resolved;
            stats.put("falsePositiveRate", Math.round(falsePositiveRate * 1000.0) / 1000.0);
        } else {
            stats.put("falsePositiveRate", null);
        }

        // SLA compliance: percentage of resolved items where review was within target hours
        long slaMet = repository.countSlaMet();
        long slaMissed = repository.countSlaMissed();
        long slaTotal = slaMet + slaMissed;
        if (slaTotal > 0) {
            double slaComplianceRate = (double) slaMet / slaTotal;
            stats.put("slaComplianceRate", Math.round(slaComplianceRate * 1000.0) / 1000.0);
            stats.put("slaMetCount", slaMet);
            stats.put("slaMissedCount", slaMissed);
        } else {
            stats.put("slaComplianceRate", null);
            stats.put("slaMetCount", 0L);
            stats.put("slaMissedCount", 0L);
        }

        // Resolution time and wait-before-view from resolved items
        List<ExceptionInboxItem> resolvedItems = repository.findResolved();
        if (!resolvedItems.isEmpty()) {
            // Average resolution hours (creation → review completion)
            double avgResolutionHours = resolvedItems.stream()
                    .map(ExceptionInboxItem::getResolutionHours)
                    .filter(h -> h != null)
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0);
            stats.put("avgResolutionHours", Math.round(avgResolutionHours * 100.0) / 100.0);

            // Average wait-before-view hours (creation → first engineer view)
            double avgWaitHours = resolvedItems.stream()
                    .map(ExceptionInboxItem::getWaitHoursBeforeView)
                    .filter(h -> h != null)
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0);
            stats.put("avgWaitBeforeViewHours", Math.round(avgWaitHours * 100.0) / 100.0);
        } else {
            stats.put("avgResolutionHours", null);
            stats.put("avgWaitBeforeViewHours", null);
        }

        // ── Per-Priority Breakdown ─────────────────────────────────────
        Map<String, Object> priorityBreakdown = new LinkedHashMap<>();
        for (String priority : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW")) {
            List<ExceptionInboxItem> byPriority = repository.findResolvedByPriority(priority);
            Map<String, Object> pStats = new LinkedHashMap<>();
            pStats.put("resolved", byPriority.size());

            if (!byPriority.isEmpty()) {
                double avgHours = byPriority.stream()
                        .map(ExceptionInboxItem::getResolutionHours)
                        .filter(h -> h != null)
                        .mapToDouble(Double::doubleValue)
                        .average()
                        .orElse(0.0);
                pStats.put("avgResolutionHours", Math.round(avgHours * 100.0) / 100.0);

                long metCount = byPriority.stream()
                        .filter(i -> Boolean.TRUE.equals(i.getSlaMet()))
                        .count();
                pStats.put("slaMetCount", metCount);
                pStats.put("slaComplianceRate",
                        Math.round((double) metCount / byPriority.size() * 1000.0) / 1000.0);
            } else {
                pStats.put("avgResolutionHours", null);
                pStats.put("slaMetCount", 0L);
                pStats.put("slaComplianceRate", null);
            }
            priorityBreakdown.put(priority, pStats);
        }
        stats.put("priorityBreakdown", priorityBreakdown);

        return ResponseEntity.ok(stats);
    }

    /**
     * Get a single exception item by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable UUID id) {
        return repository.findById(id)
                .map(item -> ResponseEntity.ok(toMap(item)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * APPROVE — Senior Engineer overrides the gate and approves the classification.
     * Requires SENIOR_ENGINEER or ADMIN role.
     */
    @PutMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable UUID id,
            @RequestBody ReviewRequest request) {

        // ── RBAC Check ─────────────────────────────────────────────────
        if (!rbac.isAuthorized(request.reviewedBy(), RbacService.Action.APPROVE_EXCEPTION)) {
            return buildForbiddenResponse(request.reviewedBy(), "approve");
        }

        return repository.findById(id).map(item -> {
            item.approve(request.reviewedBy(), request.notes());
            repository.save(item);
            log.info("Exception {} APPROVED by {} (RBAC: SENIOR_ENGINEER)", id, request.reviewedBy());

            // FEEDBACK LOOP: Promote approved classification to Experience Bank
            feedbackService.promoteApproval(item);

            // SAP WRITE-BACK: Push the approved code to SAP via BAPI
            // (The original pipeline didn't write because the gate rejected it.
            //  Now that a Senior Engineer approves, we complete the write-back.)
            try {
                bapiGateway.writeBackFromManualReview(
                        item.getSapNotificationNumber(),
                        item.getEquipmentTag(),
                        item.getSuggestedFailureCode(),
                        item.getSuggestedCauseCode(),
                        request.reviewedBy());
                log.info("SAP write-back completed for APPROVED exception {} → {}",
                        id, item.getSuggestedFailureCode());
            } catch (Exception e) {
                log.error("SAP write-back FAILED for approved exception {}: {}",
                        id, e.getMessage());
                // Don't fail the approval — it's still valid. DLQ will retry the SAP write.
            }

            // AUDIT TRAIL: Record the approval for compliance
            AuditLog auditEntry = AuditLog.recordManualReview(
                    item, "APPROVE", request.reviewedBy(),
                    item.getSuggestedFailureCode(), request.notes());
            auditLogRepository.save(auditEntry);
            log.info("Audit log created for APPROVAL of exception {}", id);

            return ResponseEntity.ok(toMap(item));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * RECLASSIFY — Senior Engineer assigns the correct failure code manually.
     * Requires SENIOR_ENGINEER or ADMIN role.
     */
    @PutMapping("/{id}/reclassify")
    public ResponseEntity<Map<String, Object>> reclassify(
            @PathVariable UUID id,
            @RequestBody ReclassifyRequest request) {

        // ── RBAC Check ─────────────────────────────────────────────────
        if (!rbac.isAuthorized(request.reviewedBy(), RbacService.Action.RECLASSIFY_EXCEPTION)) {
            return buildForbiddenResponse(request.reviewedBy(), "reclassify");
        }

        return repository.findById(id).map(item -> {
            FailureModeCode correctCode;
            try {
                correctCode = FailureModeCode.valueOf(request.failureModeCode().toUpperCase().trim());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().<Map<String, Object>>body(Map.of(
                        "error", "Invalid failure mode code: " + request.failureModeCode()));
            }
            item.reclassify(request.reviewedBy(), correctCode, request.notes());
            repository.save(item);
            log.info("Exception {} RECLASSIFIED to {} by {} (RBAC: SENIOR_ENGINEER)",
                    id, correctCode, request.reviewedBy());

            // ISO 14224 VALIDATION: Check if the engineer's code is valid for this equipment
            EquipmentClass equipClass = EquipmentClass.fromEquipmentTag(item.getEquipmentTag());
            if (equipClass != EquipmentClass.UNKNOWN
                    && !equipClass.isValidFailureMode(correctCode)) {
                log.warn("Engineer {} reclassified {} as {} but this is invalid for {} ({}). Allowing with warning.",
                        request.reviewedBy(), id, correctCode, equipClass.name(), item.getEquipmentTag());
                // We allow it (engineer override) but log a warning for audit
            }

            // FEEDBACK LOOP: Promote human correction to Experience Bank
            feedbackService.promoteHumanCorrection(item, correctCode);

            // SAP WRITE-BACK: Push the corrected code to SAP via BAPI
            // (Critical: without this, the reclassification only lives in our cache
            //  but the SAP notification still has no failure code.)
            try {
                bapiGateway.writeBackFromManualReview(
                        item.getSapNotificationNumber(),
                        item.getEquipmentTag(),
                        correctCode,
                        "HUMAN_CORRECTED",
                        request.reviewedBy());
                log.info("SAP write-back completed for RECLASSIFIED exception {} → {}",
                        id, correctCode);
            } catch (Exception e) {
                log.error("SAP write-back FAILED for reclassified exception {}: {}",
                        id, e.getMessage());
                // Don't fail the reclassification — DLQ will retry.
            }

            // AUDIT TRAIL: Record the reclassification for compliance
            AuditLog auditEntry = AuditLog.recordManualReview(
                    item, "RECLASSIFY", request.reviewedBy(),
                    correctCode, request.notes());
            auditLogRepository.save(auditEntry);
            log.info("Audit log created for RECLASSIFICATION of exception {} to {}", id, correctCode);

            return ResponseEntity.ok(toMap(item));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * DISMISS — Mark as noise / not actionable.
     * Requires SENIOR_ENGINEER or ADMIN role.
     */
    @PutMapping("/{id}/dismiss")
    public ResponseEntity<Map<String, Object>> dismiss(
            @PathVariable UUID id,
            @RequestBody ReviewRequest request) {

        // ── RBAC Check ─────────────────────────────────────────────────
        if (!rbac.isAuthorized(request.reviewedBy(), RbacService.Action.DISMISS_EXCEPTION)) {
            return buildForbiddenResponse(request.reviewedBy(), "dismiss");
        }

        return repository.findById(id).map(item -> {
            item.dismiss(request.reviewedBy(), request.notes());
            repository.save(item);
            log.info("Exception {} DISMISSED by {} (RBAC: SENIOR_ENGINEER)", id, request.reviewedBy());

            // AUDIT TRAIL: Record the dismissal for compliance
            AuditLog auditEntry = AuditLog.recordManualReview(
                    item, "DISMISS", request.reviewedBy(),
                    null, request.notes());
            auditLogRepository.save(auditEntry);
            log.info("Audit log created for DISMISSAL of exception {}", id);

            return ResponseEntity.ok(toMap(item));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── RBAC Helper ─────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> buildForbiddenResponse(String user, String action) {
        RbacService.Role role = rbac.getRole(user);
        String roleName = role != null ? role.name() : "UNKNOWN";
        log.warn("RBAC FORBIDDEN: user '{}' (role: {}) attempted to {} an exception", user, roleName, action);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "ACCESS DENIED");
        body.put("message", "User '" + user + "' (role: " + roleName +
                ") does not have permission to " + action + " exceptions. " +
                "Only SENIOR_ENGINEER or ADMIN roles can perform this action.");
        body.put("requiredRole", "SENIOR_ENGINEER");
        body.put("currentRole", roleName);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    // ── DTOs ────────────────────────────────────────────────────────────

    public record ReviewRequest(String reviewedBy, String notes) {}

    public record ReclassifyRequest(String reviewedBy, String failureModeCode, String notes) {}

    // ── Mapper ──────────────────────────────────────────────────────────

    private Map<String, Object> toMap(ExceptionInboxItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.getId());
        map.put("sapNotificationNumber", item.getSapNotificationNumber());
        map.put("equipmentTag", item.getEquipmentTag());
        map.put("originalText", item.getOriginalText());
        map.put("sapPlant", item.getSapPlant());
        map.put("suggestedFailureCode", item.getSuggestedFailureCode() != null
                ? item.getSuggestedFailureCode().name() : null);
        map.put("suggestedCauseCode", item.getSuggestedCauseCode());
        map.put("confidenceScore", item.getConfidenceScore());
        map.put("llmReasoning", item.getLlmReasoning());
        map.put("rejectionReason", item.getRejectionReason());
        map.put("gateThreshold", item.getGateThreshold());
        map.put("reviewStatus", item.getReviewStatus());
        map.put("priority", item.getPriority());
        map.put("reviewedBy", item.getReviewedBy());
        map.put("manualFailureCode", item.getManualFailureCode() != null
                ? item.getManualFailureCode().name() : null);
        map.put("reviewNotes", item.getReviewNotes());
        map.put("reviewedAt", item.getReviewedAt());
        map.put("createdAt", item.getCreatedAt());

        // SLA tracking fields
        map.put("firstViewedAt", item.getFirstViewedAt());
        map.put("firstViewedBy", item.getFirstViewedBy());
        map.put("slaTargetHours", item.getSlaTargetHours());
        map.put("slaMet", item.getSlaMet());
        map.put("waitHoursBeforeView", item.getWaitHoursBeforeView());
        map.put("resolutionHours", item.getResolutionHours());

        return map;
    }
}
