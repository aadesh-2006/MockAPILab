package com.mockapilab.modules.runtime.repository;

import com.mockapilab.modules.runtime.model.MockRuntime;
import com.mockapilab.modules.runtime.model.MockRuntimeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MockRuntimeRepository extends JpaRepository<MockRuntime, UUID> {

    List<MockRuntime> findByProjectId(UUID projectId);

    Optional<MockRuntime> findByIdAndProjectId(UUID id, UUID projectId);

    List<MockRuntime> findByContractVersionId(UUID contractVersionId);

    List<MockRuntime> findByStatus(MockRuntimeStatus status);
}