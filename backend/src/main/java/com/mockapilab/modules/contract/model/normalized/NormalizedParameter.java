package com.mockapilab.modules.contract.model.normalized;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NormalizedParameter(
        String name,
        ParameterLocation location,
        boolean required,
        String description,
        NormalizedSchema schema
) {
}
