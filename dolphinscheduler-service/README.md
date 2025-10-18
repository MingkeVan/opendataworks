# Dolphinscheduler-Service (Python)

## 背景与目标
原先的后端服务直接通过 Java WebClient 调用 DolphinScheduler 的 REST API 来完成工作流同步与触发。这种实现紧耦合、API 差异大且难以覆盖 DolphinScheduler 的全部能力。新的 `dolphinscheduler-service` 通过 Python SDK (`apache-dolphinscheduler`) 与 DolphinScheduler 交互，为其它服务提供稳定的 REST 接口，实现：

- **解耦**：后端只需关注业务编排，调度细节交由独立服务维护；
- **统一封装**：通过 SDK 复用 DolphinScheduler 的建模、校验与 Java Gateway 机制；
- **扩展能力**：集中管理登录、任务构建、错误重试、指标采集等能力。

## 架构设计

```
┌─────────────────────────┐
│ data-portal backend     │
│ (Spring Boot, Java)     │
└────────────┬────────────┘
             │ REST (JSON)
┌────────────▼────────────┐
│ dolphinscheduler-service │
│  • FastAPI               │
│  • apache-dolphinscheduler│
└────────────┬────────────┘
             │ Py4J / HTTP
┌────────────▼────────────┐
│ DolphinScheduler         │
│  • API Server            │
│  • Master/Worker         │
│  • Java Gateway          │
└─────────────────────────┘
```

- **API 层 (FastAPI)**：提供面向 Java 的 REST 接口，负责参数校验、鉴权（预留）、监控埋点。
- **调度适配层**：基于 `apache-dolphinscheduler` SDK 与 Py4J 网关交互，封装工作流、任务、依赖关系、实例触发等操作。
- **配置管理**：使用 `pydantic` 读取环境变量或 `.env` 文件，允许动态调整 DolphinScheduler 连接信息。

## 主要功能

| 功能                     | 说明                                                                                   |
|--------------------------|----------------------------------------------------------------------------------------|
| 工作流确保/创建          | 通过 SDK `Workflow.submit()` 创建或更新工作流，保证指定名称的工作流存在。             |
| DAG 同步                 | 根据上游传入的任务、依赖、坐标信息生成 `Shell` 等任务，并提交整个 DAG。               |
| 上下线控制               | 支持将工作流发布到 `ONLINE/OFFLINE`。                                                  |
| 实例触发                 | 触发一次工作流执行并返回实例 ID。                                                     |
| 健康检查                 | 简单返回服务状态，可扩展加入依赖探测结果。                                             |

## REST 接口约定

| 方法 | 路径                                      | 说明                                      |
|------|-------------------------------------------|-------------------------------------------|
| POST | `/api/v1/workflows/ensure`                | 确保项目下存在指定工作流，返回 code       |
| POST | `/api/v1/workflows/{code}/sync`           | 同步任务定义、依赖和布局                  |
| POST | `/api/v1/workflows/{code}/release`        | 更新工作流上线状态 (`ONLINE`/`OFFLINE`)，需携带 `workflowName` |
| POST | `/api/v1/workflows/{code}/start`          | 启动作业实例，返回执行标识，需要 `workflowName` |
| GET  | `/health`                                 | 服务健康检查                              |

接口响应统一为：

```json
{
  "success": true,
  "code": 0,
  "message": "ok",
  "data": {}
}
```

业务异常返回 `success=false`，`code` 为明确的错误码（如 `DS-LOGIN-FAILED`）。

## 配置

| 环境变量                         | 默认值           | 说明                                   |
|----------------------------------|------------------|----------------------------------------|
| `DS_SERVICE_HOST`                | `0.0.0.0`        | FastAPI 绑定地址                       |
| `DS_SERVICE_PORT`                | `8081`           | FastAPI 监听端口                       |
| `PYDS_JAVA_GATEWAY_ADDRESS`      | `127.0.0.1`      | DolphinScheduler Java Gateway 地址     |
| `PYDS_JAVA_GATEWAY_PORT`         | `25333`          | Java Gateway 端口                      |
| `PYDS_USER_NAME`                 | `admin`          | DolphinScheduler 用户名                |
| `PYDS_USER_PASSWORD`             | `dolphinscheduler123` | DolphinScheduler 密码             |
| `PYDS_USER_TENANT`              | `default`        | 默认租户                               |
| `PYDS_WORKFLOW_PROJECT`          | `data-portal`    | 默认项目名称，与 Java 端配置对齐       |
| `LOG_LEVEL`                      | `INFO`           | 服务日志级别                           |

支持通过 `.env` 文件覆盖上述配置。

## 依赖安装与启动

```bash
cd dolphinscheduler-service
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn dolphinscheduler_service.main:app --host 0.0.0.0 --port 8081
```

部署时需保证：

1. DolphinScheduler 已启用 Python Gateway（默认 25333）；
2. 服务账号具备目标项目的创建与发布权限；
3. Java 端的 `dolphin.service-url` 指向该服务。

## 日志与监控

- 采用标准 `logging`，输出结构化 JSON（见实现）。
- TODO：可加入 Prometheus 指标（成功/失败次数、耗时等）。

## 后续扩展建议

1. 支持 Token 鉴权或 mTLS，避免内部接口暴露风险；
2. 增加回调或 Webhook，实现实例状态回传；
3. 引入缓存与熔断策略，提高对下游 DolphinScheduler 的鲁棒性；
4. 将任务模板化，支持多种任务类型（Spark、Python、HTTP 等）。
