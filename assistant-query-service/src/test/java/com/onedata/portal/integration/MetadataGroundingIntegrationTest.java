package com.onedata.portal.integration;

import com.onedata.portal.entity.AqMetaLineageEdge;
import com.onedata.portal.entity.AqMetaTable;
import com.onedata.portal.mapper.AqMetaLineageEdgeMapper;
import com.onedata.portal.mapper.AqMetaTableMapper;
import com.onedata.portal.service.assistant.nl2lf.DefaultLfGroundingService;
import com.onedata.portal.service.assistant.nl2lf.LogicalForm;
import com.onedata.portal.service.assistant.nl2lf.MetadataContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class MetadataGroundingIntegrationTest extends AssistantIntegrationTestBase {

    @Autowired
    private DefaultLfGroundingService groundingService;

    @Autowired
    private AqMetaTableMapper tableMapper;

    @Autowired
    private AqMetaLineageEdgeMapper lineageEdgeMapper;

    @Test
    @DisplayName("NL2LF grounding 应利用 aq_meta_table 与 aq_meta_lineage_edge")
    void groundingShouldUseMetadataAndLineage() {
        insertMetaTable(1001L, "ods", "orders", "订单明细");
        insertMetaTable(2001L, "dwd", "orders_daily", "订单日汇总");
        insertLineage(1001L, 2001L);

        LogicalForm draft = new LogicalForm();
        draft.setSqlDraft("SELECT * FROM ods.orders o JOIN dwd.orders_daily d ON o.dt = d.dt");

        MetadataContext context = new MetadataContext();
        context.setDatabase(null);
        context.setSourceId(1L);

        LogicalForm grounded = groundingService.ground(draft, context);
        assertEquals("aq-metadata-lineage", grounded.getTrace().get("grounding"));
        assertEquals(Boolean.TRUE, grounded.getTrace().get("lineageUsed"));

        List<Map<String, Object>> entities = grounded.getEntities();
        assertEquals(2, entities.size());
        assertTrue(entities.stream().allMatch(it -> "matched".equals(it.get("matchStatus"))));

        List<Map<String, Object>> joins = grounded.getJoins();
        assertFalse(joins.isEmpty());
        assertEquals("aq_meta_lineage_edge", joins.get(0).get("source"));
        assertEquals(Boolean.FALSE, grounded.getClarification().get("required"));
    }

    @Test
    @DisplayName("无元数据命中时应走 fallback 并提示澄清")
    void groundingShouldFallbackWhenMetadataMissing() {
        LogicalForm draft = new LogicalForm();
        draft.setSqlDraft("SELECT * FROM ads.unknown_table");

        MetadataContext context = new MetadataContext();
        context.setDatabase("ads");
        context.setSourceId(1L);

        LogicalForm grounded = groundingService.ground(draft, context);
        assertEquals("aqs-fallback", grounded.getTrace().get("grounding"));
        assertEquals(Boolean.FALSE, grounded.getTrace().get("lineageUsed"));
        assertEquals(Boolean.TRUE, grounded.getClarification().get("required"));
        assertTrue(grounded.getEntities().stream().anyMatch(it -> "unmatched".equals(it.get("matchStatus"))));
    }

    private void insertMetaTable(Long tableId, String dbName, String tableName, String comment) {
        AqMetaTable table = new AqMetaTable();
        table.setSnapshotVersion("v-it");
        table.setTableId(tableId);
        table.setDbName(dbName);
        table.setTableName(tableName);
        table.setTableComment(comment);
        tableMapper.insert(table);
    }

    private void insertLineage(Long upstream, Long downstream) {
        AqMetaLineageEdge edge = new AqMetaLineageEdge();
        edge.setSnapshotVersion("v-it");
        edge.setLineageId(1L);
        edge.setTaskId(11L);
        edge.setUpstreamTableId(upstream);
        edge.setDownstreamTableId(downstream);
        edge.setLineageType("table");
        lineageEdgeMapper.insert(edge);
    }
}
