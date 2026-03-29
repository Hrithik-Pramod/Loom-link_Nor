package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.FailureModeCode;
import com.loomlink.edge.domain.model.MaintenanceNotification;
import com.loomlink.edge.domain.model.SemanticClassification;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Stage 2 of the Loom Link pipeline: Semantic Analysis.
 *
 * Sends the technician's free-text description to the LLM running on the
 * HM90 Sovereign Node (via Ollama) and parses the response into a structured
 * ISO 14224 failure mode classification.
 *
 * <p>Enterprise Feature: Strict JSON Schema Validation with 1-retry fallback.
 * If the LLM hallucinates the formatting on the first attempt, we retry once
 * with an explicit correction prompt. If the second attempt also fails,
 * we return a low-confidence UNK classification for the Reflector Gate
 * to route to the Exception Inbox.</p>
 *
 * <p>All inference happens locally on the HM90 node. Zero cloud calls.
 * Zero data egress. The intelligence stays where the assets are.</p>
 */
@Service
public class SemanticAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(SemanticAnalysisService.class);

    private final ChatLanguageModel chatModel;
    private final DemoModeService demoMode;
    private final LlmResponseValidator validator;
    private final String modelId;

    private static final String SYSTEM_PROMPT = """
            You are an ISO 14224 failure classifier for North Sea oil & gas equipment.
            You serve Equinor and Aker BP platforms in the Norwegian Continental Shelf.

            LANGUAGE: Technicians write in BOTH Norwegian (Bokmål) and English.
            You MUST understand and classify descriptions in either language equally well.
            Always respond in English JSON regardless of input language.

            TASK: Read the technician's description and return ONE JSON object.

            FAILURE CODES (pick exactly one):
            VIB = Vibration (grinding, shaking, bearing noise, imbalance / vibrasjon, slitasjelyd, lager)
            OHE = Overheating (high temp, thermal alarm, hot bearing / overoppheting, høy temperatur, varm)
            ELP = External leakage - process (drip, seal leak, fluid pool / lekkasje, drypp, tetning)
            ELU = External leakage - utility (cooling water, hydraulic oil leak / kjølevann, hydraulikk)
            INL = Internal leakage (valve passing, seat leak / internlekkasje, ventil passerer)
            BRD = Breakdown (seized, tripped, stopped, won't start / brudd, stoppet, startar ikkje)
            NOI = Noise (unusual sound, rattling, knocking / støy, uvanlig lyd, banking, rasling)
            PLU = Plugged/choked (blocked, fouled, restricted flow / tett, blokkert, tilstoppet)
            ERO = Erosion (material loss, wall thinning, sand damage / erosjon, veggtynnelse)
            FTS = Failure to start (won't start on demand / startar ikkje, starter ikke)
            FTF = Failure to function (not performing / fungerer ikke, svikter)
            HIO = High output (over-pressure, over-speed / høyt trykk, for høy)
            LOO = Low output (under-performing, low pressure / lavt trykk, lav ytelse)
            AIR = Abnormal instrument reading (sensor fault / feil instrumentavlesning)
            STD = Structural deficiency (crack, corrosion / strukturfeil, sprekk, korrosjon)
            PDE = Parameter deviation (out of spec, drifting / parameteravvik, utenfor spesifikasjon)
            SER = Minor in-service problems (minor adjustment / mindre justering)
            UST = Spurious stop (unexpected trip / uventet stopp, nødstopp)
            UNK = Unknown (cannot determine / ukjent, kan ikke bestemme)

            CONFIDENCE RULES — follow these strictly:
            - 0.90-0.97: Description has 2+ specific symptoms that clearly map to one code
            - 0.85-0.90: Description has 1 clear specific symptom with supporting context
            - 0.70-0.84: Description points toward a code but lacks specific measurements
            - 0.30-0.69: Description is vague, ambiguous, or could map to multiple codes
            - Below 0.30: Almost no useful information

            EXAMPLES (English):
            Input: "Pump making loud grinding noise from drive end, vibration going up, bearing temp 82C"
            Output: {"failureModeCode":"VIB","causeCode":"B01","confidence":0.94,"reasoning":"Grinding noise + rising vibration + elevated bearing temperature = three convergent bearing wear indicators"}

            Input: "Something wrong with pump, checked twice, not sure"
            Output: {"failureModeCode":"VIB","causeCode":"U01","confidence":0.35,"reasoning":"Vague description with no specific symptoms or measurements. Cannot confidently classify."}

            Input: "Oil temp alarm on compressor, reading 92C, normally 65C, rising for 6 hours"
            Output: {"failureModeCode":"OHE","causeCode":"L01","confidence":0.92,"reasoning":"Objective temperature measurement 27C above baseline with progressive trend confirms overheating"}

            EXAMPLES (Norwegian — respond in English JSON):
            Input: "Pumpe lager høy skrapelyd fra drivende, vibrasjon øker, lagertemp 84C"
            Output: {"failureModeCode":"VIB","causeCode":"B01","confidence":0.94,"reasoning":"Norwegian: 'skrapelyd' (grinding noise) + 'vibrasjon øker' (rising vibration) + elevated bearing temp at 84C = three convergent bearing wear indicators per ISO 14224 Table B.6"}

            Input: "Lekkasje fra mekanisk tetning, drypp på skid, verre enn i går"
            Output: {"failureModeCode":"ELP","causeCode":"S02","confidence":0.91,"reasoning":"Norwegian: 'lekkasje fra mekanisk tetning' (leak from mechanical seal) with visible drip and progressive worsening = ELP with seal failure root cause"}

            Input: "Noe galt med pumpe, vet ikke hva det er"
            Output: {"failureModeCode":"UNK","causeCode":"U01","confidence":0.25,"reasoning":"Norwegian: 'noe galt' (something wrong) with no specific symptoms, measurements, or failure indicators. Too vague to classify."}

            RESPOND WITH ONLY THE JSON OBJECT. No other text.
            """;

    /** Retry prompt — sent when the first attempt fails schema validation. */
    private static final String RETRY_PROMPT = """
            Your previous response was not valid JSON or was missing required fields.
            You MUST respond with ONLY a JSON object in this exact format:
            {"failureModeCode":"CODE","causeCode":"XX","confidence":0.00,"reasoning":"Your reasoning"}

            No markdown, no code blocks, no explanation — ONLY the JSON object.
            Try again for the same equipment description.
            """;

    public SemanticAnalysisService(
            ChatLanguageModel chatModel,
            DemoModeService demoMode,
            LlmResponseValidator validator,
            @Value("${loomlink.llm.model-id:mistral:7b}") String modelId) {
        this.chatModel = chatModel;
        this.demoMode = demoMode;
        this.validator = validator;
        this.modelId = modelId;
    }

    /**
     * Classify a maintenance notification's free-text into an ISO 14224 failure mode.
     * Uses strict JSON schema validation with 1-retry fallback.
     *
     * @param notification the intercepted SAP notification with raw free-text
     * @return a ClassificationResult containing the classification and metadata
     */
    public ClassificationResult classify(MaintenanceNotification notification) {
        log.info("Semantic analysis started for notification: {}", notification.getSapNotificationNumber());
        notification.markAnalyzing();

        // Demo Mode: Use deterministic response if a matching scenario exists
        var demoResult = demoMode.classify(notification);
        if (demoResult.isPresent()) {
            log.info("Demo mode provided classification for {}", notification.getSapNotificationNumber());
            return new ClassificationResult(demoResult.get(), true, 1);
        }

        String userPrompt = buildUserPrompt(notification);
        long startTime = System.currentTimeMillis();

        // ── Attempt 1: Primary LLM call ────────────────────────────────
        String rawResponse;
        try {
            rawResponse = chatModel.generate(SYSTEM_PROMPT + "\n\nUser: " + userPrompt);
        } catch (Exception e) {
            log.error("LLM inference failed for {}: {}", notification.getSapNotificationNumber(), e.getMessage());
            notification.markUnclassifiable();
            return new ClassificationResult(
                    SemanticClassification.create(
                            notification, FailureModeCode.UNK, null,
                            0.0, "LLM inference failed: " + e.getMessage(),
                            null, modelId, System.currentTimeMillis() - startTime),
                    false, 1);
        }

        long attempt1Latency = System.currentTimeMillis() - startTime;
        log.info("LLM attempt 1 completed in {}ms for {}", attempt1Latency, notification.getSapNotificationNumber());

        // Validate attempt 1
        LlmResponseValidator.ValidationResult validation = validator.validate(rawResponse);

        if (validation.valid()) {
            log.info("Schema validation passed on first attempt for {}", notification.getSapNotificationNumber());
            notification.markClassified();
            SemanticClassification classification = SemanticClassification.create(
                    notification,
                    validation.failureModeCode(),
                    validation.causeCode(),
                    validation.confidence(),
                    validation.reasoning(),
                    validation.cleanJson(),
                    modelId,
                    attempt1Latency);
            return new ClassificationResult(classification, true, 1);
        }

        // ── Attempt 2: Retry with explicit correction prompt ───────────
        log.warn("Schema validation failed on attempt 1 for {}. Errors: {}. Retrying...",
                notification.getSapNotificationNumber(), validation.errors());

        String retryResponse;
        try {
            retryResponse = chatModel.generate(
                    SYSTEM_PROMPT + "\n\nUser: " + userPrompt + "\n\n" + RETRY_PROMPT);
        } catch (Exception e) {
            log.error("LLM retry failed for {}: {}", notification.getSapNotificationNumber(), e.getMessage());
            notification.markUnclassifiable();
            long totalLatency = System.currentTimeMillis() - startTime;
            return new ClassificationResult(
                    SemanticClassification.create(
                            notification, FailureModeCode.UNK, null,
                            0.0, "LLM retry failed: " + e.getMessage(),
                            rawResponse, modelId, totalLatency),
                    false, 2);
        }

        long totalLatency = System.currentTimeMillis() - startTime;
        log.info("LLM attempt 2 completed, total time {}ms for {}", totalLatency, notification.getSapNotificationNumber());

        // Validate attempt 2
        LlmResponseValidator.ValidationResult retryValidation = validator.validate(retryResponse);

        if (retryValidation.valid()) {
            log.info("Schema validation passed on retry for {}", notification.getSapNotificationNumber());
            notification.markClassified();
            SemanticClassification classification = SemanticClassification.create(
                    notification,
                    retryValidation.failureModeCode(),
                    retryValidation.causeCode(),
                    retryValidation.confidence(),
                    retryValidation.reasoning(),
                    retryValidation.cleanJson(),
                    modelId,
                    totalLatency);
            return new ClassificationResult(classification, false, 2);
        }

        // ── Both attempts failed — route to Exception Inbox ────────────
        log.error("Schema validation failed on both attempts for {}. Attempt 1 errors: {}. Attempt 2 errors: {}",
                notification.getSapNotificationNumber(), validation.errors(), retryValidation.errors());

        notification.markUnclassifiable();
        String combinedErrors = "Attempt 1: " + validation.errors() + " | Attempt 2: " + retryValidation.errors();
        SemanticClassification fallback = SemanticClassification.create(
                notification,
                FailureModeCode.UNK, null,
                0.0,
                "Schema validation failed after 2 attempts. " + combinedErrors,
                retryResponse,
                modelId,
                totalLatency);

        return new ClassificationResult(fallback, false, 2);
    }

    private String buildUserPrompt(MaintenanceNotification notification) {
        return String.format("""
                Equipment: %s (%s)
                Plant: %s
                Technician's description: "%s"

                Classify this failure according to ISO 14224.
                """,
                notification.getEquipmentTag(),
                notification.getEquipmentClass() != null
                    ? notification.getEquipmentClass().getDescription() : "Unknown class",
                notification.getSapPlant(),
                notification.getFreeTextDescription());
    }

    /**
     * Classification result with metadata for audit logging.
     *
     * @param classification the parsed classification
     * @param jsonValidFirstAttempt true if the LLM output was valid JSON on the first try
     * @param llmAttempts how many LLM calls were made (1 or 2)
     */
    public record ClassificationResult(
            SemanticClassification classification,
            boolean jsonValidFirstAttempt,
            int llmAttempts
    ) {}
}
