package com.mockapilab.modules.contract.drift.dto;

import com.mockapilab.modules.contract.drift.model.ContractDriftChange;
import com.mockapilab.modules.contract.drift.model.DriftChangeType;
import com.mockapilab.modules.contract.drift.model.DriftClassification;
import com.mockapilab.modules.contract.drift.model.DriftSeverity;

import java.util.UUID;

public record DriftChangeResponse(
        UUID id,
        DriftChangeType changeType,
        DriftClassification classification,
        DriftSeverity severity,
        String path,
        String method,
        String location,
        String oldValue,
        String newValue,
        String message
) {
    public static DriftChangeResponse fromEntity(ContractDriftChange change) {
        return new DriftChangeResponse(
                change.getId(),
                change.getChangeType(),
                change.getClassification(),
                change.getSeverity(),
                change.getPath(),
                change.getMethod(),
                change.getLocation(),
                change.getOldValue(),
                change.getNewValue(),
                change.getMessage()
        );
    }
}
