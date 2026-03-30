package com.loomlink.edge.service;

import com.loomlink.edge.domain.enums.EmissionClassification;
import com.loomlink.edge.domain.model.EmissionEvent;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Stage 2 of the Loom Link pipeline for Challenge 02: Semantic Analysis of Emission Events.
 *
 * <p>Receives an enriched {@link EmissionEvent} from the ingestion pipeline and sends
 * the complete contextual data to the LLM running on the HM90 Sovereign Node (via Ollama)
 * for semantic classification into one of six emission categories.</p>
 *
 * <p>Classification categories:</p>
 * <ul>
 *   <li><strong>FUGITIVE_EMISSION</strong>: Real unintended gas leak requiring immediate response</li>
 *   <li><strong>PLANNED_VENTING</strong>: Known, authorized process emission during scheduled operations</li>
 *   <li><strong>MAINTENANCE_ACTIVITY</strong>: Emission during authorized maintenance work</li>
 *   <li><strong>SENSOR_ARTIFACT</strong>: False positive from environmental or sensor drift</li>
 *   <li><strong>NORMAL_PROCESS</strong>: Within expected operational baseline for this location</li>
 *   <li><strong>UNKNOWN</strong>: Cannot determine with available context</li>
 * </ul>
 *
 * <p><strong>Multi-Modal Fusion:</strong> The service leverages multi-modal sensor data
 * (thermal delta, acoustic leak signature) in addition to the primary gas sensor reading
 * to increase classification confidence. Corroborating sensor modalities strengthen the
 * emission classification.</p>
 *
 * <p><strong>SAP Integration:</strong> Active work orders, maintenance state, turnaround state,
 * and equipment history from SAP provide critical operational context. A planned maintenance
 * window, for example, can shift an emission from FUGITIVE_EMISSION to MAINTENANCE_ACTIVITY.</p>
 *
 * <p><strong>Historical Trending:</strong> The service compares the current sensor reading
 * against the historical baseline for this location and the 7-day detection history to identify
 * escalating patterns, indicating a developing leak.</p>
 *
 * <p><strong>Environmental Correction:</strong> Wind speed and direction are included to model
 * gas dispersion and improve the plausibility assessment.</p>
 *
 * <p><strong>Enterprise Feature: Strict JSON Schema Validation with 1-retry fallback.</strong>
 * If the LLM hallucinates the formatting on the first attempt, we retry once with an explicit
 * correction prompt. If the second attempt also fails, we return a low-confidence UNKNOWN
 * classification for the Reflector Gate to route to the Exception Inbox.</p>
 *
 * <p><strong>Quantification (EU 2024/1787):</strong> For emissions classified as FUGITIVE_EMISSION,
 * the service estimates a leak rate in kg/hr using a concentration-based formula with distance
 * and equipment type factors.</p>
 *
 * <p>All inference happens locally on the HM90 node. Zero cloud calls. Zero data egress.
 * The intelligence stays where the assets are.</p>
 *
 * @see EmissionEvent
 * @see EmissionClassification
 */
