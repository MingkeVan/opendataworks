# NL2SQL 输入区斜杠命令 设计

- 日期：2026-06-15
- 模块：DataAgent 智能问数（前端 `dataagent-frontend` + 后端 `dataagent-backend`）
- 影响层：前端两处输入区 + 后端命令捕获/读取接口；无 DB Schema、无部署改动

## 现状

智能问数的输入区是一个 `<textarea>`，仅接受自然语言：

- 主聊天 `views/intelligence/NL2SqlChatV2.vue`、嵌入式 `widget/WidgetChat.vue`，二者共用会话引擎 `useNl2SqlChat`（`inputText`、`send`）与纯函数 `chatMessage.js`（`isPlainEnterSubmit`）。

斜杠命令机制（依据 Claude Agent SDK 文档 agent-sdk/slash-commands）：

- **技能即斜杠命令**：`.claude/skills/<name>/SKILL.md` 既可被模型自动选用，也支持 `/<name>` 显式调用。
- **调用方式即发送以 `/` 开头的 prompt**：如 `/compact`、`/fix-issue 123 high`。
- **权威命令清单在 `system/init`**：SDK 每次运行开始会在 `SystemMessage(subtype="init")` 的 `data["slash_commands"]` 给出该会话可用命令名数组（内置 `clear/compact/context/usage` + 技能 + 自定义命令）。

后端 `core/task_executor.py` 的 `SdkResultAccumulator.ingest()` 消费 SDK 消息，但此前**未处理 `SystemMessage`**，`slash_commands` 被丢弃，也无任何接口对外暴露。

## 问题

输入区不支持斜杠命令；且命令清单若由前端臆测（如仅凭 `skill_folders`）既不完整也不权威。

## 目标与范围

- 两处输入区：输入 `/` 弹出可过滤、可键盘导航的命令菜单。
- **命令清单取自后端权威来源**：SDK `system/init` 的 `slash_commands`，而非前端猜测。
- 选中命令把 `/<name> ` 补全进输入框，用户补全参数后正常发送，由 SDK 路由执行（技能/内置命令一致）。
- 不改 DB Schema、不引入新的命令调用协议。

## 方案

### 后端：捕获 + 暴露权威清单

1. 新增 `core/slash_command_cache.py`：进程内 `agent_id -> list[str]` 缓存。API 服务与任务协调器同进程（协调器在 `main.py` 内启动），模块级 dict 即对两端可见。`record_agent_slash_commands` / `get_agent_slash_commands`，best-effort，重启后由下次运行重填。
2. `task_executor.py`：`ingest()` 增加 `SystemMessage` 分支，`subtype=="init"` 时从 `data["slash_commands"]`（兼容直挂属性）取清单，按 `params.agent_snapshot["agent_id"]` 写入缓存。
3. 新接口 `GET /api/v1/dataagent/agents/{agent_id}/slash-commands`（`admin_routes.py` 的 `skills_router`，即前端 `agentApi` 已用的前缀，嵌入式同样可达）：
   - 命中缓存 → `{slash_commands, source:"sdk"}`；
   - 冷启动未命中 → 回退到该 Agent 的 `skill_folders`（SDK 同样以 `/<folder>` 暴露技能），`source:"fallback"`；Agent 不存在 → 404。
   - 响应模型 `AgentSlashCommandsResponse`。

### 前端：拉取 + 渲染 + 调用

1. `api/nl2sql.js` `agentApi.getAgentSlashCommands(agentId)`。
2. `views/intelligence/useSlashCommands.js`（纯函数 + 组合式，两端复用）：
   - 纯函数：`parseSlashQuery`（输入整体匹配 `^/(\S*)$` 才进入命令模式，避免与正文 `/` 冲突）、`filterCommands`、`buildCommand`/`buildCommands`（命令名 → 描述符；内置命令给中文 hint，其余为「技能」）。
   - 组合式 `useSlashCommands({ getCommands, inputText, focusInput })`：维护 `visible/query/activeIndex/filtered`；`syncFromInput`、`handleKeydown`（↑/↓ 移动、Enter/Tab 选中、Esc 关闭，返回是否消费以阻止发送）、`select`（统一把 `/<name> ` 写入并聚焦）。
3. `SlashCommandMenu.vue`：纯展示弹层，`@mousedown.prevent` 防抢焦点。
4. 接入两端：textarea 统一 `@keydown="onComposerKeydown"`（先交给菜单，否则 Enter/keyCode==13 走原发送）、`@input` 加 `syncFromInput`；`handleSend`/`send` 调 `close()`。
5. 拉取时机：Agent 载入/切换时拉取；一次运行结束后再拉一次（主聊天 `afterRun`、嵌入式监听 `isBusy` 由 true→false），让内置命令在首轮运行后出现。
6. 嵌入式处于 shadow root，菜单样式同步进 `widget/styles.js`。

## 取舍

- 命令清单以 SDK `system/init` 为唯一权威来源，前端只渲染、不臆测；冷启动单层回退到 `skill_folders`，运行一次后即被权威清单取代。
- 缓存用进程内 dict 而非新表：零 Schema 改动、低风险；代价是重启后短暂为空，由下次运行重填，可接受（仅 UI 提示）。
- 选中只“补全 token + 由用户发送”，不自动派发，避免误触发 `/clear`、`/compact` 等有副作用的命令。
- 菜单直接展示命令名（与 SDK 一致），内置命令附中文 hint。

## 验证

- 后端：`slash_command_cache` 与 `ingest` 捕获单测、`/agents/{id}/slash-commands` 接口契约测试（sdk/fallback/404）；运行既有 `task_executor`/`admin_routes`/全量 pytest 回归。
- 前端：`useSlashCommands` 纯函数与组合式单测；既有两端组件测试回归；主应用与 widget 两套构建。
- 未跑真实 SDK 端到端：`system/init` 实际字段在本仓未用真实模型运行验证，捕获逻辑以构造的 `SystemMessage` 单测覆盖；接口冷启动/命中路径由契约测试覆盖。
