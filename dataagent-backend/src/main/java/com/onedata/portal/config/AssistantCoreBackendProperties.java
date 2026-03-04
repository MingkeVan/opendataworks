package com.onedata.portal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "assistant.core-backend")
public class AssistantCoreBackendProperties {

    private String baseUrl;
    private Integer connectTimeoutMs = 5000;
    private Integer readTimeoutMs = 300000;
}
