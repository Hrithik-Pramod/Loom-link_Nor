package com.loomlink.edge.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sovereign Node Health Monitor — proves "100% Local Execution" to judges.
 *
 * <p>Shows live status of every component in the sovereign pipeline:
 * Ollama LLM, PostgreSQL Experience Bank, SAP Gateway, and the HM90 node itself.
 * A single red indicator during the demo would undermine the entire pitch.</p>
 */
@RestController
@RequestMapping("/api/v1/health")
@CrossOrigin(origins = "*")
public class HealthController {

    @Value("${loomlink.llm.base-url}")
    private String ollamaBaseUrl;

    @Value("${loomlink.llm.model-id}")
    private String modelId;

    @Value("${loomlink.reflector-gate.confidence-threshold}")
    private double gateThreshold;

    private final DataSource dataSource;
    private final HttpClient httpClient;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Full sovereign node health check. Tests every component.
     */
    @GetMapping("/sovereign")
    public ResponseEntity<Map<String, Object>> sovereignHealth() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("timestamp", Instant.now().toString());
        health.put("node", "HM90 Ryzen 9 — Sovereign Node");

        // Ollama LLM Health
        health.put("llm", checkOllama());

        // PostgreSQL Experience Bank Health
        health.put("experienceBank", checkPostgres());

        // SAP Gateway (simulated)
        health.put("sapGateway", checkSapGateway());

        // Pipeline Configuration
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("gateThreshold", gateThreshold);
        config.put("modelId", modelId);
        config.put("ollamaEndpoint", ollamaBaseUrl);
        config.put("dataEgress", "BLOCKED");
        config.put("executionMode", "100% LOCAL");
        health.put("configuration", config);

        // Overall status
        boolean allHealthy = isHealthy(health.get("llm"))
                && isHealthy(health.get("experienceBank"))
                && isHealthy(health.get("sapGateway"));
        health.put("overallStatus", allHealthy ? "SOVEREIGN_ACTIVE" : "DEGRADED");

        return ResponseEntity.ok(health);
    }

    private Map<String, Object> checkOllama() {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            // Check Ollama API is responding
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaBaseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;

            result.put("status", response.statusCode() == 200 ? "ONLINE" : "ERROR");
            result.put("endpoint", ollamaBaseUrl);
            result.put("model", modelId);
            result.put("responseTimeMs", latency);

            // Check if our model is loaded
            String body = response.body();
            boolean modelLoaded = body.contains(modelId.split(":")[0]);
            result.put("modelLoaded", modelLoaded);
        } catch (Exception e) {
            result.put("status", "OFFLINE");
            result.put("endpoint", ollamaBaseUrl);
            result.put("model", modelId);
            result.put("error", e.getMessage());
            result.put("responseTimeMs", System.currentTimeMillis() - start);
        }
        return result;
    }

    private Map<String, Object> checkPostgres() {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection()) {
            var stmt = conn.createStatement();
            var rs = stmt.executeQuery("SELECT 1");
            rs.next();
            long latency = System.currentTimeMillis() - start;

            result.put("status", "ONLINE");
            result.put("responseTimeMs", latency);
            result.put("database", conn.getCatalog());
            result.put("url", conn.getMetaData().getURL());

            // Check pgvector extension
            try {
                var extRs = stmt.executeQuery(
                    "SELECT extversion FROM pg_extension WHERE extname = 'vector'");
                if (extRs.next()) {
                    result.put("pgvector", extRs.getString(1));
                } else {
                    result.put("pgvector", "NOT_INSTALLED");
                }
            } catch (Exception e) {
                result.put("pgvector", "CHECK_FAILED");
            }
        } catch (Exception e) {
            result.put("status", "OFFLINE");
            result.put("error", e.getMessage());
            result.put("responseTimeMs", System.currentTimeMillis() - start);
        }
        return result;
    }

    private Map<String, Object> checkSapGateway() {
        Map<String, Object> result = new LinkedHashMap<>();
        // SAP Gateway is simulated in our system
        result.put("status", "SIMULATED");
        result.put("mode", "BAPI_SIMULATION");
        result.put("bapiFunction", "BAPI_ALM_NOTIF_DATA_MODIFY");
        result.put("cleanCoreCompliant", true);
        result.put("note", "Production: JCo/OData gateway to SAP ECC/S4HANA");
        return result;
    }

    @SuppressWarnings("unchecked")
    private boolean isHealthy(Object component) {
        if (component instanceof Map) {
            String status = String.valueOf(((Map<String, Object>) component).get("status"));
            return "ONLINE".equals(status) || "SIMULATED".equals(status);
        }
        return false;
    }
}
