# Widget/Chat Plan Mode Approval Design

## Context

DataAgent 的会话权限模式(`default` / `acceptEdits` / `plan` / `bypassPermissions`)
是会话级选择,持久化在 `da_agent_topic.permission_mode`。widget 与 chat v2 暴露完全
相同的四个选项(`dataagent-frontend/src/widget/WidgetChat.vue`、
`dataagent-frontend/src/views/intelligence/NL2SqlChatV2.vue`)。两个入口都走同一执行
路径:`core/task_coordinator.py`(读 topic.permission_mode)→ `execute_task_stream`
→ `core/task_executor.py` → `core/agent_runtime._resolve_sdk_permission_mode`。

**问题:前台展示的模式语义与后端实际行为不一致,尤以 `plan` 模式为甚。**

根因单一处:`_resolve_sdk_permission_mode` 无视入参**始终返回 `bypassPermissions`**
(root 下降级 `default`),而 `task_executor` 只对 `default`/`acceptEdits` 走 gating,
`plan` 与 `bypassPermissions` 落入 `else` 分支 → SDK 实际都跑 `bypassPermissions`。
`plan` 的"安全"仅靠从 `allowed_tools` 剥离写工具实现;既无计划呈现/批准环节,
内置 `ExitPlanMode` 也被 bypass 自动放行,表现为用户观察到的"进入 plan 模式后自动批准"。

### 修复前四模式审计

| 逻辑模式 | 用户预期 | 当前 SDK perm_mode | 实际行为 | 结论 |
|---|---|---|---|---|
| default | 写/高危先确认 | `default`(gated) | 写操作 → waiting_permission | 符合 |
| acceptEdits | 编辑自动、高危确认 | `default`(gated) | 草稿写自动、高危确认 | 符合 |
| plan | 只读 → 出计划 → 批准 → 执行 | **`bypassPermissions`** | 仅只读;无计划/批准;ExitPlanMode 自动放行 | **不符** |
| bypassPermissions | 全自动 | `bypassPermissions` | 全自动 | 符合 |

## Goal

让 SDK 实际 permission_mode 与逻辑模式 1:1 对齐,`can_use_tool` 成为唯一权限策略引擎,
四个模式的实际行为与前台标签一致;并为 `plan` 模式实现真正的"出计划 → 用户批准 → 同 run
内继续执行"闭环。

## Non-Goals

- 不引入计划文件:计划文本从 `ExitPlanMode` 工具输入读取并持久化到 MySQL,不写工作区文件。
- 不改变 `06-26 permission-confirmation-wait` 的可持久化等待与 lost-runner 行为;plan 批准
  复用同一 `waiting_permission` 机制(含其已知限制:等待期间 runner 丢失 → suspended/run_lost)。
- 不改 portal MCP 写工具集合与高危分类。

## Design

### 目标模型

原则:**SDK permission_mode == 逻辑模式(1:1);`can_use_tool` 是唯一策略引擎;
`allowed_tools` 仅为只读/安全工具的自动放行 fast-path,写工具一律路由到回调(bypass 除外)。**

| 逻辑模式 | SDK perm_mode | 写工具路径 | can_use_tool 行为 |
|---|---|---|---|
| default | `default` | 经回调 | 全部写 → waiting_permission |
| acceptEdits | `acceptEdits` | 经回调 | 草稿自动放行、高危 → waiting_permission |
| plan | `plan` | 经回调 | 写工具 plan-deny;ExitPlanMode → 计划卡 → 批准后 setMode 继续 |
| bypassPermissions | `bypassPermissions`(root 下降级 default) | allowed_tools 直放 | 不触发(bypass 跳过回调) |

### SDK 契约(claude-agent-sdk 0.2.96,已核实源码)

- `PermissionMode` 包含 `"plan"`。
- `can_use_tool(tool_name, input_data, context) -> PermissionResult`;模型调用内置
  `ExitPlanMode` 时回调收到 `tool_name == "ExitPlanMode"`,计划文本在 `input_data["plan"]`。
- `PermissionResultAllow(behavior, updated_input, updated_permissions: list[PermissionUpdate])`。
- `PermissionUpdate(type="setMode", mode=<PermissionMode>, destination="session")`;其
  `to_dict()` 经 `_internal/query.py` 下发给 CLI 控制协议,从而在 run 内切换权限模式。
