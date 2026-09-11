package com.mockapilab.modules.project.controller;

import com.mockapilab.common.api.ApiResponse;
import com.mockapilab.modules.auth.security.UserPrincipal;
import com.mockapilab.modules.project.dto.CreateProjectRequest;
import com.mockapilab.modules.project.dto.ProjectResponse;
import com.mockapilab.modules.project.dto.UpdateProjectRequest;
import com.mockapilab.modules.project.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Controller exposing CRUD operations for Project workspaces.
 * All endpoints are strictly authenticated and enforce owner isolation.
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProjectResponse>> createProject(
            @Valid @RequestBody CreateProjectRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        ProjectResponse response = projectService.createProject(request, principal.getId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Project created successfully", response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProjectResponse>>> listProjects(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        List<ProjectResponse> response = projectService.listUserProjects(principal.getId());
        return ResponseEntity.ok(ApiResponse.success("User projects retrieved successfully", response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProjectResponse>> getProject(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        ProjectResponse response = projectService.getProject(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.success("Project retrieved successfully", response));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ProjectResponse>> updateProject(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProjectRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        ProjectResponse response = projectService.updateProject(id, request, principal.getId());
        return ResponseEntity.ok(ApiResponse.success("Project updated successfully", response));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteProject(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        projectService.deleteProject(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.success("Project deleted successfully", null));
    }
}
