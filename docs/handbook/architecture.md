# 架构与运行时

本章整合了原有 `docs/design/*.md` 与根 README 的架构描述，强调“门户 + Python 适配 + 调度引擎”的职责划分，以及运行态观察点。

## 组件一览

```
┌────────────────────────────────────────┐
│ Vue3 + Vite 前端 (frontend/)           │
│  - 表/字段建模、任务向导               │
│  - 血缘/指标/统计可视化                │
└───────────────┬────────────────────────┘
                │ HTTP/REST
┌───────────────▼────────────────────────┐
│ Spring Boot 后端 (backend/)             │
│  - 元数据 API、校验规则、调度编排       │
│  - 集成 MyBatis-Plus、WebFlux           │
│  - 数据库: onedata_portal (MySQL 8)     │
└───────────────┬────────────────────────┘
                │ 内部 HTTP
┌───────────────▼────────────────────────┐
│ DolphinScheduler Service (FastAPI)      │
│  - 基于 apache-dolphinscheduler SDK     │
│  - 负责与 DolphinScheduler API 交互     │
│  - 转译任务定义/上线/下线/回调          │
└───────────────┬────────────────────────┘
        ┌───────▼────────┐        ┌───────────────┐
        │ DolphinScheduler │        │ Dinky (预留) │
        │  - 批任务调度    │        │  - 流任务执行 │
        └──────────────────┘        └───────────────┘
```

## 关键职责与接口

| 模块 | 关键包/目录 | 职责 | 关键接口 |
| --- | --- | --- | --- |
| `backend` | `com.onedata.portal` | 表/字段/任务 CRUD、血缘生成、巡检、执行记录、调用 Python service | `/api/v1/tables`, `/api/v1/tasks`, `/api/v1/lineage`, `/api/v1/dolphin/...` |
| `dolphinscheduler-service` | `dolphinscheduler_service/` | 通过官方 SDK 创建工作流/任务、触发上线/下线、同步执行状态 | `/workflows/sync`, `/workflows/{code}/delete`, `/instances/start` |
| `frontend` | `src/views/*` | 可视化界面、交互校验、日志展示、血缘 ECharts | `/tables`, `/tasks`, `/lineage` 视图 |
| `dinky/` | 上游源码 | 作为未来流任务执行引擎 | 目前仅占位，无需构建 |

## 数据流与生命周期

1. **表建模**：用户在前端填写分层、业务域、字段 → 后端持久化 `data_table`、`data_field` → 可选择生成 Doris DDL。
2. **任务建模**：填写 SQL/调度策略/输入输出 → 后端解析依赖 → 保存 `data_task`、`table_task_relation`、`data_lineage`。
3. **调度下发**：Portal 调用 Python service → service 使用 DS SDK 创建/更新工作流、节点、调度计划 → DolphinScheduler 执行。
4. **执行反馈**：DolphinScheduler 通过 Webhook/轮询结果 → Portal 写入 `task_execution_log`，并刷新血缘/统计。
5. **巡检治理**：`inspection_rule` 定义命名/Owner/统计规则 → 定期生成 `inspection_record` + `inspection_issue` → 前端提示整改。

## 运行态观察点

| 组件 | 端口 | 健康检查 | 日志|
| --- | --- | --- | --- |
| MySQL | 3306 | `mysqladmin ping` | Docker 卷 `mysql-data` or `/var/lib/mysql` |
| Backend | 8080 | `/actuator/health` | `docker volume backend-logs` 或 `backend/logs/` |
| Dolphin Service | 8000/5001 | `/health` | `dolphinscheduler-service/logs/` |
| Frontend | 80/5173 | `/` | Nginx 日志或 `frontend/dist` |

## 技术栈

| 层 | 技术 | 备注 |
| --- | --- | --- |
| 后端 | Java 8+, Spring Boot 2.7.18, MyBatis-Plus 3.5.5, Lombok | 构建脚本 `./mvnw` |
| Python Service | FastAPI, apache-dolphinscheduler SDK, gunicorn | 运行 `uvicorn dolphinscheduler_service.main:app` |
| 前端 | Vue 3.4+, Vite 5, Pinia, Element Plus, ECharts | `npm run dev/build` |
| 数据库 | MySQL 8.0+, Doris (目标库) | 初始化脚本见 `database/mysql` |

## 架构演进提示

- **抽象层**：DolphinScheduler 适配服务隔离了后端与调度引擎；未来扩展其它调度器时可复用与 Portal 的契约。
- **数据一致性**：所有任务/表/血缘信息以 MySQL 为准，DolphinScheduler/Dinky 视为从端，因此必须由 Portal 创建/更新。
- **配置集中化**：统一放在 `deploy/.env`, `backend/src/main/resources/application*.yml`, `dolphinscheduler-service/.env`，并在 `operations-guide.md` 里列出需要修改的变量。
