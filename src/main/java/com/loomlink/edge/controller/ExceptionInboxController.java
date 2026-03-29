package com.loomlink.edge.controller;

import com.loomlink.edge.domain.enums.FailureModeCode;
import com.loomlink.edge.domain.model.AuditLog;
import com.loomlink.edge.domain.model.ExceptionInboxItem;
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

    public ExceptionInboxController(
            ExceptionInboxRepository repository,
            RbacService rbac,
            ExperienceBankFeedbackService feedbackService,
            AuditLogRepository auditLogRepository) {
        this.repository = repository;
        this.rbac = rbac;
        this.feedbackService = feedbackService;
        this.auditLogRepository = auditLogRepository;
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
     * Inbox statistics for the dashboard.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pending", repository.countPending());
        stats.put("approved", repository.countApproved());
        stats.put("reclassified", repository.countReclassified());
        stats.put("dismissed", repository.countDismissed());
        stats.put("total", repository.count());
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

            // FEEDBACK LOOP: Promote human correction to Experience Bank
            feedbackService.promoteHumanCorrection(item, correctCode);

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
        return map;
    }
}
