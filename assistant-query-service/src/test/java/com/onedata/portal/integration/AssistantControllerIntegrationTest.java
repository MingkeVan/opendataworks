package com.onedata.portal.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.entity.AssistantArtifact;
import com.onedata.portal.entity.AssistantRun;
import com.onedata.portal.mapper.AssistantArtifactMapper;
import com.onedata.portal.mapper.AssistantRunMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AssistantControllerIntegrationTest extends AssistantIntegrationTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AssistantRunMapper runMapper;

    @Autowired
    private AssistantArtifactMapper artifactMapper;

    @Test
    @DisplayName("问数执行链路: run->SSE->结果/图表产物应完整")
    void queryFlowShouldCompleteWithArtifactsAndSseEvents() throws Exception {
        enqueueAnalyzeResponse(false, null);
        enqueueExecuteResponse(true);

        Map<String, Object> session = createSession("趋势问数会话", "text_answer", "need-confirm");
        String sessionId = asString(session.get("sessionId"));
        assertNotNull(sessionId);

        Map<String, Object> submitResp = postJson("/v1/assistant/sessions/" + sessionId + "/messages", mapOf(
            "content", "查询最近7天 pv 趋势图",
            "context", mapOf(
                "sourceId", 1,
                "database", "ods",
                "limitProfile", "text_answer",
                "mode", "need-confirm"
            )
        ));
        String runId = asString(submitResp.get("runId"));
        assertNotNull(runId);

        List<String> events = readSseEvents(runId, Duration.ofSeconds(10));
        assertTrue(events.contains("run_started"));
        assertTrue(events.contains("artifact"));
        assertTrue(events.contains("run_completed"));

        AssistantRun run = waitRunStatus(runId, "completed", Duration.ofSeconds(10));
        assertNotNull(run);
        assertEquals("completed", run.getStatus());

        List<AssistantArtifact> artifacts = artifactMapper.selectList(
            new LambdaQueryWrapper<AssistantArtifact>()
                .eq(AssistantArtifact::getRunId, runId)
        );
        assertTrue(artifacts.stream().anyMatch(it -> "sql".equals(it.getArtifactType())));
        assertTrue(artifacts.stream().anyMatch(it -> "query_result".equals(it.getArtifactType())));
        assertTrue(artifacts.stream().anyMatch(it -> "chart".equals(it.getArtifactType())));

        RecordedRequest analyzeReq = CORE_BACKEND.takeRequest();
        RecordedRequest executeReq = CORE_BACKEND.takeRequest();
        assertEquals("/api/v1/data-query/analyze", analyzeReq.getPath());
        assertEquals("/api/v1/data-query/execute", executeReq.getPath());

        Map<String, Object> executeBody = objectMapper.readValue(executeReq.getBody().readUtf8(),
            new TypeReference<Map<String, Object>>() {
            });
        assertEquals(500, ((Number) executeBody.get("limit")).intValue());
    }

    @Test
    @DisplayName("审批拒绝链路: need-confirm 风险 SQL 必须等待审批并可取消")
    void approvalRejectShouldCancelRun() throws Exception {
        enqueueAnalyzeResponse(true, "token-1");

        Map<String, Object> session = createSession("审批会话", "text_answer", "need-confirm");
        String sessionId = asString(session.get("sessionId"));
        assertNotNull(sessionId);

        Map<String, Object> submitResp = postJson("/v1/assistant/sessions/" + sessionId + "/messages", mapOf(
            "content", "DELETE FROM ods.t_user WHERE id = 1",
            "context", mapOf(
                "sourceId", 1,
                "database", "ods",
                "limitProfile", "text_answer",
                "mode", "need-confirm"
            )
        ));
        String runId = asString(submitResp.get("runId"));
        assertNotNull(runId);

        List<String> events = readSseEvents(runId, Duration.ofSeconds(8));
        assertTrue(events.contains("need_approval"));

        AssistantRun waiting = waitRunStatus(runId, "waiting_approval", Duration.ofSeconds(8));
        assertNotNull(waiting);
        assertEquals("waiting_approval", waiting.getStatus());

        postJson("/v1/assistant/runs/" + runId + "/approve", mapOf(
            "approved", false,
            "comment", "reject by it"
        ));

        AssistantRun cancelled = waitRunStatus(runId, "cancelled", Duration.ofSeconds(8));
        assertNotNull(cancelled);
        assertEquals("cancelled", cancelled.getStatus());
        assertFalse(cancelled.getErrorMessage() == null || cancelled.getErrorMessage().isEmpty());

        RecordedRequest analyzeReq = CORE_BACKEND.takeRequest();
        assertEquals("/api/v1/data-query/analyze", analyzeReq.getPath());
    }

    @Test
    @DisplayName("limitProfile=bi_sampling 应映射到 2000")
    void biSamplingProfileShouldUse2000Limit() throws Exception {
        enqueueAnalyzeResponse(false, null);
        enqueueExecuteResponse(false);

        Map<String, Object> session = createSession("BI采样会话", "bi_sampling", "need-confirm");
        String sessionId = asString(session.get("sessionId"));
        assertNotNull(sessionId);

        Map<String, Object> submitResp = postJson("/v1/assistant/sessions/" + sessionId + "/messages", mapOf(
            "content", "按天统计订单数",
            "context", mapOf(
                "sourceId", 1,
                "database", "ods",
                "limitProfile", "bi_sampling",
                "mode", "need-confirm"
            )
        ));
        String runId = asString(submitResp.get("runId"));
        assertNotNull(runId);

        AssistantRun completed = waitRunStatus(runId, "completed", Duration.ofSeconds(10));
        assertNotNull(completed);

        CORE_BACKEND.takeRequest();
        RecordedRequest executeReq = CORE_BACKEND.takeRequest();
        Map<String, Object> executeBody = objectMapper.readValue(executeReq.getBody().readUtf8(),
            new TypeReference<Map<String, Object>>() {
            });
        assertEquals(2000, ((Number) executeBody.get("limit")).intValue());
    }

    private void enqueueAnalyzeResponse(boolean needApproval, String token) throws Exception {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("blocked", false);
        data.put("blockedReason", null);
        data.put("riskItems", new ArrayList<Object>());
        if (needApproval) {
            List<Map<String, Object>> challenges = new ArrayList<Map<String, Object>>();
            challenges.add(mapOf(
                "statementIndex", 1,
                "targetObject", "ods.t_user",
                "confirmTextHint", "输入目标对象确认",
                "confirmToken", token
            ));
            data.put("confirmChallenges", challenges);
        } else {
            data.put("confirmChallenges", new ArrayList<Object>());
        }
        CORE_BACKEND.enqueue(jsonResponse(mapOf("code", 200, "message", "success", "data", data)));
    }

    private void enqueueExecuteResponse(boolean hasMore) throws Exception {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("resultSetCount", 1);
        data.put("cancelled", false);
        data.put("message", "执行完成");
        data.put("columns", Arrays.asList("dt", "pv"));
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(mapOf("dt", "2026-03-01", "pv", 100));
        rows.add(mapOf("dt", "2026-03-02", "pv", 120));
        data.put("rows", rows);
        data.put("previewRowCount", rows.size());
        data.put("hasMore", hasMore);
        data.put("durationMs", 18);
        CORE_BACKEND.enqueue(jsonResponse(mapOf("code", 200, "message", "success", "data", data)));
    }

    private Map<String, Object> createSession(String title, String limitProfile, String mode) {
        return postJson("/v1/assistant/sessions", mapOf(
            "title", title,
            "context", mapOf(
                "sourceId", 1,
                "database", "ods",
                "limitProfile", limitProfile,
                "mode", mode
            )
        ));
    }

    private MockResponse jsonResponse(Object value) throws Exception {
        return new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(objectMapper.writeValueAsString(value));
    }

    private AssistantRun waitRunStatus(String runId, String status, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            AssistantRun run = runMapper.selectOne(
                new LambdaQueryWrapper<AssistantRun>()
                    .eq(AssistantRun::getRunId, runId)
                    .last("LIMIT 1")
            );
            if (run != null && status.equals(run.getStatus())) {
                return run;
            }
            Thread.sleep(120L);
        }
        return runMapper.selectOne(
            new LambdaQueryWrapper<AssistantRun>()
                .eq(AssistantRun::getRunId, runId)
                .last("LIMIT 1")
        );
    }

    private List<String> readSseEvents(String runId, Duration timeout) throws Exception {
        URL url = new URL(baseUrl() + "/v1/assistant/runs/" + runId + "/stream");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(400);
        conn.connect();
        assertEquals(200, conn.getResponseCode());

        long deadline = System.currentTimeMillis() + timeout.toMillis();
        List<String> events = new ArrayList<String>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            while (System.currentTimeMillis() < deadline) {
                String line;
                try {
                    line = reader.readLine();
                } catch (Exception ex) {
                    continue;
                }
                if (line == null || !line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).trim();
                Map<String, Object> map = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {
                });
                String event = asString(map.get("event"));
                if (event != null) {
                    events.add(event);
                }
                if ("run_completed".equals(event) || "run_failed".equals(event)
                    || "run_cancelled".equals(event) || "need_approval".equals(event)) {
                    break;
                }
            }
        } finally {
            conn.disconnect();
        }
        return events;
    }

    private Map<String, Object> postJson(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<Map<String, Object>>(body, headers);
        ResponseEntity<Map> resp = restTemplate.postForEntity(baseUrl() + path, entity, Map.class);
        assertEquals(200, resp.getStatusCode().value());
        return unwrapData(resp.getBody());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapData(Map responseBody) {
        assertNotNull(responseBody);
        assertEquals(200, ((Number) responseBody.get("code")).intValue());
        return (Map<String, Object>) responseBody.get("data");
    }

    private String baseUrl() {
        return "http://localhost:" + port + "/api";
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length - 1; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }
}
