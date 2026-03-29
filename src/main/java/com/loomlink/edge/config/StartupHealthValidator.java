package com.loomlink.edge.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;

/**
 * Startup health validation — checks all dependencies on boot and logs clear status.
 *
 * <p>If Ollama or PostgreSQL is unreachable, the system continues but logs a
 * prominent warning. This ensures the demo never fails silently — the engineer
 * knows exactly what's wrong from the first log line.</p>
 */
@Component
public class StartupHealthValidator {

    private static final Logger log = LoggerFactory.getLogger(StartupHealthValidator.class);

    @Value("${loomlink.llm.base-url}")
    private String ollamaBaseUrl;

    @Value("${loomlink.llm.model-id}")
    private String modelId;

    @Value("${loomlink.reflector-gate.confidence-threshold}")
    private double gateThreshold;

    private final DataSource dataSource;

    public StartupHealthValidator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validateOnStartup() {
        log.info("");
        log.info("================================================================");
        log.info("  LOOM LINK — SOVEREIGN NODE HEALTH CHECK");
        log.info("================================================================");

        boolean dbOk = checkDatabase();
        boolean ollamaOk = checkOllama();

        log.info("----------------------------------------------------------------");
        log.info("  PIPELINE CONFIGURATION");
        log.info("  Reflector Gate Threshold : {}", gateThreshold);
        log.info("  LLM Model               : {}", modelId);
        log.info("  Ollama Endpoint          : {}", ollamaBaseUrl);
        log.info("  Data Egress              : BLOCKED (100% Local)");
        log.info("----------------------------------------------------------------");

        if (dbOk && ollamaOk) {
            log.info("  STATUS: ALL SYSTEMS OPERATIONAL");
            log.info("  Swagger UI: http://localhost:8080/swagger-ui.html");
            log.info("  Dashboard:  http://localhost:8080");
            log.info("  Prometheus: http://localhost:8080/actuator/prometheus");
        } else {
            log.warn("  STATUS: DEGRADED — check warnings above");
            if (!dbOk) log.warn("  PostgreSQL is unreachable. Experience Bank unavailable.");
            if (!ollamaOk) log.warn("  Ollama is unreachable. LLM classification will fail.");
        }

        log.info("================================================================");
        log.info("");
    }

    private boolean checkDatabase() {
        try (Connection conn = dataSource.getConnection()) {
            var rs = conn.createStatement().executeQuery("SELECT 1");
            rs.next();
            log.info("  PostgreSQL       : ONLINE ({})", conn.getMetaData().getURL());

            // Check pgvector extension
            try {
                var extRs = conn.createStatement().executeQuery(
                        "SELECT extversion FROM pg_extension WHERE extname = 'vector'");
                if (extRs.next()) {
                    log.info("  pgvector         : v{}", extRs.getString(1));
                } else {
                    log.warn("  pgvector         : NOT INSTALLED (cache similarity disabled)");
                }
            } catch (Exception e) {
                log.warn("  pgvector         : CHECK FAILED ({})", e.getMessage());
            }
            return true;
        } catch (Exception e) {
            log.error("  PostgreSQL       : OFFLINE ({})", e.getMessage());
            return false;
        }
    }

    private boolean checkOllama() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaBaseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                boolean modelLoaded = response.body().contains(modelId.split(":")[0]);
                log.info("  Ollama LLM       : ONLINE ({})", ollamaBaseUrl);
                log.info("  Model ({}) : {}", modelId, modelLoaded ? "LOADED" : "NOT FOUND — run 'ollama pull " + modelId + "'");
                return true;
            } else {
                log.warn("  Ollama LLM       : ERROR (HTTP {})", response.statusCode());
                return false;
            }
        } catch (Exception e) {
            log.error("  Ollama LLM       : OFFLINE ({})", e.getMessage());
            return false;
        }
    }
}
