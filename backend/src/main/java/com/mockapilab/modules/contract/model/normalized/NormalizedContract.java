package com.mockapilab.modules.contract.model.normalized;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Root Normalized Contract Model for MockAPILab.
 * <p>
 * This is the canonical internal model for all API specifications ingested from
 * OpenAPI, AI controller ASTs, or natural-language definitions.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NormalizedContract(
        ContractMetadata metadata,
        List<NormalizedEndpoint> endpoints,
        Map<String, NormalizedSchema> schemas
) {
}
