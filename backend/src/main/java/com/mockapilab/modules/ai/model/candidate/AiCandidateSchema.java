package com.mockapilab.modules.ai.model.candidate;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Candidate schema definition extracted from AI analysis.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiCandidateSchema(
        String type,
        String format,
        String description,
        Boolean nullable,
        Object defaultValue,
        Object example,
        List<String> enumConstants,
        Map<String, AiCandidateSchema> properties,
        List<String> requiredProperties,
        AiCandidateSchema items,
        String ref,
        Double minimum,
        Double maximum,
        Integer minLength,
        Integer maxLength,
        String pattern
) {
}