package com.onedata.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncParitySummary;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskEdge;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSyncParityServiceTest {

    private final RuntimeSyncParityService service = new RuntimeSyncParityService(
            new WorkflowRuntimeDiffService(new ObjectMapper()),
            new ObjectMapper());

    @Test
    void compareShouldReturnConsistentWhenSnapshotsAreEqual() {
        RuntimeWorkflowDefinition primary = definition("wf_a");
        RuntimeWorkflowDefinition shadow = definition("wf_a");

        RuntimeTaskDefinition task = task(1L, "task_a", "SELECT 1");
        primary.setTasks(Collections.singletonList(task));
        shadow.setTasks(Collections.singletonList(task(1L, "task_a", "SELECT 1")));
        primary.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(1L, 1L)));
        shadow.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(1L, 1L)));

        RuntimeSyncParitySummary summary = service.compare(primary, shadow);

        assertEquals(RuntimeSyncParityService.STATUS_CONSISTENT, summary.getStatus());
        assertFalse(Boolean.TRUE.equals(summary.getChanged()));
        assertNotNull(summary.getPrimaryHash());
        assertNotNull(summary.getShadowHash());
        assertTrue(summary.getSampleMismatches().isEmpty());
    }

    @Test
    void compareShouldReturnInconsistentWhenSnapshotsDiffer() {
        RuntimeWorkflowDefinition primary = definition("wf_new");
        RuntimeWorkflowDefinition shadow = definition("wf_old");

        primary.setTasks(Arrays.asList(
                task(1L, "task_a", "SELECT id FROM ods.user"),
                task(2L, "task_b", "INSERT INTO dwd.user SELECT * FROM ods.user")
        ));
        shadow.setTasks(Collections.singletonList(task(1L, "task_a", "SELECT * FROM ods.user")));

        primary.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(1L, 2L)));
        shadow.setExplicitEdges(Collections.emptyList());

        RuntimeSyncParitySummary summary = service.compare(primary, shadow);

        assertEquals(RuntimeSyncParityService.STATUS_INCONSISTENT, summary.getStatus());
        assertTrue(Boolean.TRUE.equals(summary.getChanged()));
        assertTrue(summary.getWorkflowFieldDiffCount() > 0 || summary.getTaskModifiedDiffCount() > 0);
        assertTrue(summary.getTaskAddedDiffCount() > 0 || summary.getEdgeAddedDiffCount() > 0);
        assertFalse(summary.getSampleMismatches().isEmpty());
    }

    @Test
    void toJsonAndParseShouldRoundTrip() {
        RuntimeSyncParitySummary source = new RuntimeSyncParitySummary();
        source.setStatus(RuntimeSyncParityService.STATUS_INCONSISTENT);
        source.setChanged(true);
        source.setWorkflowFieldDiffCount(2);
        source.getSampleMismatches().add("workflow: workflow.workflowName: wf_a -> wf_b");

        String json = service.toJson(source);
        RuntimeSyncParitySummary parsed = service.parse(json);

        assertNotNull(parsed);
        assertEquals(source.getStatus(), parsed.getStatus());
        assertEquals(source.getWorkflowFieldDiffCount(), parsed.getWorkflowFieldDiffCount());
        assertEquals(source.getSampleMismatches().size(), parsed.getSampleMismatches().size());
    }

    private RuntimeWorkflowDefinition definition(String workflowName) {
        RuntimeWorkflowDefinition definition = new RuntimeWorkflowDefinition();
        definition.setProjectCode(1L);
        definition.setWorkflowCode(1001L);
        definition.setWorkflowName(workflowName);
        definition.setGlobalParams("[]");
        return definition;
    }

    private RuntimeTaskDefinition task(Long code, String name, String sql) {
        RuntimeTaskDefinition task = new RuntimeTaskDefinition();
        task.setTaskCode(code);
        task.setTaskName(name);
        task.setNodeType("SQL");
        task.setSql(sql);
        task.setDatasourceId(10L);
        task.setDatasourceName("doris_ds");
        task.setDatasourceType("DORIS");
        return task;
    }
}
