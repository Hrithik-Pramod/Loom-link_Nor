package com.loomlink.edge.controller;

import com.loomlink.edge.service.EmissionSchedulerService;
import com.loomlink.edge.service.LiveFeedEventBus;
import com.loomlink.edge.service.LiveFeedSchedulerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live Feed API — SSE streaming and scheduler control for the demo dashboard.
 *
 * <p>Provides real-time event streaming to dashboard clients and
 * pause/resume control for both Challenge 01 (Maintenance) and
 * Challenge 02 (Emission Surveillance) automated feeds.</p>
 */
@RestController
@RequestMapping("/api/v1/live-feed")
@Tag(name = "Live Feed", description = "Real-time event streaming and scheduler control")
@CrossOrigin(origins = "*")
public class LiveFeedController {

    private static final Logger log = LoggerFactory.getLogger(LiveFeedController.class);

    private final LiveFeedEventBus eventBus;
    private final LiveFeedSchedulerService schedulerService;
    private final EmissionSchedulerService emissionSchedulerService;

    public LiveFeedController(
            LiveFeedEventBus eventBus,
            LiveFeedSchedulerService schedulerService,
            EmissionSchedulerService emissionSchedulerService) {
        this.eventBus = eventBus;
        this.schedulerService = schedulerService;
        this.emissionSchedulerService = emissionSchedulerService;
    }

    // ── Shared SSE Stream ──────────────────────────────────────────────────

    /** SSE endpoint — dashboard subscribes to receive real-time events (both Ch1 and Ch2). */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Subscribe to live feed SSE stream")
    public SseEmitter stream() {
        log.info("New SSE subscriber connecting to live feed");
        return eventBus.subscribe();
    }

    /** Get recent events (for page load / reconnection). */
    @GetMapping("/recent")
    @Operation(summary = "Get recent feed events (last 50)")
    public ResponseEntity<List<Map<String, Object>>> getRecentEvents() {
        return ResponseEntity.ok(eventBus.getRecentEvents());
    }

    // ══════════════════════════════════════════════════════════════════════
    // Challenge 01 — Maintenance Classification Controls
    // ══════════════════════════════════════════════════════════════════════

    /** Get Challenge 01 scheduler status. */
    @GetMapping("/ch1/status")
    @Operation(summary = "Get Ch1 maintenance feed status")
    public ResponseEntity<Map<String, Object>> getCh1Status() {
        return ResponseEntity.ok(schedulerService.getStatus());
    }

    /** Pause Challenge 01 maintenance feed. */
    @PostMapping("/ch1/pause")
    @Operation(summary = "Pause Ch1 maintenance feed")
    public ResponseEntity<Map<String, Object>> pauseCh1(
            @RequestParam(defaultValue = "20") int minutes) {
        schedulerService.pause(minutes);
        return ResponseEntity.ok(Map.of(
                "status", "PAUSED",
                "challenge", "CH1",
                "pauseMinutes", minutes,
                "message", "Challenge 01 maintenance feed paused for " + minutes + " minutes."
        ));
    }

    /** Resume Challenge 01 maintenance feed. */
    @PostMapping("/ch1/resume")
    @Operation(summary = "Resume Ch1 maintenance feed")
    public ResponseEntity<Map<String, Object>> resumeCh1() {
        schedulerService.resume();
        return ResponseEntity.ok(Map.of(
                "status", "RESUMED",
                "challenge", "CH1",
                "message", "Challenge 01 maintenance feed resumed."
        ));
    }

