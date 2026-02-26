package com.onedata.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.DolphinTaskGroupOption;
import com.onedata.portal.dto.dolphin.DolphinPageData;
import com.onedata.portal.dto.dolphin.DolphinProject;
import com.onedata.portal.dto.dolphin.DolphinTaskGroup;
import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.service.dolphin.DolphinOpenApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DolphinSchedulerServiceTest {

    @Mock
    private DolphinConfigService dolphinConfigService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private DolphinOpenApiClient openApiClient;

    private DolphinSchedulerService service;

    @BeforeEach
    void setUp() {
        service = new DolphinSchedulerService(dolphinConfigService, objectMapper, openApiClient);

        DolphinConfig config = new DolphinConfig();
        config.setProjectName("it_project");
        when(dolphinConfigService.getActiveConfig()).thenReturn(config);

        DolphinProject project = new DolphinProject();
        project.setCode(100L);
        when(openApiClient.getProject("it_project")).thenReturn(project);
    }

    @Test
    void listTaskGroupsShouldKeepGlobalGroupsWhenFilteringByProject() {
        DolphinTaskGroup currentProjectGroup = taskGroup(1, "tg-project", 100L);
        DolphinTaskGroup globalGroup = taskGroup(2, "tg-global", 0L);
        DolphinTaskGroup otherProjectGroup = taskGroup(3, "tg-other", 200L);

        DolphinPageData<DolphinTaskGroup> page = new DolphinPageData<>();
        page.setTotalList(Arrays.asList(currentProjectGroup, globalGroup, otherProjectGroup));
        when(openApiClient.listTaskGroups(1, 200, null, null)).thenReturn(page);

        List<DolphinTaskGroupOption> options = service.listTaskGroups(null);
        assertEquals(2, options.size(), "应保留当前项目任务组 + 全局任务组");
        assertTrue(options.stream().anyMatch(item -> "tg-project".equals(item.getName())),
                "当前项目任务组应存在");
        assertTrue(options.stream().anyMatch(item -> "tg-global".equals(item.getName())),
                "全局任务组(projectCode=0)应存在");
        assertTrue(options.stream().noneMatch(item -> "tg-other".equals(item.getName())),
                "其他项目任务组应被过滤");
    }

    private DolphinTaskGroup taskGroup(int id, String name, Long projectCode) {
        DolphinTaskGroup group = new DolphinTaskGroup();
        group.setId(id);
        group.setName(name);
        group.setProjectCode(projectCode);
        group.setGroupSize(5);
        group.setUseSize(0);
        group.setStatus("1");
        return group;
    }
}
