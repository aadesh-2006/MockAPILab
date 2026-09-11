package com.mockapilab.modules.runtime.dto;

import java.util.UUID;

public record GenerateDataResponse(
        UUID runtimeId,
        String collection,
        int generatedCount,
        long seed
) {
}
