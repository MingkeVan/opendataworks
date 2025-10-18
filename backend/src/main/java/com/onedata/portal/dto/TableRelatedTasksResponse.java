package com.onedata.portal.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 表关联任务响应
 */
@Data
public class TableRelatedTasksResponse {

    private List<TableTaskInfo> writeTasks = new ArrayList<>();
    private List<TableTaskInfo> readTasks = new ArrayList<>();
}
