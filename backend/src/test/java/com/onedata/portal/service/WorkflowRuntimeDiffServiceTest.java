package com.onedata.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.workflow.runtime.RuntimeDiffSummary;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskEdge;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowRuntimeDiffServiceTest {

    private final WorkflowRuntimeDiffService diffService = new WorkflowRuntimeDiffService(new ObjectMapper());

    @Test
    void buildDiffShouldDetectWorkflowTaskEdgeAndScheduleChanges() {
        RuntimeWorkflowDefinition baselineDef = definition("wf_a");
        RuntimeTaskDefinition t1 = task(1L, "task_1", 11L, 21L);
        baselineDef.setTasks(Collections.singletonList(t1));
        WorkflowRuntimeDiffService.RuntimeSnapshot baselineSnapshot =
                diffService.buildSnapshot(baselineDef, Collections.<RuntimeTaskEdge>emptyList());

        RuntimeWorkflowDefinition currentDef = definition("wf_b");
        RuntimeTaskDefinition n1 = task(1L, "task_1_mod", 11L, 22L);
        RuntimeTaskDefinition n2 = task(2L, "task_2", 22L, 33L);
        currentDef.setTasks(Arrays.asList(n1, n2));
        WorkflowRuntimeDiffService.RuntimeSnapshot currentSnapshot =
                diffService.buildSnapshot(currentDef, Collections.singletonList(new RuntimeTaskEdge(1L, 2L)));

        RuntimeDiffSummary summary = diffService.buildDiff(baselineSnapshot.getSnapshotJson(), currentSnapshot);

        assertTrue(Boolean.TRUE.equals(summary.getChanged()));
        assertFalse(summary.getWorkflowFieldChanges().isEmpty());
        assertFalse(summary.getTaskAdded().isEmpty());
        assertFalse(summary.getTaskModified().isEmpty());
        assertFalse(summary.getEdgeAdded().isEmpty());
    }

    private RuntimeWorkflowDefinition definition(String name) {
        RuntimeWorkflowDefinition definition = new RuntimeWorkflowDefinition();
        definition.setProjectCode(1L);
        definition.setWorkflowCode(1001L);
        definition.setWorkflowName(name);
        definition.setGlobalParams("[]");
        return definition;
    }

    private RuntimeTaskDefinition task(Long code, String name, Long inputTableId, Long outputTableId) {
        RuntimeTaskDefinition task = new RuntimeTaskDefinition();
        task.setTaskCode(code);
        task.setTaskName(name);
        task.setNodeType("SQL");
        task.setSql("SELECT 1");
        task.setDatasourceId(10L);
        task.setDatasourceName("doris_ds");
        task.setDatasourceType("DORIS");
        task.setInputTableIds(Collections.singletonList(inputTableId));
        task.setOutputTableIds(Collections.singletonList(outputTableId));
        return task;
    }
}
