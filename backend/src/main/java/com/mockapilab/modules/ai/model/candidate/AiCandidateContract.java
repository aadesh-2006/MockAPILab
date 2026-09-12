package com.mockapilab.modules.ai.model.candidate;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Candidate API contract extracted from AI analysis prior to deterministic validation and normalization.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiCandidateContract(
        String title,
        String description,
        String version,
        List<AiCandidateEndpoint> endpoints,
        Map<String, AiCandidateSchema> schemas
) {
}