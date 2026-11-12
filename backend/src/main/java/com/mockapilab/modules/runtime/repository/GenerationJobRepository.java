package com.mockapilab.modules.runtime.repository;

import com.mockapilab.modules.runtime.model.GenerationJob;
import com.mockapilab.modules.runtime.model.GenerationJobStatus;
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
 * Spring Data JPA repository for durable GenerationJob records.
 */
@Repository
public interface GenerationJobRepository extends JpaRepository<GenerationJob, UUID> {

    Optional<GenerationJob> findByIdAndRuntimeId(UUID id, UUID runtimeId);

    Optional<GenerationJob> findByIdAndProjectId(UUID id, UUID projectId);

    List<GenerationJob> findByRuntimeIdOrderByCreatedAtDesc(UUID runtimeId);

    List<GenerationJob> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    boolean existsByIdAndStatus(UUID id, GenerationJobStatus status);

    @Query(value = "SELECT NEXTVAL('generation_job_fencing_seq')", nativeQuery = true)
    long nextFencingToken();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE GenerationJob j
            SET j.status = com.mockapilab.modules.runtime.model.GenerationJobStatus.RUNNING,
                j.startedAt = :startedAt
            WHERE j.id = :id
              AND j.status = com.mockapilab.modules.runtime.model.GenerationJobStatus.QUEUED
            """)
    int claimQueuedJob(@Param("id") UUID id, @Param("startedAt") Instant startedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE GenerationJob j
            SET j.status = com.mockapilab.modules.runtime.model.GenerationJobStatus.FAILED,
                j.errorMessage = :errorMessage,
                j.completedAt = :completedAt
            WHERE j.status = com.mockapilab.modules.runtime.model.GenerationJobStatus.RUNNING
              AND j.startedAt < :staleBefore
            """)
    int failStaleRunningJobs(
            @Param("staleBefore") Instant staleBefore,
            @Param("completedAt") Instant completedAt,
            @Param("errorMessage") String errorMessage
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE GenerationJob j
            SET j.effectiveSeed = :effectiveSeed,
                j.status = com.mockapilab.modules.runtime.model.GenerationJobStatus.COMPLETED,
                j.completedAt = :completedAt
            WHERE j.id = :id
              AND j.status = com.mockapilab.modules.runtime.model.GenerationJobStatus.RUNNING
            """)
    int completeRunningJob(
            @Param("id") UUID id,
            @Param("effectiveSeed") long effectiveSeed,
            @Param("completedAt") Instant completedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE GenerationJob j
            SET j.status = com.mockapilab.modules.runtime.model.GenerationJobStatus.FAILED,
                j.errorMessage = :errorMessage,
                j.completedAt = :completedAt
            WHERE j.id = :id
              AND j.status = com.mockapilab.modules.runtime.model.GenerationJobStatus.RUNNING
            """)
    int failRunningJob(
            @Param("id") UUID id,
            @Param("errorMessage") String errorMessage,
            @Param("completedAt") Instant completedAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE GenerationJob j
            SET j.status = com.mockapilab.modules.runtime.model.GenerationJobStatus.FAILED,
                j.errorMessage = :errorMessage,
                j.completedAt = :completedAt
            WHERE j.runtime.id = :runtimeId
              AND j.status IN (
                  com.mockapilab.modules.runtime.model.GenerationJobStatus.QUEUED,
                  com.mockapilab.modules.runtime.model.GenerationJobStatus.RUNNING
              )
            """)
    int failActiveJobsForRuntime(
            @Param("runtimeId") UUID runtimeId,
            @Param("completedAt") Instant completedAt,
            @Param("errorMessage") String errorMessage
    );
}
