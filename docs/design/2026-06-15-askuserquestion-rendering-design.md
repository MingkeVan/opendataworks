# AskUserQuestion 渲染与选择回填设计

- 日期: 2026-06-15
- 模块: 智能问数(`dataagent/dataagent-backend` + `dataagent/dataagent-frontend`)
- 变更规模: 中(跨后端 runtime / 持久化 / API 与前端渲染)

## 现状

智能问数采用 Claude Agent SDK 运行,后端把原生 SDK 块事件
(`content_block_start/delta/stop`、`tool_result`)落库到 `da_agent_sdk_record`,
前端 `v2StreamParser.js` 重放为消息块,`ToolOutputRenderer.vue` 按工具类型渲染。

仓库已有一套"运行内暂停—渲染卡片—用户操作—回填—恢复"机制,用于写工具的
权限确认(permission):

- 触发:`core/task_executor.py` 的 `can_use_tool` 回调
- 等待:`core/permission_wait.py` 轮询 Redis
- 回填:`POST /tasks/{task_id}/permission-decision` →
  `task_coordinator.submit_permission_decision` 写 Redis
- 落库:`permission_request` / `permission_decision` SDK 记录
- 渲染:`PermissionConfirmationCard.vue`

但目前没有任何让 Agent 主动向用户提问、由用户多选/单选作答的能力。
`allowed_tools` 仅包含 `Skill/Bash/Read/LS/Glob/Grep` 与按需挂载的 portal MCP 工具。

## 问题

希望 Agent 在需求不清时,能弹出多选/单选问题卡片,用户点选后把选择回填给
模型继续推理 —— 即"渲染 AskUserQuestion 工具调用,方便用户选择"。

### 为什么不能直接用 SDK 内置 AskUserQuestion

经查 `claude-agent-sdk`(0.2.x):

- `can_use_tool` 回调只能返回 `PermissionResultAllow(updated_input=...)` 或
  `PermissionResultDeny(...)`,**无法把"用户的选择"作为 tool_result 回传**。
- 内置 `AskUserQuestion` 在无头(headless/SDK)模式下依赖 bridge / 交互终端,
  本部署不具备,允许它执行也拿不到答案。

因此采用**自定义进程内 SDK MCP 工具**:由我们自己的处理函数返回用户选择作为
tool_result,完全可控,且与现有 permission 暂停/恢复机制同构。

## 方案

新增一个进程内 SDK MCP 工具 `ask_user_question`(限定名
`mcp__ask_user__ask_user_question`)。模型调用它时:

1. 处理函数生成 `request_id`,把问题(`questions`)写入 `question_request` SDK 记录,
   前端据此渲染选择卡片。
2. 任务状态置为 `waiting_input`,处理函数在 Redis 上阻塞等待用户作答
   (复用 permission 的自连接 Redis 轮询方式,带超时与取消)。
3. 用户在前端选择后,`POST /tasks/{task_id}/question-answer` 把答案写入 Redis。
4. 处理函数读到答案,落 `question_answer` 记录,状态恢复 `running`,并把答案
   文本作为 tool_result 返回给模型,**同一次运行内**继续推理。

这样:

- 工具调用块本身天然流式下发(`name=mcp__ask_user__ask_user_question`,
  `input.questions`),前端可直接渲染。
- 暂停/恢复语义与 permission 完全一致,复用已验证的基础设施。

### 接口

- 工具输入(模型侧):
  ```json
  { "questions": [
    { "question": "...", "header": "维度(≤12字)", "multiSelect": false,
      "options": [ { "label": "...", "description": "..." } ] } ] }
  ```
- 工具返回(模型侧,tool_result 文本):每个问题的用户选择摘要。
- 新 SDK 记录:
  - `question_request` data: `{ request_id, questions }`
  - `question_answer` data: `{ request_id, answers, answered_at }`
- 新 HTTP 接口:
  `POST /api/v1/nl2sql/tasks/{task_id}/question-answer`
  body `{ request_id, answers: [ { header, question, selected: [..], other: "" } ] }`
- 新任务状态:`waiting_input`(非终态,沿用 `set_task_status`)。
- Redis key:`da:task:answer:{task_id}:{request_id}`。

### 前端

- `v2StreamParser.js`:新增 `question_request` / `question_answer` record 处理,
  生成 `type: 'question_request'` 块。
- `QuestionSelectionCard.vue`:逐题渲染 header 标签 + 题面 + 选项(单选/多选)+
  "其他"自由文本 + 提交按钮。
- `NL2SqlChatV2.vue`:在 permission 分支旁挂载卡片并提交答案。
- `api/nl2sql.js`:`submitQuestionAnswer(taskId, requestId, answers)`。

### 技能引导

`dataagent/.claude/skills/dataagent-nl2sql/SKILL.md` 说明:需求歧义时优先调用
`mcp__ask_user__ask_user_question`,问题简明、选项互斥、`header ≤ 12` 字。
工具的启用由运行时按需挂载,保持通用 runtime 技能无关。

## 取舍

- 选择"运行内阻塞 + 进程内 MCP 工具",而非"多轮 deliver-message",因为前者
  对答案回填是确定性的,且复用 permission 机制;后者依赖模型在 tool_result 后
  恰好停止,不确定。
- 阻塞时长复用 `task_permission_wait_seconds` 上限,避免无界等待。
- 不改动通用 runtime 的工具语义:工具挂载与引导都走"按需 + 技能"路径。

## 验证

- 前端:`QuestionSelectionCard` 渲染与提交、解析器单测、`vite build`。
- 后端:`_project_sdk_records` 投影契约单测(纯函数,无需 DB)。
- 端到端冒烟(需本地 Redis/MySQL/Provider):模型调用工具 → `waiting_input` →
  前端选择 → 回填 → 运行恢复 → 最终回答落库。受环境限制时,按 AGENTS.md
  明确说明未跑通的层。
