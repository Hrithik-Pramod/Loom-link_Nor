package com.loomlink.edge.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configures the LangChain4j Ollama client to point at the HM90 Sovereign Node.
 *
 * <p>The HM90 runs an AMD Ryzen 9 with Mistral 7B served via Ollama. All inference
 * happens on this local node — no data leaves the facility boundary. The connection
 * is over the Tailscale mesh at {@code 100.64.132.128:11434}.</p>
 */
@Configuration
public class OllamaConfig {

    private static final Logger log = LoggerFactory.getLogger(OllamaConfig.class);

    @Value("${loomlink.llm.base-url:http://100.64.132.128:11434}")
    private String baseUrl;

    @Value("${loomlink.llm.model-id:mistral:7b}")
    private String modelId;

    @Value("${loomlink.llm.timeout-seconds:120}")
    private int timeoutSeconds;

    @Value("${loomlink.llm.temperature:0.1}")
    private double temperature;

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        log.info("Connecting to HM90 Sovereign Node at: {}", baseUrl);
        log.info("Model: {} | Temperature: {} | Timeout: {}s", modelId, temperature, timeoutSeconds);

        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelId)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }
}
