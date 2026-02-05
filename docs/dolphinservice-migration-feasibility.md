# DolphinScheduler Python 服务下线可行性评估

> 说明：Java 后端已完成对 DolphinScheduler OpenAPI 的切换，Python `dolphinscheduler-service` 已从仓库移除，本评估文档仅保留历史记录。

## 计划
- 理清现有 Python dolphinscheduler-service 提供的能力以及依赖（Java Gateway、REST）。
- 查找 Java 后端现状（已通过 HTTP 代理到 Python 服务）与字段契合度。
- 映射到 DolphinScheduler 官方 OpenAPI/REST 能否覆盖相同操作，尤其是工作流创建（含任务、依赖、布局坐标）、上线、运行、查询、删除。
- 给出可落地的 Java 模块重写建议、接口示例与风险点。

## 现状梳理
- Python 服务入口：`dolphinscheduler_service.main` 提供 REST；核心逻辑在 `scheduler.py`。
- 核心功能：同步工作流 (`/api/v1/workflows/{code}/sync`，用 PyDolphinScheduler+Java Gateway 提交任务与依赖)、上线/下线、启动实例、查询实例详情/列表/日志、列数据源、删除流程。
- 依赖：
  - Java Gateway（Py4J）用于：查询项目、获取工作流信息、构建 Workflow/Task 并 `submit()`、`start()`。
  - DolphinScheduler REST：登录拿 sessionId，修改发布状态、列举实例、列数据源、删除流程。
- 任务定义：支持 SHELL/SQL，Java 侧传入 taskCode/taskVersion/taskParams/priority/workerGroup/timeout 等，Python 直接写入 `task.code/task.version`，说明 DolphinScheduler 接受外部自定义 code。
- 布局坐标：请求体里有 locations(x,y)，但 Python 目前未使用（提交时没带），UI 布局依赖默认值。

## 用 Java 直连 DolphinScheduler 的可替代性
- DolphinScheduler 提供完整 REST/OpenAPI（Swagger）可覆盖：
  - 登录/Token：可用 `/login` 获取 sessionId 或安全中心 Token（实际配置存于 `dolphin_config` 表，通过系统管理界面维护）。Java 可用 WebClient/RestTemplate 直接带 `token`/`sessionId` 头/参数。
  - 查询项目：`GET /projects` + `searchVal` 按名称过滤，或 `GET /projects/{projectCode}`。已有 `project-code` 配置可复用。
  - 创建/更新流程定义：`POST /projects/{projectCode}/process-definition`（新增）或 `PUT /projects/{projectCode}/process-definition/{code}`（更新）。入参含：
    - `taskDefinitionJson`（数组，字段与现有 payload 基本一致：`code/name/version/taskType/taskParams/taskPriority/workerGroup/environmentCode/failRetryTimes/failRetryInterval/timeout/flag` 等）。
    - `taskRelationJson`（数组：`preTaskCode/preTaskVersion/postTaskCode/postTaskVersion/conditionType`）。
    - `locations`（数组：`taskCode/x/y`，可承载“长宽高/坐标”需求；宽高可按前端默认 200x60，如需）。
    - `processDefinitionCode`（更新时带）、`name`、`tenantCode`、`executionType`、`warningType` 等。
  - 上下线：`POST /projects/{projectCode}/process-definition/{code}/release`，`releaseState=ONLINE|OFFLINE`。
  - 运行实例：`POST /projects/{projectCode}/executors/start-process-instance`，传 `processDefinitionCode`、`workerGroup`、`failureStrategy`、`warningType`、`taskDependType`、`runMode` 等；返回 `processInstanceId`。
  - 查询实例列表/详情：`GET /projects/{projectCode}/process-instances`（分页、状态、时间过滤），`GET /projects/{projectCode}/process-instances/{instanceId}`。
  - 查看日志：`GET /projects/{projectCode}/task-instances/{taskInstanceId}/log` 或 `/log/detail`（按版本选择具体路径）。
  - 删除流程：`DELETE /projects/{projectCode}/process-definition/{code}`。
  - 数据源列表：`GET /datasources?pageNo&pageSize&type&searchVal`。
- 自定义 taskCode：REST 接口接受前端生成的 Snowflake code（UI 也是前端生成后直接提交），与当前 Java 的自增 long 一致，可继续沿用或调用 DS 的 `gen-task-codes` 接口（如需官方生成）。
- 因此不依赖 Py4J/SDK 也能覆盖现有全部功能，且还能把布局 locations 真正落盘。

## 建议的 Java 模块设计
- 新建 `dolphin-integration` 模块或在现有 `service` 包内增加 `DolphinOpenApiClient`：
  - 负责 token/session 管理、HTTP 调用、错误码封装。
  - 提供方法：`syncWorkflow(code, name, tasks, relations, locations, releaseState)`、`setReleaseState(...)`、`startInstance(...)`、`getInstance(...)`、`listInstances(...)`、`getTaskLog(...)`、`listDatasources(...)`、`deleteWorkflow(...)`。
  - 结构化 DTO 直接复用当前 Java service 里的 Task/Relation/Location 构造器，序列化为 OpenAPI 入参。
  - 支持两种认证：session 登录（用户名/密码）或 Token（安全中心生成）。
  - 选用 WebClient/HttpClient + 封装重试和错误解析（DS 成功通常 `code=0` 或 `success=true`）。
- 迁移步骤建议：
  1) 在 Java 中实现上述客户端并写集成测试对着本地 DS（流程：创建 -> 上线 -> 启动 -> 查询 -> 下线 -> 删除）。
  2) 将现有 `DolphinSchedulerService` 的 HTTP 调用路径从 Python 服务切换到新客户端，并保持返回模型不变，避免上层改动。
  3) 补齐 Python 里未落盘的 `locations`，以便界面显示“长宽高/坐标”。
  4) 双写/灰度：先加开关（usePythonService=false/true），验证后删除 Python 部署。

## 风险与注意事项
- 接口版本差异：不同 DS 版本路径和字段略有差异（3.1 vs 3.2+）；需对照部署版本确认 start/log 接口路径、`taskParams` 字段名大小写。
- 权限/认证：token 模式需在 header `token` 或 cookie `sessionId`，确保和网关/CSRF 设置兼容。
- code 冲突：继续用自增 long 时注意与已有 code 不重复；必要时在切换前读取现有任务 code 并调整初始序列。
- 日志接口：Python 目前返回占位符；迁移后可直接打通 DS 日志接口，需处理大日志分页/offset。
- 超时与错误码：REST 接口可能返回 `code` 非 0；需要比 Python 现有实现更严谨的异常映射，避免吞错。

## 结论
- 从能力覆盖角度，完全可行用 Java 后端直接对接 DolphinScheduler OpenAPI，替换掉 Python dolphinscheduler-service 和 Java Gateway 依赖。
- 主要工作量在于：实现 OpenAPI 客户端（含认证）、映射现有任务/依赖/布局字段、兼容不同 DS 版本的细节、做一次端到端回归。完成后可减少一层服务部署和 Py4J 依赖，运维与故障面会更简单。
