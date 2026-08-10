package com.onedata.portal.service;

import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.mapper.DolphinConfigMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DolphinConfigServiceTest {

    @Mock
    private DolphinConfigMapper dolphinConfigMapper;

    @Mock
    private RuntimeBindingLock runtimeBindingLock;

    @Test
    void createShouldNormalizeDefaultsAndInsertNamedConfig() {
        DolphinConfigService service = new DolphinConfigService(dolphinConfigMapper, runtimeBindingLock);
        DolphinConfig config = new DolphinConfig();
        config.setConfigName(" New Dolphin ");
        config.setUrl("http://new-ds/dolphinscheduler/");
        config.setToken("token");
        config.setProjectName("new_project");
        config.setTenantCode(null);
        config.setWorkerGroup(null);
        config.setExecutionType(null);
        config.setIsActive(null);
        config.setIsDefault(1);

        service.create(config);

        ArgumentCaptor<DolphinConfig> captor = ArgumentCaptor.forClass(DolphinConfig.class);
        verify(dolphinConfigMapper).insert(captor.capture());
        DolphinConfig saved = captor.getValue();
        assertEquals("New Dolphin", saved.getConfigName());
        assertEquals("http://new-ds/dolphinscheduler", saved.getUrl());
        assertEquals("default", saved.getTenantCode());
        assertEquals("default", saved.getWorkerGroup());
        assertEquals("PARALLEL", saved.getExecutionType());
        assertEquals(Boolean.TRUE, saved.getIsActive());
        assertEquals(Integer.valueOf(1), saved.getIsDefault());
    }

    @Test
    void getEnabledConfigShouldRejectInactiveConfig() {
        DolphinConfigService service = new DolphinConfigService(dolphinConfigMapper, runtimeBindingLock);
        DolphinConfig inactive = new DolphinConfig();
        inactive.setId(2L);
        inactive.setConfigName("stopped");
        inactive.setIsActive(false);
        when(dolphinConfigMapper.selectById(2L)).thenReturn(inactive);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.getEnabledConfig(2L));
        assertEquals("Dolphin 环境未启用: stopped", ex.getMessage());
    }

    @Test
    void deleteShouldRejectRuntimeBoundConfig() {
        DolphinConfigService service = new DolphinConfigService(dolphinConfigMapper, runtimeBindingLock);
        when(dolphinConfigMapper.selectById(3L)).thenReturn(activeConfig(3L));
        when(dolphinConfigMapper.countRuntimeBoundWorkflows(3L)).thenReturn(1L);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.delete(3L));
        assertEquals("Dolphin 环境已被运行态工作流绑定，不能删除", ex.getMessage());
    }

    private DolphinConfig activeConfig(Long id) {
        DolphinConfig config = new DolphinConfig();
        config.setId(id);
        config.setConfigName("active");
        config.setUrl("http://ds/dolphinscheduler");
        config.setToken("token");
        config.setProjectName("opendataworks");
        config.setTenantCode("default");
        config.setWorkerGroup("default");
        config.setExecutionType("PARALLEL");
        config.setIsActive(true);
        config.setIsDefault(0);
        return config;
    }
}
