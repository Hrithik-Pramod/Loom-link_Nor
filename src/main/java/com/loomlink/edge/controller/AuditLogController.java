package com.loomlink.edge.controller;

import com.loomlink.edge.domain.model.AuditLog;
import com.loomlink.edge.repository.AuditLogRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Audit Trail API — Compliance endpoint for the immutable classification audit log.
 *
 * <p>North Sea operators (Equinor, Aker BP) require full traceability of every
 * SAP write-back. This controller exposes the append-only audit trail with
 * filtering and summary statistics for regulatory review.</p>
 */
@RestController
@RequestMapping("/api/v1/audit")
@CrossOrigin(origins = "*")
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;

    public AuditLogController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /** List recent audit logs (newest first). */
    @GetMapping("/logs")
    public ResponseEntity<List<Map<String, Object>>> recentLogs() {
        List<AuditLog> logs = auditLogRepository.findRecentLogs();
        return ResponseEntity.ok(logs.stream().map(this::toMap).toList());
    }

    /** Filter audit logs by equipment tag. */
    @GetMapping("/logs/equipment/{tag}")
    public ResponseEntity<List<Map<String, Object>>> byEquipment(@PathVariable String tag) {
        List<AuditLog> logs = auditLogRepository.findByEquipmentTag(tag);
        return ResponseEntity.ok(logs.stream().map(this::toMap).toList());
    }

    /** Filter audit logs by SAP notification number. */
    @GetMapping("/logs/notification/{sapNo}")
    public ResponseEntity<List<Map<String, Object>>> byNotification(@PathVariable String sapNo) {
        List<AuditLog> logs = auditLogRepository.findBySapNotificationNumber(sapNo);
        return ResponseEntity.ok(logs.stream().map(this::toMap).toList());
    }

    /** Filter audit logs by gate verdict. */
    @GetMapping("/logs/gate/{passed}")
    public ResponseEntity<List<Map<String, Object>>> byGateVerdict(@PathVariable boolean passed) {
        List<AuditLog> logs = auditLogRepository.findByGatePassed(passed);
        return ResponseEntity.ok(logs.stream().map(this::toMap).toList());
    }

    /** Aggregate audit statistics for dashboard KPIs. */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        long total = auditLogRepository.count();
        long passed = auditLogRepository.countPassedClassifications();
        long rejected = auditLogRepository.countRejectedClassifications();
        Double avgConfidence = auditLogRepository.averagePassedConfidence();
        Double avgLatency = auditLogRepository.averagePipelineLatency();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalClassifications", total);
        stats.put("passed", passed);
        stats.put("rejected", rejected);
        stats.put("passRate", total > 0 ? Math.round((double) passed / total * 1000.0) / 10.0 : 0.0);
        stats.put("averageConfidence", avgConfidence != null ? Math.round(avgConfidence * 1000.0) / 1000.0 : 0.0);
        stats.put("averagePipelineLatencyMs", avgLatency != null ? Math.round(avgLatency) : 0);
        stats.put("gateThreshold", 0.85);
        return ResponseEntity.ok(stats);
    }

    /**
     * Export audit logs as CSV for regulatory compliance.
     *
     * <p>North Sea operators require exportable audit records for
     * DNV and Petroleumstilsynet (PSA) compliance reviews.</p>
     */
    @GetMapping(value = "/export/csv", produces = "text/csv")
    public ResponseEntity<String> exportCsv() {
        List<AuditLog> logs = auditLogRepository.findRecentLogs();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.of("Europe/Oslo"));

        StringBuilder csv = new StringBuilder();
        csv.append("Timestamp,SAP Notification,Equipment Tag,Original Text,Failure Code,Cause Code,");
        csv.append("Confidence,Gate Passed,Gate Reasoning,Written To SAP,Model,");
        csv.append("Inference Latency (ms),Pipeline Latency (ms),JSON Valid 1st,LLM Attempts,Demo Mode\n");

        for (AuditLog log : logs) {
            csv.append(fmt.format(log.getCreatedAt())).append(",");
            csv.append(escapeCsv(log.getSapNotificationNumber())).append(",");
            csv.append(escapeCsv(log.getEquipmentTag())).append(",");
            csv.append(escapeCsv(log.getOriginalText())).append(",");
            csv.append(log.getFailureModeCode().name()).append(",");
            csv.append(escapeCsv(log.getCauseCode())).append(",");
            csv.append(String.format("%.3f", log.getConfidenceScore())).append(",");
            csv.append(log.isGatePassed()).append(",");
            csv.append(escapeCsv(log.getGateReasoning())).append(",");
            csv.append(log.isWrittenToSap()).append(",");
            csv.append(escapeCsv(log.getModelName())).append(",");
            csv.append(log.getInferenceLatencyMs()).append(",");
            csv.append(log.getTotalPipelineLatencyMs()).append(",");
            csv.append(log.isJsonValidFirstAttempt()).append(",");
            csv.append(log.getLlmAttempts()).append(",");
            csv.append(log.isDemoMode()).append("\n");
        }

        String filename = "loom-link-audit-" +
                DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .withZone(ZoneId.of("Europe/Oslo"))
                        .format(Instant.now()) + ".csv";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.toString());
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private Map<String, Object> toMap(AuditLog log) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", log.getId());
        m.put("sapNotificationNumber", log.getSapNotificationNumber());
        m.put("equipmentTag", log.getEquipmentTag());
        m.put("originalText", log.getOriginalText());
        m.put("failureModeCode", log.getFailureModeCode().name());
        m.put("causeCode", log.getCauseCode());
        m.put("confidenceScore", log.getConfidenceScore());
        m.put("llmReasoning", log.getLlmReasoning());
        m.put("modelName", log.getModelName());
        m.put("inferenceLatencyMs", log.getInferenceLatencyMs());
        m.put("gateThreshold", log.getGateThreshold());
        m.put("gatePassed", log.isGatePassed());
        m.put("gateReasoning", log.getGateReasoning());
        m.put("writtenToSap", log.isWrittenToSap());
        m.put("bapiFunction", log.getBapiFunction());
        m.put("totalPipelineLatencyMs", log.getTotalPipelineLatencyMs());
        m.put("demoMode", log.isDemoMode());
        m.put("jsonValidFirstAttempt", log.isJsonValidFirstAttempt());
        m.put("llmAttempts", log.getLlmAttempts());
        m.put("createdAt", log.getCreatedAt());
        return m;
    }
}
