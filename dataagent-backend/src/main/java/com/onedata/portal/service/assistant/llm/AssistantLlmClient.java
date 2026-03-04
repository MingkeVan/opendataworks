package com.onedata.portal.service.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.config.AssistantLlmChatProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AssistantLlmClient {

    private final AssistantLlmChatProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    public String completeJson(String systemPrompt, String userPrompt) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("LLM 未启用");
        }
        String apiKey = resolveApiKey();

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("model", properties.getModel());
        payload.put("temperature", properties.getTemperature());
        payload.put("max_tokens", properties.getMaxTokens());
        payload.put("messages", buildMessages(systemPrompt, userPrompt));

        String response = webClientBuilder.clone()
            .baseUrl(properties.getBaseUrl())
            .build()
            .post()
            .uri(resolveCompletionsPath())
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue(payload)
            .retrieve()
            .onStatus(HttpStatusCode::isError, result ->
                result.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMap(body -> Mono.error(new IllegalStateException(
                        "LLM 请求失败, status=" + result.statusCode().value() + ", body=" + abbreviate(body)))))
            .bodyToMono(String.class)
            .timeout(Duration.ofMillis(resolveTimeoutMs()))
            .block();

        if (!StringUtils.hasText(response)) {
            throw new IllegalStateException("LLM 响应为空");
        }

        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (contentNode.isArray()) {
                StringBuilder builder = new StringBuilder();
                for (JsonNode item : contentNode) {
                    String text = item.path("text").asText(null);
                    if (StringUtils.hasText(text)) {
                        builder.append(text).append('\n');
                    }
                }
                String merged = builder.toString().trim();
                if (StringUtils.hasText(merged)) {
                    return merged;
                }
            }
            String content = contentNode.asText(null);
            if (!StringUtils.hasText(content)) {
                throw new IllegalStateException("LLM choices[0].message.content 为空");
            }
            return content;
        } catch (Exception ex) {
            throw new IllegalStateException("解析 LLM 响应失败: " + ex.getMessage(), ex);
        }
    }

    private List<Map<String, Object>> buildMessages(String systemPrompt, String userPrompt) {
        List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        return messages;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private String resolveCompletionsPath() {
        String base = properties.getBaseUrl();
        if (!StringUtils.hasText(base)) {
            return "/v1/chat/completions";
        }
        String normalized = base.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("/v1") || normalized.contains("/v1/")) {
            return "/chat/completions";
        }
        return "/v1/chat/completions";
    }

    private String resolveApiKey() {
        String envName = properties.getApiKeyEnv();
        if (!StringUtils.hasText(envName)) {
            throw new IllegalStateException("LLM api-key-env 未配置");
        }
        String apiKey = System.getenv(envName);
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("缺少 LLM API Key，请配置环境变量: " + envName);
        }
        return apiKey;
    }

    private int resolveTimeoutMs() {
        Integer timeout = properties.getTimeoutMs();
        if (timeout == null || timeout <= 0) {
            return 30000;
        }
        return timeout;
    }

    private String abbreviate(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String value = text.trim();
        if (value.length() <= 500) {
            return value;
        }
        return value.substring(0, 500) + "...";
    }
}
