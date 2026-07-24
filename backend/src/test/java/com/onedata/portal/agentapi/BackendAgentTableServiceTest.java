package com.onedata.portal.agentapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.agentapi.dto.AgentTableCreateRequest;
import com.onedata.portal.agentapi.service.BackendAgentTableService;
import com.onedata.portal.dto.TableCreateRequest;
import com.onedata.portal.service.TableCreateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BackendAgentTableServiceTest {

    @Mock
    private TableCreateService tableCreateService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private BackendAgentTableService service() {
        return new BackendAgentTableService(tableCreateService, objectMapper);
    }

    private AgentTableCreateRequest sampleRequest() {
        AgentTableCreateRequest.Column col = new AgentTableCreateRequest.Column();
        col.setColumnName("order_id");
        col.setDataType("BIGINT");
        col.setComment("订单ID");
        AgentTableCreateRequest req = new AgentTableCreateRequest();
        req.setDbName("dwd");
        req.setLayer("dwd");
        req.setTableModel("DUPLICATE");
        req.setBucketNum(10);
        req.setReplicaNum(3);
        req.setKeyColumns(Arrays.asList("dt", "order_id"));
        req.setDistributionColumns(Collections.singletonList("order_id"));
        req.setDorisClusterId(5L);
        req.setSyncToDoris(Boolean.TRUE);
        req.setColumns(Collections.singletonList(col));
        return req;
    }

    @Test
    void createTableMapsAgentRequestToDomainAndDelegates() {
        service().createTable(sampleRequest(), "agent-operator");

        ArgumentCaptor<TableCreateRequest> captor = ArgumentCaptor.forClass(TableCreateRequest.class);
        verify(tableCreateService).create(captor.capture());
        TableCreateRequest domain = captor.getValue();

        // Scalar fields map by name (AgentTableCreateRequest mirrors TableCreateRequest).
        assertEquals("dwd", domain.getDbName());
        assertEquals("DUPLICATE", domain.getTableModel());
        assertEquals(Integer.valueOf(10), domain.getBucketNum());
        assertEquals(Integer.valueOf(3), domain.getReplicaNum());
        assertEquals(Arrays.asList("dt", "order_id"), domain.getKeyColumns());
        assertEquals(Collections.singletonList("order_id"), domain.getDistributionColumns());
        assertEquals(Long.valueOf(5L), domain.getDorisClusterId());
        assertEquals(Boolean.TRUE, domain.getSyncToDoris());
        // Operator becomes the table owner when none was provided.
        assertEquals("agent-operator", domain.getOwner());
        // Nested column list maps by field name (Column -> TableColumnRequest).
        assertEquals(1, domain.getColumns().size());
        assertEquals("order_id", domain.getColumns().get(0).getColumnName());
        assertEquals("BIGINT", domain.getColumns().get(0).getDataType());
        assertEquals("订单ID", domain.getColumns().get(0).getComment());
    }

    @Test
    void previewCreateTableDelegatesToPreview() {
        service().previewCreateTable(sampleRequest(), "agent-operator");
        verify(tableCreateService).preview(any(TableCreateRequest.class));
    }

    @Test
    void createTableKeepsExplicitOwner() {
        AgentTableCreateRequest req = sampleRequest();
        req.setOwner("alice");
        service().createTable(req, "agent-operator");

        ArgumentCaptor<TableCreateRequest> captor = ArgumentCaptor.forClass(TableCreateRequest.class);
        verify(tableCreateService).create(captor.capture());
        assertEquals("alice", captor.getValue().getOwner());
    }
}
