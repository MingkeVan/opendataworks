# 📘 数据中台统一门户设计方案（批任务 Dolphin / 流任务 Dinky）

## 一、背景与需求澄清

### 1.1 背景

当前平台基于 **Dinky + DolphinScheduler + Doris** 搭建，用于数据开发、调度和分析。  
但现状存在以下问题：

- Dinky 界面复杂，难以清晰管理任务依赖；
- DolphinScheduler 仅关注任务级调度，不理解数据表模型；
- 缺少全局的“表-任务-血缘”统一视图。

### 1.2 目标

建立一个 **统一数据门户（Data Portal）**：

- 所有数据表、任务、依赖都在门户定义；
- 批任务通过 API 下发至 DolphinScheduler；
- 流任务未来可下发至 Dinky；
- 门户维护血缘与执行状态，构建完整数据资产体系。

---

## 二、系统总体目标与阶段范围

| 阶段 | 目标 | 范围 |
|------|------|------|
| **Phase 1（当前）** | 实现批任务的统一管理与调度 | 仅支持批任务（DolphinScheduler），无认证体系 |
| **Phase 2** | 支持流任务接入（Dinky），统一可视化血缘 | Dolphin + Dinky 双通道协同执行 |
| **Phase 3** | 增强治理（权限、审批、质量监控） | 引入角色管理、质量检测、告警等 |

---

## 三、技术调研结论

### 3.1 DolphinScheduler（批任务）

- ✅ REST API 完备，支持创建工作流、任务节点、调度计划；
- ✅ 可执行 Shell、SQL、Spark、Flink 等多类型任务；
- ✅ 支持 Token 鉴权；
- ✅ 支持 HTTP Webhook 回调（可通知门户任务结果）；
- ⚠️ Flink SQL 任务能力有限，但可通过 SQL 或 Shell 节点执行批作业。

### 3.2 Dinky（流任务）

- ✅ 提供 OpenAPI，可提交、启动 Flink SQL / Jar 作业；
- ✅ 支持作业运行状态查询；
- ⚠️ API 创建任务定义能力较弱（需预创建模板）；
- ⚠️ 无回调通知机制（需轮询或 Flink 通知）；
- ⚠️ 鉴权需配置 Token；
- 🚀 适合作为“流计算作业执行引擎”，未来扩展接入。

### 3.3 Doris（存储层）

- 支持 MySQL 协议，易于获取元数据；
- 可用作结果表与指标表；
- 与门户同步元数据结构（字段、大小、更新时间）。

---

## 四、产品设计

### 4.1 系统定位

门户是 **数据建模、任务下发、血缘展示与调度反馈中心**。

### 4.2 核心功能模块

| 模块 | 功能描述 | 阶段 |
|------|-----------|------|
| 表管理 | 表注册、字段定义、分层（ODS/DWD/DIM/DWS） | ✅ Phase 1 |
| 任务管理 | 任务定义、SQL 模型、输入输出依赖 | ✅ Phase 1 |
| 批任务调度 | 通过 API 下发至 DolphinScheduler | ✅ Phase 1 |
| 执行监控 | 状态查询、运行日志、时长统计 | ✅ Phase 1 |
| 流任务管理 | 下发 Flink SQL 至 Dinky，实时监控 | 🔜 Phase 2 |
| 血缘视图 | 生成 DAG（基于 MySQL 关系） | ✅ Phase 1 |
| 审批/权限 | 用户体系、角色控制 | 🔜 Phase 3 |

---

## 五、系统架构设计

┌──────────────────────────────┐
│ 数据门户（Spring Boot + Vue3） │
│ - 表管理、任务建模、血缘可视化、任务下发 │
│ - 存储于 MySQL │
└──────────────┬────────────────┘
│
REST API 调度下发
│
┌──────────────┴─────────────┐
│ DolphinScheduler（批任务调度） │
│ - 工作流定义、执行、回调通知 │
└──────────────┬─────────────┘
│
┌──────────────┴─────────────┐
│ Doris / ODS / DWD / DIM 存储层 │
│ - 存放任务输出表、指标表 │
└──────────────────────────────┘

### Python 调度适配服务（新增）

