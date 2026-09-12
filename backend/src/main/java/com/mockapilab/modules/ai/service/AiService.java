package com.mockapilab.modules.ai.service;

import com.mockapilab.modules.ai.converter.AiCandidateConverter;
import com.mockapilab.modules.ai.dto.ExtractionInputType;
import com.mockapilab.modules.ai.model.candidate.AiCandidateContract;
import com.mockapilab.modules.ai.provider.AiProvider;
import com.mockapilab.modules.ai.validation.AiCandidateValidator;
import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * High-level orchestration service for AI-assisted contract extraction, validation, and normalization.
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final AiProvider aiProvider;
    private final AiCandidateValidator candidateValidator;
    private final AiCandidateConverter candidateConverter;

    public AiService(
            AiProvider aiProvider,
            AiCandidateValidator candidateValidator,
            AiCandidateConverter candidateConverter
    ) {
        this.aiProvider = aiProvider;
        this.candidateValidator = candidateValidator;
        this.candidateConverter = candidateConverter;
    }

    /**
     * Extracts a candidate contract via AI provider, deterministically validates it, and normalizes it.
     *
     * @param input the raw untrusted input (natural-language or code)
     * @param inputType the input format type
     * @return extraction result containing the validated candidate and canonical normalized contract
     */
    public AiExtractionResult extractAndNormalize(String input, ExtractionInputType inputType) {
        log.info("Beginning AI contract extraction using provider '{}' for input type: {}", aiProvider.getProviderName(), inputType);

        // 1. Extract Candidate via AI Provider
        AiCandidateContract candidate = aiProvider.extractCandidateContract(input, inputType);

        // 2. Deterministic Validation
        log.debug("Validating extracted candidate contract: {}", candidate.title());
        candidateValidator.validate(candidate);

        // 3. Convert to Canonical NormalizedContract
        NormalizedContract normalizedContract = candidateConverter.convert(candidate);

        log.info("Successfully extracted and normalized contract '{}' with {} endpoints and {} schemas",
                normalizedContract.metadata().title(),
                normalizedContract.endpoints().size(),
                normalizedContract.schemas() != null ? normalizedContract.schemas().size() : 0);

        return new AiExtractionResult(candidate, normalizedContract);
    }

    public record AiExtractionResult(
            AiCandidateContract candidate,
            NormalizedContract normalizedContract
    ) {
    }
}