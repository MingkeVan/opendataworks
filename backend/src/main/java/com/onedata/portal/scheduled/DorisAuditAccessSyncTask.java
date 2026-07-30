package com.onedata.portal.scheduled;

import com.onedata.portal.service.audit.DorisAuditAccessSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Doris 审计访问汇总定时任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "doris.audit-access-sync",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DorisAuditAccessSyncTask {

    private final DorisAuditAccessSyncService syncService;

    @Scheduled(fixedDelayString = "${doris.audit-access-sync.fixed-delay-ms:600000}")
    public void syncAuditAccess() {
        syncService.syncActiveClusters();
    }

    @Scheduled(cron = "0 45 3 * * ?")
    public void cleanupAuditAccess() {
        try {
            syncService.cleanupExpiredData();
        } catch (Exception e) {
            log.warn("Failed to clean Doris audit access summaries: {}", e.getMessage(), e);
        }
    }
}
