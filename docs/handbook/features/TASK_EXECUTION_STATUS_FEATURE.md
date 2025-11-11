# 任务执行状态展示功能

## 功能概述

在任务管理页面中，现在可以查看每个任务的最近一次执行状态，包括执行时间、执行结果等信息，并且可以直接跳转到 DolphinScheduler Web UI 查看详细信息。

## 实现的功能

1. ✅ **显示最近执行状态**
   - 执行状态（等待中/运行中/成功/失败/已终止）
   - 执行开始时间（相对时间显示，如"5分钟前"、"2小时前"）
   - 状态标签颜色区分（成功=绿色、失败=红色、运行中=黄色等）

2. ✅ **跳转到 DolphinScheduler**
   - 提供"查看Dolphin"按钮
   - 点击后在新标签页打开 DolphinScheduler Web UI
   - 直接定位到对应的工作流定义页面

3. ✅ **通过 Python 服务获取实时状态**
   - 所有与 DolphinScheduler 的交互都通过 Python 服务
   - 支持获取工作流实例的实时状态
   - 自动映射 DolphinScheduler 状态到本地状态

## 技术实现

### 后端实现

#### 1. DolphinSchedulerService 新增方法

**文件**: `backend/src/main/java/com/onedata/portal/service/DolphinSchedulerService.java`

```java
/**
 * Get workflow instance status via dolphinscheduler-service.
 */
public JsonNode getWorkflowInstanceStatus(Long workflowCode, String instanceId)

/**
 * Get DolphinScheduler Web UI URL for workflow instance.
 */
public String getWorkflowInstanceUrl(Long workflowCode)
```

#### 2. 新增 DTO

**文件**: `backend/src/main/java/com/onedata/portal/dto/TaskExecutionStatus.java`

包含字段：
- `taskId`: 任务ID
- `executionId`: 执行ID
- `status`: 执行状态
- `startTime`: 开始时间
- `endTime`: 结束时间
- `durationSeconds`: 持续时间
- `errorMessage`: 错误信息
- `dolphinWorkflowCode`: DolphinScheduler 工作流代码
- `dolphinWebUrl`: DolphinScheduler Web UI URL

#### 3. DataTaskService 新增方法

**文件**: `backend/src/main/java/com/onedata/portal/service/DataTaskService.java`

```java
/**
 * 获取任务的最近一次执行状态
 */
public TaskExecutionStatus getLatestExecutionStatus(Long taskId)
```

功能：
- 从数据库获取最近一次执行记录
- 通过 Python 服务获取 DolphinScheduler 实时状态
- 生成 DolphinScheduler Web UI 跳转链接
- 状态映射（DolphinScheduler 状态 → 本地状态）

#### 4. Controller API

**文件**: `backend/src/main/java/com/onedata/portal/controller/DataTaskController.java`

```java
/**
 * 获取任务最近一次执行状态
 */
@GetMapping("/{id}/execution-status")
public Result<TaskExecutionStatus> getExecutionStatus(@PathVariable Long id)
```

#### 5. 配置文件

**文件**: `backend/src/main/resources/application.yml`

```yaml
dolphin:
  service-url: http://localhost:5001
  web-url: http://localhost:12345/dolphinscheduler  # 新增
  project-name: test-project
```

**文件**: `backend/src/main/java/com/onedata/portal/config/DolphinSchedulerProperties.java`

```java
/** DolphinScheduler Web UI base URL for generating jump links. */
private String webUrl = "http://localhost:12345/dolphinscheduler";
```

### 前端实现

#### 1. API 方法

**文件**: `frontend/src/api/task.js`

```javascript
// 获取任务执行状态
getExecutionStatus(id) {
  return request.get(`/v1/tasks/${id}/execution-status`)
}
```

#### 2. 任务列表页面

**文件**: `frontend/src/views/tasks/TaskList.vue`

**新增表格列**:
```vue
<el-table-column label="最近执行" width="200">
  <template #default="{ row }">
    <div v-if="row.executionStatus" class="execution-status">
      <div>
        <el-tag :type="getExecutionStatusType(row.executionStatus.status)" size="small">
          {{ getExecutionStatusText(row.executionStatus.status) }}
        </el-tag>
      </div>
      <div class="execution-time" v-if="row.executionStatus.startTime">
        {{ formatTime(row.executionStatus.startTime) }}
      </div>
    </div>
    <span v-else class="text-gray">未执行</span>
  </template>
</el-table-column>
```

**新增操作按钮**:
```vue
<el-button link type="info" @click="openDolphinUI(row)"
           v-if="row.executionStatus && row.executionStatus.dolphinWebUrl">
  查看Dolphin
</el-button>
```

**核心方法**:

```javascript
// 加载执行状态
const loadExecutionStatuses = async () => {
  const promises = tableData.value.map(async (task) => {
    try {
      const status = await taskApi.getExecutionStatus(task.id)
      task.executionStatus = status
    } catch (error) {
      task.executionStatus = null
    }
  })
  await Promise.all(promises)
}

// 打开 DolphinScheduler UI
const openDolphinUI = (row) => {
  if (row.executionStatus && row.executionStatus.dolphinWebUrl) {
    window.open(row.executionStatus.dolphinWebUrl, '_blank')
  }
}

// 格式化时间（相对时间）
const formatTime = (timeStr) => {
  // 返回 "5分钟前"、"2小时前" 等
}
```

