package com.onedata.portal.service.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.context.UserContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@RequiredArgsConstructor
public class ToolGatewayClient {

    private final WebClient assistantCoreWebClient;
    private final ObjectMapper objectMapper;

    public <T> T postForData(String path, Object request, Class<T> dataType) {
        JsonNode root = assistantCoreWebClient.post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .headers(this::injectUserHeaders)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block();

        if (root == null) {
            throw new RuntimeException("核心查询服务返回为空");
        }

        int code = root.path("code").asInt(500);
        if (code != 200) {
            throw new RuntimeException(root.path("message").asText("核心查询服务调用失败"));
        }

        JsonNode dataNode = root.get("data");
        if (dataNode == null || dataNode.isNull()) {
            return null;
        }
        return objectMapper.convertValue(dataNode, dataType);
    }

    private void injectUserHeaders(HttpHeaders headers) {
        String userId = UserContextHolder.getCurrentUserId();
        if (StringUtils.hasText(userId)) {
            headers.set("X-User-Id", userId);
        }
        String username = UserContextHolder.getCurrentUsername();
        if (StringUtils.hasText(username)) {
            headers.set("X-Username", username);
        }
    }
}
