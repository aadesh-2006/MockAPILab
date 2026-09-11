package com.mockapilab.common.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * System status & health endpoint providing runtime metadata for MockAPILab.
 */
@RestController
@RequestMapping("/api/v1/status")
public class SystemStatusController {

    private static final String APP_NAME = "MockAPILab Backend";
    private static final String APP_VERSION = "0.1.0-SNAPSHOT";
    private static final String MILESTONE = "Milestone 1: Project Foundation";

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        Map<String, Object> statusData = Map.of(
                "service", APP_NAME,
                "version", APP_VERSION,
                "milestone", MILESTONE,
                "status", "UP",
                "javaVersion", System.getProperty("java.version"),
                "architecture", "Modular Monolith"
        );
        return ResponseEntity.ok(ApiResponse.success("System operational", statusData));
    }
}
