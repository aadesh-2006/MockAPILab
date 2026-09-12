package com.mockapilab.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private CorrelationIdFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdFilter();
        MDC.clear();
    }

    @Test
    @DisplayName("Generates new UUID correlation ID when header is absent")
    void testGeneratesNewCorrelationId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> capturedMdcId = new AtomicReference<>();

        FilterChain chain = (req, res) -> {
            capturedMdcId.set(CorrelationIdFilter.getCorrelationId());
            if (res instanceof HttpServletResponse httpRes) {
                httpRes.setStatus(200);
            }
        };

        filter.doFilter(request, response, chain);

        String responseHeader = response.getHeader("X-Request-Id");
        assertThat(responseHeader).isNotNull().isNotEmpty();
        assertThat(capturedMdcId.get()).isEqualTo(responseHeader);
        assertThat(MDC.get("requestId")).isNull(); // Cleaned up in finally
    }

    @Test
    @DisplayName("Preserves existing X-Request-Id header when supplied by client")
    void testPreservesExistingCorrelationId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/projects");
        request.addHeader("X-Request-Id", "client-trace-custom-id-12345");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> capturedMdcId = new AtomicReference<>();

        FilterChain chain = (req, res) -> {
            capturedMdcId.set(CorrelationIdFilter.getCorrelationId());
            if (res instanceof HttpServletResponse httpRes) {
                httpRes.setStatus(201);
            }
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("client-trace-custom-id-12345");
        assertThat(capturedMdcId.get()).isEqualTo("client-trace-custom-id-12345");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    @DisplayName("Cleans up MDC context even when exception occurs down the chain")
    void testCleansUpMdcOnException() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/contracts");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain failingChain = (req, res) -> {
            throw new RuntimeException("Simulated filter chain failure");
        };

        try {
            filter.doFilter(request, response, failingChain);
        } catch (Exception ignored) {
        }

        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("httpMethod")).isNull();
        assertThat(MDC.get("requestUri")).isNull();
    }
}