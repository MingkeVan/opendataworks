package com.onedata.portal.service.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.context.UserContext;
import com.onedata.portal.context.UserContextHolder;
import com.onedata.portal.dto.SqlAnalyzeResponse;
import com.onedata.portal.dto.SqlQueryRequest;
import com.onedata.portal.dto.assistant.AssistantContextDTO;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolGatewayClientContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer server;
    private AssistantToolExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        String baseUrl = server.url("/api").toString();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();
        ToolGatewayClient toolGatewayClient = new ToolGatewayClient(webClient, objectMapper);
        executor = new AssistantToolExecutor(toolGatewayClient);

        UserContext userContext = new UserContext();
        userContext.setUserId("u-contract");
        userContext.setUsername("contract-user");
        UserContextHolder.setContext(userContext);
    }

    @AfterEach
    void tearDown() throws Exception {
        UserContextHolder.clear();
        server.shutdown();
    }

    @Test
    void analyzeShouldMapRequestToCoreApi() throws Exception {
        server.enqueue(jsonSuccess(mapOf(
            "blocked", false,
            "confirmChallenges", Arrays.asList()
        )));

        AssistantContextDTO context = new AssistantContextDTO();
        context.setSourceId(9L);
        context.setDatabase("ods");
        SqlAnalyzeResponse response = executor.analyzeSql("run-1", "SELECT * FROM ods.t_order", context);
        assertNotNull(response);
        assertTrue(!response.isBlocked());

        RecordedRequest request = server.takeRequest();
        assertEquals("/api/v1/data-query/analyze", request.getPath());
        assertEquals("u-contract", request.getHeader("X-User-Id"));
        assertEquals("contract-user", request.getHeader("X-Username"));

        Map<String, Object> body = objectMapper.readValue(request.getBody().readUtf8(), new TypeReference<Map<String, Object>>() {
        });
        assertEquals("run-1", body.get("clientQueryId"));
        assertEquals(9, ((Number) body.get("clusterId")).intValue());
        assertEquals("ods", body.get("database"));
        assertEquals("SELECT * FROM ods.t_order", body.get("sql"));
    }

    @Test
    void executeShouldMapConfirmationsAndLimit() throws Exception {
        server.enqueue(jsonSuccess(mapOf(
            "message", "ok",
            "columns", Arrays.asList("dt", "pv"),
            "rows", Arrays.asList(mapOf("dt", "2026-03-01", "pv", 100)),
            "previewRowCount", 1,
            "resultSetCount", 1,
            "hasMore", false
        )));

        AssistantContextDTO context = new AssistantContextDTO();
        context.setSourceId(10L);
        context.setDatabase("dwd");
        context.setLimitProfile("manual_execute");
        context.setManualLimit(99999);

        SqlAnalyzeResponse.ConfirmChallenge challenge = new SqlAnalyzeResponse.ConfirmChallenge();
        challenge.setStatementIndex(1);
        challenge.setTargetObject("dwd.t_order");
        challenge.setConfirmToken("token-1");

        executor.executeSql(
            "run-2",
            "DELETE FROM dwd.t_order WHERE dt='2026-03-01'",
            context,
            null,
            Arrays.asList(challenge),
            true
        );

        RecordedRequest request = server.takeRequest();
        assertEquals("/api/v1/data-query/execute", request.getPath());

        Map<String, Object> body = objectMapper.readValue(request.getBody().readUtf8(), new TypeReference<Map<String, Object>>() {
        });
        assertEquals("run-2", body.get("clientQueryId"));
        assertEquals(10000, ((Number) body.get("limit")).intValue());
        assertEquals("dwd", body.get("database"));
        assertEquals("DELETE FROM dwd.t_order WHERE dt='2026-03-01'", body.get("sql"));

        @SuppressWarnings("unchecked")
        Map<String, Object> confirmation = ((java.util.List<Map<String, Object>>) body.get("confirmations")).get(0);
        assertEquals(1, ((Number) confirmation.get("statementIndex")).intValue());
        assertEquals("dwd.t_order", confirmation.get("targetObject"));
        assertEquals("dwd.t_order", confirmation.get("inputText"));
        assertEquals("token-1", confirmation.get("confirmToken"));
    }

    @Test
    void stopShouldCallCoreApi() throws Exception {
        server.enqueue(jsonSuccess(Boolean.TRUE));
        executor.stopRunQuery("u-contract", "run-3");
        RecordedRequest request = server.takeRequest();
        assertEquals("/api/v1/data-query/stop", request.getPath());
        Map<String, Object> body = objectMapper.readValue(request.getBody().readUtf8(), new TypeReference<Map<String, Object>>() {
        });
        assertEquals("run-3", body.get("clientQueryId"));
    }

    private MockResponse jsonSuccess(Object data) throws Exception {
        return new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(objectMapper.writeValueAsString(mapOf(
                "code", 200,
                "message", "success",
                "data", data
            )));
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length - 1; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }
}
