package com.mockapilab.modules.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockapilab.modules.ai.config.AiProperties;
import com.mockapilab.modules.ai.dto.ExtractionInputType;
import com.mockapilab.modules.ai.exception.AiConfigurationException;
import com.mockapilab.modules.ai.exception.AiProviderException;
import com.mockapilab.modules.ai.model.candidate.AiCandidateContract;
import com.mockapilab.modules.ai.prompt.AiExtractionPromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("Gemini AI Provider Tests")
class GeminiAiProviderTest {

    private AiProperties properties;
    private AiExtractionPromptBuilder promptBuilder;
    private ObjectMapper objectMapper;
    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private GeminiAiProvider provider;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        properties.getGemini().setApiKey("test-api-key");
        properties.getGemini().setModel("gemini-1.5-flash");
        properties.getGemini().setBaseUrl("https://generativelanguage.googleapis.com/v1beta");
        properties.getGemini().setTimeoutSeconds(5);

        promptBuilder = new AiExtractionPromptBuilder();
        objectMapper = new ObjectMapper();

        restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        provider = new GeminiAiProvider(properties, promptBuilder, objectMapper, restClientBuilder.build());
    }

    @Test
    @DisplayName("Throws AiConfigurationException when API key is blank")
    void extract_missingApiKey_throwsConfigurationException() {
        properties.getGemini().setApiKey("");
        GeminiAiProvider unconfiguredProvider = new GeminiAiProvider(properties, promptBuilder, objectMapper, restClientBuilder.build());

        assertThatThrownBy(() -> unconfiguredProvider.extractCandidateContract("GET /users", ExtractionInputType.DESCRIPTION))
                .isInstanceOf(AiConfigurationException.class)
                .hasMessageContaining("Gemini AI provider is not configured");
    }

    @Test
    @DisplayName("Parses valid Gemini JSON response successfully")
    void extract_validGeminiResponse_parsesCandidate() {
        String geminiResponseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "{\\\"title\\\":\\\"Users API\\\",\\\"version\\\":\\\"1.0.0\\\",\\\"endpoints\\\":[{\\\"path\\\":\\\"/users\\\",\\\"method\\\":\\\"GET\\\",\\\"responses\\\":[{\\\"statusCode\\\":\\\"200\\\"}]}],\\\"schemas\\\":{}}"
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=test-api-key"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(geminiResponseBody, MediaType.APPLICATION_JSON));

        AiCandidateContract candidate = provider.extractCandidateContract("GET /users", ExtractionInputType.DESCRIPTION);

        mockServer.verify();
        assertThat(candidate).isNotNull();
        assertThat(candidate.title()).isEqualTo("Users API");
        assertThat(candidate.endpoints()).hasSize(1);
        assertThat(candidate.endpoints().get(0).path()).isEqualTo("/users");
        assertThat(candidate.endpoints().get(0).method()).isEqualTo("GET");
    }

    @Test
    @DisplayName("Handles Gemini JSON enclosed in Markdown code fences")
    void extract_markdownCodeFences_cleansAndParses() {
        String geminiResponseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "```json\\n{\\\"title\\\":\\\"Users API\\\",\\\"endpoints\\\":[{\\\"path\\\":\\\"/users\\\",\\\"method\\\":\\\"GET\\\",\\\"responses\\\":[{\\\"statusCode\\\":\\\"200\\\"}]}]}\\n```"
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=test-api-key"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(geminiResponseBody, MediaType.APPLICATION_JSON));

        AiCandidateContract candidate = provider.extractCandidateContract("GET /users", ExtractionInputType.DESCRIPTION);

        mockServer.verify();
        assertThat(candidate).isNotNull();
        assertThat(candidate.title()).isEqualTo("Users API");
    }

    @Test
    @DisplayName("Translates HTTP 429 Rate Limit to AiProviderException")
    void extract_rateLimit_throwsAiProviderException() {
        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=test-api-key"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> provider.extractCandidateContract("GET /users", ExtractionInputType.DESCRIPTION))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("rate limit exceeded");
    }

    @Test
    @DisplayName("Translates malformed JSON response to AiProviderException")
    void extract_malformedJson_throwsAiProviderException() {
        String geminiResponseBody = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {
                            "text": "NOT_A_VALID_JSON_STRING"
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=test-api-key"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(geminiResponseBody, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.extractCandidateContract("GET /users", ExtractionInputType.DESCRIPTION))
                .isInstanceOf(AiProviderException.class)
                .hasMessageContaining("Failed to parse structured candidate contract");
    }
}