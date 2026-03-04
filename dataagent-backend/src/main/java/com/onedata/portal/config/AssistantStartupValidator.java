package com.onedata.portal.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.Socket;

@Component
@RequiredArgsConstructor
public class AssistantStartupValidator implements ApplicationRunner {

    private final AssistantCoreBackendProperties coreBackendProperties;
    private final AssistantLlmChatProperties llmChatProperties;
    private final AssistantStartupProperties startupProperties;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!startupProperties.isFailFast()) {
            return;
        }
        validateLlmConfig();
        validateCoreBackend();
    }

    private void validateLlmConfig() {
        if (!llmChatProperties.isEnabled()) {
            return;
        }
        if (!StringUtils.hasText(llmChatProperties.getBaseUrl())) {
            throw new IllegalStateException("assistant.llm.chat.base-url 未配置");
        }
        if (!StringUtils.hasText(llmChatProperties.getModel())) {
            throw new IllegalStateException("assistant.llm.chat.model 未配置");
        }
        if (!StringUtils.hasText(llmChatProperties.getApiKeyEnv())) {
            throw new IllegalStateException("assistant.llm.chat.api-key-env 未配置");
        }
        String envName = llmChatProperties.getApiKeyEnv();
        String apiKey = System.getenv(envName);
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("缺少 LLM API Key，请配置环境变量: " + envName);
        }
    }

    private void validateCoreBackend() {
        if (!StringUtils.hasText(coreBackendProperties.getBaseUrl())) {
            throw new IllegalStateException("assistant.core-backend.base-url 未配置");
        }

        URI uri;
        try {
            uri = new URI(coreBackendProperties.getBaseUrl());
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("assistant.core-backend.base-url 非法: " + coreBackendProperties.getBaseUrl(), ex);
        }

        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw new IllegalStateException("assistant.core-backend.base-url 缺少 host: " + coreBackendProperties.getBaseUrl());
        }
        int port = uri.getPort();
        if (port <= 0) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }

        int timeout = coreBackendProperties.getConnectTimeoutMs() == null ? 5000 : coreBackendProperties.getConnectTimeoutMs();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
        } catch (Exception ex) {
            throw new IllegalStateException(
                "core backend 连通性检查失败: " + host + ":" + port + ", baseUrl=" + coreBackendProperties.getBaseUrl(), ex);
        }
    }
}
