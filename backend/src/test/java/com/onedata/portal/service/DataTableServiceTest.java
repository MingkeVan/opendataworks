package com.onedata.portal.service;

import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DataFieldMapper;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataTableServiceTest {

    @Mock
    private DataTableMapper dataTableMapper;

    @Mock
    private DataFieldMapper dataFieldMapper;

    @Mock
    private TableTaskRelationMapper tableTaskRelationMapper;

    @Mock
    private DataTaskMapper dataTaskMapper;

    @Mock
    private TaskExecutionLogMapper taskExecutionLogMapper;

    @Mock
    private DataLineageMapper dataLineageMapper;

    @Mock
    private DorisClusterMapper dorisClusterMapper;

    @Mock
    private DorisConnectionService dorisConnectionService;

    @Mock
    private TableMetadataVersionService tableMetadataVersionService;

    private DataTableService service;

    @BeforeEach
    void setUp() {
        service = new DataTableService(
                dataTableMapper,
                dataFieldMapper,
                tableTaskRelationMapper,
                dataTaskMapper,
                taskExecutionLogMapper,
                dataLineageMapper,
                dorisClusterMapper,
                dorisConnectionService,
                tableMetadataVersionService);
    }

    @Test
    void createFieldDoesNotTreatDefaultReplicaNumAsDorisWhenTableIsUnsynced() {
        DataTable table = table(10L, null, 0);
        table.setReplicaNum(1);
        when(dataTableMapper.selectById(10L)).thenReturn(table);
        when(dataFieldMapper.selectOne(any())).thenReturn(null);

        DataField field = field("amount", "BIGINT");

        DataField result = service.createField(10L, field, null);

        assertSame(field, result);
        verify(dataFieldMapper).insert(field);
        verify(tableMetadataVersionService).captureVersion(
                eq(10L), eq(TableMetadataVersionService.TRIGGER_MANUAL_EDIT), eq(null));
        verifyNoInteractions(dorisClusterMapper);
        verifyNoInteractions(dorisConnectionService);
    }

    @Test
    void createFieldUsesTableDatasourceWhenSyncedDorisTableDoesNotPassClusterId() {
        DataTable table = table(10L, 7L, 1);
        table.setDbName("dw");
        table.setTableName("fact_orders");
        when(dataTableMapper.selectById(10L)).thenReturn(table);
        when(dataFieldMapper.selectOne(any())).thenReturn(null);
        when(dorisClusterMapper.selectById(7L)).thenReturn(cluster(7L, "DORIS"));

        DataField field = field("amount", "BIGINT");
        when(dorisConnectionService.buildColumnDefinition(field, false)).thenReturn("`amount` BIGINT");

        DataField result = service.createField(10L, field, null);

        assertSame(field, result);
        verify(dorisConnectionService).addColumn(7L, "dw", "fact_orders", "`amount` BIGINT");
        verify(dataFieldMapper).insert(field);
    }

    @Test
    void createFieldRejectsSyncedNonDorisTableInsteadOfCallingDorisDdl() {
        DataTable table = table(10L, 8L, 1);
        table.setDbName("dw");
        table.setTableName("fact_orders");
        when(dataTableMapper.selectById(10L)).thenReturn(table);
        when(dataFieldMapper.selectOne(any())).thenReturn(null);
        when(dorisClusterMapper.selectById(8L)).thenReturn(cluster(8L, "MYSQL"));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.createField(10L, field("amount", "BIGINT"), null));

        assertEquals("暂不支持同步 MYSQL 数据源的表变更", exception.getMessage());
        verify(dorisConnectionService, never()).addColumn(any(), any(), any(), any());
        verify(dataFieldMapper, never()).insert(any());
        verifyNoInteractions(tableMetadataVersionService);
    }

    @Test
    void updateTableAllowsMetadataOnlyChangeForSyncedNonDorisTable() {
        DataTable existing = table(10L, 8L, 1);
        existing.setDbName("dw");
        existing.setTableName("fact_orders");
        when(dataTableMapper.selectById(10L)).thenReturn(existing);

        DataTable update = new DataTable();
        update.setLayer("ADS");
        update.setOwner("alice");

        DataTable result = service.updateTable(10L, update, null);

        assertSame(update, result);
        verify(dataTableMapper).updateById(update);
        verifyNoInteractions(dorisClusterMapper);
        verifyNoInteractions(dorisConnectionService);
    }

    @Test
    void updateTableRejectsPhysicalChangeForSyncedNonDorisTable() {
        DataTable existing = table(10L, 8L, 1);
        existing.setDbName("dw");
        existing.setTableName("fact_orders");
        existing.setTableComment("old comment");
        when(dataTableMapper.selectById(10L)).thenReturn(existing);
        when(dorisClusterMapper.selectById(8L)).thenReturn(cluster(8L, "MYSQL"));

        DataTable update = new DataTable();
        update.setLayer("DWD");
        update.setTableComment("new comment");

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.updateTable(10L, update, null));

        assertEquals("暂不支持同步 MYSQL 数据源的表变更", exception.getMessage());
        verify(dataTableMapper, never()).updateById(any());
        verifyNoInteractions(dorisConnectionService);
    }

    private DataTable table(Long id, Long clusterId, Integer isSynced) {
        DataTable table = new DataTable();
        table.setId(id);
        table.setClusterId(clusterId);
        table.setTableName("metadata_table");
        table.setLayer("DWD");
        table.setIsSynced(isSynced);
        return table;
    }

    private DataField field(String name, String type) {
        DataField field = new DataField();
        field.setFieldName(name);
        field.setFieldType(type);
        return field;
    }

    private DorisCluster cluster(Long id, String sourceType) {
        DorisCluster cluster = new DorisCluster();
        cluster.setId(id);
        cluster.setClusterName("test-" + id);
        cluster.setSourceType(sourceType);
        return cluster;
    }
}
