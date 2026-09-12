package com.mockapilab.modules.ai.model.candidate;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Candidate endpoint definition extracted from AI analysis.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiCandidateEndpoint(
        String path,
        String method,
        String operationId,
        String summary,
        String description,
        List<AiCandidateParameter> parameters,
        AiCandidateRequestBody requestBody,
        List<AiCandidateResponse> responses
) {
}