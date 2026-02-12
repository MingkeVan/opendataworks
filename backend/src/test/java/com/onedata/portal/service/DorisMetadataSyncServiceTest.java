package com.onedata.portal.service;

import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DataFieldMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.mapper.TableStatisticsHistoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DorisMetadataSyncServiceTest {

    @Mock
    private DorisConnectionService dorisConnectionService;

    @Mock
    private DorisClusterMapper dorisClusterMapper;

    @Mock
    private DataTableMapper dataTableMapper;

    @Mock
    private DataFieldMapper dataFieldMapper;

    @Mock
    private TableStatisticsHistoryMapper tableStatisticsHistoryMapper;

    @InjectMocks
    private DorisMetadataSyncService service;

    @BeforeEach
    void setUp() {
        DorisCluster cluster = new DorisCluster();
        cluster.setId(1L);
        cluster.setSourceType("DORIS");

        when(dorisClusterMapper.selectById(1L)).thenReturn(cluster);
        when(dorisConnectionService.getTableCreateInfo(anyLong(), anyString(), anyString()))
                .thenReturn(Collections.emptyMap());
        when(dorisConnectionService.getColumnsInTable(anyLong(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());
    }

    @Test
    void syncDatabaseSetsLayerFromPrefixForNewTable() {
        Map<String, Object> dorisTable = new HashMap<>();
        dorisTable.put("tableName", "dwd_order_detail");
        dorisTable.put("tableComment", "order detail");

        when(dorisConnectionService.getTablesInDatabase(1L, "dw"))
                .thenReturn(Collections.singletonList(dorisTable));
        when(dataTableMapper.selectList(any()))
                .thenReturn(Collections.emptyList());

        service.syncDatabase(1L, "dw", null);

        ArgumentCaptor<DataTable> captor = ArgumentCaptor.forClass(DataTable.class);
        verify(dataTableMapper).insert(captor.capture());
        assertEquals("DWD", captor.getValue().getLayer());
    }

    @Test
    void syncDatabaseCorrectsLayerFromPrefixForExistingTable() {
        DataTable existing = new DataTable();
        existing.setId(10L);
        existing.setClusterId(1L);
        existing.setDbName("dw");
        existing.setTableName("ads_sales_summary");
        existing.setLayer("ODS");
        existing.setStatus("active");

        Map<String, Object> dorisTable = new HashMap<>();
        dorisTable.put("tableName", "ads_sales_summary");
        dorisTable.put("tableComment", "sales summary");

        when(dorisConnectionService.getTablesInDatabase(1L, "dw"))
                .thenReturn(Collections.singletonList(dorisTable));
        when(dataTableMapper.selectList(any()))
                .thenReturn(Collections.singletonList(existing));
        when(dataFieldMapper.selectList(any())).thenReturn(Collections.emptyList());

        service.syncDatabase(1L, "dw", null);

        ArgumentCaptor<DataTable> captor = ArgumentCaptor.forClass(DataTable.class);
        verify(dataTableMapper).updateById(captor.capture());
        assertEquals("ADS", captor.getValue().getLayer());
    }
}
