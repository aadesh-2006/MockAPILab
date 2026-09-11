package com.mockapilab.modules.contract.model.normalized;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NormalizedHeader(
        String name,
        String description,
        NormalizedSchema schema
) {
}
