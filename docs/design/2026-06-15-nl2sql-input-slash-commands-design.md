# NL2SQL 输入区斜杠命令 设计

- 日期：2026-06-15
- 模块：DataAgent 智能问数前端（`dataagent/dataagent-frontend`）
- 影响层：仅前端（主聊天 `NL2SqlChatV2.vue` + 嵌入式 `WidgetChat.vue`），无后端、Schema、部署改动

## 现状

智能问数的输入区是一个 `<textarea>`，仅接受自然语言：

- 主聊天 `views/intelligence/NL2SqlChatV2.vue`：`v-model="inputText"`、`@keydown.enter="onEnterKey"`、`@input="autoResize"`，回车经 `isPlainEnterSubmit` 判定后 `handleSend()`。
- 嵌入式 `widget/WidgetChat.vue`：结构同上（`onEnterKey` / `autoResizeTextarea` / `send`），落地页另有 `preset_questions` 建议气泡。

两者共用会话引擎 `useNl2SqlChat`（提供 `inputText`、`send`）与纯函数 `chatMessage.js`（`isPlainEnterSubmit`）。

技能（skill）方面：

- 技能定义在 `dataagent/.claude/skills/<folder>/SKILL.md`，由 LLM 依据 `description` 自动选用，**没有显式的“命令”概念**，也没有 `/command` 调用路径。
- Agent 档案 `AgentProfile.skill_folders: List[str]` 记录该助手启用的技能目录名；`GET /agents`、`GET /agents/{id}` 均返回完整档案。主聊天经 `listAgents()` 加载，嵌入式经 `getAgent()` 加载，两端都能拿到 `skill_folders`。
- 技能清单的富信息（描述）只在 admin 路由 `/v1/dataagent/skills/documents`，嵌入式外部用户不可依赖，故不作为命令数据源。

## 问题

输入区不支持斜杠命令，用户无法在输入框内快速“触发技能”或执行常用会话操作。

## 目标与范围

- 在两处输入区支持斜杠命令：输入 `/` 弹出可过滤、可键盘导航的命令菜单。
- 命令来源（单一主路径，两端一致）：
  - **技能命令**：来自当前 Agent 的 `skill_folders`。选中后向输入框插入一条“请使用某技能”的自然语言指令，由用户补全问题后正常发送，从而引导 Agent 调用该技能。
  - **内置操作命令**：`/clear` 清空输入（两端）；`/new` 新建话题（仅主聊天）。选中后直接执行。
- 不改动后端、API、Schema；不引入新的技能调用协议。

## 方案

新增前端模块，两个输入区复用：

### 1. `views/intelligence/useSlashCommands.js`

纯函数 + 组合式 API：

- 纯函数（可单测）：
  - `parseSlashQuery(text)`：输入整体匹配 `^/(\S*)$` 时返回查询串，否则返回 `null`。即仅当输入是单个以 `/` 开头、不含空格的 token 时进入命令模式。
  - `filterCommands(commands, query)`：对 `id`/`label` 做大小写不敏感子串匹配。
  - `buildSkillCommand(folder)` / `buildSkillCommands(folders)`：把技能目录名构造成命令描述符。
- 组合式 `useSlashCommands({ getCommands, inputText, focusInput })`：维护 `visible`、`query`、`activeIndex`、`filtered`；暴露 `syncFromInput()`、`handleKeydown(event)`（↑/↓ 移动、Enter/Tab 选中、Esc 关闭，返回是否已消费以阻止发送）、`select(cmd)`、`close()`、`setActive(i)`。

命令描述符：`{ id, type: 'skill' | 'builtin', label, hint, insertText?, run? }`。

- `type==='builtin'`：选中时清空 `/token` 并执行 `run()`。
- `type==='skill'`：选中时把 `inputText` 设为 `insertText`（形如 `请使用「<folder>」技能：`）并聚焦输入框，等待用户补全。

### 2. `views/intelligence/SlashCommandMenu.vue`

纯展示组件，浮于输入框上方。Props：`visible`、`commands`、`activeIndex`；事件：`select`、`hover`。条目用 `@mousedown.prevent` 防止抢焦点后再发 `select`。样式贴合现有 v2/widget 视觉。

### 3. 接入两个输入区

- textarea 改为统一 `@keydown="onComposerKeydown"`（移除独立 `@keydown.enter`）：先交给 `handleKeydown`，已消费则返回；否则在 plain-Enter 时 `preventDefault()` 并发送。
- `@input` 处增加 `syncFromInput()`，保留原 `autoResize`。
- 模板渲染 `<SlashCommandMenu>`，`select` 走组合式 `select(cmd)`。
- 主聊天命令 = `buildSkillCommands(当前 agent.skill_folders)` + `/clear`、`/new`；嵌入式 = `buildSkillCommands(agent.skill_folders)` + `/clear`。
- 主聊天 `listAgents()` 归一化补充保留 `skill_folders`（当前被丢弃）；嵌入式已有。
- `handleSend()` / `send()` / `handleSuggestion()` 中调用 `close()`，避免发送后菜单残留。

## 取舍

- 技能无原生命令协议，选择“插入自然语言指令”而非新增后端调用契约，保持零后端改动、低风险、两端一致。
- 命令标签直接用技能目录名（Agent 与运行时的规范标识），避免依赖 admin 描述接口，嵌入式同样可用。
- 触发条件限定为“输入整体为单个 `/token`”，避免与正文中的 `/`（如路径、日期）冲突。

## 验证

- 对 `useSlashCommands.js` 纯函数补充 `vitest` 单测（解析、过滤、技能命令构造、键盘选中）。
- 前端 `nvm use` 后运行该模块相关单测与构建检查。
