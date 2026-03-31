package com.loomlink.edge.controller;

import com.loomlink.edge.repository.*;
import com.loomlink.edge.service.EmissionSchedulerService;
import com.loomlink.edge.service.LiveFeedEventBus;
import com.loomlink.edge.service.LiveFeedSchedulerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Demo Reset Controller — Nuclear reset endpoint for Stavanger demo.
 *
 * <p>Clears ALL demo data across Challenge 01, 02, and 04 in a single call.
 * Use this before each demo run to guarantee a clean starting state.</p>
 *
 * <p>What gets cleared:</p>
 * <ul>
 *   <li>Audit logs (Ch1 classification history)</li>
 *   <li>Exception inbox items (Ch1 rejected items)</li>
 *   <li>Emission events (Ch2 surveillance data)</li>
 *   <li>SAP sync queue / DLQ entries</li>
 *   <li>Semantic cache / Experience Bank entries</li>
 *   <li>Robot mission records (Ch4)</li>
 *   <li>Vibration readings</li>
 *   <li>Failure history</li>
 *   <li>Both scheduler cycle counters</li>
 * </ul>
 *
 * <p>What is PRESERVED:</p>
 * <ul>
 *   <li>Asset master data (equipment registry — these are facility constants)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/demo/reset")
@Tag(name = "Demo Reset", description = "Nuclear demo reset — clears all data across all challenges")
@CrossOrigin(origins = "*")
public class DemoResetController {

    private static final Logger log = LoggerFactory.getLogger(DemoResetController.class);

    private final AuditLogRepository auditLogRepository;
    private final ExceptionInboxRepository exceptionInboxRepository;
    private final EmissionEventRepository emissionEventRepository;
    private final SapSyncQueueRepository sapSyncQueueRepository;
    private final SemanticCacheRepository semanticCacheRepository;
    private final RobotMissionRepository robotMissionRepository;
    private final VibrationReadingRepository vibrationReadingRepository;
    private final FailureHistoryRepository failureHistoryRepository;
    private final LiveFeedSchedulerService ch1Scheduler;
    private final EmissionSchedulerService ch2Scheduler;
    private final LiveFeedEventBus eventBus;

    public DemoResetController(
            AuditLogRepository auditLogRepository,
            ExceptionInboxRepository exceptionInboxRepository,
            EmissionEventRepository emissionEventRepository,
            SapSyncQueueRepository sapSyncQueueRepository,
            SemanticCacheRepository semanticCacheRepository,
            RobotMissionRepository robotMissionRepository,
            VibrationReadingRepository vibrationReadingRepository,
            FailureHistoryRepository failureHistoryRepository,
            LiveFeedSchedulerService ch1Scheduler,
            EmissionSchedulerService ch2Scheduler,
            LiveFeedEventBus eventBus) {
        this.auditLogRepository = auditLogRepository;
        this.exceptionInboxRepository = exceptionInboxRepository;
        this.emissionEventRepository = emissionEventRepository;
        this.sapSyncQueueRepository = sapSyncQueueRepository;
        this.semanticCacheRepository = semanticCacheRepository;
        this.robotMissionRepository = robotMissionRepository;
        this.vibrationReadingRepository = vibrationReadingRepository;
        this.failureHistoryRepository = failureHistoryRepository;
        this.ch1Scheduler = ch1Scheduler;
        this.ch2Scheduler = ch2Scheduler;
        this.eventBus = eventBus;
    }

