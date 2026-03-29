package com.loomlink.edge.controller;

import com.loomlink.edge.service.ExperienceBankFeedbackService;
import com.loomlink.edge.service.RbacService;
import com.loomlink.edge.service.SapSyncRetryScheduler;
import com.loomlink.edge.service.SemanticCacheService;
import com.loomlink.edge.repository.SapSyncQueueRepository;
import com.loomlink.edge.domain.model.SapSyncQueueItem;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resilience Dashboard API — exposes the health and metrics of the
 * Scale & Resilience features for the demo dashboard.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET /api/v1/resilience/cache/stats — Semantic Cache metrics</li>
 *   <li>GET /api/v1/resilience/dlq/stats   — Dead Letter Queue metrics</li>
 *   <li>GET /api/v1/resilience/dlq/items   — List DLQ items</li>
 *   <li>GET /api/v1/resilience/rbac/users  — Show user roles</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/resilience")
@CrossOrigin(origins = "*")
public class ResilienceController {

    private final SemanticCacheService cacheService;
    private final SapSyncRetryScheduler retryScheduler;
    private final SapSyncQueueRepository dlqRepository;
    private final RbacService rbacService;
    private final ExperienceBankFeedbackService feedbackService;

    public ResilienceController(
            SemanticCacheService cacheService,
            SapSyncRetryScheduler retryScheduler,
            SapSyncQueueRepository dlqRepository,
            RbacService rbacService,
            ExperienceBankFeedbackService feedbackService) {
        this.cacheService = cacheService;
        this.retryScheduler = retryScheduler;
        this.dlqRepository = dlqRepository;
        this.rbacService = rbacService;
        this.feedbackService = feedbackService;
    }

    /** Semantic Cache statistics. */
    @GetMapping("/cache/stats")
    public ResponseEntity<Map<String, Object>> cacheStats() {
        return ResponseEntity.ok(cacheService.getStats());
    }

    /** Dead Letter Queue statistics. */
    @GetMapping("/dlq/stats")
    public ResponseEntity<Map<String, Object>> dlqStats() {
        return ResponseEntity.ok(retryScheduler.getStats());
    }

    /** List DLQ items by status. */
    @GetMapping("/dlq/items")
    public ResponseEntity<List<Map<String, Object>>> dlqItems(
            @RequestParam(defaultValue = "PENDING_SYNC") String status) {
        List<SapSyncQueueItem> items = dlqRepository.findBySyncStatusOrderByCreatedAtDesc(status);
        return ResponseEntity.ok(items.stream().map(this::dlqToMap).toList());
    }

    /** Experience Bank Feedback Loop statistics. */
    @GetMapping("/feedback/stats")
    public ResponseEntity<Map<String, Object>> feedbackStats() {
        return ResponseEntity.ok(feedbackService.getStats());
    }

    /** Show all registered RBAC users and their roles. */
    @GetMapping("/rbac/users")
    public ResponseEntity<Map<String, Object>> rbacUsers() {
        Map<String, Object> result = new LinkedHashMap<>();
        rbacService.getAllUsers().forEach((name, role) -> result.put(name, role.name()));
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> dlqToMap(SapSyncQueueItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.getId());
        map.put("sapNotificationNumber", item.getSapNotificationNumber());
        map.put("equipmentTag", item.getEquipmentTag());
        map.put("failureModeCode", item.getFailureModeCode().name());
        map.put("confidence", item.getConfidence());
        map.put("syncStatus", item.getSyncStatus());
        map.put("retryCount", item.getRetryCount());
        map.put("maxRetries", item.getMaxRetries());
        map.put("lastError", item.getLastError());
        map.put("nextRetryAt", item.getNextRetryAt());
        map.put("syncedAt", item.getSyncedAt());
        map.put("createdAt", item.getCreatedAt());
        return map;
    }
}
