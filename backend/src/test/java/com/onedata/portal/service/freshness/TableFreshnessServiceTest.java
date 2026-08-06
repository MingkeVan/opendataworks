package com.onedata.portal.service.freshness;

import com.onedata.portal.dto.TableFreshnessRequest;
import com.onedata.portal.dto.TableFreshnessResponse;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.TableFreshnessConfig;
import com.onedata.portal.mapper.DataFieldMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.TableFreshnessConfigMapper;
import com.onedata.portal.mapper.TableFreshnessResultMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 契约保存校验：列白名单、互斥、custom_sql 形状、filter 拒绝、partition 前置条件。
 */
class TableFreshnessServiceTest {

    private final DataTableMapper dataTableMapper = mock(DataTableMapper.class);
    private final DataFieldMapper dataFieldMapper = mock(DataFieldMapper.class);
    private final TableFreshnessConfigMapper configMapper = mock(TableFreshnessConfigMapper.class);
    private final TableFreshnessResultMapper resultMapper = mock(TableFreshnessResultMapper.class);
    private final com.onedata.portal.mapper.TableTaskRelationMapper relationMapper =
        mock(com.onedata.portal.mapper.TableTaskRelationMapper.class);
    private final FreshnessContractResolver resolver = new FreshnessContractResolver();
    private final FreshnessCheckService checkService = mock(FreshnessCheckService.class);

    private final TableFreshnessService service = new TableFreshnessService(
        dataTableMapper, dataFieldMapper, configMapper, resultMapper, relationMapper, resolver, checkService);

    private void tableExists() {
        DataTable t = new DataTable();
        t.setId(1L);
        t.setClusterId(10L);
        t.setDbName("dwd");
        t.setTableName("dwd_order_di");
        when(dataTableMapper.selectById(1L)).thenReturn(t);
    }

    private void fields(DataField... fs) {
        when(dataFieldMapper.selectList(any())).thenReturn(Arrays.asList(fs));
    }

    private DataField field(String name, int isPartition) {
        DataField f = new DataField();
        f.setFieldName(name);
        f.setIsPartition(isPartition);
        return f;
    }

    private TableFreshnessRequest req(String mode) {
        TableFreshnessRequest r = new TableFreshnessRequest();
        r.setMode(mode);
        r.setWarnAfterCount(2);
        r.setWarnAfterPeriod("hour");
        r.setErrorAfterCount(4);
        r.setErrorAfterPeriod("hour");
        return r;
    }

    @Test
    void column_realColumn_saves() {
        tableExists();
        fields(field("etl_time", 0));
        when(configMapper.selectOne(any())).thenReturn(null);
        TableFreshnessRequest r = req("column");
        r.setLoadedAtField("etl_time");

        service.saveFreshness(1L, r, "alice");
        verify(configMapper).insert(any(TableFreshnessConfig.class));
    }

    @Test
    void column_unknownColumn_rejected() {
        tableExists();
        fields(field("etl_time", 0));
        TableFreshnessRequest r = req("column");
        r.setLoadedAtField("not_a_column");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.saveFreshness(1L, r, "alice"));
        assertTrue(ex.getMessage().contains("不存在"));
        verify(configMapper, never()).insert(any());
    }

    @Test
    void mutualExclusion_rejected() {
        tableExists();
        fields(field("etl_time", 0));
        TableFreshnessRequest r = req("column");
        r.setLoadedAtField("etl_time");
        r.setLoadedAtQuery("select max(etl_time) from t");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.saveFreshness(1L, r, "alice"));
        assertTrue(ex.getMessage().contains("互斥"));
    }

    @Test
    void customSql_mustStartWithSelect() {
        tableExists();
        fields();
        TableFreshnessRequest r = req("custom_sql");
        r.setLoadedAtQuery("delete from t");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.saveFreshness(1L, r, "alice"));
        assertTrue(ex.getMessage().contains("SELECT"));
    }

    @Test
    void customSql_rejectsSemicolon() {
        tableExists();
        fields();
        TableFreshnessRequest r = req("custom_sql");
        r.setLoadedAtQuery("select max(dt) from t; drop table t");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.saveFreshness(1L, r, "alice"));
        assertTrue(ex.getMessage().contains("分号"));
    }

    @Test
    void filter_rejectsComment() {
        tableExists();
        fields(field("etl_time", 0));
        TableFreshnessRequest r = req("column");
        r.setLoadedAtField("etl_time");
        r.setFilterExpr("1=1 -- bypass");
        assertThrows(IllegalArgumentException.class, () -> service.saveFreshness(1L, r, "alice"));
    }

    @Test
    void partition_requiresPartitionColumn() {
        tableExists();
        fields(field("etl_time", 0)); // 无分区列
        TableFreshnessRequest r = req("partition");
        r.setPartitionFormat("yyyyMMdd");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.saveFreshness(1L, r, "alice"));
        assertTrue(ex.getMessage().contains("分区"));
    }

    @Test
    void partition_withPartitionColumn_saves() {
        tableExists();
        fields(field("ds", 1));
        when(configMapper.selectOne(any())).thenReturn(null);
        TableFreshnessRequest r = req("partition");
        r.setPartitionFormat("yyyyMMdd");
        service.saveFreshness(1L, r, "alice");
        verify(configMapper).insert(any(TableFreshnessConfig.class));
    }

    @Test
    void getFreshness_returnsEffectiveWithSources() {
        tableExists();
        TableFreshnessConfig cfg = new TableFreshnessConfig();
        cfg.setTableId(1L);
        cfg.setMode("column");
        cfg.setLoadedAtField("etl_time");
        cfg.setWarnAfterCount(2);
        cfg.setWarnAfterPeriod("hour");
        cfg.setErrorAfterCount(4);
        cfg.setErrorAfterPeriod("hour");
        cfg.setEnabled(true);
        when(configMapper.selectOne(any())).thenReturn(cfg);
        when(resultMapper.selectList(any())).thenReturn(Collections.emptyList());

        TableFreshnessResponse response = service.getFreshness(1L);
        assertTrue(response.isConfigured());
        assertEquals("column", response.getEffective().getMode());
        assertEquals("table", response.getEffective().getFieldSources().get("mode"));
        assertEquals(2, response.getEffective().getWarnAfter().getCount());
    }
}
