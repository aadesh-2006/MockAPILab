package com.mockapilab.modules.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockapilab.modules.ai.config.AiProperties;
import com.mockapilab.modules.ai.dto.ExtractionInputType;
import com.mockapilab.modules.ai.exception.AiConfigurationException;
import com.mockapilab.modules.ai.exception.AiProviderException;
import com.mockapilab.modules.ai.model.candidate.AiCandidateContract;
import com.mockapilab.modules.ai.prompt.AiExtractionPromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * AI Provider implementation communicating with Google Gemini Generative Language REST API.
 */
@Component
public class GeminiAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiAiProvider.class);

    private final AiProperties aiProperties;
    private final AiExtractionPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Autowired
    public GeminiAiProvider(
            AiProperties aiProperties,
            AiExtractionPromptBuilder promptBuilder,
            ObjectMapper objectMapper
    ) {
        this(aiProperties, promptBuilder, objectMapper, null);
    }

    public GeminiAiProvider(
            AiProperties aiProperties,
            AiExtractionPromptBuilder promptBuilder,
            ObjectMapper objectMapper,
            RestClient restClient
    ) {
        this.aiProperties = aiProperties;
        this.promptBuilder = promptBuilder;
        this.objectMapper = objectMapper;

        if (restClient != null) {
            this.restClient = restClient;
        } else {
            int timeout = aiProperties.getGemini().getTimeoutSeconds();
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(timeout));
            requestFactory.setReadTimeout(Duration.ofSeconds(timeout));
            this.restClient = RestClient.builder()
                    .requestFactory(requestFactory)
                    .build();
        }
    }

    @Override
    public String getProviderName() {
        return "gemini";
    }

    @Override
    public AiCandidateContract extractCandidateContract(String input, ExtractionInputType inputType) {
        String apiKey = aiProperties.getGemini().getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new AiConfigurationException(
                    "Gemini AI provider is not configured: missing API key. Please configure GEMINI_API_KEY environment variable or mockapilab.ai.gemini.api-key property."
            );
        }

        String model = aiProperties.getGemini().getModel();
        String baseUrl = aiProperties.getGemini().getBaseUrl();
        String systemInstruction = promptBuilder.buildSystemPrompt();
        String userPrompt = promptBuilder.buildUserPrompt(input, inputType);

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of(
                                "role", "user",
                                "parts", List.of(
                                        Map.of("text", systemInstruction + "\n\n" + userPrompt)
                                )
                        )
                ),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "temperature", 0.1
                )
        );

        String uri = String.format("%s/models/%s:generateContent?key=%s", baseUrl, model, apiKey);

        log.info("Dispatching AI contract extraction request to Gemini model: {} for input type: {}", model, inputType);

        try {
            String rawResponse = restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            if (!StringUtils.hasText(rawResponse)) {
                throw new AiProviderException("Gemini API returned an empty response");
            }

            return parseGeminiResponse(rawResponse);
        } catch (RestClientResponseException ex) {
            int statusCode = ex.getStatusCode().value();
            log.error("Gemini API returned HTTP status {}: {}", statusCode, ex.getResponseBodyAsString());
            if (statusCode == 429) {
                throw new AiProviderException("Gemini AI rate limit exceeded or quota exhausted. Please retry later.", ex);
            } else if (statusCode == 401 || statusCode == 403) {
                throw new AiProviderException("Gemini API authentication failed: invalid or unauthorized API key.", ex);
            } else {
                throw new AiProviderException("Gemini API error (HTTP " + statusCode + "): " + ex.getStatusText(), ex);
            }
        } catch (AiConfigurationException | AiProviderException e) {
            throw e;
        } catch (Exception ex) {
            log.error("Gemini AI extraction communication failure: {}", ex.getMessage(), ex);
            throw new AiProviderException("Gemini AI extraction service communication failure: " + ex.getMessage(), ex);
        }
    }

    private AiCandidateContract parseGeminiResponse(String rawResponse) {
        try {
            JsonNode root = objectMapper.readTree(rawResponse);
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                throw new AiProviderException("Gemini response contained no candidate generation choices");
            }

            JsonNode firstCandidate = candidates.get(0);
            JsonNode parts = firstCandidate.path("content").path("parts");
            if (!parts.isArray() || parts.isEmpty()) {
                throw new AiProviderException("Gemini response contained no text content parts");
            }

            String candidateText = parts.get(0).path("text").asText();
            if (!StringUtils.hasText(candidateText)) {
                throw new AiProviderException("Gemini candidate content part was blank");
            }

            String cleanedJson = stripMarkdownCodeFences(candidateText);
            return objectMapper.readValue(cleanedJson, AiCandidateContract.class);
        } catch (AiProviderException e) {
            throw e;
        } catch (Exception ex) {
            log.error("Failed to parse structured JSON from Gemini response: {}", ex.getMessage());
            throw new AiProviderException("Failed to parse structured candidate contract from Gemini AI response: " + ex.getMessage(), ex);
        }
    }

    private String stripMarkdownCodeFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }
}