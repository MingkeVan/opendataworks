package com.onedata.portal.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DorisCreateTableUtilsTest {

    @Test
    void parseReplicationNum_prefersReplicationAllocationForNonDynamicPartition() {
        String ddl = "CREATE TABLE `db`.`t` (\n" +
                "  `id` INT NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`id`)\n" +
                "DISTRIBUTED BY HASH(`id`) BUCKETS 10\n" +
                "PROPERTIES (\n" +
                "  \"replication_allocation\" = \"tag.location.default: 3\",\n" +
                "  \"storage_format\" = \"V2\"\n" +
                ");";

        assertEquals(3, DorisCreateTableUtils.parseReplicationNum(ddl));
    }

    @Test
    void parseReplicationNum_sumsReplicaCountAcrossTags() {
        String ddl = "PROPERTIES (\n" +
                "  \"replication_allocation\" = \"tag.location.default: 2, tag.location.other: 1\"\n" +
                ");";

        assertEquals(3, DorisCreateTableUtils.parseReplicationNum(ddl));
    }

    @Test
    void parseReplicationNum_prefersDynamicPartitionReplicationAllocationWhenEnabled() {
        String ddl = "PROPERTIES (\n" +
                "  \"dynamic_partition.enable\" = \"true\",\n" +
                "  \"dynamic_partition.replication_allocation\" = \"tag.location.default: 3\",\n" +
                "  \"replication_allocation\" = \"tag.location.default: 1\"\n" +
                ");";

        assertEquals(3, DorisCreateTableUtils.parseReplicationNum(ddl));
    }

    @Test
    void parseReplicationNum_fallsBackToReplicationNum() {
        String ddl = "PROPERTIES (\n" +
                "  \"replication_num\" = \"3\"\n" +
                ");";

        assertEquals(3, DorisCreateTableUtils.parseReplicationNum(ddl));
    }

    @Test
    void parseReplicationNum_fallsBackToDynamicPartitionReplicationNum() {
        String ddl = "PROPERTIES (\n" +
                "  \"dynamic_partition.enable\" = \"true\",\n" +
                "  \"dynamic_partition.replication_num\" = \"2\"\n" +
                ");";

        assertEquals(2, DorisCreateTableUtils.parseReplicationNum(ddl));
    }

    @Test
    void parseReplicationNum_returnsNullWhenMissing() {
        assertNull(DorisCreateTableUtils.parseReplicationNum(""));
        assertNull(DorisCreateTableUtils.parseReplicationNum(null));
        assertNull(DorisCreateTableUtils.parseReplicationNum("CREATE TABLE t (id INT)"));
    }
}

