package com.mockapilab.modules.contract.repository;

import com.mockapilab.modules.contract.model.Contract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContractRepository extends JpaRepository<Contract, UUID> {

    List<Contract> findAllByProjectIdOrderByCreatedAtDesc(UUID projectId);

    Optional<Contract> findByIdAndProjectId(UUID id, UUID projectId);

    boolean existsByIdAndProjectId(UUID id, UUID projectId);
}
