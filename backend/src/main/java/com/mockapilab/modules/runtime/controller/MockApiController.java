package com.mockapilab.modules.runtime.controller;

import com.mockapilab.modules.runtime.engine.MockRequestDispatcher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Public dynamic mock server gateway.
 * <p>
 * Receives all incoming HTTP requests under /mock/{runtimeId}/** and delegates
 * directly to the runtime engine for schema matching, validation, and stateful execution.
 */
@RestController
@RequestMapping("/mock/{runtimeId}")
@Tag(name = "Mock API Gateway", description = "Public dynamic mock server execution endpoint")
public class MockApiController {

    private final MockRequestDispatcher requestDispatcher;

    public MockApiController(MockRequestDispatcher requestDispatcher) {
        this.requestDispatcher = requestDispatcher;
    }

    @RequestMapping(
            value = "/**",
            method = {
                    RequestMethod.GET,
                    RequestMethod.POST,
                    RequestMethod.PUT,
                    RequestMethod.DELETE,
                    RequestMethod.PATCH,
                    RequestMethod.HEAD,
                    RequestMethod.OPTIONS
            }
    )
    @Operation(summary = "Dynamic Mock Request Gateway", description = "Dispatches request to the running contract mock server without authentication.")
    public ResponseEntity<Object> handleMockRequest(
            @PathVariable UUID runtimeId,
            HttpServletRequest request
    ) throws IOException {
        String fullPath = request.getRequestURI();
        String contextPath = request.getContextPath() != null ? request.getContextPath() : "";
        if (!contextPath.isEmpty() && fullPath.startsWith(contextPath)) {
            fullPath = fullPath.substring(contextPath.length());
        }

        String prefix = "/mock/" + runtimeId;
        String subPath = "/";
        if (fullPath.length() > prefix.length()) {
            subPath = fullPath.substring(prefix.length());
        }

        Map<String, String> queryParams = new LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) -> {
            if (v != null && v.length > 0) {
                queryParams.put(k, v[0]);
            }
        });

        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames()).forEach(headerName ->
                headers.put(headerName, request.getHeader(headerName))
        );

        String rawBody = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);

        return requestDispatcher.dispatch(
                runtimeId,
                request.getMethod(),
                subPath,
                rawBody,
                queryParams,
                headers
        );
    }
}