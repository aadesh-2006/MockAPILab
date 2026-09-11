package com.mockapilab.modules.project.service;

import com.mockapilab.common.exception.ForbiddenException;
import com.mockapilab.common.exception.ResourceNotFoundException;
import com.mockapilab.modules.auth.model.User;
import com.mockapilab.modules.auth.repository.UserRepository;
import com.mockapilab.modules.project.dto.CreateProjectRequest;
import com.mockapilab.modules.project.dto.ProjectResponse;
import com.mockapilab.modules.project.dto.UpdateProjectRequest;
import com.mockapilab.modules.project.model.Project;
import com.mockapilab.modules.project.repository.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Service managing project workspace lifecycle and ownership boundaries.
 */
@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    public ProjectService(ProjectRepository projectRepository, UserRepository userRepository) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request, UUID ownerId) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Owner user not found with id: " + ownerId));

        Project project = new Project(request.name().trim(), request.description(), owner);
        Project savedProject = projectRepository.save(project);

        return ProjectResponse.fromEntity(savedProject);
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> listUserProjects(UUID ownerId) {
        return projectRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId)
                .stream()
                .map(ProjectResponse::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getProject(UUID projectId, UUID ownerId) {
        Project project = findAndVerifyOwnership(projectId, ownerId);
        return ProjectResponse.fromEntity(project);
    }

    @Transactional
    public ProjectResponse updateProject(UUID projectId, UpdateProjectRequest request, UUID ownerId) {
        Project project = findAndVerifyOwnership(projectId, ownerId);
        project.setName(request.name().trim());
        project.setDescription(request.description());
        Project updatedProject = projectRepository.save(project);
        return ProjectResponse.fromEntity(updatedProject);
    }

    @Transactional
    public void deleteProject(UUID projectId, UUID ownerId) {
        Project project = findAndVerifyOwnership(projectId, ownerId);
        projectRepository.delete(project);
    }

    private Project findAndVerifyOwnership(UUID projectId, UUID ownerId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + projectId));

        if (!project.getOwner().getId().equals(ownerId)) {
            throw new ForbiddenException("Access denied: You do not have permission to access this project");
        }

        return project;
    }
}
