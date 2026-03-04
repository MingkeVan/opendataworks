package com.onedata.portal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "assistant.startup")
public class AssistantStartupProperties {

    private boolean failFast = true;
}