- 新增 `dolphinscheduler-service`（FastAPI + apache-dolphinscheduler SDK），负责将门户侧的任务同步为 DolphinScheduler 工作流；
- Java 门户通过 REST 调用 Python 服务，Python 服务再通过 SDK/Java Gateway 调用 DolphinScheduler，降低对 DolphinScheduler REST 版本差异的敏感度；
- 提供工作流确保、DAG 同步、上线/下线等接口，便于后续扩展（指标、监控、鉴权等能力）。

shell
复制代码

### Phase 2 扩展架构（流任务）

┌──────────────────────────────┐
│ 数据门户 │
│ ├── DolphinScheduler 批任务接口 │
│ └── Dinky 流任务接口 │
└──────────────┬──────────────┘
批任务 流任务
(周期执行) (实时执行)

sql
复制代码

---

## 六、数据库模型（MySQL）

| 表名 | 作用 |
|------|------|
| `data_table` | 存储表元信息（层级、负责人、状态） |
| `data_field` | 字段定义（类型、描述） |
| `data_task` | 任务定义（SQL、类型、调度配置） |
| `data_lineage` | 表间依赖关系（upstream/downstream） |
| `task_execution_log` | 执行记录（状态、耗时、输出行数） |

### 任务类型区分

```sql
ALTER TABLE data_task ADD COLUMN task_type ENUM('batch','stream') DEFAULT 'batch';
ALTER TABLE data_task ADD COLUMN engine ENUM('dolphin','dinky') DEFAULT 'dolphin';
七、接口设计（RESTful）
7.1 创建任务
http
复制代码
POST /api/v1/tasks
{
  "name": "load_dwd_user",
  "sql": "insert into dwd_user select * from ods_user",
  "task_type": "batch",
  "engine": "dolphin",
  "schedule": "0 3 * * *",
  "inputs": ["ods_user"],
  "outputs": ["dwd_user"]
}
7.2 发布任务（批）
http
复制代码
POST /api/v1/tasks/{id}/publish
{
  "scheduler": "dolphin",
  "auto_start": true
}
7.3 Dolphin 回调
http
复制代码
POST /api/v1/tasks/callback
{
  "task_id": 12,
  "status": "SUCCESS",
  "start_time": "2025-10-17T03:00:00",
  "end_time": "2025-10-17T03:04:00",
  "rows_output": 15890
}
7.4 获取血缘图
http
复制代码
GET /api/v1/lineage
Response:
{
  "nodes": [
    {"id": "ods_user", "layer": "ODS"},
    {"id": "dwd_user", "layer": "DWD"}
  ],
  "edges": [
    {"source": "ods_user", "target": "dwd_user"}
  ]
}
八、Portal 与 Dolphin 调度交互流程
bash
复制代码
用户 → 门户：定义任务（输入输出表、SQL）
门户 → Dolphin：调用 /process-definition 创建流程
门户 → Dolphin：调用 /executors/start 触发执行
Dolphin → 门户：HTTP Webhook 回调执行结果
门户 → MySQL：记录日志、更新血缘状态
门户 → 前端：展示任务运行状态
九、后续扩展（流任务接入 Dinky）
目标
接入 Dinky OpenAPI，支持流作业（Flink SQL / JAR）；

门户通过 engine 字段区分任务类型；

提供实时状态监控与手动停止功能。

接口示例
http
复制代码
POST /api/v1/tasks/stream/start
{
  "task_id": 88,
  "engine": "dinky"
}
查询流任务状态：

http
复制代码
GET /api/v1/tasks/stream/status?task_id=88
Response:
{
  "status": "RUNNING",
  "uptime": "5h 23m",
  "throughput": 12500
}
十、实施计划
阶段	时长	内容	输出
Phase 1	6-8 周	批任务功能（门户 + Dolphin 集成）	MVP 版本，可调度批作业
Phase 2	4-6 周	接入流任务（Dinky 集成）	流批统一门户
Phase 3	4 周	权限治理 + 数据质量 + 告警	稳定版上线

✅ 十一、结论
第一阶段仅支持批任务（DolphinScheduler）。

流任务（Dinky）将在第二阶段接入，前端和后端已预留字段与接口。

架构轻量、接口标准、扩展性强。

未来可演进为完整的“数据中台调度 + 治理一体化平台”。
