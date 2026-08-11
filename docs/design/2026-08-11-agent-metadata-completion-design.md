# Agent Metadata Completion Design

**Date:** 2026-08-11
**Goal:** 让智能助手（DataAgent widget）能**批量扫描**出元数据薄弱的表，并**逐个完善**其元数据——表描述、字段描述、受控表属性（分层/业务域/数据域）与**数据新鲜度契约**——全程走已有的 portal-mcp + `/v1/ai/*` 后端面。扫描复用现有读工具；新增一个受控的元数据**写**工具与后端接口，安全防线放服务端。
**Tech Stack:** DataAgent skill bundle（`dataagent/.claude/skills/opendataworks-data-dev`）+ portal-mcp（FastMCP，Python）+ backend-agent-api（Java `/v1/ai/*`）+ backend（复用 `TableService` / `TableFreshnessService`）。

## Current State

- **portal-mcp 读工具**已覆盖发现与取证：`portal_search_tables`（按库/表名/注释关键字）、`portal_export_metadata`、`portal_get_table_ddl`、`portal_get_lineage`、`portal_query_readonly`。
- **portal-mcp 写工具**只有"数据开发"面：`portal_create_table` / `portal_create_task` / `portal_update_task` / `portal_create_workflow` / `portal_update_workflow` / `portal_publish_workflow` / `portal_upsert_schedule` …
- 后端 AI 面在独立模块 **`backend-agent-api`**，路由 `/v1/ai/*`：
  - `AgentMetadataController`（`/v1/ai/metadata`）当前**只读**：`inspect` / `lineage` / `datasource/resolve` / `ddl` / `export`。
  - 写面在 `AgentTableController`（`/v1/ai/table`）/ `AgentTaskController` / `AgentWorkflowController`。
  - 委托模式：Controller → `AgentXxxService` 接口（backend-agent-api）→ 实现类在 `backend` 模块，调用真实 `TableService` 等。
- **单表「智能元数据」**（`docs/design/2026-07-24-table-metadata-auto-generation-design.md`）由**前端**编排：前端拼 prompt → 走 NL2SQL 任务 → 前端解析 JSON → 前端调 `PUT /v1/tables/{id}/comment`、`PUT /v1/tables/{id}`、`PUT /v1/tables/{id}/fields/{fieldId}` 写回。安全防线（受控属性 `filterTableAttributes`、枚举只用实测取值 `filterEnumValuesByObserved`、Doris 只改注释的轻量 ALTER）**都在前端**。
- **数据新鲜度契约**（`table_freshness_config`，`TableFreshnessService.saveFreshness(tableId, TableFreshnessRequest, operator)`，业务面 `PUT /v1/tables/{id}/freshness`）未在 `/v1/ai/*` 暴露。
- 「元数据完善度」是前端纯函数（`computeMetadataCompleteness`）、**只针对当前打开的单表**、无服务端存储、无跨表扫描。

## Problem

助手能**建**带注释的新表，却**没有任何工具去完善一张已存在的表的元数据**。批量元数据治理只能人工逐表在 Data Studio 里做。缺一个助手可调用、带服务端防线的元数据写能力，才能实现"从 widget 触发：批量扫描 → 逐个完善"。

## Scope

**做：**

- 后端 `backend-agent-api` 新增受控写端点 `POST /v1/ai/metadata/complete`：一次提交 表描述 + 受控属性 + 逐字段注释 + 新鲜度契约；服务端校验后复用既有 service 写回。
- portal-mcp 新增写工具 `portal_update_table_metadata`（+ backend_client 封装 + 权限门 + 测试）。
- `opendataworks-data-dev` skill 新增「批量扫描 → 逐个完善元数据」recipe，扫描复用现有读工具；同步 skill 文档/内置内容回归测试。

**不做（本次）：**

- 前端新页面（用户明确从 widget 触发，不要 portal UI）。
- 服务端持久化"完善度"或专门的扫描/排名接口（扫描靠现有读工具 + playbook；`GET /v1/ai/metadata/gaps` 列为后续优化）。
- **枚举取值写入**：v1 不写 `enum_values`（fail-closed）。枚举需要"先查实测取值再贴标签"的完整防线（见 2026-07-24 设计的第 3 层），本次先不纳入，留 follow-up；助手可照常产出字段业务含义。

## Solution

### 1. 后端写端点（backend-agent-api + backend impl）

`POST /v1/ai/metadata/complete`，body `AgentMetadataCompleteRequest`：

```
{
  "tableId": 123,                       // 必填；或 database+table 二选一解析
  "database": "dw", "table": "dwd_x",   // tableId 缺失时用于解析
  "tableComment": "……",                 // 可选
  "attributes": {                        // 可选，受控
    "layer": "DWD",
    "businessDomain": "trade",
    "dataDomain": "order"
  },
  "fields": [                            // 可选，逐字段注释
    { "fieldName": "etl_time", "comment": "……" }
  ],
  "freshness": {                         // 可选，等价 TableFreshnessRequest
    "mode": "column", "loadedAtField": "etl_time",
    "warnAfterCount": 1, "warnAfterPeriod": "day",
    "errorAfterCount": 1, "errorAfterPeriod": "day",
    "enabled": true
  }
}
```

实现（backend 模块的 `AgentMetadataService` impl，逐段可选、互相独立）：

