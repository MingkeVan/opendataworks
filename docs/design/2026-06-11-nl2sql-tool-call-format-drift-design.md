# NL2SQL 伪工具调用格式漂移收口 — 设计

> **2026-06-16 更新：本设计描述的「伪工具调用 / format-drift 检测与改判」已整体移除。**
> 该检测会扫描 thinking 文本、把含工具标签子串的正常回答误判为错误，属不可控因素。
> 详见 `docs/design/2026-06-16-nl2sql-runtime-simplification-design.md`。本文保留作历史记录。
> `empty_completion` / `incomplete_answer` 改判与一次性自动恢复不受影响，仍然保留。


## 背景与问题

在智能问数（NL2SQL）链路中，部分评测用例（ARCH_RISK_002 / ARCH_RISK_003 /
ARCH_PERF_005）出现「工具/SQL 已经实际执行、却没有可用最终回答」的现象。

定位后确认：根因不是 SQL 口径问题，也主要不是评测器误判，而是**工具调用格式漂移**。
模型把伪/假的工具调用 XML 标签（如 `</parameter></function></tool_call>`、
`<tool_call>` 等）当作纯文本吐出来，通常出现在某个 `thinking` 块的结尾，而不是发起
真正的 `tool_use`。

发生时的链路表现：

- SDK 不会把这段泄漏文本识别成真实工具调用。
- 模型本轮「正常」结束（`ResultMessage` 不是错误）。
- `SdkResultAccumulator.build_result()` 落入成功分支，把任务标记为 `finished`，
  内容取 `content or "已完成。"`。
- 由于 `thinking` 与 tool_result 内容本就不进入最终可见回答，最终答案为空或停在
  「准备查询阶段」。

净效果：一个其实已经取到数据的任务，被静默地报告为已完成、却给出死胡同回答。

## 现状代码

- `core/task_executor.py`
  - `SdkResultAccumulator`：只有 `text` 类型块进入最终答案；`_ingest_stream_event`
    对非 `text` delta 提前 return，`thinking`（伪标签所在）内容被丢弃。
  - `build_result()` 成功分支返回 `finished` + `content or "已完成。"`。
- `core/agent_runtime.py`
  - `_recover_partial_content(question, main_text, blocks, reason)`：可复用的兜底
    收口函数——有可见文本时返回清洗后的文本 + 「未完整结束」提示；无文本但 `blocks`
    含工具输出时返回「请查看上方思考过程中的工具输出」提示；否则返回空串。
  - `_block_has_tool_output`、`_partial_completion_note`、
    `_sanitize_user_visible_content`（当前为 no-op）。
- `prompts/data_agent_system_prompt.md`「四、输出要求」未禁止 XML 风格工具标签。

## 方案

采用「检测 + 兜底收口 / 标错」为主，配合提示词缓解，分两层：

### 1. 运行时检测与收口（主路径）

在 `SdkResultAccumulator` 中新增检测：

- 新增标志 `_saw_pseudo_tool_call`、`_saw_tool_use`。
- 在三条文本路径上检测伪标签：整条消息文本、`text_delta`、以及 **`thinking_delta`**
  （在原非 `text` 提前返回之前检测，但不把 thinking 写入可见答案）。注意 thinking delta
  文本位于 `thinking` 键、text delta 文本位于 `text` 键。
- 出现 `tool_use` 块时置 `_saw_tool_use`。

在 `build_result()` 成功分支返回前，新增**单一显式分支**
（仅在非错误、subtype 非 `error*` 时进入）：

- 若检测到伪标签：
  - 用 `_strip_pseudo_tool_call_tags` 清洗可见文本，连同合成的 tool 输出标记一起交给
    `_recover_partial_content` 收口；
  - （2026-06-12 迭代后）无论是否兜底出内容，任务状态一律为 `error`，错误码
    `tool_call_format_drift`；兜底出的部分文本保留在 `content` 中供历史展示，
    `error.message` 携带面向用户的可重试提示。

错误路径继续走既有 `sdk_writer.append_error(...)`，使漂移也体现在 SDK 记录流中。

仅在收口路径上清洗标签，不做全局过滤——遵循「最小兜底、单层」与未选用全局清洗的决定。

### 2. 提示词缓解

在系统提示词「四、输出要求」新增一条：工具只能经真实 Bash/Skill 等工具调用执行；严禁把
`<tool_call>`、`</tool_call>`、`<function>`、`<parameter>`、`</invoke>` 等 XML 风格
标签当文本或思考内容输出；已取到数据时直接写最终结论。该规则属通用输出格式约束，置于
系统提示词而非技能包，符合「共享运行时保持技能无关」的约束。

