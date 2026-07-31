package com.onedata.portal.service.audit;

import com.onedata.portal.config.DorisAuditAccessSyncProperties;
import com.onedata.portal.entity.DorisAuditAccessCheckpoint;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖“取消历史回填”的升级转换判定。
 */
class DorisAuditAccessSyncServiceTest {

    private final DorisAuditAccessSyncProperties properties = new DorisAuditAccessSyncProperties();
    private final DorisAuditAccessSyncService service = new DorisAuditAccessSyncService(
            properties, null, null, null, null, null, null, null, null, null, null);

    private final LocalDateTime safeUpperBound = LocalDateTime.now().minusMinutes(2);

    @Test
    void legacyNinetyDayBackfillIsConvertedToIncremental() {
        assertTrue(service.isStaleBackfill(
                checkpoint("BACKFILLING", safeUpperBound.minusDays(90)), safeUpperBound));
    }

    @Test
    void caughtUpClusterIsNeverReset() {
        // READY 表示水位已追平，即使因故障落后很久也不能重置覆盖起点。
        assertFalse(service.isStaleBackfill(
                checkpoint("READY", safeUpperBound.minusDays(3)), safeUpperBound));
    }

    @Test
    void freshlyInitializedCheckpointIsNotTreatedAsStale() {
        assertFalse(service.isStaleBackfill(
                checkpoint("BACKFILLING", safeUpperBound), safeUpperBound));
    }

    @Test
    void backfillWithinOneOverlapWindowIsResumedInsteadOfReset() {
        assertFalse(service.isStaleBackfill(
                checkpoint("BACKFILLING", safeUpperBound.minusMinutes(
                        properties.getOverlapMinutes() - 1)), safeUpperBound));
    }

    private DorisAuditAccessCheckpoint checkpoint(String status, LocalDateTime watermarkTime) {
        DorisAuditAccessCheckpoint checkpoint = new DorisAuditAccessCheckpoint();
        checkpoint.setClusterId(1L);
        checkpoint.setSyncStatus(status);
        checkpoint.setWatermarkTime(watermarkTime);
        return checkpoint;
    }
}
