package com.mockapilab.modules.contract.model.normalized;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NormalizedResponse(
        String statusCode,
        String description,
        Map<String, NormalizedMediaType> contentTypes,
        Map<String, NormalizedHeader> headers
) {
}
