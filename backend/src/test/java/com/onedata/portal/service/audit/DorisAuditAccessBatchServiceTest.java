package com.onedata.portal.service.audit;

import com.onedata.portal.entity.DorisAuditAccessCheckpoint;
import com.onedata.portal.entity.TableAccessDaily;
import com.onedata.portal.mapper.DorisAuditAccessCheckpointMapper;
import com.onedata.portal.mapper.DorisAuditProcessedEventMapper;
import com.onedata.portal.mapper.TableAccessDailyMapper;
import com.onedata.portal.mapper.TableAccessUserDailyMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DorisAuditAccessBatchServiceTest {

    @Mock
    private DorisAuditProcessedEventMapper processedEventMapper;
    @Mock
    private TableAccessDailyMapper dailyMapper;
    @Mock
    private TableAccessUserDailyMapper userDailyMapper;
    @Mock
    private DorisAuditAccessCheckpointMapper checkpointMapper;

    @Test
    void duplicateEventDoesNotIncrementSummary() {
        DorisAuditAccessBatchService service = service();
        DorisAuditAccessEvent event = event();
        when(processedEventMapper.insertIgnore(eq(1L), eq("q-1"), any())).thenReturn(0);

        DorisAuditAccessBatchService.BatchApplyResult result = service.applyBatch(
                1L, "audit", event.getEventTime().minusDays(90),
                Collections.singletonList(event), event.getEventTime(), "q-1", "READY");

        assertEquals(0, result.getAcceptedEvents());
        assertEquals(1, result.getDuplicateEvents());
        verify(dailyMapper, never()).upsertBatch(anyList());
        verify(userDailyMapper, never()).upsertBatch(anyList());
    }

    @Test
    void acceptedEventAggregatesOneTotalWithReadAndWriteRoles() {
        DorisAuditAccessBatchService service = service();
        DorisAuditAccessEvent event = event();
        AuditTableReference reference = event.getTableReferences().get(0);
        reference.setRead(true);
        reference.setWrite(true);
        when(processedEventMapper.insertIgnore(eq(1L), eq("q-1"), any())).thenReturn(1);

        service.applyBatch(
                1L, "audit", event.getEventTime().minusDays(90),
                Collections.singletonList(event), event.getEventTime(), "q-1", "READY");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TableAccessDaily>> captor = ArgumentCaptor.forClass(List.class);
        verify(dailyMapper).upsertBatch(captor.capture());
        TableAccessDaily daily = captor.getValue().get(0);
        assertEquals(1L, daily.getTotalAccessCount());
        assertEquals(1L, daily.getReadAccessCount());
        assertEquals(1L, daily.getWriteAccessCount());
        assertEquals(25L, daily.getDurationSumMs());
    }

    @Test
    void failedSummaryWriteDoesNotAdvanceCheckpointAndUsesTransactionBoundary() throws Exception {
        DorisAuditAccessBatchService service = new DorisAuditAccessBatchService(
                processedEventMapper, dailyMapper, userDailyMapper, checkpointMapper);
        DorisAuditAccessEvent event = event();
        when(processedEventMapper.insertIgnore(eq(1L), eq("q-1"), any())).thenReturn(1);
        doThrow(new IllegalStateException("summary write failed"))
                .when(dailyMapper).upsertBatch(anyList());

        assertThrows(IllegalStateException.class, () -> service.applyBatch(
                1L, "audit", event.getEventTime().minusDays(90),
                Collections.singletonList(event), event.getEventTime(), "q-1", "READY"));

        verify(checkpointMapper, never()).insert(any());
        verify(checkpointMapper, never()).updateById(any());
        assertTrue(DorisAuditAccessBatchService.class
                .getMethod("applyBatch", Long.class, String.class, LocalDateTime.class,
                        List.class, LocalDateTime.class, String.class, String.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class));
    }

    @Test
    void checkpointInitializationDoesNotClaimACompletedSync() {
        DorisAuditAccessBatchService service = new DorisAuditAccessBatchService(
                processedEventMapper, dailyMapper, userDailyMapper, checkpointMapper);
        LocalDateTime coverageStart = LocalDateTime.now().minusDays(90);

        service.initializeCheckpoint(1L, "audit", coverageStart, coverageStart);

        ArgumentCaptor<DorisAuditAccessCheckpoint> captor =
                ArgumentCaptor.forClass(DorisAuditAccessCheckpoint.class);
        verify(checkpointMapper).insert(captor.capture());
        assertEquals("BACKFILLING", captor.getValue().getSyncStatus());
        org.junit.jupiter.api.Assertions.assertNull(captor.getValue().getLastSyncedAt());
    }

    private DorisAuditAccessBatchService service() {
        when(checkpointMapper.selectById(1L)).thenReturn(new DorisAuditAccessCheckpoint());
        return new DorisAuditAccessBatchService(
                processedEventMapper, dailyMapper, userDailyMapper, checkpointMapper);
    }

    private DorisAuditAccessEvent event() {
        DorisAuditAccessEvent event = new DorisAuditAccessEvent();
        event.setEventKey("q-1");
        event.setCursorKey("q-1");
        event.setEventTime(LocalDateTime.now().minusMinutes(3));
        event.setUserName("alice");
        event.setQueryTimeMs(25L);
        event.setTableReferences(Collections.singletonList(new AuditTableReference("dw", "orders")));
        return event;
    }
}
