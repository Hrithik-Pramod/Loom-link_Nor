package com.loomlink.edge.integration;

import com.loomlink.edge.domain.enums.EquipmentClass;
import com.loomlink.edge.domain.model.MaintenanceNotification;
import com.loomlink.edge.repository.SemanticCacheRepository;
import com.loomlink.edge.service.MaintenancePipelineOrchestrator;
import com.loomlink.edge.service.MaintenancePipelineOrchestrator.PipelineResult;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Integration test: Semantic Cache (promote → cache hit on repeat).
 *
 * <p>Proves that after an LLM classification is cached, identical or near-identical
 * text bypasses the LLM entirely and returns from cache. This is the key
 * performance differentiator — 50ms cache hit vs 2-8s LLM inference.</p>
 */
@SpringBootTest
@ActiveProfiles("local")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Semantic Cache Integration — Two-Tier Lookup")
class SemanticCacheIntegrationTest {

    @Autowired
    private MaintenancePipelineOrchestrator pipeline;

    @Autowired
    private SemanticCacheRepository cacheRepo;

    @MockBean
    private ChatLanguageModel chatModel;

    @BeforeEach
    void resetCache() {
        cacheRepo.deleteAll();
        reset(chatModel);
    }

    // ── First call invokes LLM, second call hits cache ─────────────────────

    @Test
    @Order(1)
    @DisplayName("Identical text hits exact-match cache on second request")
    void identicalTextHitsCache() {
        when(chatModel.generate(anyString()))
                .thenReturn("{\"failureModeCode\":\"VIB\",\"causeCode\":\"B01\",\"confidence\":0.94," +
                        "\"reasoning\":\"Grinding noise from drive end bearing\"}");

        String freeText = "Pump making loud grinding noise from drive end, vibration going up";

        // First call: LLM is invoked, result is cached
        MaintenanceNotification first = MaintenanceNotification.fromODataEvent(
                "20001001", freeText, "P-1001A", EquipmentClass.PUMP, "1000");
        PipelineResult result1 = pipeline.process(first);

        assertFalse(result1.cacheHit(), "First call should NOT be a cache hit");
        verify(chatModel, atLeastOnce()).generate(anyString());

        // Reset mock invocation counter
        clearInvocations(chatModel);

        // Second call: identical text should hit cache, LLM NOT invoked
        MaintenanceNotification second = MaintenanceNotification.fromODataEvent(
                "20001002", freeText, "P-1001A", EquipmentClass.PUMP, "1000");
        PipelineResult result2 = pipeline.process(second);

        assertTrue(result2.cacheHit(), "Second identical request MUST be a cache hit");
        assertEquals("EXACT", result2.cacheMatchType(), "Should be an exact normalized text match");
        verify(chatModel, never()).generate(anyString()); // LLM was NOT called

        // Classification should be the same
        assertEquals(result1.classification().getFailureModeCode(),
                result2.classification().getFailureModeCode(),
                "Cached classification must match original");
    }

    // ── Norwegian text also caches correctly ───────────────────────────────

    @Test
    @Order(2)
    @DisplayName("Norwegian text caches and hits correctly with special characters")
    void norwegianTextCachesCorrectly() {
        when(chatModel.generate(anyString()))
                .thenReturn("{\"failureModeCode\":\"ELP\",\"causeCode\":\"S02\",\"confidence\":0.91," +
                        "\"reasoning\":\"Lekkasje fra tetning = external seal leak\"}");

        String norwegianText = "Lekkasje fra mekanisk tetning, drypp på skid";

        // First call: LLM invoked
        MaintenanceNotification first = MaintenanceNotification.fromODataEvent(
                "20002001", norwegianText, "P-1003B", EquipmentClass.PUMP, "1000");
        pipeline.process(first);

        clearInvocations(chatModel);

        // Second call with identical Norwegian text: should cache hit
        MaintenanceNotification second = MaintenanceNotification.fromODataEvent(
                "20002002", norwegianText, "P-1003B", EquipmentClass.PUMP, "1000");
        PipelineResult result2 = pipeline.process(second);

        assertTrue(result2.cacheHit(), "Norwegian text must cache and hit correctly");
        assertEquals("ELP", result2.classification().getFailureModeCode().name());
        verify(chatModel, never()).generate(anyString());
    }

    // ── Rejected classifications are also cached ───────────────────────────

    @Test
    @Order(3)
    @DisplayName("Low-confidence rejected classifications are cached (saves re-inference)")
    void rejectedClassificationsAreCached() {
        when(chatModel.generate(anyString()))
                .thenReturn("{\"failureModeCode\":\"VIB\",\"causeCode\":\"U01\",\"confidence\":0.35," +
                        "\"reasoning\":\"Vague description\"}");

        String vagueText = "Something wrong with pump, checked it, not sure";

        // First call: LLM invoked, result cached even though confidence is low
        MaintenanceNotification first = MaintenanceNotification.fromODataEvent(
                "20003001", vagueText, "P-1002A", EquipmentClass.PUMP, "1000");
        PipelineResult result1 = pipeline.process(first);

        assertFalse(result1.gateResult().isPassed(), "Low confidence should be rejected");
        clearInvocations(chatModel);

        // Second call: should hit cache, NOT re-invoke LLM
        MaintenanceNotification second = MaintenanceNotification.fromODataEvent(
                "20003002", vagueText, "P-1002A", EquipmentClass.PUMP, "1000");
        PipelineResult result2 = pipeline.process(second);

        assertTrue(result2.cacheHit(), "Rejected results must also be cached");
        assertFalse(result2.gateResult().isPassed(), "Cache hit should still be rejected by gate");
        verify(chatModel, never()).generate(anyString());
    }

    // ── Cache entry count grows ────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("Each unique text creates one cache entry")
    void cacheGrows() {
        when(chatModel.generate(anyString()))
                .thenReturn("{\"failureModeCode\":\"OHE\",\"causeCode\":\"L01\",\"confidence\":0.92," +
                        "\"reasoning\":\"Temperature alarm\"}");

        long before = cacheRepo.count();

        pipeline.process(MaintenanceNotification.fromODataEvent(
                "20004001", "Oil temp alarm on compressor 92C", "C-2001A",
                EquipmentClass.COMPRESSOR, "1000"));

        pipeline.process(MaintenanceNotification.fromODataEvent(
                "20004002", "Bearing temperature high on pump", "P-1001A",
                EquipmentClass.PUMP, "1000"));

        long after = cacheRepo.count();
        assertEquals(before + 2, after, "Two unique texts should create two cache entries");
    }
}
