package com.mockapilab.modules.ai.model.candidate;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Candidate endpoint parameter extracted from AI analysis.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiCandidateParameter(
        String name,
        String in,
        Boolean required,
        String description,
        AiCandidateSchema schema
) {
}