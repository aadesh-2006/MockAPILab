package com.mockapilab.common.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * System status & health endpoint providing runtime metadata and platform diagnostics.
 */
@RestController
@RequestMapping("/api/v1/status")
@Tag(name = "System Status", description = "Platform health, versioning, and environment metadata")
public class SystemStatusController {

    private static final String APP_NAME = "MockAPILab Backend";
    private static final String APP_VERSION = "0.1.0-SNAPSHOT";
    private static final String MILESTONE = "Milestone 11: Observability, Testing & Production Polish";

    @GetMapping
    @SecurityRequirements // Public endpoint
    @Operation(summary = "Get system status", description = "Returns high-level application health, milestone info, and runtime metadata without authentication.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        Map<String, Object> statusData = new LinkedHashMap<>();
        statusData.put("service", APP_NAME);
        statusData.put("version", APP_VERSION);
        statusData.put("milestone", MILESTONE);
        statusData.put("status", "UP");
        statusData.put("javaVersion", System.getProperty("java.version"));
        statusData.put("architecture", "Modular Monolith");
        statusData.put("observability", "Enabled (MDC Correlation, Micrometer, Actuator Probes)");

        return ResponseEntity.ok(ApiResponse.success("System operational", statusData));
    }
}