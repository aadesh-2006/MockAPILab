package com.mockapilab.modules.contract.model.normalized;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NormalizedRequestBody(
        String description,
        boolean required,
        Map<String, NormalizedMediaType> contentTypes
) {
}
