# Agent Metadata Completion Plan

**Date:** 2026-08-11
**Design:** [`docs/design/2026-08-11-agent-metadata-completion-design.md`](../design/2026-08-11-agent-metadata-completion-design.md)

受影响栈：backend（写回底座）、backend-agent-api（`/v1/ai/metadata` 写端点）、portal-mcp（写工具）、DataAgent skill（recipe）。

## Tasks

### T1 — 后端写端点（backend-agent-api + backend impl）

- backend-agent-api 新增 DTO `AgentMetadataCompleteRequest`（tableId/database/table、tableComment、attributes{layer,businessDomain,dataDomain}、fields[{fieldName|fieldId,comment}]、freshness≈TableFreshnessRequest）与 `AgentMetadataCompleteResponse`（逐段 applied/skipped/failed）。
- `AgentMetadataService` 接口加 `complete(request, operator)`；`AgentMetadataController` 加 `@PostMapping("/complete")`（取 `X-Agent-Operator`）。
- backend 模块实现 `complete`：
  - 表描述 → `TableService.updateComment`
  - 受控属性 → 服务端校验（分层白名单/业务域存在/数据域归属+分层回落）后 `TableService.updateTable`（只带分层+被采纳属性）
  - 字段注释 → `fieldName→fieldId` 解析后 `TableService.updateField`（只改注释路径），逐字段汇总失败
  - 新鲜度 → `TableFreshnessService.saveFreshness`
- 抽出受控属性校验为可测方法（对齐前端 `filterTableAttributes` 口径）。
- 触碰文件：`backend-agent-api/.../controller/AgentMetadataController.java`、`.../service/AgentMetadataService.java`、`.../dto/AgentMetadataCompleteRequest.java`、`.../dto/AgentMetadataCompleteResponse.java`；`backend/.../agentapi/impl/AgentMetadataServiceImpl.java`（沿用既有 impl 命名）。

### T2 — portal-mcp 写工具

- `backend_client.py`：`update_table_metadata(payload) -> POST /v1/ai/metadata/complete`。
- `service.py`：透传方法。
- `app.py`：注册 `portal_update_table_metadata`（写 annotations）+ pydantic input 模型（snake→camel 别名）。
- `permission_gate.py`：归入写工具（default 触发确认；非高危清单）。
- 触碰文件：`dataagent/portal-mcp/portal_mcp/{backend_client,service,app,config?}.py`、`core/permission_gate.py`。

### T3 — Skill recipe（opendataworks-data-dev）

- `reference/30-tool-recipes.md` 加「元数据完善」recipe（扫描→取上下文→提案→确认→`portal_update_table_metadata`，含新鲜度默认 T-1、v1 不写枚举、受控属性）。
- `SKILL.md` 能力地图加一行「完善元数据」。
- 若有 skill 内置内容生成器/同步，一并更新（模块规则）。
- 触碰文件：`dataagent/.claude/skills/opendataworks-data-dev/{SKILL.md,reference/30-tool-recipes.md}`、内置内容生成器（如存在）。

## Verification

- backend：`mvn -pl backend-agent-api,backend -am -DskipTests compile`；受控属性校验单测；`complete` 委托契约测试。
- portal-mcp：`pytest tests/test_write_tools.py tests/test_permission_gate.py`（新工具映射 + 写归类）。
- skill：`pytest tests/test_builtin_skill_content.py`（recipe/能力条目存在）。
- e2e smoke（有本地环境时）：widget → 扫一个库 → 对一张薄弱表 complete（描述+属性+新鲜度）→ 校验 `data_table`/`data_field` 注释、`data_table` 属性、`table_freshness_config` 写入；无环境则显式说明未覆盖 e2e。

## Rollout

- 纯增量：新端点/新工具/新 recipe，不改既有工具契约与既有前端智能元数据。
- portal-mcp 与 backend-agent-api 需随各自服务发版；skill recipe 随 skill bundle 生效。
- 写工具默认对话内确认，上线即安全默认。

## Backout

- 撤下 `portal_update_table_metadata`（app.py 注册 + permission_gate 条目）即让助手无法写元数据，后端端点闲置无副作用。
- 后端端点与 skill recipe 可独立回退；无 DB schema 变更，无数据迁移需回滚。

## Out of Scope / Follow-ups

- 枚举取值写入（需服务端实测取值防线）。
- `GET /v1/ai/metadata/gaps` 完善度排名接口（省 token 的批量扫描优化）。
- 服务端持久化完善度指标与治理看板。
