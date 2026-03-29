package com.loomlink.edge.integration;

import com.loomlink.edge.domain.enums.EquipmentClass;
import com.loomlink.edge.domain.model.MaintenanceNotification;
import com.loomlink.edge.repository.AuditLogRepository;
import com.loomlink.edge.repository.ExceptionInboxRepository;
import com.loomlink.edge.service.MaintenancePipelineOrchestrator;
import com.loomlink.edge.service.MaintenancePipelineOrchestrator.PipelineResult;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: Full Pipeline (Text → LLM → Gate → SAP → Audit).
 *
 * <p>Proves that the Loom Link pipeline processes a notification end-to-end
 * with real Spring context, real H2 database, and a mocked LLM (we cannot
 * call Ollama from CI). The LLM mock returns valid ISO 14224 JSON.</p>
 */
@SpringBootTest
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Pipeline Integration — End-to-End Classification")
class PipelineIntegrationTest {

    @Autowired
    private MaintenancePipelineOrchestrator pipeline;

    @Autowired
    private AuditLogRepository auditLogRepo;

    @Autowired
    private ExceptionInboxRepository exceptionInboxRepo;

    @MockBean
    private ChatLanguageModel chatModel;

    @BeforeEach
    void resetDatabase() {
        auditLogRepo.deleteAll();
        exceptionInboxRepo.deleteAll();
    }

    // ── High-confidence classification → GATE PASS → SAP write-back ────────

    @Test
    @Order(1)
    @DisplayName("High-confidence English text passes gate and creates audit log")
    void highConfidenceEnglishPassesGate() {
        // Mock LLM: return high-confidence VIB classification
        org.mockito.Mockito.when(chatModel.generate(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("{\"failureModeCode\":\"VIB\",\"causeCode\":\"B01\",\"confidence\":0.94," +
                        "\"reasoning\":\"Grinding noise + rising vibration + elevated bearing temperature\"}");

        MaintenanceNotification notification = MaintenanceNotification.fromODataEvent(
                "10004567",
                "Pump making loud grinding noise from drive end, vibration going up, bearing temp 82C",
                "P-1001A",
                EquipmentClass.PUMP,
                "1000");

        PipelineResult result = pipeline.process(notification);

        // Gate should pass at 0.94 (threshold 0.85)
        assertTrue(result.gateResult().isPassed(), "Gate should pass at 0.94 confidence");
        assertEquals("VIB", result.classification().getFailureModeCode().name());
        assertEquals("B01", result.classification().getCauseCode());
        assertEquals(0.94, result.classification().getConfidence(), 0.01);

        // Audit log should exist
        var auditLogs = auditLogRepo.findBySapNotificationNumber("10004567");
        assertFalse(auditLogs.isEmpty(), "Audit log entry must be created for every pipeline execution");
        assertTrue(auditLogs.get(0).isGatePassed(), "Audit log should record gate PASS");

        // Exception inbox should be empty (gate passed)
        assertEquals(0, exceptionInboxRepo.countPending(),
                "No exception inbox items for passed classifications");
    }

    // ── Low-confidence classification → GATE REJECT → Exception Inbox ──────

    @Test
    @Order(2)
    @DisplayName("Low-confidence text is rejected and routed to Exception Inbox")
    void lowConfidenceRejectsToExceptionInbox() {
        org.mockito.Mockito.when(chatModel.generate(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("{\"failureModeCode\":\"VIB\",\"causeCode\":\"U01\",\"confidence\":0.35," +
                        "\"reasoning\":\"Vague description, no specific symptoms\"}");

        MaintenanceNotification notification = MaintenanceNotification.fromODataEvent(
                "10004570",
                "Something wrong with pump, not sure what",
                "P-1002A",
                EquipmentClass.PUMP,
                "1000");

        PipelineResult result = pipeline.process(notification);

        // Gate should reject at 0.35
        assertFalse(result.gateResult().isPassed(), "Gate should reject at 0.35 confidence");

        // Exception inbox should have 1 pending item
        assertEquals(1, exceptionInboxRepo.countPending(),
                "Rejected classification must create an Exception Inbox item");

        // Audit log should record the rejection
        var auditLogs = auditLogRepo.findBySapNotificationNumber("10004570");
        assertFalse(auditLogs.isEmpty(), "Audit log entry must be created even for rejections");
        assertFalse(auditLogs.get(0).isGatePassed(), "Audit log should record gate REJECT");
    }

    // ── Norwegian text classification ──────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("Norwegian free-text is classified correctly through the pipeline")
    void norwegianTextClassifiedCorrectly() {
        org.mockito.Mockito.when(chatModel.generate(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("{\"failureModeCode\":\"ELP\",\"causeCode\":\"S02\",\"confidence\":0.91," +
                        "\"reasoning\":\"Norwegian: lekkasje fra mekanisk tetning = external seal leak\"}");

        MaintenanceNotification notification = MaintenanceNotification.fromODataEvent(
                "10004575",
                "Lekkasje fra mekanisk tetning, drypp på skid, verre enn i går",
                "P-1003B",
                EquipmentClass.PUMP,
                "1000");

        PipelineResult result = pipeline.process(notification);

        assertTrue(result.gateResult().isPassed(), "Norwegian classification should pass gate");
        assertEquals("ELP", result.classification().getFailureModeCode().name());
        assertEquals(0.91, result.classification().getConfidence(), 0.01);
    }

    // ── UNK code always rejected ───────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("UNK failure code is always rejected regardless of confidence")
    void unkAlwaysRejected() {
        org.mockito.Mockito.when(chatModel.generate(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("{\"failureModeCode\":\"UNK\",\"causeCode\":\"U01\",\"confidence\":0.90," +
                        "\"reasoning\":\"Cannot determine failure mode\"}");

        MaintenanceNotification notification = MaintenanceNotification.fromODataEvent(
                "10004580",
                "Unknown issue on equipment",
                "P-1001A",
                EquipmentClass.PUMP,
                "1000");

        PipelineResult result = pipeline.process(notification);

        // UNK should ALWAYS be rejected by the Reflector Gate
        assertFalse(result.gateResult().isPassed(),
                "UNK classification must be rejected regardless of confidence score");
        assertEquals(1, exceptionInboxRepo.countPending());
    }

    // ── Pipeline latency is tracked ────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("Pipeline tracks total latency in milliseconds")
    void pipelineTracksLatency() {
        org.mockito.Mockito.when(chatModel.generate(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("{\"failureModeCode\":\"OHE\",\"causeCode\":\"L01\",\"confidence\":0.92," +
                        "\"reasoning\":\"Temperature alarm with objective measurement\"}");

        MaintenanceNotification notification = MaintenanceNotification.fromODataEvent(
                "10004585",
                "Oil temp alarm on compressor, reading 92C, normally 65C",
                "C-2001A",
                EquipmentClass.COMPRESSOR,
                "1000");

        PipelineResult result = pipeline.process(notification);

        assertTrue(result.totalLatencyMs() >= 0, "Pipeline latency must be tracked");
        assertTrue(result.totalLatencyMs() < 30000, "Pipeline should complete within 30s (mocked LLM)");
    }
}
