package com.mockapilab.modules.contract.model.normalized;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NormalizedEndpoint(
        String path,
        String method,
        String operationId,
        String summary,
        String description,
        List<NormalizedParameter> parameters,
        NormalizedRequestBody requestBody,
        List<NormalizedResponse> responses
) {
}
