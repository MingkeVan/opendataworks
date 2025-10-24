package com.onedata.portal.scheduled;

import com.onedata.portal.service.DorisMetadataSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Doris 元数据同步定时任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DorisTableSyncTask {

    private final DorisMetadataSyncService dorisMetadataSyncService;

    /**
     * 每天凌晨 2 点执行 Doris 元数据同步
     * cron 表达式: 秒 分 时 日 月 周
     * 0 0 2 * * ? 表示每天凌晨 2 点
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledDorisMetadataSync() {
        log.info("=== Scheduled Doris metadata sync task started ===");
        try {
            // 同步默认集群的元数据（clusterId 为 null 表示使用默认集群）
            DorisMetadataSyncService.SyncResult result = dorisMetadataSyncService.syncAllMetadata(null);

            log.info("=== Scheduled Doris metadata sync task completed successfully ===");
            log.info("Sync statistics: {}", result);

            // 记录详细信息
            log.info("New tables: {}, Updated tables: {}", result.getNewTables(), result.getUpdatedTables());
            log.info("New fields: {}, Updated fields: {}, Deleted fields: {}",
                result.getNewFields(), result.getUpdatedFields(), result.getDeletedFields());

            // 如果有错误，记录错误信息
            if (!result.getErrors().isEmpty()) {
                log.warn("Sync completed with {} errors:", result.getErrors().size());
                for (String error : result.getErrors()) {
                    log.warn("  - {}", error);
                }
            }
        } catch (Exception e) {
            log.error("=== Scheduled Doris metadata sync task failed ===", e);
        }
    }

    /**
     * 测试用的定时任务 - 每 10 分钟执行一次
     * 生产环境请删除或注释掉此任务
     */
    // @Scheduled(fixedRate = 600000) // 600000 毫秒 = 10 分钟
    // public void testScheduledDorisMetadataSync() {
    //     log.info("=== Test Doris metadata sync task started (every 10 minutes) ===");
    //     try {
    //         DorisMetadataSyncService.SyncResult result = dorisMetadataSyncService.syncAllMetadata(null);
    //         log.info("=== Test Doris metadata sync task completed ===");
    //         log.info("Sync statistics: {}", result);
    //     } catch (Exception e) {
    //         log.error("=== Test Doris metadata sync task failed ===", e);
    //     }
    // }
}
