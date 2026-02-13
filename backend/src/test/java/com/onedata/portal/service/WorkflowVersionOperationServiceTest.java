package com.onedata.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.workflow.WorkflowVersionCompareRequest;
import com.onedata.portal.dto.workflow.WorkflowVersionCompareResponse;
import com.onedata.portal.dto.workflow.WorkflowVersionErrorCodes;
import com.onedata.portal.dto.workflow.WorkflowVersionRollbackRequest;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowVersionOperationServiceTest {

    @Mock
    private WorkflowVersionMapper workflowVersionMapper;

    @Mock
    private DataWorkflowMapper dataWorkflowMapper;

    @Mock
    private DataTaskMapper dataTaskMapper;

    @Mock
    private DataTaskService dataTaskService;

    @Mock
    private WorkflowService workflowService;

    private WorkflowVersionOperationService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowVersionOperationService(
                workflowVersionMapper,
                dataWorkflowMapper,
                dataTaskMapper,
                dataTaskService,
                workflowService,
                new ObjectMapper());
    }

    @Test
    void compareShouldSwapLeftAndRightWhenLeftGreaterThanRight() {
        WorkflowVersion version1 = version(1L, 11L, 1, canonicalSnapshot("wf", "task_a"));
        WorkflowVersion version2 = version(2L, 11L, 2, canonicalSnapshot("wf2", "task_b"));

        when(workflowVersionMapper.selectById(1L)).thenReturn(version1);
        when(workflowVersionMapper.selectById(2L)).thenReturn(version2);

        WorkflowVersionCompareRequest request = new WorkflowVersionCompareRequest();
        request.setLeftVersionId(2L);
        request.setRightVersionId(1L);

        WorkflowVersionCompareResponse response = service.compare(11L, request);

        assertEquals(1L, response.getLeftVersionId());
        assertEquals(2L, response.getRightVersionId());
    }

    @Test
    void compareShouldFailWhenLeftEqualsRight() {
        WorkflowVersionCompareRequest request = new WorkflowVersionCompareRequest();
        request.setLeftVersionId(2L);
        request.setRightVersionId(2L);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.compare(11L, request));
        assertTrue(ex.getMessage().contains(WorkflowVersionErrorCodes.VERSION_COMPARE_INVALID));
    }

    @Test
    void compareShouldTreatNullLeftAsEmptyBaseline() {
        WorkflowVersion rightVersion = version(2L, 11L, 2, canonicalSnapshot("wf2", "task_b"));
        when(workflowVersionMapper.selectById(2L)).thenReturn(rightVersion);

        WorkflowVersionCompareRequest request = new WorkflowVersionCompareRequest();
        request.setLeftVersionId(null);
        request.setRightVersionId(2L);

        WorkflowVersionCompareResponse response = service.compare(11L, request);

        assertTrue(Boolean.TRUE.equals(response.getChanged()));
        assertTrue((response.getAdded().getTasks().size() + response.getAdded().getWorkflowFields().size()) > 0);
        assertEquals(0, response.getSummary().getRemoved());
    }

    @Test
    void rollbackShouldFailForLegacySnapshot() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(11L);
        workflow.setWorkflowName("wf");

        WorkflowVersion legacyVersion = version(1L, 11L, 1,
                "{\"workflowId\":11,\"workflowName\":\"wf\",\"tasks\":[{\"taskId\":1}]}");

        when(dataWorkflowMapper.selectById(11L)).thenReturn(workflow);
        when(workflowVersionMapper.selectById(1L)).thenReturn(legacyVersion);

        WorkflowVersionRollbackRequest request = new WorkflowVersionRollbackRequest();
        request.setOperator("tester");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.rollback(11L, 1L, request));
        assertTrue(ex.getMessage().contains(WorkflowVersionErrorCodes.VERSION_SNAPSHOT_UNSUPPORTED));
    }

    private WorkflowVersion version(Long id, Long workflowId, Integer versionNo, String snapshot) {
        WorkflowVersion version = new WorkflowVersion();
        version.setId(id);
        version.setWorkflowId(workflowId);
        version.setVersionNo(versionNo);
        version.setStructureSnapshot(snapshot);
        return version;
    }

    private String canonicalSnapshot(String workflowName, String taskName) {
        return "{"
                + "\"schemaVersion\":2,"
                + "\"workflow\":{\"workflowName\":\"" + workflowName + "\"},"
                + "\"tasks\":[{\"taskId\":1,\"taskName\":\"" + taskName + "\",\"inputTableIds\":[1],\"outputTableIds\":[2]}],"
                + "\"edges\":[],"
                + "\"schedule\":{}"
                + "}";
    }
}
