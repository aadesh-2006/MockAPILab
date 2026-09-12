package com.mockapilab.modules.contract.drift.repository;

import com.mockapilab.modules.contract.drift.model.ContractDriftReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContractDriftReportRepository extends JpaRepository<ContractDriftReport, UUID> {

    @Query("SELECT r FROM ContractDriftReport r LEFT JOIN FETCH r.changes WHERE r.contract.id = :contractId ORDER BY r.createdAt DESC")
    List<ContractDriftReport> findAllByContractIdOrderByCreatedAtDesc(@Param("contractId") UUID contractId);

    @Query("SELECT r FROM ContractDriftReport r LEFT JOIN FETCH r.changes WHERE r.id = :id AND r.contract.id = :contractId")
    Optional<ContractDriftReport> findByIdAndContractId(@Param("id") UUID id, @Param("contractId") UUID contractId);
}
