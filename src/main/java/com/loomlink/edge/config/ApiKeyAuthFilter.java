package com.loomlink.edge.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * API Key Authentication Filter — lightweight request authentication for the Sovereign Node.
 *
 * <p>Why not Spring Security? For two reasons:</p>
 * <ul>
 *   <li>Clean Core compliance: we minimize dependencies on frameworks that could
 *       complicate the SAP JCo classloader in production</li>
 *   <li>Sovereign simplicity: on an air-gapped node, a shared API key between
 *       the SAP OData gateway and Loom Link is sufficient. Full OAuth2/SAML
 *       adds complexity with no security gain on a private network segment.</li>
 * </ul>
 *
 * <p>The API key is sent via the {@code X-API-Key} header. Requests without a valid
 * key receive a 401 Unauthorized response with a JSON error body.</p>
 *
 * <p>Public endpoints (health, Swagger, static assets) are excluded from authentication
 * to allow monitoring and documentation access without credentials.</p>
 *
 * <p>Disable authentication for local development by setting:
 * {@code loomlink.security.api-key-enabled=false}</p>
 */
@Component
@Order(0) // Run BEFORE CorrelationIdFilter (Order 1)
public class ApiKeyAuthFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private static final String API_KEY_HEADER = "X-API-Key";

    /** Endpoints that never require authentication. */
    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/actuator",       // Health checks, Prometheus scraping
            "/swagger-ui",     // API documentation
            "/api-docs",       // OpenAPI spec
            "/v3/api-docs",    // SpringDoc default path
            "/index.html",     // Dashboard (served separately, uses API key from JS)
            "/favicon.ico",
            "/error"
    );

    /** Static asset extensions that bypass auth. */
    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            ".html", ".css", ".js", ".ico", ".png", ".svg", ".woff", ".woff2"
    );

    private final boolean enabled;
    private final String apiKey;

    public ApiKeyAuthFilter(
            @Value("${loomlink.security.api-key-enabled:true}") boolean enabled,
            @Value("${loomlink.security.api-key:#{null}}") String apiKey) {
        this.enabled = enabled;
        this.apiKey = apiKey;

        if (enabled && (apiKey == null || apiKey.isBlank())) {
            log.warn("╔══════════════════════════════════════════════════════════════╗");
            log.warn("║  API KEY AUTH ENABLED BUT NO KEY CONFIGURED!                ║");
            log.warn("║  Set loomlink.security.api-key in application.yml or env    ║");
            log.warn("║  All API requests will be REJECTED until a key is set.      ║");
            log.warn("╚══════════════════════════════════════════════════════════════╝");
        } else if (enabled) {
            log.info("API Key authentication ENABLED — protected endpoints require X-API-Key header");
        } else {
            log.info("API Key authentication DISABLED — all endpoints are open (development mode)");
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpRes = (HttpServletResponse) response;
        String path = httpReq.getRequestURI();

        // Allow public endpoints without authentication
        if (isPublicPath(path)) {
            chain.doFilter(request, response);
            return;
        }

        // Validate API key — accept from header OR query parameter (for browser downloads)
        String providedKey = httpReq.getHeader(API_KEY_HEADER);
        if (providedKey == null || providedKey.isBlank()) {
            providedKey = httpReq.getParameter("apiKey");
        }

        if (apiKey == null || apiKey.isBlank()) {
            sendUnauthorized(httpRes, "API key authentication is enabled but no key is configured on the server.");
            return;
        }

        if (providedKey == null || providedKey.isBlank()) {
            log.warn("AUTH DENIED: missing X-API-Key header for {} {}", httpReq.getMethod(), path);
            sendUnauthorized(httpRes, "Missing X-API-Key header. Include your API key in the request header.");
            return;
        }

        if (!apiKey.equals(providedKey)) {
            log.warn("AUTH DENIED: invalid API key for {} {} from {}", httpReq.getMethod(), path, httpReq.getRemoteAddr());
            sendUnauthorized(httpRes, "Invalid API key.");
            return;
        }

        // Key is valid — proceed
        chain.doFilter(request, response);
    }

    private boolean isPublicPath(String path) {
        // Check prefix matches
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        // Check static assets
        for (String ext : STATIC_EXTENSIONS) {
            if (path.endsWith(ext)) return true;
        }
        // Root path serves dashboard
        return "/".equals(path);
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(String.format(
                "{\"error\":\"UNAUTHORIZED\",\"status\":401,\"message\":\"%s\",\"service\":\"loom-link-edge\"}",
                message));
    }
}
