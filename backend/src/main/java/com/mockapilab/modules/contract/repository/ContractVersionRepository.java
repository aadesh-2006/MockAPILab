package com.mockapilab.modules.contract.repository;

import com.mockapilab.modules.contract.model.ContractVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContractVersionRepository extends JpaRepository<ContractVersion, UUID> {

    List<ContractVersion> findAllByContractIdOrderByVersionNumberDesc(UUID contractId);

    Optional<ContractVersion> findByContractIdAndVersionNumber(UUID contractId, int versionNumber);

    Optional<ContractVersion> findFirstByContractIdOrderByVersionNumberDesc(UUID contractId);
}
