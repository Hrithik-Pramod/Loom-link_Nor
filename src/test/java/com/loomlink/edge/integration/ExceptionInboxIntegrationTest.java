package com.loomlink.edge.integration;

import com.loomlink.edge.domain.enums.EquipmentClass;
import com.loomlink.edge.domain.model.MaintenanceNotification;
import com.loomlink.edge.repository.AuditLogRepository;
import com.loomlink.edge.repository.ExceptionInboxRepository;
import com.loomlink.edge.service.MaintenancePipelineOrchestrator;
import com.loomlink.edge.service.RbacService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test: Exception Inbox lifecycle (Reject → Human Review → Audit Log).
 *
 * <p>Verifies that rejected classifications flow to the Exception Inbox,
 * Senior Engineers can approve/reclassify, RBAC blocks unauthorized users,
 * and audit log entries are created for every human decision.</p>
 */
@SpringBootTest
@ActiveProfiles("local")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Exception Inbox Integration — Human Review Lifecycle")
class ExceptionInboxIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MaintenancePipelineOrchestrator pipeline;

    @Autowired
    private ExceptionInboxRepository exceptionInboxRepo;

    @Autowired
    private AuditLogRepository auditLogRepo;

    @MockBean
    private ChatLanguageModel chatModel;

    @BeforeEach
    void setUp() {
        auditLogRepo.deleteAll();
        exceptionInboxRepo.deleteAll();

        // Mock LLM: low confidence → rejection → Exception Inbox
        org.mockito.Mockito.when(chatModel.generate(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("{\"failureModeCode\":\"NOI\",\"causeCode\":\"B01\",\"confidence\":0.65," +
                        "\"reasoning\":\"Noise report without specific measurements\"}");
    }

    private void createRejectedNotification(String sapNo) {
        MaintenanceNotification notification = MaintenanceNotification.fromODataEvent(
                sapNo,
                "Uvanlig støy fra pumpe, hører banking fra drivende",
                "P-1002A",
                EquipmentClass.PUMP,
                "1000");
        pipeline.process(notification);
    }

    // ── Rejected classification lands in Exception Inbox ───────────────────

    @Test
    @Order(1)
    @DisplayName("Rejected classification appears in Exception Inbox via API")
    void rejectedClassificationAppearsInInbox() throws Exception {
        createRejectedNotification("10005001");

        mockMvc.perform(get("/api/v1/exceptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sapNotificationNumber").value("10005001"))
                .andExpect(jsonPath("$[0].reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$[0].confidenceScore").value(0.65));
    }

    // ── Senior Engineer can approve ────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("Senior Engineer can approve a rejected classification")
    void seniorEngineerCanApprove() throws Exception {
        createRejectedNotification("10005002");

        var items = exceptionInboxRepo.findPendingByPriority();
        assertFalse(items.isEmpty());
        String itemId = items.get(0).getId().toString();

        long auditCountBefore = auditLogRepo.count();

        mockMvc.perform(put("/api/v1/exceptions/" + itemId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewedBy\":\"Lars Hansen\",\"notes\":\"Confirmed noise from bearing\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.reviewedBy").value("Lars Hansen"));

        // Audit log must have a new entry for the approval
        long auditCountAfter = auditLogRepo.count();
        assertTrue(auditCountAfter > auditCountBefore,
                "Approving an exception must create a new audit log entry");
    }

    // ── Senior Engineer can reclassify ─────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("Senior Engineer can reclassify with correct failure code")
    void seniorEngineerCanReclassify() throws Exception {
        createRejectedNotification("10005003");

        var items = exceptionInboxRepo.findPendingByPriority();
        String itemId = items.get(0).getId().toString();

        mockMvc.perform(put("/api/v1/exceptions/" + itemId + "/reclassify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewedBy\":\"Ingrid Johansen\",\"failureModeCode\":\"VIB\",\"notes\":\"Reclassified to vibration\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("RECLASSIFIED"))
                .andExpect(jsonPath("$.manualFailureCode").value("VIB"));
    }

    // ── RBAC blocks unauthorized Operator ──────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("Operator is blocked from approving exceptions (RBAC)")
    void operatorCannotApprove() throws Exception {
        createRejectedNotification("10005004");

        var items = exceptionInboxRepo.findPendingByPriority();
        String itemId = items.get(0).getId().toString();

        mockMvc.perform(put("/api/v1/exceptions/" + itemId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewedBy\":\"Operator Sven\",\"notes\":\"Trying to approve\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS DENIED"));
    }

    // ── Unknown user defaults to Operator (cannot approve) ─────────────────

    @Test
    @Order(5)
    @DisplayName("Unknown user defaults to OPERATOR and is blocked from approving")
    void unknownUserBlockedFromApprove() throws Exception {
        createRejectedNotification("10005005");

        var items = exceptionInboxRepo.findPendingByPriority();
        String itemId = items.get(0).getId().toString();

        mockMvc.perform(put("/api/v1/exceptions/" + itemId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewedBy\":\"Random Person\",\"notes\":\"Should be denied\"}"))
                .andExpect(status().isForbidden());
    }

    // ── Inbox statistics reflect actions ───────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("Exception Inbox statistics are accurate")
    void inboxStatsAreAccurate() throws Exception {
        createRejectedNotification("10005010");
        createRejectedNotification("10005011");

        // Approve the first one
        var items = exceptionInboxRepo.findPendingByPriority();
        String firstId = items.get(0).getId().toString();

        mockMvc.perform(put("/api/v1/exceptions/" + firstId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewedBy\":\"Lars Hansen\",\"notes\":\"OK\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/exceptions/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.approved").value(1));
    }
}
