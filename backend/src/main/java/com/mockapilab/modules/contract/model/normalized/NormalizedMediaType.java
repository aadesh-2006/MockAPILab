package com.mockapilab.modules.contract.model.normalized;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NormalizedMediaType(
        NormalizedSchema schema,
        Object example
) {
    public static NormalizedMediaType of(NormalizedSchema schema) {
        return new NormalizedMediaType(schema, null);
    }
}
