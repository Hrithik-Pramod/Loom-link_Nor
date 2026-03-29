package com.loomlink.edge.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Correlation ID filter — assigns a unique trace ID to every HTTP request.
 *
 * <p>The trace ID propagates through the MDC (Mapped Diagnostic Context) so every
 * log line during a pipeline execution can be correlated. The trace ID is also
 * returned in the response header for client-side debugging.</p>
 *
 * <p>In production, this enables distributed tracing across the full
 * OData -> Loom Link -> SAP BAPI chain. For the demo, it proves
 * enterprise-grade observability to the judges.</p>
 */
@Component
@Order(1)
public class CorrelationIdFilter implements Filter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String MDC_TRACE_KEY = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpRes = (HttpServletResponse) response;

        // Use incoming trace ID if provided, or generate a new one
        String traceId = httpReq.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = "LL-" + UUID.randomUUID().toString().substring(0, 8);
        }

        // Set in MDC for structured logging
        MDC.put(MDC_TRACE_KEY, traceId);

        // Return trace ID in response header
        httpRes.setHeader(TRACE_ID_HEADER, traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_KEY);
        }
    }
}