## 状态映射

### DolphinScheduler 状态 → 本地状态

| DolphinScheduler 状态 | 本地状态 | 显示文本 | 颜色 |
|---------------------|--------|---------|------|
| RUNNING_EXECUTION   | running | 运行中 | 黄色 |
| SUBMITTED_SUCCESS   | running | 运行中 | 黄色 |
| SUCCESS             | success | 成功 | 绿色 |
| FAILURE / FAILED    | failed  | 失败 | 红色 |
| STOP / KILL         | killed  | 已终止 | 灰色 |
| 其他                 | pending | 等待中 | 蓝色 |

## API 调用流程

```
前端: TaskList.vue
    ↓ 调用 taskApi.getExecutionStatus(taskId)
    ↓
后端: DataTaskController.getExecutionStatus()
    ↓ 调用 dataTaskService.getLatestExecutionStatus()
    ↓
    ├─→ 从数据库查询最近执行记录 (task_execution_log)
    │
    └─→ 通过 DolphinSchedulerService 调用 Python 服务
        ↓ HTTP POST: /api/v1/workflows/{workflowCode}/instances/{instanceId}
        ↓
    Python 服务 (dolphinscheduler-service)
        ↓ 调用 DolphinScheduler SDK
        ↓
    返回工作流实例状态
```

## DolphinScheduler Web UI 跳转

### URL 格式

```
{webUrl}/projects/{projectName}/workflow/definitions/{workflowCode}
```

### 示例

```
http://localhost:12345/dolphinscheduler/projects/test-project/workflow/definitions/19373185461472
```

## 使用指南

### 1. 配置 DolphinScheduler Web URL

在 `application.yml` 中配置：

```yaml
dolphin:
  web-url: http://your-dolphinscheduler-host:port/dolphinscheduler
```

### 2. 查看任务执行状态

1. 登录系统
2. 进入"任务管理"页面
3. 在任务列表中查看"最近执行"列
   - 如果任务执行过，会显示状态和时间
   - 如果未执行过，显示"未执行"

### 3. 跳转到 DolphinScheduler

1. 在任务列表找到已执行的任务
2. 点击"查看Dolphin"按钮
3. 自动在新标签页打开 DolphinScheduler Web UI
4. 直接定位到对应的工作流定义页面

## 注意事项

1. **Python 服务依赖**
   - 所有 DolphinScheduler 交互都通过 Python 服务
   - 确保 Python 服务 (`dolphinscheduler-service`) 正常运行
   - 默认地址: `http://localhost:5001`

2. **状态更新**
   - 执行状态在页面加载时获取
   - 如需刷新状态，可重新加载页面或实现定时刷新

3. **性能考虑**
   - 执行状态是并行加载的
   - 如果任务较多，可能需要一定加载时间

4. **权限控制**
   - 需要确保用户有访问 DolphinScheduler Web UI 的权限

## 未来优化方向

1. **实时状态刷新**
   - 添加定时轮询或 WebSocket 实时更新执行状态
   - 运行中的任务自动刷新进度

2. **更多执行信息**
   - 显示执行进度百分比
   - 显示任务日志预览
   - 显示资源使用情况

3. **批量操作**
   - 批量查看多个任务的执行状态
   - 批量重试失败的任务

4. **执行历史**
   - 查看任务的所有历史执行记录
   - 执行记录趋势图表

5. **告警通知**
   - 任务失败时自动告警
   - 执行时间异常提醒

## 故障排查

### 问题1：无法显示执行状态

**可能原因**:
- Python 服务未启动或无法连接
- 数据库中没有执行记录

**解决方法**:
1. 检查 Python 服务状态
2. 确认任务至少执行过一次
3. 查看后端日志错误信息

### 问题2：无法跳转到 DolphinScheduler

**可能原因**:
- `dolphin.web-url` 配置错误
- DolphinScheduler Web UI 未启动

**解决方法**:
1. 检查 `application.yml` 中的 `web-url` 配置
2. 确认 DolphinScheduler Web UI 可访问
3. 检查浏览器是否拦截弹窗

### 问题3：执行状态不准确

**可能原因**:
- 缓存的执行记录未更新
- DolphinScheduler 状态映射问题

**解决方法**:
1. 刷新页面重新加载状态
2. 检查 Python 服务返回的状态值
3. 查看状态映射逻辑是否正确

## 相关文件清单

### 后端文件

1. `DolphinSchedulerProperties.java` - 添加 webUrl 配置
2. `DolphinSchedulerService.java` - 添加获取实例状态和 URL 方法
3. `TaskExecutionStatus.java` - 新建执行状态 DTO
4. `DataTaskService.java` - 添加 getLatestExecutionStatus 方法
5. `DataTaskController.java` - 添加 getExecutionStatus API
6. `application.yml` - 添加 web-url 配置

### 前端文件

1. `task.js` - 添加 getExecutionStatus API 方法
2. `TaskList.vue` - 添加执行状态列和跳转按钮

## 技术栈

- **后端**: Spring Boot 2.7.18, MyBatis Plus, WebClient
- **前端**: Vue 3.4, Element Plus 2.5
- **集成**: DolphinScheduler Python Service
- **数据库**: MySQL 8.0 (task_execution_log 表)

---

## 联系与支持

如有问题或建议，请联系开发团队。