    /** Reset Challenge 01 demo data. */
    @PostMapping("/ch1/reset")
    @Operation(summary = "Reset Ch1 demo data")
    public ResponseEntity<Map<String, Object>> resetCh1() {
        schedulerService.resetDemo();
        return ResponseEntity.ok(Map.of(
                "status", "RESET",
                "challenge", "CH1",
                "message", "Ch1 demo data cleared. Scheduler will re-process all 18 scenarios."
        ));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Challenge 02 — Emission Surveillance Controls
    // ══════════════════════════════════════════════════════════════════════

    /** Get Challenge 02 emission scheduler status. */
    @GetMapping("/ch2/status")
    @Operation(summary = "Get Ch2 emission surveillance feed status")
    public ResponseEntity<Map<String, Object>> getCh2Status() {
        return ResponseEntity.ok(emissionSchedulerService.getStatus());
    }

    /** Pause Challenge 02 emission feed. */
    @PostMapping("/ch2/pause")
    @Operation(summary = "Pause Ch2 emission surveillance feed")
    public ResponseEntity<Map<String, Object>> pauseCh2(
            @RequestParam(defaultValue = "20") int minutes) {
        emissionSchedulerService.pause(minutes);
        return ResponseEntity.ok(Map.of(
                "status", "PAUSED",
                "challenge", "CH2",
                "pauseMinutes", minutes,
                "message", "Challenge 02 emission feed paused for " + minutes + " minutes."
        ));
    }

    /** Resume Challenge 02 emission feed. */
    @PostMapping("/ch2/resume")
    @Operation(summary = "Resume Ch2 emission surveillance feed")
    public ResponseEntity<Map<String, Object>> resumeCh2() {
        emissionSchedulerService.resume();
        return ResponseEntity.ok(Map.of(
                "status", "RESUMED",
                "challenge", "CH2",
                "message", "Challenge 02 emission feed resumed."
        ));
    }

    /** Reset Challenge 02 emission demo data. */
    @PostMapping("/ch2/reset")
    @Operation(summary = "Reset Ch2 emission demo data")
    public ResponseEntity<Map<String, Object>> resetCh2() {
        emissionSchedulerService.resetDemo();
        return ResponseEntity.ok(Map.of(
                "status", "RESET",
                "challenge", "CH2",
                "message", "Ch2 emission data cleared. Scheduler will re-process all 15 scenarios."
        ));
    }

    // ══════════════════════════════════════════════════════════════════════
    // Legacy Endpoints (backward compatible — controls Ch1 only)
    // ══════════════════════════════════════════════════════════════════════

    /** Get scheduler status (legacy — returns Ch1 status). */
    @GetMapping("/status")
    @Operation(summary = "Get live feed scheduler status (legacy)")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> combined = new LinkedHashMap<>();
        combined.put("ch1", schedulerService.getStatus());
        combined.put("ch2", emissionSchedulerService.getStatus());
        return ResponseEntity.ok(combined);
    }

    /** Pause all automated feeds (legacy). */
    @PostMapping("/pause")
    @Operation(summary = "Pause all live feeds (legacy)")
    public ResponseEntity<Map<String, Object>> pause(
            @RequestParam(defaultValue = "20") int minutes) {
        schedulerService.pause(minutes);
        emissionSchedulerService.pause(minutes);
        return ResponseEntity.ok(Map.of(
                "status", "PAUSED",
                "pauseMinutes", minutes,
                "message", "All feeds paused for " + minutes + " minutes."
        ));
    }

    /** Resume all feeds immediately (legacy). */
    @PostMapping("/resume")
    @Operation(summary = "Resume all live feeds (legacy)")
    public ResponseEntity<Map<String, Object>> resume() {
        schedulerService.resume();
        emissionSchedulerService.resume();
        return ResponseEntity.ok(Map.of(
                "status", "RESUMED",
                "message", "All automated feeds resumed."
        ));
    }

    /** Reset all demo data (legacy). */
    @PostMapping("/reset")
    @Operation(summary = "Reset all demo data (legacy)")
    public ResponseEntity<Map<String, Object>> resetDemo() {
        schedulerService.resetDemo();
        emissionSchedulerService.resetDemo();
        return ResponseEntity.ok(Map.of(
                "status", "RESET",
                "message", "All demo data cleared."
        ));
    }
}
