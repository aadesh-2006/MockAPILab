package com.mockapilab.modules.ai.model.candidate;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Candidate endpoint request body extracted from AI analysis.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiCandidateRequestBody(
        String description,
        Boolean required,
        String contentType,
        AiCandidateSchema schema
) {
}