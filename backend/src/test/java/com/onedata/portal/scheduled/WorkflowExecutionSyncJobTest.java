package com.onedata.portal.scheduled;

import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新鲜度触发的「新变为成功」判定：手动/定时触发、补数排除、已知成功去重。
 */
class WorkflowExecutionSyncJobTest {

    private final WorkflowExecutionSyncJob job = new WorkflowExecutionSyncJob(null, null, null, null);

    private WorkflowInstanceSummary instance(long id, String state, String commandType) {
        return WorkflowInstanceSummary.builder()
            .instanceId(id)
            .state(state)
            .commandType(commandType)
            .build();
    }

    @Test
    void scheduledSuccess_triggers() {
        boolean fired = job.hasNewlySucceeded(
            Collections.singletonList(instance(1L, "SUCCESS", "SCHEDULER")), new HashSet<>());
        assertTrue(fired);
    }

    @Test
    void manualSuccess_triggers() {
        boolean fired = job.hasNewlySucceeded(
            Collections.singletonList(instance(1L, "SUCCESS", "START_PROCESS")), new HashSet<>());
        assertTrue(fired);
    }

    @Test
    void backfillSuccess_doesNotTrigger() {
        // 补数写过去的调度日期，不改变当前新鲜度 → 不触发
        boolean fired = job.hasNewlySucceeded(
            Collections.singletonList(instance(1L, "SUCCESS", "COMPLEMENT_DATA")), new HashSet<>());
        assertFalse(fired);
    }

    @Test
    void backfillMixedWithScheduled_triggersOnScheduledOnly() {
        boolean fired = job.hasNewlySucceeded(Arrays.asList(
            instance(1L, "SUCCESS", "COMPLEMENT_DATA"),
            instance(2L, "SUCCESS", "SCHEDULER")), new HashSet<>());
        assertTrue(fired);
    }

    @Test
    void onlyBackfillSuccesses_doesNotTrigger() {
        boolean fired = job.hasNewlySucceeded(Arrays.asList(
            instance(1L, "SUCCESS", "COMPLEMENT_DATA"),
            instance(2L, "SUCCESS", "COMPLEMENT_DATA")), new HashSet<>());
        assertFalse(fired);
    }

    @Test
    void alreadyKnownSuccess_doesNotTrigger() {
        Set<Long> prior = new HashSet<>(Collections.singletonList(1L));
        boolean fired = job.hasNewlySucceeded(
            Collections.singletonList(instance(1L, "SUCCESS", "SCHEDULER")), prior);
        assertFalse(fired);
    }

    @Test
    void nonSuccess_doesNotTrigger() {
        boolean fired = job.hasNewlySucceeded(
            Collections.singletonList(instance(1L, "RUNNING_EXECUTION", "SCHEDULER")), new HashSet<>());
        assertFalse(fired);
    }
}
