package com.onedata.portal.service.assistant.nl2lf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.config.AssistantLlmChatProperties;
import com.onedata.portal.dto.assistant.AssistantContextDTO;
import com.onedata.portal.service.assistant.llm.AssistantLlmClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmLfPlannerTest {

    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        mockWebServer.shutdown();
    }

    @Test
    void shouldUseLlmForNaturalLanguageQuestion() throws Exception {
        mockWebServer.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("{\"id\":\"chatcmpl-test\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"intent\\\":\\\"bi_query\\\",\\\"sqlDraft\\\":\\\"SELECT dt, SUM(order_amount) AS total_amount FROM dwd_order GROUP BY dt ORDER BY dt LIMIT 200\\\",\\\"confidence\\\":{\\\"overall\\\":0.92}}\"}}]}"));

        ObjectMapper objectMapper = new ObjectMapper();
        AssistantLlmChatProperties properties = llmProperties();
        properties.setBaseUrl(mockWebServer.url("/").toString());

        AssistantLlmClient llmClient = new AssistantLlmClient(properties, objectMapper, WebClient.builder());
        LlmLfPlanner planner = new LlmLfPlanner(llmClient, properties, new DefaultLfPlanner(), objectMapper);

        NlQueryInput input = new NlQueryInput();
        input.setIntent("query");
        input.setContent("统计最近7天订单金额趋势");
        AssistantContextDTO context = new AssistantContextDTO();
        context.setSourceId(1L);
        context.setDatabase("doris_dwd");
        input.setContext(context);

        LogicalForm logicalForm = planner.draft(input);

        assertNotNull(logicalForm);
        assertEquals("bi_query", logicalForm.getIntent());
        assertEquals("SELECT dt, SUM(order_amount) AS total_amount FROM dwd_order GROUP BY dt ORDER BY dt LIMIT 200",
            logicalForm.getSqlDraft());
        assertEquals("llm-openai-compatible", logicalForm.getTrace().get("planner"));

        RecordedRequest request = mockWebServer.takeRequest();
        assertEquals("/v1/chat/completions", request.getPath());
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"model\":\"test-model\""));
        assertTrue(body.contains("统计最近7天订单金额趋势"));
    }

    @Test
    void shouldBypassLlmWhenInputIsSql() {
        ObjectMapper objectMapper = new ObjectMapper();
        AssistantLlmChatProperties properties = llmProperties();
        properties.setBaseUrl(mockWebServer.url("/").toString());

        AssistantLlmClient llmClient = new AssistantLlmClient(properties, objectMapper, WebClient.builder());
        LlmLfPlanner planner = new LlmLfPlanner(llmClient, properties, new DefaultLfPlanner(), objectMapper);

        NlQueryInput input = new NlQueryInput();
        input.setIntent("query");
        input.setContent("SELECT 1 AS value");

        LogicalForm logicalForm = planner.draft(input);
        assertEquals("SELECT 1 AS value", logicalForm.getSqlDraft());
        assertEquals("sql-direct-bypass", logicalForm.getTrace().get("planner"));
        assertEquals(0, mockWebServer.getRequestCount());
    }

    private AssistantLlmChatProperties llmProperties() {
        AssistantLlmChatProperties properties = new AssistantLlmChatProperties();
        properties.setEnabled(true);
        properties.setModel("test-model");
        properties.setApiKeyEnv("PATH");
        properties.setStrict(true);
        properties.setTemperature(0.1D);
        properties.setMaxTokens(500);
        properties.setTimeoutMs(5000);
        return properties;
    }
}
