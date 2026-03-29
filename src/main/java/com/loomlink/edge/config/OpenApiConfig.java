package com.loomlink.edge.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 3.0 configuration for Swagger UI.
 *
 * <p>Provides interactive API documentation at /swagger-ui.html.
 * Judges and architects can explore every endpoint, see request/response schemas,
 * and test the API directly from the browser.</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI loomLinkOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Loom Link - Sovereign Intelligence Layer API")
                        .description("""
                                Nordic Energy Matchbox 2026 | Challenge 01: Maintenance Optimization

                                Loom Link intercepts free-text SAP maintenance notifications, classifies them
                                into ISO 14224 failure codes using a locally-hosted LLM (Mistral 7B on HM90 Ryzen 9),
                                enforces a deterministic Reflector Gate, and writes back structured codes to SAP.

                                **Architectural Pillars:**
                                - 100% Local Execution (Zero data egress)
                                - SAP Clean Core Compliance (BAPI only)
                                - Deterministic Governance (Reflector Gate >= 0.85)
                                - Full Audit Trail & Explainability
                                """)
                        .version("0.1.0-SNAPSHOT")
                        .contact(new Contact()
                                .name("Loom Link Team")
                                .url("https://nordicenergymatchbox.no"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://nordicenergymatchbox.no")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Development"),
                        new Server().url("http://192.168.29.152:8080").description("HM90 Sovereign Node")))
                .tags(List.of(
                        new Tag().name("Pipeline").description("Core classification pipeline endpoints"),
                        new Tag().name("Dashboard").description("Fleet monitoring and asset intelligence"),
                        new Tag().name("Exception Inbox").description("Human review of rejected classifications"),
                        new Tag().name("Audit Trail").description("Immutable classification audit log"),
                        new Tag().name("Analytics").description("Pipeline KPIs and operational savings"),
                        new Tag().name("Health").description("Sovereign node health monitoring"),
                        new Tag().name("Resilience").description("Cache, DLQ, and RBAC metrics"),
                        new Tag().name("Demo").description("Demo mode controls")))
                // API Key security scheme — appears as "Authorize" button in Swagger UI
                .components(new Components()
                        .addSecuritySchemes("ApiKeyAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key")
                                .description("Sovereign Node API Key — shared between SAP OData gateway and Loom Link")))
                .addSecurityItem(new SecurityRequirement().addList("ApiKeyAuth"));
    }
}
