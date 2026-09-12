package com.mockapilab.modules.ai.provider;

import com.mockapilab.modules.ai.dto.ExtractionInputType;
import com.mockapilab.modules.ai.model.candidate.AiCandidateContract;

/**
 * Pluggable AI provider abstraction for extracting candidate API contracts.
 */
public interface AiProvider {

    /**
     * Extracts a candidate API contract from informal description or source code.
     *
     * @param input the raw content to analyze (untrusted text)
     * @param inputType the type of content (DESCRIPTION or SPRING_BOOT_CODE)
     * @return structured candidate contract
     */
    AiCandidateContract extractCandidateContract(String input, ExtractionInputType inputType);

    /**
     * @return the provider name (e.g., 'gemini')
     */
    String getProviderName();
}