- `PermissionResultDeny(behavior, message, interrupt=False)`;plan 拒绝保持 `interrupt=False`,
  模型留在 plan 继续完善。

### plan 批准闭环

1. SDK 以 `permission_mode="plan"` 启动;模型只读研究后调用 `ExitPlanMode` 呈现计划。
2. `can_use_tool` 命中 `is_exit_plan_mode`:从 `input_data["plan"]` 取计划文本,记录
   `permission_request`(`risk_level="plan"`),task 置 `waiting_permission`,复用
   `wait_for_decision(task_id, request_id)` 持久化等待。
3. 批准:回调内部可变 `effective_mode` 由 `plan` 翻转为 `post_plan_mode()`(= `acceptEdits`),
   并返回 `PermissionResultAllow(updated_permissions=[PermissionUpdate(setMode, acceptEdits, session)])`,
   SDK 退出 plan,模型在同一 run 内继续执行;后续写工具按 `acceptEdits` 走 gating
   (草稿自动、发布/上线仍确认),不再被 plan-deny。
4. 拒绝:`PermissionResultDeny`,模型继续完善计划后再申请。

`post_plan_mode` 选 `acceptEdits` 而非 `default`:用户已批准整份计划,不应再对每个草稿写
逐条确认;高危发布/上线仍保留确认。该取舍集中在 `core/permission_gate.POST_PLAN_MODE`。

### plan-deny 覆盖范围(防御纵深)

`plan_denies_tool` 覆盖两类:portal MCP 写工具,以及内置文件写工具
`PLAN_DENIED_BUILTIN_TOOLS = {Write, Edit, MultiEdit, NotebookEdit}`。后者虽不在
`allowed_tools` 自动放行集中,但模型仍可能调用;若仅靠 `requires_confirmation(...,"plan")`
会得到 `False` 而被直接放行,违背"只读出计划"承诺,故在 plan 模式显式 deny;批准后切到
`acceptEdits` 时这些工具自动放行。`Bash` 不纳入 plan-deny:它在 `allowed_tools` 中由 SDK
上游自动放行(回调无法拦截),是只读研究的必经路径(skill 脚本、只读 SQL),文件写入被
工作区边界 hook 限定在临时 per-topic 工作区。这是一条**可接受的信任边界,而非硬保证**:
sandbox 会把 DB/portal 凭证(`MYSQL_` / `DATAAGENT_PORTAL_` / `ODW_` 等 env,见
`sandbox_runner_main.py:_FORWARDED_ENV_PREFIXES`)转发进子进程,Bash 理论上可绕过受控的
MCP 路径直达平台状态;plan 阶段依赖模型遵守只读研究,而非 Bash 本身不可写。

### root 兜底(保留,非投机分支)

`_resolve_sdk_permission_mode` 改为恒等映射,**仅** `bypassPermissions` 在 root 下降级
`default`。依据:SDK 在 sandbox runner 内执行(`sandbox_runner_main.py` →
`_execute_task_stream_local`),runner 服务默认 uid 0:0(`deploy/docker-compose.dev.yml`、
prod 同),因其需挂载的 docker socket 拉起子沙箱;Claude Code 拒绝 root 下
`--dangerously-skip-permissions`。该兜底单层、仅作用于 bypass,与其它模式无关,可经
`DATAAGENT_RUNNER_UID/GID` 覆盖去除 root。

### 记录与前端

- `permission_request` 块的 `risk_level` 全链路为自由字符串透传
  (`core/topic_task_store.py`),`"plan"` 取值无需 schema/记录改动;决策端点按
  `(task_id, request_id)` 幂等复用。
- 前端 `chatMessage.js` 的 `permission_request` 投影对 `risk_level==='plan'` 渲染
  "计划待批准"卡片(展示计划全文 + 批准/拒绝),复用现有确认卡决策链路;两个入口
  (NL2SqlChatV2 / WidgetChat)模式选择器补 tooltip 使标签承诺与行为一致。

## Tradeoffs

- plan 批准在同一 run 内继续执行,沿用 06-26 的可持久化等待;若等待期间 runner 丢失,
  task 落 suspended/run_lost,不自动续跑(与现有写确认一致)。
- `post_plan_mode=acceptEdits` 在便捷与安全间取折中;若需更严格可改 `default`,仅一处常量。
- 纯只读会话(未挂 portal 写工具)四模式表现一致(都只读),plan 与 default 的差异仅在
  挂载写工具时显现。
