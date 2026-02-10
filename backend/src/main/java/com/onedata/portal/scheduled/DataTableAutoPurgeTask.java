package com.onedata.portal.scheduled;

import com.onedata.portal.entity.DataTable;
import com.onedata.portal.service.DataTableService;
import com.onedata.portal.service.DorisConnectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 废弃表自动物理清理任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataTableAutoPurgeTask {

    private static final int BATCH_SIZE = 200;

    private final DataTableService dataTableService;
    private final DorisConnectionService dorisConnectionService;

    /**
     * 每天凌晨 03:15 清理到期废弃表
     */
    @Scheduled(cron = "0 15 3 * * ?")
    public void purgeExpiredTables() {
        LocalDateTime now = LocalDateTime.now();
        List<DataTable> dueTables = dataTableService.listDueForPurge(now, BATCH_SIZE);
        if (dueTables.isEmpty()) {
            return;
        }

        for (DataTable table : dueTables) {
            try {
                if (isDorisTable(table)) {
                    String database = table.getDbName();
                    String actualTableName = extractActualTableName(table.getTableName());
                    if (!StringUtils.hasText(database) || !StringUtils.hasText(actualTableName)) {
                        throw new RuntimeException("缺少数据库名或表名");
                    }
                    if (table.getClusterId() == null) {
                        throw new RuntimeException("缺少 clusterId");
                    }
                    dorisConnectionService.dropTable(table.getClusterId(), database, actualTableName);
                }
                dataTableService.purgeTableMetadata(table.getId());
                log.info("Auto purged deprecated table, tableId={}, db={}, table={}",
                        table.getId(), table.getDbName(), table.getTableName());
            } catch (Exception e) {
                // 删除失败保留记录，交给下一轮重试
                log.error("Auto purge failed for tableId={}, db={}, table={}",
                        table.getId(), table.getDbName(), table.getTableName(), e);
            }
        }
    }

    private boolean isDorisTable(DataTable table) {
        if (table == null) {
            return false;
        }
        if (table.getIsSynced() != null && table.getIsSynced() == 1) {
            return true;
        }
        return StringUtils.hasText(table.getTableModel())
                || isPositive(table.getBucketNum())
                || isPositive(table.getReplicaNum())
                || StringUtils.hasText(table.getDistributionColumn())
                || StringUtils.hasText(table.getKeyColumns())
                || StringUtils.hasText(table.getPartitionField());
    }

    private boolean isPositive(Integer value) {
        return value != null && value > 0;
    }

    private String extractActualTableName(String tableName) {
        if (!StringUtils.hasText(tableName)) {
            return null;
        }
        if (tableName.contains(".")) {
            String[] parts = tableName.split("\\.", 2);
            if (parts.length == 2 && StringUtils.hasText(parts[1])) {
                return parts[1];
            }
        }
        return tableName;
    }
}