    /**
     * Nuclear reset — clears ALL demo data across all challenges.
     *
     * <p>Call this endpoint before each demo run to start with a pristine state.
     * Both schedulers are paused, data is wiped, counters reset, then schedulers
     * resume — so the demo begins fresh immediately.</p>
     */
    @PostMapping
    @Operation(summary = "Reset all demo data across all challenges",
               description = "Nuclear reset: clears audit logs, exception inbox, emission events, "
                           + "SAP sync queue, semantic cache, robot missions, vibration readings, "
                           + "failure history, and resets both scheduler counters. "
                           + "Asset master data is preserved.")
    public ResponseEntity<Map<String, Object>> resetAll() {
        Instant start = Instant.now();
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║  NUCLEAR DEMO RESET — Clearing ALL demo data                   ║");
        log.info("╚══════════════════════════════════════════════════════════════════╝");

        // ── Step 1: Pause both schedulers to prevent writes during cleanup ──
        ch1Scheduler.pause(5);
        ch2Scheduler.pause(5);

        Map<String, Object> counts = new LinkedHashMap<>();

        // ── Step 2: Clear Ch1 data ──
        long auditCount = auditLogRepository.count();
        long inboxCount = exceptionInboxRepository.count();
        auditLogRepository.deleteAll();
        exceptionInboxRepository.deleteAll();
        counts.put("auditLogsCleared", auditCount);
        counts.put("exceptionInboxCleared", inboxCount);
        log.info("  Ch1: cleared {} audit logs, {} exception inbox items", auditCount, inboxCount);

        // ── Step 3: Clear Ch2 data ──
        long emissionCount = emissionEventRepository.count();
        emissionEventRepository.deleteAll();
        counts.put("emissionEventsCleared", emissionCount);
        log.info("  Ch2: cleared {} emission events", emissionCount);

        // ── Step 4: Clear SAP sync queue ──
        long sapQueueCount = sapSyncQueueRepository.count();
        sapSyncQueueRepository.deleteAll();
        counts.put("sapSyncQueueCleared", sapQueueCount);
        log.info("  SAP: cleared {} sync queue entries", sapQueueCount);

        // ── Step 5: Clear Experience Bank (semantic cache) ──
        long cacheCount = semanticCacheRepository.count();
        semanticCacheRepository.deleteAll();
        counts.put("semanticCacheCleared", cacheCount);
        log.info("  Experience Bank: cleared {} cache entries", cacheCount);

        // ── Step 6: Clear Ch4 data ──
        long missionCount = robotMissionRepository.count();
        robotMissionRepository.deleteAll();
        counts.put("robotMissionsCleared", missionCount);
        log.info("  Ch4: cleared {} robot missions", missionCount);

        // ── Step 7: Clear sensor data ──
        long vibrationCount = vibrationReadingRepository.count();
        vibrationReadingRepository.deleteAll();
        counts.put("vibrationReadingsCleared", vibrationCount);
        log.info("  Sensors: cleared {} vibration readings", vibrationCount);

        long failureCount = failureHistoryRepository.count();
        failureHistoryRepository.deleteAll();
        counts.put("failureHistoryCleared", failureCount);
        log.info("  History: cleared {} failure records", failureCount);

        // ── Step 8: Reset scheduler counters ──
        ch1Scheduler.resetDemo();
        ch2Scheduler.resetDemo();

        // ── Step 9: Clear the SSE event buffer ──
        eventBus.clearHistory();

        // ── Step 10: Resume both schedulers ──
        ch1Scheduler.resume();
        ch2Scheduler.resume();

        long durationMs = Instant.now().toEpochMilli() - start.toEpochMilli();
        long totalCleared = auditCount + inboxCount + emissionCount + sapQueueCount
                          + cacheCount + missionCount + vibrationCount + failureCount;

        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║  RESET COMPLETE — {} records cleared in {}ms                    ║", totalCleared, durationMs);
        log.info("╚══════════════════════════════════════════════════════════════════╝");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "RESET_COMPLETE");
        result.put("totalRecordsCleared", totalCleared);
        result.put("durationMs", durationMs);
        result.put("details", counts);
        result.put("schedulers", Map.of(
                "ch1", "RUNNING — will re-process all 18 scenarios",
                "ch2", "RUNNING — will re-process all 15 scenarios"
        ));
        result.put("preserved", "Asset master data (equipment registry)");
        result.put("message", "All demo data cleared. Both schedulers restarted. Ready for fresh demo run.");

        return ResponseEntity.ok(result);
    }

    /**
     * Get current demo state — useful for checking data volumes before reset.
     */
    @GetMapping("/preview")
    @Operation(summary = "Preview data volumes before reset — see what will be cleared")
    public ResponseEntity<Map<String, Object>> demoStatus() {
        Map<String, Object> status = new LinkedHashMap<>();

        // Data volumes
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("auditLogs", auditLogRepository.count());
        data.put("exceptionInbox", exceptionInboxRepository.count());
        data.put("emissionEvents", emissionEventRepository.count());
        data.put("sapSyncQueue", sapSyncQueueRepository.count());
        data.put("semanticCache", semanticCacheRepository.count());
        data.put("robotMissions", robotMissionRepository.count());
        data.put("vibrationReadings", vibrationReadingRepository.count());
        data.put("failureHistory", failureHistoryRepository.count());
        status.put("dataVolumes", data);

        // Scheduler states
        status.put("ch1Scheduler", ch1Scheduler.getStatus());
        status.put("ch2Scheduler", ch2Scheduler.getStatus());

        return ResponseEntity.ok(status);
    }
}
