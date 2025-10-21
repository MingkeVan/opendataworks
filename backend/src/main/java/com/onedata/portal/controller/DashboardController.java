package com.onedata.portal.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.onedata.portal.dto.DashboardStatistics;
import com.onedata.portal.dto.Result;
import com.onedata.portal.entity.DataDomain;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.InspectionIssue;
import com.onedata.portal.entity.TaskExecutionLog;
import com.onedata.portal.mapper.DataDomainMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.InspectionIssueMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 控制台统计 Controller
 */
@RestController
@RequestMapping("/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DataTableMapper dataTableMapper;
    private final DataTaskMapper dataTaskMapper;
    private final DataDomainMapper dataDomainMapper;
    private final TaskExecutionLogMapper taskExecutionLogMapper;
    private final InspectionIssueMapper inspectionIssueMapper;

    /**
     * 获取控制台统计数据
     */
    @GetMapping("/statistics")
    public Result<DashboardStatistics> getStatistics() {
        try {
            // 1. 统计表数量
            Long totalTables = dataTableMapper.selectCount(null);

            // 2. 统计任务数量
            Long totalTasks = dataTaskMapper.selectCount(null);

            // 3. 统计域数量
            Long totalDomains = dataDomainMapper.selectCount(null);

            // 4. 统计执行总次数
            Long totalExecutions = taskExecutionLogMapper.selectCount(null);

            // 5. 统计成功执行次数
            QueryWrapper<TaskExecutionLog> successWrapper = new QueryWrapper<>();
            successWrapper.eq("status", "success");
            Long successExecutions = taskExecutionLogMapper.selectCount(successWrapper);

            // 6. 统计失败执行次数
            QueryWrapper<TaskExecutionLog> failedWrapper = new QueryWrapper<>();
            failedWrapper.eq("status", "failed");
            Long failedExecutions = taskExecutionLogMapper.selectCount(failedWrapper);

            // 7. 统计运行中执行次数
            QueryWrapper<TaskExecutionLog> runningWrapper = new QueryWrapper<>();
            runningWrapper.eq("status", "running");
            Long runningExecutions = taskExecutionLogMapper.selectCount(runningWrapper);

            // 8. 计算执行成功率
            Double executionSuccessRate = 0.0;
            if (totalExecutions > 0) {
                executionSuccessRate = (successExecutions * 100.0) / totalExecutions;
                executionSuccessRate = Math.round(executionSuccessRate * 100.0) / 100.0; // 保留两位小数
            }

            // 9. 统计今日执行次数
            LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
            LocalDateTime todayEnd = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);

            QueryWrapper<TaskExecutionLog> todayWrapper = new QueryWrapper<>();
            todayWrapper.ge("start_time", todayStart)
                    .le("start_time", todayEnd);
            Long todayExecutions = taskExecutionLogMapper.selectCount(todayWrapper);

            // 10. 统计今日成功次数
            QueryWrapper<TaskExecutionLog> todaySuccessWrapper = new QueryWrapper<>();
            todaySuccessWrapper.eq("status", "success")
                    .ge("start_time", todayStart)
                    .le("start_time", todayEnd);
            Long todaySuccessExecutions = taskExecutionLogMapper.selectCount(todaySuccessWrapper);

            // 11. 统计今日失败次数
            QueryWrapper<TaskExecutionLog> todayFailedWrapper = new QueryWrapper<>();
            todayFailedWrapper.eq("status", "failed")
                    .ge("start_time", todayStart)
                    .le("start_time", todayEnd);
            Long todayFailedExecutions = taskExecutionLogMapper.selectCount(todayFailedWrapper);

            // 12. 统计待解决问题数（巡检 - open状态）
            Long openIssues = 0L;
            Long criticalIssues = 0L;
            try {
                QueryWrapper<InspectionIssue> openIssuesWrapper = new QueryWrapper<>();
                openIssuesWrapper.eq("status", "open");
                openIssues = inspectionIssueMapper.selectCount(openIssuesWrapper);

                // 13. 统计严重问题数（critical级别且open状态）
                QueryWrapper<InspectionIssue> criticalIssuesWrapper = new QueryWrapper<>();
                criticalIssuesWrapper.eq("status", "open")
                        .eq("severity", "critical");
                criticalIssues = inspectionIssueMapper.selectCount(criticalIssuesWrapper);
            } catch (Exception e) {
                // 如果巡检表不存在或查询失败，使用默认值0
                System.out.println("Failed to query inspection issues: " + e.getMessage());
            }

            // 构建统计结果
            DashboardStatistics statistics = DashboardStatistics.builder()
                    .totalTables(totalTables)
                    .totalTasks(totalTasks)
                    .totalDomains(totalDomains)
                    .totalExecutions(totalExecutions)
                    .successExecutions(successExecutions)
                    .failedExecutions(failedExecutions)
                    .runningExecutions(runningExecutions)
                    .executionSuccessRate(executionSuccessRate)
                    .openIssues(openIssues)
                    .criticalIssues(criticalIssues)
                    .todayExecutions(todayExecutions)
                    .todaySuccessExecutions(todaySuccessExecutions)
                    .todayFailedExecutions(todayFailedExecutions)
                    .build();

            return Result.success(statistics);
        } catch (Exception e) {
            return Result.fail("获取统计数据失败: " + e.getMessage());
        }
    }
}
