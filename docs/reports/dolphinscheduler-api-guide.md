# Apache DolphinScheduler 官方 API 与 PyDolphinScheduler 用法速览

> 适用版本：基于 `apache/dolphinscheduler` `dev` 分支控制器定义与 `apache/dolphinscheduler-sdk-python` 主干文档整理。涉及路径均来自官方仓库，便于进一步查阅。
>
> 注：OpenDataWorks 已改为由 Java 后端直接调用 DolphinScheduler OpenAPI，Python SDK 与中间服务已从仓库移除，本文档仅供历史参考。

## 1. 认证与访问入口

- **访问地址**：`http://{api-host}:12345/dolphinscheduler`，Swagger UI 位于 `.../swagger-ui/index.html`，可用来确认接口参数 [参考官方文档](https://github.com/apache/dolphinscheduler/blob/dev/docs/docs/en/guide/api/open-api.md#L1-L55)。
- **身份认证**：通过 Web 控制台 -> Security -> Token 管理生成 Token，在所有请求头中携带 `token: <your-token>`。
- **常用请求头**
  - `token: <token-string>`
  - `Content-Type: application/json`（主体为 JSON 时）或 `application/x-www-form-urlencoded`（与 Swagger 示例一致）
  - `Accept: application/json`

## 2. Python Java Gateway 与 PyDolphinScheduler

### 2.1 环境变量快速配置

- 关键变量：`PYDS_JAVA_GATEWAY_ADDRESS`、`PYDS_JAVA_GATEWAY_PORT`、`PYDS_JAVA_GATEWAY_AUTH_TOKEN`、`PYDS_JAVA_GATEWAY_AUTO_CONVERT` 等，可在 shell 或 Python 中设置 [参考 `docs/source/config.rst`](https://github.com/apache/dolphinscheduler-sdk-python/blob/main/docs/source/config.rst#L41-L118)。
- 示例（shell）：

```bash
export PYDS_JAVA_GATEWAY_ADDRESS="192.168.1.10"
export PYDS_JAVA_GATEWAY_PORT="25333"
export PYDS_JAVA_GATEWAY_AUTH_TOKEN="ReplaceMe"
```

- 示例（Python）：

```python
import os
os.environ["PYDS_JAVA_GATEWAY_ADDRESS"] = "192.168.1.10"
os.environ["PYDS_JAVA_GATEWAY_PORT"] = "25333"
```

### 2.2 配置文件覆盖

运行 `pydolphinscheduler config --init` 可导出默认配置到 `~/pydolphinscheduler/config.yaml`，随后用 `pydolphinscheduler config --set` 修改。配置优先级：环境变量 > 配置文件 > 内置默认值 [参考 `docs/source/config.rst`](https://github.com/apache/dolphinscheduler-sdk-python/blob/main/docs/source/config.rst#L88-L186)。

默认配置（节选）中可看到 Java Gateway 与工作流默认属性 [参考 `default_config.yaml`](https://github.com/apache/dolphinscheduler-sdk-python/blob/main/src/pydolphinscheduler/default_config.yaml#L18-L64)，例如：

- 默认地址 `127.0.0.1`、端口 `25333`
- 默认令牌 `jwUDzpLsNKEFER4*a8gruBH_GsAurNxU7A@Xc`（生产环境务必替换）
- 默认工作流 `release_state: online`，提交时自动上线

### 2.3 启动 Python Gateway 服务

在 DolphinScheduler API Server 所在机器上：

```bash
export API_PYTHON_GATEWAY_ENABLED="true"
./bin/dolphinscheduler-daemon.sh start api-server
```

必要时通过 `API_PYTHON_GATEWAY_AUTH_TOKEN` 或 `api-server/conf/application.yaml` 修改令牌 [参考 `docs/source/start.rst`](https://github.com/apache/dolphinscheduler-sdk-python/blob/main/docs/source/start.rst#L97-L174)。`jps` 出现 `ApiApplicationServer` 即表示 API 与 Gateway 正常工作。

### 2.4 PyDolphinScheduler 典型工作流脚本

PyPI 包名为 `apache-dolphinscheduler`。以下脚本演示如何定义任务并运行工作流 [参考示例](https://github.com/apache/dolphinscheduler-sdk-python/blob/main/src/pydolphinscheduler/examples/tutorial.py#L32-L82)：

```python
from pydolphinscheduler.core.workflow import Workflow
from pydolphinscheduler.tasks.shell import Shell

with Workflow(name="tutorial", schedule="0 0 0 * * ? *", start_time="2025-01-01") as workflow:
    parent = Shell(name="task_parent", command="echo hello pydolphinscheduler")
    child = Shell(name="task_child", command="echo child")
    parent.set_downstream(child)
    workflow.run()  # 提交到 Java Gateway，按默认 release_state 在线上执行
```

提交前若 API Server 不在本机，请用 `pydolphinscheduler config --set java_gateway.address <ip>` 覆盖连接信息。

### 2.5 直连 25333 端口的 Java Gateway（Py4J）

PyDolphinScheduler 底层通过 `py4j` 直接连接 API Server 内置的 Python Gateway（默认端口 `25333`、默认地址 `127.0.0.1`，见 [`default_config.yaml`](https://github.com/apache/dolphinscheduler-sdk-python/blob/main/src/pydolphinscheduler/default_config.yaml#L18-L35)）。如果你需要绕过 HTTP API，直接使用 Python 包访问 Gateway，可按下面流程：

1. **保持 Gateway 运行**：参考 2.3 已启用 `API_PYTHON_GATEWAY_ENABLED=true` 的 API Server。
2. **配置连接信息**：用环境变量或 `pydolphinscheduler config --set java_gateway.address <ip> --set java_gateway.port 25333` 指向目标网段。
3. **在代码中实例化 `GatewayEntryPoint`**：

   ```python
   from pydolphinscheduler.java_gateway import GatewayEntryPoint

   entry = GatewayEntryPoint(
       address="api-host",   # 不传则读取配置
       port=25333,
       auth_token="ReplaceMe"  # 与 API Server 上的 python-gateway.auth-token 一致
   )
   gateway = entry.gateway
   print("Gateway version:", entry.get_gateway_version())
   ```

   `GatewayEntryPoint` 会自动检查返回结果并在 Java Gateway 与 SDK 版本不匹配时给出 warning [实现见 `java_gateway.py`](https://github.com/apache/dolphinscheduler-sdk-python/blob/main/src/pydolphinscheduler/java_gateway.py#L24-L120)。

4. **调用 Gateway 暴露的管理方法**：`GatewayEntryPoint` 封装了常用操作（项目、租户、资源等），本质是调用 `gateway.entry_point.<method>`。例如：

   ```python
   result = entry.create_or_grant_project(
       user="userPythonGateway",
       name="python-gateway-demo",
       description="created via py4j"
   )
   print(result)
   ```

   其它可用方法包括 `get_datasource`、`create_or_update_resource`、`query_environment_info`、`grant_tenant_to_user` 等，全部定义在 [`GatewayEntryPoint` 类中](https://github.com/apache/dolphinscheduler-sdk-python/blob/main/src/pydolphinscheduler/java_gateway.py#L54-L205)。

5. **自定义端口 / 自动转换**：若 Gateway 端口非 25333，可在实例化时传入 `port` 或设置 `PYDS_JAVA_GATEWAY_PORT`。如果要关闭 Py4J 的 `auto_convert` 以获得更高性能，也可以在 `GatewayEntryPoint(auto_convert=False)` 中指定。

通过以上方式，Python 直接走 Py4J 套接字，而非 HTTP REST。Workflow 代码（2.4）在执行 `workflow.run()` 时也会使用同一 Gateway，将 DAG 元数据写入 DolphinScheduler。

### 2.6 Gateway 侧的工作流 / 任务方法

PyDolphinScheduler 的 `Workflow` 类在 `submit()` 与 `start()` 阶段分别调用 `GatewayEntryPoint.create_or_update_workflow` 与 `GatewayEntryPoint.exec_workflow_instance`，源码位于 [`workflow.py`](https://github.com/apache/dolphinscheduler-sdk-python/blob/main/src/pydolphinscheduler/core/workflow.py#L406-L490) 与 [`java_gateway.py`](https://github.com/apache/dolphinscheduler-sdk-python/blob/main/src/pydolphinscheduler/java_gateway.py#L241-L314)。常见用法如下：

```python
from pydolphinscheduler.core.workflow import Workflow
from pydolphinscheduler.tasks.shell import Shell

with Workflow(name="gateway-demo", start_time="2025-01-01") as wf:
    cleanup = Shell(name="cleanup", command="echo clean")
    main = Shell(name="main", command="python run.py")
    cleanup.set_downstream(main)

    wf.submit()  # -> gateway.create_or_update_workflow(... task_definition_json, task_relation_json ...)
    wf.start()   # -> gateway.exec_workflow_instance(...)
```

如果需要完全跳过 `Workflow` 上下文、直接调用 Gateway 暴露的工作流方法，可以直接构造 JSON 串并调用下列接口：

| 方法 | 说明 | 参考源码 |
| --- | --- | --- |
| `create_or_update_workflow(...)` | 以 `task_relation_json` / `task_definition_json`/`schedule` 等参数创建或更新工作流定义 | [`java_gateway.py#L258-L295`](https://github.com/apache/dolphinscheduler-sdk-python/blob/main/src/pydolphinscheduler/java_gateway.py#L258-L295) |
| `exec_workflow_instance(...)` | 基于项目名 + workflow 名触发一次实例运行 | [`java_gateway.py#L297-L314`](https://github.com/apache/dolphinscheduler-sdk-python/blob/main/src/pydolphinscheduler/java_gateway.py#L297-L314) |
| `get_workflow_info(...)` | 按用户 / 项目 / workflow 名查询定义详情 | [`java_gateway.py#L252-L256`](https://github.com/apache/dolphinscheduler-sdk-python/blob/main/src/pydolphinscheduler/java_gateway.py#L252-L256) |
| `get_dependent_info(...)` | 获取 workflow 或具体 task 的依赖关系（便于分析 DAG） | [`java_gateway.py#L241-L250`](https://github.com/apache/dolphinscheduler-sdk-python/blob/main/src/pydolphinscheduler/java_gateway.py#L241-L250) |
| `get_code_and_version(...)` | 根据项目 / workflow / task 名获取任务编码、版本 | [`java_gateway.py#L137-L150`](https://github.com/apache/dolphinscheduler-sdk-python/blob/main/src/pydolphinscheduler/java_gateway.py#L137-L150) |

示例：查询并再次运行一个已存在的 workflow

```python
from pydolphinscheduler.java_gateway import GatewayEntryPoint

g = GatewayEntryPoint()
info = g.get_workflow_info("userPythonGateway", "project-pydolphin", "gateway-demo")
print("definition:", info)

run_result = g.exec_workflow_instance(
    user_name="userPythonGateway",
    project_name="project-pydolphin",
    workflow_name="gateway-demo",
    worker_group="default",
    warning_type="NONE",
    warning_group_id=0,
)
print("instance ids:", run_result)
```

任务级别的元数据也可通过 Gateway 查询。例如 `get_code_and_version` 可获得 task code，以便后续结合 REST API 查询任务实例日志；`get_resources_file_info`、`create_or_update_resource` 则能操作脚本 / Jar 等资源，确保在 `workflow.submit()` 前资源已经同步。

> 小贴士：`Workflow.get_tasks_by_name`、`Workflow.get_one_task_by_name`（[`workflow.py#L386-L405`](https://github.com/apache/dolphinscheduler-sdk-python/blob/main/src/pydolphinscheduler/core/workflow.py#L386-L405)）可以在本地 DAG 中快速定位任务并调整依赖，然后再调用 `submit()` 将最新 JSON 推送给 Gateway。

## 3. 工作流生命周期相关 REST API

> 所有路径均在 `http://{api-host}:12345/dolphinscheduler` 下，需在请求头带 `token`。controller 对应源码路径已注明。

### 3.1 工作流定义上线 / 下线

- **接口**：`POST /projects/{projectCode}/workflow-definition/{code}/release`
- **参数**：`releaseState=ONLINE | OFFLINE`
- **出处**：[`WorkflowDefinitionController.java`](https://github.com/apache/dolphinscheduler/blob/dev/dolphinscheduler-api/src/main/java/org/apache/dolphinscheduler/api/controller/WorkflowDefinitionController.java#L352-L377)

示例（上线）：

```bash
curl -X POST \
  "http://{host}:12345/dolphinscheduler/projects/123456789/workflow-definition/987654321/release" \
  -H "token: ${TOKEN}" \
  -d "releaseState=ONLINE"
```

设置 `OFFLINE` 即可下线同一工作流。

### 3.2 启动工作流实例（手动触发）

- **接口**：`POST /projects/{projectCode}/executors/start-workflow-instance`
- **必填参数**：`workflowDefinitionCode`、`scheduleTime`（时间范围或单点）、`failureStrategy`、`warningType`
- **常用可选**：`startNodeList`（逗号分隔节点 code）、`workerGroup`、`tenantCode`、`startParams`
- **出处**：[`ExecutorController.java`](https://github.com/apache/dolphinscheduler/blob/dev/dolphinscheduler-api/src/main/java/org/apache/dolphinscheduler/api/controller/ExecutorController.java#L128-L199)

典型 form 请求（表单编码）：

```bash
curl -X POST \
  "http://{host}:12345/dolphinscheduler/projects/123456789/executors/start-workflow-instance" \
  -H "token: ${TOKEN}" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "workflowDefinitionCode=987654321" \
  -d "scheduleTime=$(date '+%Y-%m-%d %H:%M:%S'),$(date '+%Y-%m-%d %H:%M:%S')" \
  -d "failureStrategy=CONTINUE" \
  -d "warningType=NONE" \
  -d "workerGroup=default"
```

`scheduleTime` 在补数 (`execType=COMPLEMENT_DATA`) 时需指定时间区间或列表，普通启动可给当前时间范围。

> 批量触发可使用 `POST /projects/{projectCode}/executors/batch-start-workflow-instance` 并传入 `workflowDefinitionCodes=code1,code2` 等同组参数 [同源文件第 200-219 行](https://github.com/apache/dolphinscheduler/blob/dev/dolphinscheduler-api/src/main/java/org/apache/dolphinscheduler/api/controller/ExecutorController.java#L200-L219)。

### 3.3 工作流 & 任务状态查询

1. **分页查看工作流实例状态**
   - `GET /projects/{projectCode}/workflow-instances`
   - 关键参数：`workflowDefinitionCode`、`stateType`（`WorkflowExecutionStatus` 枚举，例如 `RUNNING_EXECUTION`、`SUCCESS`、`FAILURE`，定义见 [`WorkflowExecutionStatus.java`](https://github.com/apache/dolphinscheduler/blob/dev/dolphinscheduler-common/src/main/java/org/apache/dolphinscheduler/common/enums/WorkflowExecutionStatus.java)）、`pageNo/pageSize`
   - [出处 `WorkflowInstanceController.java:105-127`](https://github.com/apache/dolphinscheduler/blob/dev/dolphinscheduler-api/src/main/java/org/apache/dolphinscheduler/api/controller/WorkflowInstanceController.java#L105-L127)

   ```bash
   curl -G "http://{host}:12345/dolphinscheduler/projects/123456789/workflow-instances" \
     -H "token: ${TOKEN}" \
     --data-urlencode "workflowDefinitionCode=987654321" \
     --data-urlencode "stateType=RUNNING_EXECUTION" \
     --data-urlencode "pageNo=1" \
     --data-urlencode "pageSize=10"
   ```

   响应 JSON 的 `data.totalList[].state` 就是实例状态，`data.totalList[].startTime/endTime/host` 等字段可辅助排查。

2. **查看单个工作流实例详情**
   - `GET /projects/{projectCode}/workflow-instances/{id}`
   - 可获取当前 `state`、`stateHistory`、`duration` 等更详细字段 [出处 `WorkflowInstanceController.java:200-220`](https://github.com/apache/dolphinscheduler/blob/dev/dolphinscheduler-api/src/main/java/org/apache/dolphinscheduler/api/controller/WorkflowInstanceController.java#L200-L220)

   ```bash
   curl -H "token: ${TOKEN}" \
     "http://{host}:12345/dolphinscheduler/projects/123456789/workflow-instances/456"
   ```

3. **查看某实例下所有任务节点状态**
   - `GET /projects/{projectCode}/workflow-instances/{id}/tasks`
   - 返回列表中每个任务的 `state`, `taskType`, `startTime`, `host` 等字段 [出处 `WorkflowInstanceController.java:137-149`](https://github.com/apache/dolphinscheduler/blob/dev/dolphinscheduler-api/src/main/java/org/apache/dolphinscheduler/api/controller/WorkflowInstanceController.java#L137-L149)

4. **按条件分页查询任务实例状态**
   - `GET /projects/{projectCode}/task-instances`
   - 支持过滤 `workflowInstanceId/name`、`taskCode/name`、`stateType`（`TaskExecutionStatus`，例如 `SUBMITTED_SUCCESS`、`RUNNING_EXECUTION`、`FAILURE`，定义见 [`TaskExecutionStatus.java`](https://github.com/apache/dolphinscheduler/blob/dev/dolphinscheduler-task/src/main/java/org/apache/dolphinscheduler/plugin/task/api/enums/TaskExecutionStatus.java)）、`taskExecuteType` 等 [出处 `TaskInstanceController.java:81-120`](https://github.com/apache/dolphinscheduler/blob/dev/dolphinscheduler-api/src/main/java/org/apache/dolphinscheduler/api/controller/TaskInstanceController.java#L81-L120)

   ```bash
   curl -G "http://{host}:12345/dolphinscheduler/projects/123456789/task-instances" \
     -H "token: ${TOKEN}" \
     --data-urlencode "workflowInstanceId=456" \
     --data-urlencode "stateType=FAILURE" \
     --data-urlencode "pageNo=1" \
     --data-urlencode "pageSize=20"
   ```

5. **Gateway 方式快速查看状态**
   - `GatewayEntryPoint.get_workflow_info(user, project, workflow_name)` 会返回最新上线版本的 workflow 定义以及 `releaseState`、`scheduleState` 等字段。
   - 若需要实时观察运行状态，仍建议使用上述 REST 接口，因为实例级状态由 Master/Worker 维护在 API 层数据库，通过 HTTP 查询可拿到最新结果。

### 3.4 定时调度（Schedule）

- **创建**：`POST /projects/{projectCode}/schedules`
  - 以表单提交为主，其中 `schedule` 为 JSON 字符串（包含 `startTime`、`endTime`、`crontab`、`timezoneId` 等）
- **更新**：`PUT /projects/{projectCode}/schedules/{id}`
- **上线 / 下线**：`POST /projects/{projectCode}/schedules/{id}/online` 与 `/offline`
- **查询**：
  - `GET /projects/{projectCode}/schedules` 分页
  - `POST /projects/{projectCode}/schedules/list` 返回全部列表
  - `POST /projects/{projectCode}/schedules/preview` 预览未来触发时间
- **出处**：[`SchedulerController.java`](https://github.com/apache/dolphinscheduler/blob/dev/dolphinscheduler-api/src/main/java/org/apache/dolphinscheduler/api/controller/SchedulerController.java#L106-L212) 及 [查询相关方法](https://github.com/apache/dolphinscheduler/blob/dev/dolphinscheduler-api/src/main/java/org/apache/dolphinscheduler/api/controller/SchedulerController.java#L232-L307)

> **参数差异（重要）**：部分版本使用 `processDefinitionCode`，部分版本使用 `workflowDefinitionCode`。为了兼容，建议同时传两者（值相同）。
>
> 另外，DolphinScheduler WebUI 创建/编辑调度时通常还会传：`processInstancePriority`、`workerGroup`、`tenantCode`、`environmentCode`、`warningGroupId`。

创建示例（注意 schedule 需转义或使用单引号包裹 JSON；下方同时传了两种 definition code 参数）：

```bash
curl -X POST \
  "http://{host}:12345/dolphinscheduler/projects/123456789/schedules" \
  -H "token: ${TOKEN}" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "processDefinitionCode=987654321" \
  -d "workflowDefinitionCode=987654321" \
  -d "schedule={\"startTime\":\"2025-01-01 00:00:00\",\"endTime\":\"2025-12-31 23:59:59\",\"timezoneId\":\"Asia/Shanghai\",\"crontab\":\"0 0 * * * ? *\"}" \
  -d "processInstancePriority=MEDIUM" \
  -d "warningType=NONE" \
  -d "failureStrategy=CONTINUE" \
  -d "warningGroupId=0" \
  -d "workerGroup=default" \
  -d "tenantCode=default" \
  -d "environmentCode=-1"
```

成功响应示例（`data` 为 schedule 对象，含 `id`；`releaseState` 默认为 `OFFLINE`）：

```json
{
  "code": 0,
  "msg": "成功",
  "data": {
    "id": 38,
    "processDefinitionCode": 156105016810496,
    "startTime": "2026-02-04 00:00:00",
    "endTime": "2126-02-04 00:00:00",
    "timezoneId": "Asia/Shanghai",
    "crontab": "0 0 * * * ? *",
    "failureStrategy": "CONTINUE",
    "warningType": "NONE",
    "releaseState": "OFFLINE",
    "warningGroupId": 0,
    "processInstancePriority": "MEDIUM",
    "workerGroup": "default",
    "tenantCode": "default",
    "environmentCode": -1
  },
  "failed": false,
  "success": true
}
```

查询流程定义时可同时拿到调度信息（包含 `scheduleReleaseState` 与 `schedule` 字段）：

- `GET /projects/{projectCode}/process-definition?pageSize=10&pageNo=1`

示例字段（节选）：

```json
{
  "code": 0,
  "data": {
    "totalList": [
      {
        "code": 156105016810496,
        "name": "test",
        "releaseState": "ONLINE",
        "scheduleReleaseState": "OFFLINE",
        "schedule": {
          "id": 38,
          "processDefinitionCode": 156105016810496,
          "startTime": "2026-02-04 00:00:00",
          "endTime": "2126-02-04 00:00:00",
          "timezoneId": "Asia/Shanghai",
          "crontab": "0 0 * * * ? *",
          "failureStrategy": "CONTINUE",
          "warningType": "NONE",
          "releaseState": "OFFLINE",
          "warningGroupId": 0,
          "processInstancePriority": "MEDIUM",
          "workerGroup": "default",
          "tenantCode": "default",
          "environmentCode": -1
        }
      }
    ]
  }
}
```

更新示例（与创建类似；部分版本会同时在表单里带上 `id` 字段）：

```bash
curl -X PUT \
  "http://{host}:12345/dolphinscheduler/projects/123456789/schedules/38" \
  -H "token: ${TOKEN}" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "id=38" \
  -d "processDefinitionCode=987654321" \
  -d "workflowDefinitionCode=987654321" \
  -d "schedule={\"startTime\":\"2026-02-04 00:00:00\",\"endTime\":\"2126-02-04 00:00:00\",\"timezoneId\":\"Asia/Shanghai\",\"crontab\":\"0 0 * * * ? *\"}" \
  -d "processInstancePriority=MEDIUM" \
  -d "warningType=NONE" \
  -d "failureStrategy=CONTINUE" \
  -d "warningGroupId=0" \
  -d "workerGroup=default" \
  -d "tenantCode=default" \
  -d "environmentCode=-1"
```

上线调度：

```bash
curl -X POST \
  "http://{host}:12345/dolphinscheduler/projects/123456789/schedules/321/online" \
  -H "token: ${TOKEN}"
```

下线调度：

```bash
curl -X POST \
  "http://{host}:12345/dolphinscheduler/projects/123456789/schedules/321/offline" \
  -H "token: ${TOKEN}"
```

### 3.5 任务日志获取

- `GET /log/detail`：按任务实例 ID 分页读取日志（skip/limit）
- `GET /log/{projectCode}/detail`：限定项目范围
- `GET /log/download-log`：下载整份日志文件
- **出处**：[`LoggerController.java`](https://github.com/apache/dolphinscheduler/blob/dev/dolphinscheduler-api/src/main/java/org/apache/dolphinscheduler/api/controller/LoggerController.java#L69-L135)

示例（读取最新 500 行日志）：

```bash
curl -G "http://{host}:12345/dolphinscheduler/log/detail" \
  -H "token: ${TOKEN}" \
  --data-urlencode "taskInstanceId=789" \
  --data-urlencode "skipLineNum=0" \
  --data-urlencode "limit=500"
```

若需下载全部：

```bash
curl -O -J \
  "http://{host}:12345/dolphinscheduler/log/download-log?taskInstanceId=789" \
  -H "token: ${TOKEN}"
```

## 4. 调用建议与注意事项

- **Token 安全**：生产环境务必定期刷新 Token，与 Python Gateway 默认令牌一样需要替换。
- **统一时间格式**：所有时间字段使用 `yyyy-MM-dd HH:mm:ss`（24 小时制），若包含区间则以逗号分隔起止时间。
- **Content-Type**：Swagger 默认使用表单，若改为 JSON，请确保控制器支持；否则坚持与官方示例一致。
- **PyDolphinScheduler 与 REST 并用**：Python SDK 在 `default_config.yaml` 中默认上线工作流，如需仅生成定义而不上线，可将 `PYDS_WORKFLOW_RELEASE_STATE` 或配置文件中的 `release_state` 调整为 `offline`。
- **排查问题**：
  1. 使用 `GET /projects/{projectCode}/workflow-instances` 过滤 `stateType=FAILURE` 快速定位失败实例；
  2. 通过日志接口拉取失败任务日志；
  3. 必要时使用 `POST /projects/{projectCode}/executors/execute`（`executeType` 为 `REPEAT_RUNNING` / `PAUSE` / `STOP` 等）做实例级控制。

以上即覆盖 Python Java Gateway 配置、工作流启动/上下线、定时、状态与日志等常用官方接口。结合 Swagger UI 可进一步探索更多企业级场景（批量补数、动态子流程等）。
