package com.mockapilab.modules.ai.prompt;

import com.mockapilab.modules.ai.dto.ExtractionInputType;
import org.springframework.stereotype.Component;

/**
 * Builds structured prompts for Gemini AI contract extraction.
 */
@Component
public class AiExtractionPromptBuilder {

    private static final String JSON_SCHEMA_TEMPLATE = """
            {
              \"title\": \"string (API title)\",
              \"description\": \"string (optional API overview)\",
              \"version\": \"string (e.g. '1.0.0')\",
              \"endpoints\": [
                {
                  \"path\": \"string (e.g. '/users' or '/users/{id}')\",
                  \"method\": \"string (GET, POST, PUT, DELETE, PATCH, OPTIONS, HEAD)\",
                  \"operationId\": \"string (optional unique method identifier)\",
                  \"summary\": \"string (short description of the operation)\",
                  \"description\": \"string (detailed description)\",
                  \"parameters\": [
                    {
                      \"name\": \"string\",
                      \"in\": \"string ('path', 'query', 'header', 'cookie')\",
                      \"required\": true,
                      \"description\": \"string\",
                      \"schema\": {
                        \"type\": \"string ('string', 'integer', 'number', 'boolean', 'array', 'object')\",
                        \"format\": \"string (optional: 'uuid', 'email', 'date-time', 'int32', 'int64', etc.)\",
                        \"enumConstants\": [\"string\"]
                      }
                    }
                  ],
                  \"requestBody\": {
                    \"description\": \"string\",
                    \"required\": true,
                    \"contentType\": \"application/json\",
                    \"schema\": {
                      \"type\": \"string ('object', 'array', etc.)\",
                      \"ref\": \"string (optional name of schema in schemas map)\",
                      \"properties\": {},
                      \"requiredProperties\": [\"string\"]
                    }
                  },
                  \"responses\": [
                    {
                      \"statusCode\": \"string (e.g. '200', '201', '204', '400', '404')\",
                      \"description\": \"string\",
                      \"contentType\": \"application/json\",
                      \"schema\": {
                        \"type\": \"string\",
                        \"ref\": \"string (optional reference)\",
                        \"items\": {},
                        \"properties\": {}
                      }
                    }
                  ]
                }
              ],
              \"schemas\": {
                \"ModelName\": {
                  \"type\": \"object\",
                  \"description\": \"string\",
                  \"properties\": {
                    \"propertyName\": {
                      \"type\": \"string ('string', 'integer', 'number', 'boolean', 'array', 'object')\",
                      \"format\": \"string\",
                      \"enumConstants\": [\"string\"],
                      \"items\": {},
                      \"ref\": \"string\"
                    }
                  },
                  \"requiredProperties\": [\"propertyName\"]
                }
              }
            }
            """;

    public String buildSystemPrompt() {
        return """
            You are a strict, world-class API contract extraction engine for MockAPILab.
            Your task is to analyze developer input (natural-language API descriptions or Spring Boot controller/model code) and extract a complete, formal Candidate API Contract.

            RULES AND CONSTRAINTS:
            1. Extract ONLY information that is explicitly stated or directly implied by the input.
            2. NEVER invent unrelated endpoints, arbitrary business logic, or hallucinated fields.
            3. Preserve HTTP methods (GET, POST, PUT, DELETE, PATCH) and URL paths EXACTLY as identifiable. Ensure all paths start with a leading slash '/'.
            4. Path parameters must use OpenAPI bracket notation: '/users/{id}' with a corresponding parameter where 'in'='path' and 'required'=true.
            5. Identify property data types ('string', 'integer', 'number', 'boolean', 'array', 'object'), string formats ('uuid', 'email', 'date-time', 'uri', 'phone'), and enums wherever applicable.
            6. Distinguish shared models and place them in the 'schemas' map, referencing them via 'ref' in endpoints where appropriate.
            7. For collections returning lists, use schema type 'array' with 'items' referencing the element schema.
            8. For entity creation/update, associate appropriate status codes (e.g., 201 Created for POST, 200/204 for PUT/DELETE, 200 for GET, 404 for lookup by ID).
            9. OUTPUT REQUIREMENT: Output MUST be valid, raw JSON adhering to the exact target JSON structure. Do NOT include markdown code fences (`json ... `), commentary, or surrounding text.
            """;
    }

    public String buildUserPrompt(String input, ExtractionInputType inputType) {
        String inputTypeDescription = switch (inputType) {
            case DESCRIPTION -> "NATURAL LANGUAGE API DESCRIPTION";
            case SPRING_BOOT_CODE -> "SPRING BOOT CONTROLLER AND MODEL SOURCE CODE";
        };

        return String.format("""
            Extract a candidate API contract from the following %s.

            INPUT CONTENT:
            \"\"\"
            %s
            \"\"\"

            EXPECTED JSON OUTPUT STRUCTURE:
            %s

            Return strictly raw JSON conforming to this structure:
            """, inputTypeDescription, input.trim(), JSON_SCHEMA_TEMPLATE);
    }
}