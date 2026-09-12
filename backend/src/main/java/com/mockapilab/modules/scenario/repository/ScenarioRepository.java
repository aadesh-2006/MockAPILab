package com.mockapilab.modules.scenario.repository;

import com.mockapilab.modules.scenario.model.Scenario;
import com.mockapilab.modules.scenario.model.ScenarioStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for Scenario entities.
 */
@Repository
public interface ScenarioRepository extends JpaRepository<Scenario, UUID> {

    List<Scenario> findByRuntimeIdOrderByCreatedAtAsc(UUID runtimeId);

    List<Scenario> findByRuntimeIdAndStatusOrderByCreatedAtAsc(UUID runtimeId, ScenarioStatus status);

    Optional<Scenario> findByIdAndRuntimeId(UUID id, UUID runtimeId);

    Optional<Scenario> findByIdAndProjectId(UUID id, UUID projectId);

    /**
     * Atomically increments the execution count if max_executions is null or current count is under the limit.
     *
     * @param id  the scenario id
     * @param now the update timestamp
     * @return number of affected rows (1 if execution reserved, 0 if limit exceeded or not found)
     */
    @Modifying
    @Query("UPDATE Scenario s SET s.executionCount = s.executionCount + 1, s.updatedAt = :now WHERE s.id = :id AND (s.maxExecutions IS NULL OR s.executionCount < s.maxExecutions)")
    int incrementExecutionCountIfUnderLimit(@Param("id") UUID id, @Param("now") Instant now);
}
