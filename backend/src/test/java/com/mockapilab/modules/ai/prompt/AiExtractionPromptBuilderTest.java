package com.mockapilab.modules.ai.prompt;

import com.mockapilab.modules.ai.dto.ExtractionInputType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AI Extraction Prompt Builder Tests")
class AiExtractionPromptBuilderTest {

    private AiExtractionPromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        promptBuilder = new AiExtractionPromptBuilder();
    }

    @Test
    @DisplayName("System prompt should contain strict extraction rules and JSON requirements")
    void buildSystemPrompt_containsStrictRules() {
        String systemPrompt = promptBuilder.buildSystemPrompt();

        assertThat(systemPrompt).isNotBlank();
        assertThat(systemPrompt).contains("MockAPILab");
        assertThat(systemPrompt).contains("NEVER invent unrelated endpoints");
        assertThat(systemPrompt).contains("Preserve HTTP methods");
        assertThat(systemPrompt).contains("valid, raw JSON");
    }

    @Test
    @DisplayName("User prompt for Description should include natural language guidance")
    void buildUserPrompt_forDescription() {
        String input = "Create a products API with GET /products and POST /products.";
        String userPrompt = promptBuilder.buildUserPrompt(input, ExtractionInputType.DESCRIPTION);

        assertThat(userPrompt).contains("NATURAL LANGUAGE API DESCRIPTION");
        assertThat(userPrompt).contains(input);
        assertThat(userPrompt).contains("EXPECTED JSON OUTPUT STRUCTURE");
    }

    @Test
    @DisplayName("User prompt for Spring Boot Code should include code guidance")
    void buildUserPrompt_forSpringBootCode() {
        String code = "@RestController class UserController { @GetMapping(\"/users\") public List<User> list() {} }";
        String userPrompt = promptBuilder.buildUserPrompt(code, ExtractionInputType.SPRING_BOOT_CODE);

        assertThat(userPrompt).contains("SPRING BOOT CONTROLLER AND MODEL SOURCE CODE");
        assertThat(userPrompt).contains(code);
        assertThat(userPrompt).contains("EXPECTED JSON OUTPUT STRUCTURE");
    }
}