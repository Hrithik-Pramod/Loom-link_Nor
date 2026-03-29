package com.loomlink.edge.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Micrometer metrics configuration for Prometheus observability.
 *
 * <p>Exposes production-grade metrics at /actuator/prometheus:
 * pipeline throughput, gate pass/reject rates, cache hit rates,
 * LLM inference latency, and SAP sync reliability.</p>
 *
 * <p>These metrics enable Grafana dashboards for production monitoring
 * of the sovereign node's health and pipeline performance.</p>
 */
@Configuration
public class PipelineMetricsConfig {

    @Bean
    public Counter pipelineProcessedCounter(MeterRegistry registry) {
        return Counter.builder("loomlink.pipeline.processed")
                .description("Total notifications processed through the pipeline")
                .tag("challenge", "01")
                .register(registry);
    }

    @Bean
    public Counter gatePassedCounter(MeterRegistry registry) {
        return Counter.builder("loomlink.gate.passed")
                .description("Notifications that passed the Reflector Gate")
                .register(registry);
    }

    @Bean
    public Counter gateRejectedCounter(MeterRegistry registry) {
        return Counter.builder("loomlink.gate.rejected")
                .description("Notifications rejected by the Reflector Gate")
                .register(registry);
    }

    @Bean
    public Counter cacheHitCounter(MeterRegistry registry) {
        return Counter.builder("loomlink.cache.hits")
                .description("Semantic cache hits (LLM bypassed)")
                .register(registry);
    }

    @Bean
    public Counter cacheMissCounter(MeterRegistry registry) {
        return Counter.builder("loomlink.cache.misses")
                .description("Semantic cache misses (LLM invoked)")
                .register(registry);
    }

    @Bean
    public Counter feedbackLoopCounter(MeterRegistry registry) {
        return Counter.builder("loomlink.feedback.promotions")
                .description("Human corrections promoted to Experience Bank")
                .register(registry);
    }

    @Bean
    public Timer pipelineLatencyTimer(MeterRegistry registry) {
        return Timer.builder("loomlink.pipeline.latency")
                .description("End-to-end pipeline processing time")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    @Bean
    public Timer llmInferenceTimer(MeterRegistry registry) {
        return Timer.builder("loomlink.llm.inference")
                .description("LLM inference time via Ollama")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }
}