@Service
public class EmissionAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(EmissionAnalysisService.class);

    private final ChatLanguageModel chatModel;
    private final DemoModeService demoMode;
    private final LlmResponseValidator validator;
    private final String modelId;

    private static final String SYSTEM_PROMPT = """
            You are an emission event classifier for North Sea oil & gas offshore platforms.
            You serve Equinor and Aker BP platforms in the Norwegian Continental Shelf.

            LANGUAGE: Sensor events and operational data are logged in BOTH Norwegian (Bokmål) and English.
            You MUST understand and classify descriptions in either language equally well.
            Always respond in English JSON regardless of input language.

            TASK: Read the enriched sensor event data and return ONE JSON object classifying it into exactly one of these categories.

            EMISSION CLASSIFICATION CODES (pick exactly one):
            FUGITIVE_EMISSION = Real unintended gas leak requiring immediate response and regulatory notification
            PLANNED_VENTING = Known, authorized process emission during scheduled operations or blowdown events
            MAINTENANCE_ACTIVITY = Emission during authorized maintenance work, turnaround, or commissioning
            SENSOR_ARTIFACT = False positive from environmental conditions, sensor drift, or calibration error
            NORMAL_PROCESS = Within expected operational baseline for this location and conditions
            UNKNOWN = Cannot determine classification with available context

            CONFIDENCE RULES — follow these strictly:
            - 0.90-0.99: Classification is certain. 2+ independent indicators (sensors, SAP context, trend) converge.
            - 0.80-0.89: Classification is high confidence. Clear pattern in multi-modal data or strong SAP context.
            - 0.70-0.79: Classification is moderate. Primary sensor clear but limited corroborating data.
            - 0.50-0.69: Classification is low confidence. Signal is present but ambiguous or conflicting context.
            - Below 0.50: Classification is very uncertain. Recommend UNKNOWN or additional investigation.

            CLASSIFICATION DECISION TREE:

            1. CHECK SAP CONTEXT FIRST:
               - If sapMaintenanceActive OR sapTurnaroundActive: likely MAINTENANCE_ACTIVITY or PLANNED_VENTING
               - If sapActiveWorkOrders contains commissioning/startup/decommissioning: likely PLANNED_VENTING
               - If no active work orders and equipment is in normal operation: proceed to sensor analysis

            2. ANALYZE MULTI-MODAL SENSOR FUSION:
               - Primary sensor reading (ppm, thermal delta, acoustic):
                 * CH4/gas > 2x historical baseline AND thermal delta present AND acoustic leak signature: FUGITIVE_EMISSION (0.92+)
                 * Thermal delta > +15°C above ambient with acoustic hiss: FUGITIVE_EMISSION (0.88+)
                 * Elevated reading but thermal/acoustic normal: SENSOR_ARTIFACT or NORMAL_PROCESS
               - Corroborating sensors (multiple modalities):
                 * 3+ sensors agree: confidence +0.10
                 * 2 sensors agree: confidence +0.05
                 * Single sensor only: confidence baseline

            3. APPLY ENVIRONMENTAL CORRECTION:
               - Wind speed > 15 km/h can disperse gases: lower confidence in leak size estimates
               - Wind direction away from facilities: gas may not reach sensors
               - Temperature extremes affect sensor calibration

            4. EVALUATE TREND DIRECTION:
               - ESCALATING trend in last 7 days: supports FUGITIVE_EMISSION classification
               - STABLE trend: could be NORMAL_PROCESS or sensor artifact
               - DECLINING trend: likely transient or already-mitigated
               - FIRST_DETECTION: neutral, use other signals

            5. HISTORICAL CONTEXT:
               - If reading >> historical baseline: more likely real emission
               - If reading ≈ baseline: likely NORMAL_PROCESS
               - If days since last maintenance >> threshold: equipment may be degraded

            EXAMPLES (English):

            Example 1: Real Fugitive Emission
            Input:
            Equipment: FLG-4401, Location: Module B Deck 2
            Sensor: CH4 concentration 85 ppm (baseline 5 ppm)
            Thermal: +22°C above ambient (98°C vs 76°C)
            Acoustic: 112 dB with high-frequency hiss signature detected
            Wind: 8 km/h stable
            SAP Context: No active work orders, normal operation, last maintenance 45 days ago
            Trend: ESCALATING (5 detections in 7 days, readings climbing from 25 ppm to 85 ppm)
            Output:
            {
              "classification": "FUGITIVE_EMISSION",
              "confidence": 0.94,
              "reasoning": "Methane concentration 17x baseline, thermal signature matches equipment hot-spot failure, acoustic high-frequency hiss is classic leak indicator, escalating 7-day trend confirms developing leak. Three independent sensor modalities convergent. No authorized work orders explain the event. High-confidence real emission.",
              "estimatedLeakRateKgHr": 2.8
            }

            Example 2: Planned Venting During Turnaround
            Input:
            Equipment: VLV-2203, Location: Module C Deck 3
            Sensor: CH4 concentration 320 ppm (baseline 0-10 ppm)
            Thermal: Normal, 65°C
            Acoustic: 95 dB generic background noise, no leak signature
            SAP Context: sapTurnaroundActive = true, active work order "Q1 Turnaround Maintenance", equipment shutdown scheduled
            Trend: FIRST_DETECTION
            Output:
            {
              "classification": "PLANNED_VENTING",
              "confidence": 0.91,
              "reasoning": "Equipment Q1 turnaround in progress per SAP work order. High gas reading expected during controlled depressurization and purging. No thermal anomaly, no acoustic leak signature, no escalating trend. Reading consistent with routine venting operations.",
              "estimatedLeakRateKgHr": 0.0
            }

            Example 3: Sensor Artifact / Environmental Interference
            Input:
            Equipment: PMP-1105, Location: Module A Deck 1
            Sensor: CH4 concentration 18 ppm (baseline 8 ppm)
            Thermal: Normal, 52°C
            Acoustic: No unusual signature
            Environmental: Wind 28 km/h, direction 180° (away from facility)
            SAP Context: Normal operation, last maintenance 3 days ago
            Trend: STABLE (1 detection in 7 days, one-off spike)
            Output:
            {
              "classification": "SENSOR_ARTIFACT",
              "confidence": 0.78,
              "reasoning": "Single elevated reading 2.25x baseline, but no thermal or acoustic corroboration. High wind speed (28 km/h) and away direction may cause sensor drift or environmental interference. Equipment recently maintained. Single isolated event suggests transient artifact rather than equipment failure.",
              "estimatedLeakRateKgHr": 0.0
            }

            Example 4: Normal Process Variation
            Input:
            Equipment: CMP-3302, Location: Module D Deck 2
            Sensor: CH4 concentration 9 ppm (baseline 8 ppm)
            Thermal: Normal
            Acoustic: Normal background
            SAP Context: Normal operation, no work orders
            Trend: STABLE (readings consistently 7-11 ppm over 7 days)
            Output:
            {
              "classification": "NORMAL_PROCESS",
              "confidence": 0.88,
              "reasoning": "Reading within 1.1x baseline variation, all sensors nominal, stable 7-day pattern within expected operational envelope. No anomalies detected. Natural process variation.",
              "estimatedLeakRateKgHr": 0.0
            }

            Example 5: Unknown / Insufficient Context
            Input:
            Equipment: VLV-9999, Location: Unknown
            Sensor: CH4 concentration 15 ppm
            SAP Context: No data available, equipment tag not found in SAP
            Trend: Unknown
            Output:
            {
              "classification": "UNKNOWN",
              "confidence": 0.35,
              "reasoning": "Equipment not found in SAP system. No historical baseline available. Cannot compare reading to normal operational envelope. Insufficient context to classify reliably. Recommend manual investigation and SAP record reconciliation.",
              "estimatedLeakRateKgHr": 0.0
            }

            EXAMPLES (Norwegian — respond in English JSON):

            Example 1: Vedvarende lekkasje (Norwegian text describing a leak)
            Input:
            Equipment: FLG-4401
            Sensor: Metangass 75 ppm (baseline 5 ppm)
            Thermal: +19°C over ambient
            Acoustic: Høyfrekvent hvislyd oppdaget (high-frequency hiss detected)
            Output:
            {
              "classification": "FUGITIVE_EMISSION",
              "confidence": 0.92,
              "reasoning": "Norwegian input: 'metangass' significantly above 'baseline', 'høyfrekvent hvislyd' (high-frequency hiss) confirms leak, thermal delta indicates hot equipment fault. Multi-modal convergence indicates real leak event requiring immediate response.",
              "estimatedLeakRateKgHr": 2.3
            }

            RESPOND WITH ONLY THE JSON OBJECT. No markdown, no code blocks, no explanation. Only valid JSON.
            """;

    /**
     * Retry prompt — sent when the first attempt fails schema validation.
     */
    private static final String RETRY_PROMPT = """
            Your previous response was not valid JSON or was missing required fields.
            You MUST respond with ONLY a JSON object in this exact format:
            {"classification":"CODE","confidence":0.00,"reasoning":"Your reasoning","estimatedLeakRateKgHr":0.0}

            Where CODE is exactly one of: FUGITIVE_EMISSION, PLANNED_VENTING, MAINTENANCE_ACTIVITY, SENSOR_ARTIFACT, NORMAL_PROCESS, UNKNOWN

            No markdown, no code blocks, no explanation — ONLY the JSON object.
            Try again for the same emission event.
            """;

    public EmissionAnalysisService(
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
     * Classify an emission event using semantic analysis with LLM reasoning.
     *
     * <p>Processes the enriched emission event through the semantic analysis pipeline:
     * 1. Check demo mode for deterministic test responses
     * 2. Build comprehensive user prompt from event data
     * 3. Send to LLM with strict JSON schema validation
     * 4. Retry once if schema validation fails
     * 5. Calculate leak rate estimate for FUGITIVE_EMISSION classifications
     * 6. Return result with confidence and reasoning for audit trail</p>
     *
     * @param event the enriched emission event with all contextual data
     * @return a ClassificationResult containing the classification, confidence, and reasoning
     */
    public ClassificationResult classify(EmissionEvent event) {
        log.info("Emission semantic analysis started for event: {} ({}), equipment: {}",
                event.getId(), event.getInspectionId(), event.getEquipmentTag());

        // Demo Mode: Use deterministic response if a matching scenario exists
        var demoResult = demoMode.classifyEmission(event);
        if (demoResult.isPresent()) {
            log.info("Demo mode provided emission classification for event {}", event.getId());
            EmissionClassification classification = demoResult.get();
            double leakRate = estimateLeakRate(event, classification);
            return new ClassificationResult(
                    classification, 0.95, "Demo mode classification", leakRate, true, 1);
        }

        String userPrompt = buildUserPrompt(event);
        long startTime = System.currentTimeMillis();

        // ── Attempt 1: Primary LLM call ────────────────────────────────
        String rawResponse;
        try {
            rawResponse = chatModel.generate(SYSTEM_PROMPT + "\n\nUser: " + userPrompt);
        } catch (Exception e) {
            log.error("LLM inference failed for emission event {}: {}", event.getId(), e.getMessage());
            return new ClassificationResult(
                    EmissionClassification.UNKNOWN,
                    0.0,
                    "LLM inference failed: " + e.getMessage(),
                    0.0,
                    false,
                    1);
        }

        long attempt1Latency = System.currentTimeMillis() - startTime;
        log.info("LLM attempt 1 completed in {}ms for emission event {}", attempt1Latency, event.getId());

        // Validate attempt 1
        EmissionValidationResult validation = validateEmissionResponse(rawResponse);

        if (validation.valid()) {
            log.info("Schema validation passed on first attempt for emission event {}", event.getId());
            double leakRate = estimateLeakRate(event, validation.classification());
            return new ClassificationResult(
                    validation.classification(),
                    validation.confidence(),
                    validation.reasoning(),
                    leakRate,
                    true,
                    1);
        }

        // ── Attempt 2: Retry with explicit correction prompt ───────────
        log.warn("Schema validation failed on attempt 1 for emission event {}. Errors: {}. Retrying...",
                event.getId(), validation.errors());

        String retryResponse;
        try {
            retryResponse = chatModel.generate(
                    SYSTEM_PROMPT + "\n\nUser: " + userPrompt + "\n\n" + RETRY_PROMPT);
        } catch (Exception e) {
            log.error("LLM retry failed for emission event {}: {}", event.getId(), e.getMessage());
            long totalLatency = System.currentTimeMillis() - startTime;
            return new ClassificationResult(
                    EmissionClassification.UNKNOWN,
                    0.0,
                    "LLM retry failed: " + e.getMessage(),
                    0.0,
                    false,
                    2);
        }

        long totalLatency = System.currentTimeMillis() - startTime;
        log.info("LLM attempt 2 completed, total time {}ms for emission event {}", totalLatency, event.getId());

        // Validate attempt 2
        EmissionValidationResult retryValidation = validateEmissionResponse(retryResponse);

        if (retryValidation.valid()) {
            log.info("Schema validation passed on retry for emission event {}", event.getId());
            double leakRate = estimateLeakRate(event, retryValidation.classification());
            return new ClassificationResult(
                    retryValidation.classification(),
                    retryValidation.confidence(),
                    retryValidation.reasoning(),
                    leakRate,
                    false,
                    2);
        }

        // ── Both attempts failed — route to Exception Inbox ────────────
        log.error("Schema validation failed on both attempts for emission event {}. Attempt 1: {}. Attempt 2: {}",
                event.getId(), validation.errors(), retryValidation.errors());

        return new ClassificationResult(
                EmissionClassification.UNKNOWN,
                0.0,
                "Schema validation failed after 2 attempts. Attempt 1: " + validation.errors() +
                " | Attempt 2: " + retryValidation.errors(),
                0.0,
                false,
                2);
    }

    /**
     * Build the comprehensive user prompt from an enriched emission event.
     *
     * <p>Includes sensor data, multi-modal fusion readings, SAP context,
     * historical baseline, trend information, and environmental conditions.</p>
     *
     * @param event the enriched emission event
     * @return the formatted user prompt for the LLM
     */
    private String buildUserPrompt(EmissionEvent event) {
        StringBuilder prompt = new StringBuilder();

        // Equipment and Location
        prompt.append(String.format("""
                Equipment: %s
                Location: %s
                Robot ID: %s, Mission ID: %s, Inspection ID: %s
                Detection Time: %s

                """,
                event.getEquipmentTag(),
                event.getLocationArea(),
                event.getRobotId(),
                event.getMissionId(),
                event.getInspectionId(),
                event.getDetectedAt()));

        // Primary Sensor Reading
        prompt.append(String.format("""
                PRIMARY SENSOR READING:
                Modality: %s
                Reading: %.2f %s
                """,
                event.getSensorModality(),
                event.getRawReading(),
                event.getReadingUnit()));

        // Multi-Modal Fusion Data
        if (event.getThermalReadingCelsius() != null || event.getAcousticReadingDb() != null) {
            prompt.append("\nMULTI-MODAL SENSOR FUSION:\n");
            if (event.getThermalReadingCelsius() != null) {
                prompt.append(String.format("  Thermal Reading: %.1f°C\n", event.getThermalReadingCelsius()));
            }
            if (event.getThermalDeltaCelsius() != null) {
                prompt.append(String.format("  Thermal Delta (above ambient): %.1f°C\n", event.getThermalDeltaCelsius()));
            }
            if (event.getAcousticReadingDb() != null) {
                prompt.append(String.format("  Acoustic Reading: %.1f dB\n", event.getAcousticReadingDb()));
            }
            if (event.getAcousticLeakSignature() != null) {
                prompt.append(String.format("  Acoustic Leak Signature: %s\n",
                        event.getAcousticLeakSignature() ? "Yes (high-frequency hiss)" : "No"));
            }
            prompt.append(String.format("  Corroborating Sensor Modalities: %d\n", event.getCorroboratingSensors()));
        }

        // Environmental Conditions
        prompt.append("\nENVIRONMENTAL CONDITIONS:\n");
        if (event.getAmbientTempCelsius() != null) {
            prompt.append(String.format("  Ambient Temperature: %.1f°C\n", event.getAmbientTempCelsius()));
        }
        if (event.getWindSpeedKmh() != null) {
            prompt.append(String.format("  Wind Speed: %.1f km/h\n", event.getWindSpeedKmh()));
        }
        if (event.getWindDirection() != null) {
            prompt.append(String.format("  Wind Direction: %s\n", event.getWindDirection()));
        }

        // SAP Context
        prompt.append("\nSAP OPERATIONAL CONTEXT:\n");
        prompt.append(String.format("  Maintenance Active: %s\n", event.isSapMaintenanceActive()));
        prompt.append(String.format("  Turnaround Active: %s\n", event.isSapTurnaroundActive()));
        if (event.getSapActiveWorkOrders() != null && !event.getSapActiveWorkOrders().isBlank()) {
            prompt.append(String.format("  Active Work Orders: %s\n", event.getSapActiveWorkOrders()));
        }
        if (event.getDaysSinceLastMaintenance() != null) {
            prompt.append(String.format("  Days Since Last Maintenance: %d\n", event.getDaysSinceLastMaintenance()));
        }

        // Historical Baseline and Trend
        prompt.append("\nHISTORICAL CONTEXT:\n");
        if (event.getHistoricalBaselinePpm() != null) {
            prompt.append(String.format("  Historical Baseline PPM: %.1f\n", event.getHistoricalBaselinePpm()));
        }
        if (event.getPreviousDetections7d() > 0) {
            prompt.append(String.format("  Detections in Last 7 Days: %d\n", event.getPreviousDetections7d()));
        }
        if (event.getTrendDirection() != null) {
            prompt.append(String.format("  Trend Direction: %s\n", event.getTrendDirection()));
        }

        prompt.append("\n");
        prompt.append("Classify this emission event using the decision tree in your system prompt.\n");

        return prompt.toString();
    }

    /**
     * Validate the LLM response and extract emission classification data.
     *
     * @param rawResponse the raw string response from the LLM
     * @return a validation result with extracted fields or error list
     */
    private EmissionValidationResult validateEmissionResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return EmissionValidationResult.invalid(java.util.List.of("LLM returned empty response"));
        }

        // Extract JSON from response (LLM may wrap it in markdown or text)
        String jsonStr = extractJson(rawResponse);
        if (jsonStr == null) {
            return EmissionValidationResult.invalid(java.util.List.of(
                    "No valid JSON object found in LLM response"));
        }

        try {
            // Parse JSON manually to extract fields
            Map<String, Object> jsonMap = parseJsonSimple(jsonStr);

            String classificationStr = (String) jsonMap.get("classification");
            Object confidenceObj = jsonMap.get("confidence");
            String reasoning = (String) jsonMap.get("reasoning");

            // Validate required fields
            java.util.List<String> errors = new java.util.ArrayList<>();

            if (classificationStr == null || classificationStr.isBlank()) {
                errors.add("Missing or empty 'classification' field");
            }

            if (confidenceObj == null) {
                errors.add("Missing 'confidence' field");
            }

            if (reasoning == null || reasoning.isBlank()) {
                errors.add("Missing or empty 'reasoning' field");
            }

            if (!errors.isEmpty()) {
                return EmissionValidationResult.invalid(errors);
            }

            // Parse confidence as double
            double confidence;
            try {
                confidence = ((Number) confidenceObj).doubleValue();
                if (confidence < 0.0 || confidence > 1.0) {
                    errors.add("Confidence must be between 0.0 and 1.0, got: " + confidence);
                    return EmissionValidationResult.invalid(errors);
                }
            } catch (NumberFormatException | ClassCastException e) {
                errors.add("Confidence must be a number between 0.0 and 1.0");
                return EmissionValidationResult.invalid(errors);
            }

            // Validate classification code
            EmissionClassification classification;
            try {
                classification = EmissionClassification.valueOf(classificationStr.toUpperCase().trim());
            } catch (IllegalArgumentException e) {
                errors.add("Unknown classification code: '" + classificationStr +
                        "'. Must be one of: FUGITIVE_EMISSION, PLANNED_VENTING, MAINTENANCE_ACTIVITY, SENSOR_ARTIFACT, NORMAL_PROCESS, UNKNOWN");
                return EmissionValidationResult.invalid(errors);
            }

            return EmissionValidationResult.valid(classification, confidence, reasoning);

        } catch (Exception e) {
            return EmissionValidationResult.invalid(java.util.List.of(
                    "Failed to parse JSON response: " + e.getMessage()));
        }
    }

    /**
     * Extract a JSON object from a potentially noisy LLM response.
     * Handles cases where the model wraps JSON in markdown code blocks or adds trailing text.
     *
     * @param response the raw LLM response
     * @return the extracted JSON string, or null if no valid JSON object found
     */
    private String extractJson(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }

        // Try to find JSON object: match balanced braces
        int startIdx = response.indexOf('{');
        if (startIdx == -1) {
            return null;
        }

        int braceCount = 0;
        for (int i = startIdx; i < response.length(); i++) {
            char c = response.charAt(i);
            if (c == '{') {
                braceCount++;
            } else if (c == '}') {
                braceCount--;
                if (braceCount == 0) {
                    return response.substring(startIdx, i + 1);
                }
            }
        }

        return null;
    }

    /**
     * Simple JSON parser for extracting key-value pairs.
     *
     * @param jsonStr the JSON string
     * @return a map of parsed key-value pairs
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonSimple(String jsonStr) throws Exception {
        // Use a basic approach: look for key: value patterns
        Map<String, Object> result = new HashMap<>();

        // Remove outer braces
        String content = jsonStr.trim();
        if (content.startsWith("{")) {
            content = content.substring(1);
        }
        if (content.endsWith("}")) {
            content = content.substring(0, content.length() - 1);
        }

        // Split by commas (naive, but works for our simple schema)
        String[] pairs = content.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");

        for (String pair : pairs) {
            pair = pair.trim();
            int colonIdx = pair.indexOf(':');
            if (colonIdx > 0) {
                String key = pair.substring(0, colonIdx).trim();
                String value = pair.substring(colonIdx + 1).trim();

                // Remove quotes from key
                key = key.replaceAll("^\"|\"$", "");

                // Parse value
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    // String value
                    result.put(key, value.substring(1, value.length() - 1));
                } else if (value.equalsIgnoreCase("true")) {
                    result.put(key, true);
                } else if (value.equalsIgnoreCase("false")) {
                    result.put(key, false);
                } else {
                    // Try to parse as number
                    try {
                        if (value.contains(".")) {
                            result.put(key, Double.parseDouble(value));
                        } else {
                            result.put(key, Long.parseLong(value));
                        }
                    } catch (NumberFormatException e) {
                        result.put(key, value);
                    }
                }
            }
        }

        return result;
    }

    /**
     * Estimate the leak rate in kg/hr for a FUGITIVE_EMISSION classification.
     *
     * <p>Uses a concentration-to-leak-rate formula with distance and equipment type factors:
     * Formula: (ppm / 10000) * distanceFactor * equipmentTypeFactor
     *
     * <p>Only returns a non-zero estimate for FUGITIVE_EMISSION. Other classifications
     * return 0.0.</p>
     *
     * @param event the emission event with sensor data
     * @param classification the determined classification
     * @return estimated leak rate in kg/hr, or 0.0 if not a fugitive emission
     */
    private double estimateLeakRate(EmissionEvent event, EmissionClassification classification) {
        if (classification != EmissionClassification.FUGITIVE_EMISSION) {
            return 0.0;
        }

        // Extract PPM reading
        double ppm = event.getRawReading();
        if (ppm <= 0) {
            return 0.0;
        }

        // Distance factor: normalized to 1.5 if unknown
        double distanceFactor = 1.5;
        if (event.getSensorDistanceMeters() != null && event.getSensorDistanceMeters() > 0) {
            // Assume baseline distance is ~1 meter; adjust factor proportionally
            distanceFactor = event.getSensorDistanceMeters() / 1.0;
            if (distanceFactor > 5.0) {
                distanceFactor = 5.0; // Cap at 5.0 for very far detections
            }
        }

        // Equipment type factor: inferred from equipment tag
        double equipmentTypeFactor = 1.0;
        if (event.getEquipmentTag() != null) {
            String tag = event.getEquipmentTag().toUpperCase();
            if (tag.startsWith("PMP")) {
                equipmentTypeFactor = 0.8; // Pumps: moderate leak rates
            } else if (tag.startsWith("CMP")) {
                equipmentTypeFactor = 1.2; // Compressors: higher pressure, higher rate
            } else if (tag.startsWith("VLV")) {
                equipmentTypeFactor = 0.6; // Valves: typically small leaks
            } else if (tag.startsWith("HX") || tag.startsWith("HTX")) {
                equipmentTypeFactor = 1.1; // Heat exchangers: moderate-high
            } else if (tag.startsWith("FLG") || tag.startsWith("FLA")) {
                equipmentTypeFactor = 1.5; // Flanges/connections: high leak potential
            }
        }

        // Calculate leak rate: (ppm / 10000) * distanceFactor * equipmentTypeFactor
        double leakRate = (ppm / 10000.0) * distanceFactor * equipmentTypeFactor;

        // Round to 2 decimal places
        return Math.round(leakRate * 100.0) / 100.0;
    }

    /**
     * Classification result with metadata for audit logging.
     *
     * @param classification the determined emission classification
     * @param confidence confidence score (0.0 - 1.0)
     * @param reasoning the LLM's reasoning chain for this classification
     * @param estimatedLeakRateKgHr estimated leak rate in kg/hr (0.0 for non-fugitive)
     * @param jsonValidFirstAttempt true if LLM output was valid JSON on first try
     * @param llmAttempts how many LLM calls were made (1 or 2)
     */
    public record ClassificationResult(
            EmissionClassification classification,
            double confidence,
            String reasoning,
            double estimatedLeakRateKgHr,
            boolean jsonValidFirstAttempt,
            int llmAttempts
    ) {}

    /**
     * Internal validation result for emission classification responses.
     */
    private record EmissionValidationResult(
            boolean valid,
            EmissionClassification classification,
            double confidence,
            String reasoning,
            java.util.List<String> errors
    ) {
        static EmissionValidationResult valid(
                EmissionClassification classification,
                double confidence,
                String reasoning) {
            return new EmissionValidationResult(true, classification, confidence, reasoning, java.util.List.of());
        }

        static EmissionValidationResult invalid(java.util.List<String> errors) {
            return new EmissionValidationResult(false, null, 0.0, null, errors);
        }
    }
}
