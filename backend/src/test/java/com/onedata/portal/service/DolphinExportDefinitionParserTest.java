package com.onedata.portal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import com.onedata.portal.service.dolphin.DolphinOpenApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DolphinExportDefinitionParserTest {

    @Mock
    private DolphinOpenApiClient openApiClient;

    @Mock
    private DolphinSchedulerService dolphinSchedulerService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DolphinRuntimeDefinitionService service;

    @BeforeEach
    void setUp() {
        service = new DolphinRuntimeDefinitionService(openApiClient, dolphinSchedulerService, objectMapper);
    }

    @Test
    void shouldParseWorkflowDefinitionExportForDs34() {
        ObjectNode exported = objectMapper.createObjectNode();
        ObjectNode workflowDefinition = exported.putObject("workflowDefinition");
        workflowDefinition.put("code", 1001L);
        workflowDefinition.put("name", "wf_export_34");
        workflowDefinition.put("description", "desc_34");
        workflowDefinition.put("releaseState", "ONLINE");
        workflowDefinition.put("globalParams", "[{\"prop\":\"bizdate\",\"value\":\"2026-01-01\"}]");

        ArrayNode taskDefinitions = exported.putArray("taskDefinitionList");
        ObjectNode task = taskDefinitions.addObject();
        task.put("code", 2001L);
        task.put("name", "task_sql");
        task.put("taskType", "SQL");
        task.put("taskParams", "{\"sql\":\"select 1\",\"datasource\":10,\"type\":\"DORIS\"}");

        ArrayNode relations = exported.putArray("workflowTaskRelationList");
        ObjectNode relation = relations.addObject();
        relation.put("preTaskCode", 2001L);
        relation.put("postTaskCode", 2002L);

        ObjectNode schedule = exported.putObject("schedule");
        schedule.put("id", 301L);
        schedule.put("crontab", "0 0 1 * * ?");
        schedule.put("timezoneId", "Asia/Shanghai");
        schedule.put("releaseState", "ONLINE");

        when(openApiClient.exportDefinitionByCode(1L, 1001L)).thenReturn(exported);

        RuntimeWorkflowDefinition definition = service.loadRuntimeDefinitionFromExport(1L, 1001L);

        assertEquals(1L, definition.getProjectCode());
        assertEquals(1001L, definition.getWorkflowCode());
        assertEquals("wf_export_34", definition.getWorkflowName());
        assertEquals("desc_34", definition.getDescription());
        assertEquals("ONLINE", definition.getReleaseState());
        assertEquals(1, definition.getTasks().size());
        assertEquals(1, definition.getExplicitEdges().size());
        assertNotNull(definition.getSchedule());
        assertEquals(301L, definition.getSchedule().getScheduleId());
        assertNotNull(definition.getRawDefinitionJson());
    }

    @Test
    void shouldParseProcessDefinitionExportForDs32() {
        ObjectNode exported = objectMapper.createObjectNode();
        ObjectNode processDefinition = exported.putObject("processDefinition");
        processDefinition.put("code", 1002L);
        processDefinition.put("name", "wf_export_32");
        processDefinition.put("description", "desc_32");
        processDefinition.put("releaseState", "OFFLINE");
        processDefinition.put("globalParams", "[{\"prop\":\"dt\",\"value\":\"2026-02-01\"}]");

        ArrayNode taskDefinitions = exported.putArray("taskDefinitionList");
        ObjectNode task = taskDefinitions.addObject();
        task.put("code", 2101L);
        task.put("name", "task_sql_32");
        task.put("taskType", "SQL");
        task.put("taskParams", "{\"sql\":\"select * from ods.t1\",\"datasource\":11,\"type\":\"MYSQL\"}");

        ArrayNode relations = exported.putArray("processTaskRelationList");
        ObjectNode relation = relations.addObject();
        relation.put("preTaskCode", 2101L);
        relation.put("postTaskCode", 2102L);

        when(openApiClient.exportDefinitionByCode(1L, 1002L)).thenReturn(exported);

        RuntimeWorkflowDefinition definition = service.loadRuntimeDefinitionFromExport(1L, 1002L);

        assertEquals(1002L, definition.getWorkflowCode());
        assertEquals("wf_export_32", definition.getWorkflowName());
        assertEquals("desc_32", definition.getDescription());
        assertEquals("OFFLINE", definition.getReleaseState());
        assertEquals(1, definition.getTasks().size());
        RuntimeTaskDefinition taskDefinition = definition.getTasks().get(0);
        assertEquals(2101L, taskDefinition.getTaskCode());
        assertEquals("task_sql_32", taskDefinition.getTaskName());
        assertEquals("select * from ods.t1", taskDefinition.getSql());
        assertEquals(1, definition.getExplicitEdges().size());
    }

    @Test
    void shouldFallbackToDefinitionTaskJsonWhenTaskDefinitionListMissing() {
        ObjectNode exported = objectMapper.createObjectNode();
        ObjectNode workflowDefinition = exported.putObject("workflowDefinition");
        workflowDefinition.put("code", 1003L);
        workflowDefinition.put("name", "wf_fallback");
        workflowDefinition.put("taskDefinitionJson",
                "[{\"code\":2301,\"name\":\"task_from_inline\",\"taskType\":\"SQL\",\"taskParams\":\"{\\\"sql\\\":\\\"select 2\\\",\\\"datasource\\\":12,\\\"type\\\":\\\"MYSQL\\\"}\"}]");

        when(openApiClient.exportDefinitionByCode(1L, 1003L)).thenReturn(exported);

        RuntimeWorkflowDefinition definition = service.loadRuntimeDefinitionFromExport(1L, 1003L);

        assertEquals(1, definition.getTasks().size());
        assertEquals("task_from_inline", definition.getTasks().get(0).getTaskName());
    }

    @Test
    void shouldThrowWhenExportPayloadIsEmpty() {
        when(openApiClient.exportDefinitionByCode(1L, 1004L)).thenReturn(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.loadRuntimeDefinitionFromExport(1L, 1004L));
        assertTrue(ex.getMessage().contains("导出工作流定义"));
    }
}
