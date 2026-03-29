package com.loomlink.edge.controller;

import com.loomlink.edge.service.DemoModeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Demo Mode controller — allows toggling demo mode on/off from the dashboard
 * and provides scenario metadata for the UI.
 */
@RestController
@RequestMapping("/api/v1/demo")
@CrossOrigin(origins = "*")
public class DemoController {

    private final DemoModeService demoMode;

    public DemoController(DemoModeService demoMode) {
        this.demoMode = demoMode;
    }

    /** Toggle demo mode on/off. */
    @PostMapping("/toggle")
    public ResponseEntity<Map<String, Object>> toggle() {
        demoMode.toggle();
        return ResponseEntity.ok(Map.of(
                "demoMode", demoMode.isEnabled(),
                "message", demoMode.isEnabled()
                        ? "Demo mode ON — deterministic responses active"
                        : "Demo mode OFF — using live Mistral 7B on HM90"
        ));
    }

    /** Get current demo mode status. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "demoMode", demoMode.isEnabled()
        ));
    }

    /** Get all available demo scenarios for the dashboard. */
    @GetMapping("/scenarios")
    public ResponseEntity<List<Map<String, Object>>> scenarios() {
        return ResponseEntity.ok(demoMode.getScenarios());
    }
}