## 2026-06-12 迭代：漂移一律标错 + 前端错误卡片重试

首版上线后用户反馈：深度思考中出现 `</parameter></function></tool_call>` 后对话直接
终止，前端没有任何错误提示，也无法恢复。复盘确认是首版「兜底成 `finished`」的盲区：

- 实时聊天视图渲染的是 SDK 记录流里的原始块（含泄漏标签的 thinking），兜底拼出的
  「工具调用格式异常」说明只写进了持久化消息，**实时流里前端只收到一条正常的 `done`**；
- 于是用户看到思考停在泄漏标签处、对话静默结束，既无法感知失败也无法重试。

本迭代调整为：

1. 后端 `_build_format_drift_result` 不再区分能否兜底——漂移一律 `task_status = "error"`，
   错误码 `tool_call_format_drift`，兜底文本仍保留在 `content`；复用既有
   「`build_result` 为 error 时 `sdk_writer.append_error(...)`」通道，使实时流必然带终止
   `error` 记录（写入时机在 `finish_task` 之前，流端点按「任务终态且无新记录」停止，
   记录可送达）。前端实时与刷新后（持久化 `status=error`）均渲染错误卡片，话题徽标同步。
2. 前端共享引擎 `useNl2SqlChat` 新增 `retryMessage(failedMessage)`：取失败回复之前最近
   一条用户提问，作为**正常新轮次**重新发送（`deliver-message` 每次投递都会持久化用户
   消息，重发可保证实时视图与刷新后的历史一致；失败回复保留在会话中作为失败记录）。
3. 门户 `NL2SqlChatV2.vue` 错误卡片增加「重试」按钮（流式进行中禁用；Widget 审计只读
   视图不显示），点击后恢复并继续对话。

范围说明：可发送消息的嵌入式 `WidgetChat.vue` 本次未加重试按钮（其既有错误卡片不变），
如需可在后续迭代直接复用引擎的 `retryMessage`。

## 2026-06-15 迭代：纯思考空回答（无 tool_use）标错可重试

继漂移收口后，用户复现到另一类同源现象：深度思考结束后对话直接停住，任务落库为
`task_status=finished`、`error` 为空、`content=已完成。`，前端无报错也无法感知，只能手动
「继续」才把答案补出来。

复盘确认这是漂移收口的**残留盲区**：

- `SdkResultAccumulator.build_result()` 成功兜底分支 `content or "已完成。"` 把「非错误
  结束 + 可见回答为空」当作正常完成。`current_answer_text()` 只收集 `text` 块，`thinking`
  内容本就不进可见答案，因此**纯思考结束、无可见文本、无 `tool_use`、且未命中伪标签**的
  回合会落进这条静默成功分支。
- 06-12 迭代只在命中已登记伪标签（`_saw_pseudo_tool_call`）时才标错；本类现象没有可识别
  的伪标签（模型思考后直接 stop，常见于第三方 `anthropic_compatible` 端点的扩展思考），
  于是绕过漂移护栏，静默成 `finished + 已完成。`。
- 「继续就好了」是因为后续走 resume 路径（`resume_session_id`）重发同一会话，模型补出
  上一轮跳过的回答。空回合是模型/代理侧产物，不是后端崩溃。

初版调整：

1. `build_result()` 成功分支在返回前新增**单一窄分支**:**可见回答为空且本轮无真实
   `tool_use`（`not content and not self._saw_tool_use`）时 `task_status="error"`**，错误码
   `empty_completion`，`error.message` 为面向用户的可重试提示。复用既有「`build_result`
   为 error 时 `sdk_writer.append_error(...)`」通道，使实时流必然带终止 `error` 记录；前端
   复用 06-12 已有的错误卡片与「重试」按钮（均对任意 `status=error` 生效，**前端无需改动**）。
   其余情况保持原 `finished + content or "已完成。"` 行为不变。
2. 漂移收口 `_build_format_drift_result` 与空回答收口 `_build_empty_completion_result`
   抽取共用私有方法 `_build_incomplete_run_result(content, reason, error_code, message,
   fallback)`，统一「合成 tool 输出标记 → `_recover_partial_content` 兜底文本 → 组装 `error`
   结果」，避免重复 guard 分支（遵循「最小兜底、单层」）。其中合成 tool 输出标记仅对**漂移**
   路径有意义（漂移可发生在真实 `tool_use` 之后）；空回答路径因 `_saw_tool_use` 为假，兜底
   恒为「无可重试内容」文案。