- **表描述** → `TableService.updateComment`（既有，同步 Doris 与本地）。
- **受控属性** → 服务端校验后走 `TableService.updateTable`。**防线搬到服务端**（对齐前端 `filterTableAttributes`）：
  - `layer` 必须是已知分层（ODS/DWD/DIM/DWS/ADS，大小写归一）；
  - `businessDomain` 必须是已有编码；`dataDomain` 必须存在且**归属于**所选业务域，否则丢弃（业务域被丢则数据域一并丢）。
  - 采纳属性时提交的分层取「本次属性的分层 || 表上已有分层」，避免 `updateTable` 的"分层非空"校验误伤目标表；两者皆空则该段跳过并在响应里说明。
  - 载荷只带分层与被采纳属性，不带 `tableComment`/`bucketNum`/`replicaNum`，避免触发 Doris 物理变更（沿用 2026-07-30 修订）。
- **字段注释** → 按 `fieldName`（或 `fieldId`）解析到字段后 `TableService.updateField`，走既有 Doris **只改注释**的轻量 `MODIFY COLUMN ... COMMENT` 路径（2026-07-29 修订，避免 key 列 `Invalid column order`）。逐字段写、**非事务**，失败按字段汇总。
- **新鲜度契约** → `TableFreshnessService.saveFreshness(tableId, request, operator)`（既有校验：字段白名单、SQL 形状、模式必填与互斥）。`loadedAtField` 必须是该表真实字段（saveFreshness 已校验）。

**响应**逐段回报 applied/skipped/failed（`tableComment`、`attributes`、`fields[]`、`freshness`），部分失败不整体回滚，模型据此决定是否重试。

`operator` 取 `X-Agent-Operator` 头（沿用其它 Agent 写端点）。鉴权沿用 backend-agent-api 既有 service-token/data-scope 机制。

### 2. portal-mcp 写工具

- `backend_client.update_table_metadata(payload) -> POST /v1/ai/metadata/complete`。
- `app.py` 注册 `portal_update_table_metadata`，annotations `readOnlyHint=False, destructiveHint=False, idempotentHint=False`；input schema 映射上面的 body（snake_case → camelCase 别名，沿用现有工具风格）。
- `permission_gate.py` 归类为**写工具**：`default` 模式触发对话内确认；不列入 `portal_create_table`/`publish`/`schedule-online` 那种"高危仍确认"清单（元数据可改回，风险低于建表/上线）。

### 3. Skill recipe（opendataworks-data-dev）

新增「元数据完善」recipe（reference/30-tool-recipes.md + SKILL.md 能力条目）：

1. **批量扫描找缺口**：`portal_search_tables`（按库/关键字）/ `portal_export_metadata kind=tables` 取表与注释 → 助手据"描述为空/与表名相同、字段注释缺失"判定薄弱表，排出待完善清单。
2. **逐表取上下文**：`portal_get_table_ddl` + 字段 + 必要时 `portal_get_lineage`，据此提案表/字段描述与受控属性；新鲜度按 T-1 语义默认 `warn/error=1天`、`loadedAtField` 选一个真实时间列。
3. **确认后写回**：`portal_update_table_metadata`（写工具触发确认）。逐表循环即"逐个完善"。
4. **安全**：受控属性只填清单内编码；**v1 不编造枚举**；`loadedAtField` 必须是真实字段。

同步更新 skill 生成器/内置内容回归测试（模块规则：改 skill 契约要同一变更内更新文档+生成器+回归测试）。

## Interfaces

**新增：**

- 后端：`POST /v1/ai/metadata/complete`（backend-agent-api `AgentMetadataController` + `AgentMetadataService.complete(...)`，impl 在 backend）。
- portal-mcp：工具 `portal_update_table_metadata`；`backend_client.update_table_metadata`。

**复用，不新增：**

- 扫描/取证：`portal_search_tables` / `portal_export_metadata` / `portal_get_table_ddl` / `portal_get_lineage`。
- 写回底座：`TableService.updateComment` / `updateTable` / `updateField`；`TableFreshnessService.saveFreshness`。

## Risks and Tradeoffs

- **防线搬服务端**：受控属性校验从前端复制到后端 impl，是本次安全关键；两处口径需保持一致（未来可抽公共校验）。
- **枚举 v1 不做**：fail-closed，宁缺毋滥。写入枚举需要实测取值防线，单列。
- **非事务多字段写回**：逐字段失败按字段汇总，模型可对失败项重试；已成功部分即时生效。
- **扫描靠读工具**：大目录下逐库扫描有 token 成本，可接受；`GET /v1/ai/metadata/gaps` 完善度排名接口作为后续优化。
- **新鲜度写入并入元数据完善**：契约本属表级元数据，一次提交更顺；但它会触发新鲜度检查链路，需保证 `loadedAtField` 合法（saveFreshness 已校验）。
- **权限**：写工具默认触发对话内确认，避免助手静默改库。

## Verification

- portal-mcp：`tests/test_write_tools.py` 覆盖新工具的 payload 映射与权限归类；`test_permission_gate.py` 断言 `portal_update_table_metadata` 属写工具。
- backend：`AgentMetadataService` impl 的受控属性校验单测（分层白名单、数据域归属、分层回落）；字段/新鲜度写回委托的契约测试；模块编译。
- skill：`test_builtin_skill_content.py` 断言 recipe/能力条目存在。
- 具备本地环境时补一次 e2e smoke：widget → 扫描一个库 → 对一张薄弱表 complete（描述+属性+新鲜度）→ 校验写回与新鲜度契约生效；否则显式说明未覆盖层。
