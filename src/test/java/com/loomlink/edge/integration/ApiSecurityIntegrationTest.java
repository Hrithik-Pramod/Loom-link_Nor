package com.loomlink.edge.integration;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test: API Key Security Filter.
 *
 * <p>Verifies that the Sovereign Node authentication layer correctly:</p>
 * <ul>
 *   <li>Rejects requests without an API key</li>
 *   <li>Rejects requests with an invalid API key</li>
 *   <li>Allows requests with the correct API key</li>
 *   <li>Allows public endpoints (health, Swagger) without a key</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "loomlink.security.api-key-enabled=true",
        "loomlink.security.api-key=test-sovereign-key-2026"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("API Security — Sovereign Node Authentication")
class ApiSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatLanguageModel chatModel;

    private static final String VALID_KEY = "test-sovereign-key-2026";
    private static final String INVALID_KEY = "wrong-key";

    // ── Protected endpoints reject missing API key ─────────────────────────

    @Test
    @Order(1)
    @DisplayName("Pipeline endpoint rejects request without API key")
    void pipelineRejectsWithoutKey() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/assets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    // ── Protected endpoints reject invalid API key ─────────────────────────

    @Test
    @Order(2)
    @DisplayName("Pipeline endpoint rejects invalid API key")
    void pipelineRejectsInvalidKey() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/assets")
                        .header("X-API-Key", INVALID_KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid API key."));
    }

    // ── Protected endpoints accept valid API key ───────────────────────────

    @Test
    @Order(3)
    @DisplayName("Pipeline endpoint accepts valid API key")
    void pipelineAcceptsValidKey() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/assets")
                        .header("X-API-Key", VALID_KEY))
                .andExpect(status().isOk());
    }

    // ── Audit endpoint requires auth ───────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("Audit trail endpoint requires authentication")
    void auditRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/audit/stats"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/audit/stats")
                        .header("X-API-Key", VALID_KEY))
                .andExpect(status().isOk());
    }

    // ── Exception Inbox requires auth ──────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("Exception Inbox endpoint requires authentication")
    void exceptionInboxRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/exceptions"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/exceptions")
                        .header("X-API-Key", VALID_KEY))
                .andExpect(status().isOk());
    }

    // ── Public endpoints do NOT require API key ────────────────────────────

    @Test
    @Order(6)
    @DisplayName("Actuator health check is publicly accessible")
    void healthCheckIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @Order(7)
    @DisplayName("Dashboard HTML is publicly accessible")
    void dashboardHtmlIsPublic() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    @Order(8)
    @DisplayName("Swagger API docs are publicly accessible")
    void swaggerIsPublic() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk());
    }

    // ── Notification submission requires auth ──────────────────────────────

    @Test
    @Order(9)
    @DisplayName("Notification submission is protected by API key")
    void notificationRequiresAuth() throws Exception {
        String requestBody = """
                {
                    "sapNotificationNumber": "10009999",
                    "freeTextDescription": "Test pump noise",
                    "equipmentTag": "P-1001A",
                    "equipmentClass": "PUMP_CENTRIFUGAL",
                    "sapPlant": "1000"
                }
                """;

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isUnauthorized());
    }
}