取舍（范围）：本次只收口「无真实 `tool_use` 的纯思考空回答」——这正是用户复现的场景，且
重试安全。**有真实 `tool_use` 但无文字结论的空回答先保持原 `finished`（`content or
"已完成。"`）不变**：这类运行已经实际执行过工具、可能含写操作，标错并引导重试有重复执行
（如重复写）的风险，故暂不纳入本次收口。如后续需要，再单独评估更安全的收口方式（例如指向
工具输出的非重试提示，而非走重试按钮）。

## 2026-06-15 追加迭代：空回答自动恢复一次

后续通过导出的 child 容器挂载目录复盘到更精确证据：

- Claude project JSONL 中，同一 `chatcmpl` 先产生 `thinking`，随后以 `stop_reason=end_turn`
  结束，最终 `text` 仅为两个换行。
- Claude CLI telemetry 记录 `tengu_model_whitespace_response`，metadata 标明
  `length=2`，证明这是模型/CLI 层可识别的 whitespace-only assistant response。
- 同会话下一轮用户追问时，模型能够意识到上一轮中断并继续回答，说明通过同一 session
  追加一次恢复提示有实际修复价值。

因此将「直接标错」升级为「先自动恢复一次，失败再标错」：

1. 仍由 `SdkResultAccumulator.build_result()` 识别 `empty_completion`，识别条件不扩大：
   非错误结束、无可见回答、无真实 `tool_use`、未命中伪工具调用漂移。
2. `core/task_executor.py` 在把 `empty_completion` 写成终止错误前，若存在可 resume 的
   `session_id`，自动发起**一次**内部恢复 turn，使用同一 SDK session、同一工具/权限/技能
   配置，并给模型一条最小诊断提示：
   「上一轮 `end_turn` 但最终只输出空白、未调用工具、未给用户可见回答；请继续完成原始问题，
   必须输出可见回答」。
3. 恢复提示不持久化为平台用户消息，只存在 Claude session JSONL 中；恢复过程产生的 SDK
   records 仍写入当前 task，使实时流和历史投影可以看到恢复后的 assistant blocks。
4. 恢复 turn 使用独立短超时，按当前任务超时的一半推导，范围为 `10-120s`，常见 `60s`
   任务恢复超时为 `30s`，`180s` 为 `90s`，`420s` 为 `120s`。不新增部署配置，避免再引入一层
   timeout knob。
5. 若恢复 turn 产生可见回答，则当前任务最终为 `finished`；若仍为空、无 session、取消、
   或恢复异常，则按既有错误路径返回 `empty_completion` 或具体异常错误。

安全边界：

- 只对**无真实 `tool_use`**的空回答自动恢复，避免重复执行已经发生过的工具调用，尤其是写操作。
- 恢复最多一次，不做循环或级联 fallback。
- 只向模型暴露最小诊断事实，不把 runner log、路径、token 状态、工具输出等运行时日志注入模型。

## 接口与契约变化

- 任务错误码 `tool_call_format_drift`：2026-06-12 起对所有伪工具调用漂移返回
  （首版仅在无可兜底内容时返回）；兜底出的部分文本保留在任务 `content` 中。
- 任务错误码 `empty_completion`：2026-06-15 起，非错误结束、无可见回答**且无真实
  `tool_use`**的运行不再静默 `finished + "已完成。"`。追加迭代后该错误码只在一次自动
  resume 恢复仍无法产生可见回答、缺少可 resume session、或恢复被取消/失败时返回。有真实
  `tool_use` 但无文字结论的空回答仍保持 `finished + "已完成。"`，本次不改。
- 前端共享引擎新增动作 `retryMessage(failedMessage)`。
- `_recover_partial_content` 复用，不改签名。
- 新增内部辅助 `_contains_pseudo_tool_call` / `_strip_pseudo_tool_call_tags`
  与 `_partial_completion_note` 的「工具调用格式」分支。

## 取舍

- 自动追加一次 continuation 收口只用于 `empty_completion`，不用于伪工具调用漂移或有真实
  `tool_use` 的空回答；这避免重复工具副作用，同时覆盖当前已证实的 whitespace-only
  assistant response。
- 不新增全局标签清洗器，也不做「答案是否引用 query_result」的收口门禁——超出本次范围。

## 范围外

- 全局可见文本标签清洗。
- query_result 与最终答案一致性门禁。
