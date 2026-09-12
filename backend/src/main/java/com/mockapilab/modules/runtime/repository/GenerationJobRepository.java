package com.mockapilab.modules.runtime.repository;

import com.mockapilab.modules.runtime.model.GenerationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for durable GenerationJob records.
 */
@Repository
public interface GenerationJobRepository extends JpaRepository<GenerationJob, UUID> {

    Optional<GenerationJob> findByIdAndRuntimeId(UUID id, UUID runtimeId);

    Optional<GenerationJob> findByIdAndProjectId(UUID id, UUID projectId);

    List<GenerationJob> findByRuntimeIdOrderByCreatedAtDesc(UUID runtimeId);

    List<GenerationJob> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
