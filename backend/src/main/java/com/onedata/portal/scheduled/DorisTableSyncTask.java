package com.onedata.portal.scheduled;

import com.onedata.portal.service.DorisMetadataSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Doris 元数据稽核定时任务
 * 注意：这是稽核任务，只检查差异不自动同步，需要人工确认后手动同步
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DorisTableSyncTask {

    private final DorisMetadataSyncService dorisMetadataSyncService;

    /**
     * 每天凌晨 2 点执行 Doris 元数据稽核
     * cron 表达式: 秒 分 时 日 月 周
     * 0 0 2 * * ? 表示每天凌晨 2 点
     *
     * 稽核逻辑：只比对差异不同步，发现不一致时记录日志，需要人工确认后手动同步
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledDorisMetadataAudit() {
        log.info("=== Scheduled Doris metadata audit task started ===");
        try {
            // 稽核默认集群的元数据（clusterId 为 null 表示使用默认集群）
            DorisMetadataSyncService.AuditResult result = dorisMetadataSyncService.auditAllMetadata(null);

            log.info("=== Scheduled Doris metadata audit task completed successfully ===");
            log.info("Audit result: {}", result);

            // 如果发现差异，记录详细信息
            if (result.hasDifferences()) {
                log.warn("发现 {} 处元数据差异，建议登录平台手动检查并同步！", result.getTotalDifferences());

                // 记录每个差异的详细信息
                for (DorisMetadataSyncService.TableDifference diff : result.getTableDifferences()) {
                    log.warn("表 {}.{} - 差异类型: {}",
                        diff.getDatabase(), diff.getTableName(), diff.getType());

                    // 记录表级别的变更
                    for (String change : diff.getChanges()) {
                        log.warn("  - {}", change);
                    }

                    // 记录字段级别的变更
                    if (!diff.getFieldDifferences().isEmpty()) {
                        log.warn("  字段差异数量: {}", diff.getFieldDifferences().size());
                        for (DorisMetadataSyncService.FieldDifference fieldDiff : diff.getFieldDifferences()) {
                            log.warn("    - 字段 '{}' ({})", fieldDiff.getFieldName(), fieldDiff.getType());
                        }
                    }
                }
            } else {
                log.info("元数据稽核通过，平台与 Doris 保持一致");
            }

            // 如果有错误，记录错误信息
            if (!result.getErrors().isEmpty()) {
                log.error("稽核过程中发生 {} 个错误:", result.getErrors().size());
                for (String error : result.getErrors()) {
                    log.error("  - {}", error);
                }
            }
        } catch (Exception e) {
            log.error("=== Scheduled Doris metadata audit task failed ===", e);
        }
    }

    /**
     * 测试用的稽核任务 - 每 10 分钟执行一次
     * 生产环境请删除或注释掉此任务
     */
    // @Scheduled(fixedRate = 600000) // 600000 毫秒 = 10 分钟
    // public void testScheduledDorisMetadataAudit() {
    //     log.info("=== Test Doris metadata audit task started (every 10 minutes) ===");
    //     try {
    //         DorisMetadataSyncService.AuditResult result = dorisMetadataSyncService.auditAllMetadata(null);
    //         log.info("=== Test Doris metadata audit task completed ===");
    //         log.info("Audit result: {}", result);
    //         if (result.hasDifferences()) {
    //             log.warn("发现 {} 处差异", result.getTotalDifferences());
    //         }
    //     } catch (Exception e) {
    //         log.error("=== Test Doris metadata audit task failed ===", e);
    //     }
    // }
}
