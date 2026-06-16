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

### SDK 内置 AskUserQuestion 的工作机制

经查 `claude-agent-sdk`(0.2.x)随包 CLI,`AskUserQuestion` 是**内置工具**:

- 它的 `checkPermissions` 恒返回 `behavior:"ask"`,且 `requiresUserInteraction()`
  为真,因此**总是经过 `can_use_tool` 回调**(即使 `bypassPermissions`)。
- 工具的结果由其**输入上的 `answers` 字段**生成:
  `call({questions, answers})` 把 `answers` 回显进结果,
  `mapToolResultToToolResultBlockParam` 将其格式化为 `"问题"="所选项"` 文本喂回模型。

所以宿主答题的正确方式是:在 `can_use_tool` 中返回
`PermissionResultAllow(updated_input={questions, answers, annotations})`。
不需要任何自定义工具 —— 直接复用本仓库已有的 `can_use_tool` 链路即可。

## 方案

启用内置 `AskUserQuestion`(加入 `allowed_tools`),并在 `can_use_tool` 回调里
拦截它:

1. 回调以 `tool_use_id` 作为 `request_id`,把问题(`questions`)写入
   `question_request` SDK 记录,前端据此渲染选择卡片。
2. 任务状态置为 `waiting_input`,回调在 Redis 上等待用户作答
   (复用 permission 的自连接 Redis 轮询,带超时与取消)。
3. 用户在前端选择后,`POST /tasks/{task_id}/question-answer` 把答案写入 Redis。
4. 回调读到答案,落 `question_answer` 记录,状态恢复 `running`,把答案映射为
   `updated_input.answers` 并 `PermissionResultAllow` 返回,内置工具据此产出
   tool_result,**同一次运行内**继续推理。

要点:

- 用 `can_use_tool` 回调答题是 SDK 的既定机制,**不引入自定义工具**。
- 内置工具的 tool_use 块自身也会流式下发(`name=AskUserQuestion`),前端将其
  抑制,改由 `question_request` 驱动的选择卡片渲染,避免重复。
- 暂停/恢复语义与 permission 完全一致,复用已验证的基础设施。
- AskUserQuestion 因 `requiresUserInteraction` 总会把 `ask` 交给 `can_use_tool`,
  故只需在启用时**安装回调**即可,不改动 SDK 权限模式(`plan`/`bypassPermissions`
  语义保持不变);非写工具在回调中自动放行,`allowed_tools` 仍是能力边界。

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
- `NL2SqlChatV2.vue`:在 permission 分支旁挂载卡片并提交答案;抑制
  `name=AskUserQuestion` 的原始 tool_use 块,避免与卡片重复渲染。
- `api/nl2sql.js`:`submitQuestionAnswer(taskId, requestId, answers)`。

### 提问引导

不引入自定义工具,因此引导即内置 `AskUserQuestion` 的既有训练先验:需求歧义、
存在多种合理理解、或需在若干口径/范围/维度间取舍时调用,问题简明、选项互斥、
`header ≤ 12` 字。是否启用由 `dataagent_ask_user_question_enabled`(默认开)控制,
保持通用 runtime 技能无关。

## 取舍

- 选择"内置 AskUserQuestion + `can_use_tool` 运行内答题",而非自定义工具或
  "多轮 deliver-message":前者是 SDK 既定机制、对答案回填是确定性的,且复用
  permission 暂停/恢复;自定义工具属重复造轮子;多轮方式依赖模型在 tool_result
  后恰好停止,不确定。
- 阻塞时长复用 `task_permission_wait_seconds` 上限,避免无界等待。
- 不改动通用 runtime 的工具语义:工具挂载与引导都走"按需 + 技能"路径。

## 验证

- 前端:`QuestionSelectionCard` 渲染与提交、解析器单测、`vite build`。
- 后端:`_project_sdk_records` 投影契约单测(纯函数,无需 DB)。
- 端到端冒烟(需本地 Redis/MySQL/Provider):模型调用工具 → `waiting_input` →
  前端选择 → 回填 → 运行恢复 → 最终回答落库。受环境限制时,按 AGENTS.md
  明确说明未跑通的层。
