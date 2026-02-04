package com.onedata.portal.controller;

import com.onedata.portal.dto.DolphinAlertGroupOption;
import com.onedata.portal.dto.DolphinEnvironmentOption;
import com.onedata.portal.dto.Result;
import com.onedata.portal.service.DolphinSchedulerService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * DolphinScheduler 元数据/选项接口（用于前端表单下拉框）
 */
@RestController
@RequestMapping("/v1/dolphin")
@RequiredArgsConstructor
public class DolphinMetaController {

    private final DolphinSchedulerService dolphinSchedulerService;

    @GetMapping("/worker-groups")
    public Result<List<String>> listWorkerGroups() {
        return Result.success(dolphinSchedulerService.listWorkerGroups());
    }

    @GetMapping("/tenants")
    public Result<List<String>> listTenants() {
        return Result.success(dolphinSchedulerService.listTenants());
    }

    @GetMapping("/alert-groups")
    public Result<List<DolphinAlertGroupOption>> listAlertGroups() {
        return Result.success(dolphinSchedulerService.listAlertGroups());
    }

    @GetMapping("/environments")
    public Result<List<DolphinEnvironmentOption>> listEnvironments() {
        return Result.success(dolphinSchedulerService.listEnvironments());
    }

    @PostMapping("/schedules/preview")
    public Result<List<String>> previewSchedule(@RequestBody SchedulePreviewRequest request) {
        if (request == null || !StringUtils.hasText(request.getSchedule())) {
            return Result.fail("schedule is required");
        }
        return Result.success(dolphinSchedulerService.previewSchedule(request.getSchedule()));
    }

    @Data
    public static class SchedulePreviewRequest {
        /**
         * schedule JSON string (startTime/endTime/timezoneId/crontab)
         */
        private String schedule;
    }
}

