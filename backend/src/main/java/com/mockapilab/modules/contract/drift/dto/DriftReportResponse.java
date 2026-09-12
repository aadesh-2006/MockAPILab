package com.mockapilab.modules.contract.drift.dto;

import com.mockapilab.modules.contract.drift.model.ContractDriftReport;
import com.mockapilab.modules.contract.drift.model.DriftSeverity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DriftReportResponse(
        UUID id,
        UUID projectId,
        UUID contractId,
        UUID fromVersionId,
        UUID toVersionId,
        int fromVersionNumber,
        int toVersionNumber,
        int breakingChangeCount,
        int nonBreakingChangeCount,
        int informationalChangeCount,
        DriftSeverity overallSeverity,
        Instant createdAt,
        List<DriftChangeResponse> changes
) {
    public static DriftReportResponse fromEntity(ContractDriftReport report) {
        List<DriftChangeResponse> changeResponses = report.getChanges() != null
                ? report.getChanges().stream().map(DriftChangeResponse::fromEntity).toList()
                : List.of();

        return new DriftReportResponse(
                report.getId(),
                report.getProject().getId(),
                report.getContract().getId(),
                report.getFromVersion().getId(),
                report.getToVersion().getId(),
                report.getFromVersionNumber(),
                report.getToVersionNumber(),
                report.getBreakingChangeCount(),
                report.getNonBreakingChangeCount(),
                report.getInformationalChangeCount(),
                report.getOverallSeverity(),
                report.getCreatedAt(),
                changeResponses
        );
    }
}
