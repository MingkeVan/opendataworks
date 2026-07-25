package com.onedata.portal.service.table;

import com.onedata.portal.dto.TableColumnRequest;
import com.onedata.portal.dto.TableCreateRequest;
import com.onedata.portal.service.DorisConnectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class DorisTableEngineHandlerTest {

    @Mock
    private DorisConnectionService dorisConnectionService;

    private TableCreateRequest sampleRequest() {
        TableColumnRequest col = new TableColumnRequest();
        col.setColumnName("order_id");
        col.setDataType("BIGINT");
        TableCreateRequest req = new TableCreateRequest();
        req.setDbName("dwd");
        req.setTableModel("DUPLICATE");
        req.setReplicaNum(3);
        req.setBucketNum(10);
        req.setKeyColumns(Arrays.asList("dt", "order_id"));
        req.setDistributionColumns(Collections.singletonList("order_id"));
        req.setColumns(Collections.singletonList(col));
        return req;
    }

    @Test
    void buildCreateDdlOmitsStorageFormatAndCompression() {
        String ddl = new DorisTableEngineHandler(dorisConnectionService)
                .buildCreateDdl("dwd_user_order_di", sampleRequest());

        // storage_format / compression are Doris defaults and are no longer emitted.
        assertFalse(ddl.contains("storage_format"), ddl);
        assertFalse(ddl.contains("compression"), ddl);
        // PROPERTIES keeps only replication_num, and closes cleanly (no dangling comma).
        assertTrue(ddl.contains("\"replication_num\" = \"3\""), ddl);
        assertTrue(ddl.contains("\"replication_num\" = \"3\"\n)"), ddl);
        // Core structure is unchanged.
        assertTrue(ddl.contains("ENGINE=OLAP"), ddl);
        assertTrue(ddl.contains("DISTRIBUTED BY HASH(`order_id`) BUCKETS 10"), ddl);
    }
}
