package com.mockapilab.modules.runtime.repository;

import com.mockapilab.modules.runtime.model.GenerationJobOutbox;
import com.mockapilab.modules.runtime.model.GenerationJobOutboxStatus;
import org.springframework.data.domain.Pageable;
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
 * Spring Data JPA repository for durable GenerationJobOutbox records with atomic claiming queries.
 */
@Repository
public interface GenerationJobOutboxRepository extends JpaRepository<GenerationJobOutbox, UUID> {

    Optional<GenerationJobOutbox> findByJobId(UUID jobId);

    List<GenerationJobOutbox> findByStatusOrderByCreatedAtAsc(GenerationJobOutboxStatus status);

    @Query("""
            SELECT o.id FROM GenerationJobOutbox o
            WHERE o.status = com.mockapilab.modules.runtime.model.GenerationJobOutboxStatus.PENDING
              AND o.nextAttemptAt <= :now
            ORDER BY o.createdAt ASC
            """)
    List<UUID> findReadyPendingIds(@Param("now") Instant now, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE GenerationJobOutbox o
            SET o.status = com.mockapilab.modules.runtime.model.GenerationJobOutboxStatus.PROCESSING,
                o.lockedBy = :lockedBy,
                o.lockedAt = :lockedAt
            WHERE o.id = :id
              AND o.status = com.mockapilab.modules.runtime.model.GenerationJobOutboxStatus.PENDING
              AND o.nextAttemptAt <= :now
            """)
    int claimPending(
            @Param("id") UUID id,
            @Param("lockedBy") String lockedBy,
            @Param("lockedAt") Instant lockedAt,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE GenerationJobOutbox o
            SET o.status = com.mockapilab.modules.runtime.model.GenerationJobOutboxStatus.PENDING,
                o.lockedBy = null,
                o.lockedAt = null
            WHERE o.status = com.mockapilab.modules.runtime.model.GenerationJobOutboxStatus.PROCESSING
              AND o.lockedAt < :staleBefore
            """)
    int reclaimStaleProcessing(@Param("staleBefore") Instant staleBefore);
}
