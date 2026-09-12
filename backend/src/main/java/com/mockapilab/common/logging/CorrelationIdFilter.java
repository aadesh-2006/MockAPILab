package com.mockapilab.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * High-precedence servlet filter establishing request correlation IDs (X-Request-Id)
 * and populating SLF4J Mapped Diagnostic Context (MDC) for structured logging.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY_REQUEST_ID = "requestId";
    public static final String MDC_KEY_HTTP_METHOD = "httpMethod";
    public static final String MDC_KEY_REQUEST_URI = "requestUri";

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (!StringUtils.hasText(requestId)) {
            requestId = UUID.randomUUID().toString();
        } else {
            // Sanitize correlation ID to prevent CRLF log injection
            requestId = requestId.replaceAll("[\\r\\n]", "").trim();
            if (requestId.length() > 64) {
                requestId = requestId.substring(0, 64);
            }
        }

        response.setHeader(REQUEST_ID_HEADER, requestId);

        long startTime = System.currentTimeMillis();
        String method = request.getMethod();
        String uri = request.getRequestURI();

        try {
            MDC.put(MDC_KEY_REQUEST_ID, requestId);
            MDC.put(MDC_KEY_HTTP_METHOD, method);
            MDC.put(MDC_KEY_REQUEST_URI, uri);

            filterChain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - startTime;
            int status = response.getStatus();

            // Do not log noisy health probes at INFO level
            if (uri.startsWith("/actuator") || uri.equals("/api/v1/status")) {
                log.debug("HTTP {} {} status={} duration={}ms requestId={}", method, uri, status, durationMs, requestId);
            } else {
                log.info("HTTP {} {} status={} duration={}ms requestId={}", method, uri, status, durationMs, requestId);
            }

            MDC.remove(MDC_KEY_REQUEST_ID);
            MDC.remove(MDC_KEY_HTTP_METHOD);
            MDC.remove(MDC_KEY_REQUEST_URI);
        }
    }

    public static String getCorrelationId() {
        String id = MDC.get(MDC_KEY_REQUEST_ID);
        return id != null ? id : "unknown";
    }
}