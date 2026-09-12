package com.mockapilab.modules.ai.model.candidate;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Candidate endpoint response definition extracted from AI analysis.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiCandidateResponse(
        String statusCode,
        String description,
        String contentType,
        AiCandidateSchema schema
) {
}