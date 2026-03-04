package com.onedata.portal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "assistant.llm.chat")
public class AssistantLlmChatProperties {

    private boolean enabled = true;
    private String baseUrl;
    private String model;
    private String apiKeyEnv = "AQS_LLM_API_KEY";
    private boolean strict = true;
    private Double temperature = 0.1D;
    private Integer maxTokens = 1200;
    private Integer timeoutMs = 30000;
}
