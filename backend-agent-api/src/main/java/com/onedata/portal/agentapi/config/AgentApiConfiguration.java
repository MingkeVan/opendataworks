package com.onedata.portal.agentapi.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(AgentApiProperties.class)
public class AgentApiConfiguration implements WebMvcConfigurer {

    private final AgentApiAuthInterceptor agentApiAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(agentApiAuthInterceptor)
                .addPathPatterns("/v1/ai/**");
    }
}
