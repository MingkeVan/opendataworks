package com.onedata.portal.scheduled;

import com.onedata.portal.service.audit.DorisAuditAccessSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.mockito.Mockito.mock;

class DorisAuditAccessSyncTaskTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(DorisAuditAccessSyncService.class, () -> mock(DorisAuditAccessSyncService.class))
            .withUserConfiguration(DorisAuditAccessSyncTask.class);

    @Test
    void disabledPropertyDoesNotCreateScheduledTask() {
        contextRunner
                .withPropertyValues("doris.audit-access-sync.enabled=false")
                .run(context -> org.junit.jupiter.api.Assertions.assertFalse(
                        context.containsBean("dorisAuditAccessSyncTask")));
    }

    @Test
    void taskIsEnabledByDefault() {
        contextRunner.run(context -> org.junit.jupiter.api.Assertions.assertTrue(
                context.containsBean("dorisAuditAccessSyncTask")));
    }
}
