package com.mockapilab.modules.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for AI providers in MockAPILab.
 */
@Configuration
@ConfigurationProperties(prefix = "mockapilab.ai")
public class AiProperties {

    private Gemini gemini = new Gemini();

    public Gemini getGemini() {
        return gemini;
    }

    public void setGemini(Gemini gemini) {
        this.gemini = gemini;
    }

    public static class Gemini {
        private String apiKey = "";
        private String model = "gemini-1.5-flash";
        private int timeoutSeconds = 30;
        private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}