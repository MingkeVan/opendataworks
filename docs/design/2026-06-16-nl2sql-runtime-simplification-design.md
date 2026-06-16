# NL2SQL 运行时收口简化 — 设计

## 背景与问题

对比 `v1.3.0` 与当前 main：v1.3.0 的会话链路极简、稳定，很少出现「空回复」或
「思考后直接结束」。v1.3.0→main 之间在 `SdkResultAccumulator.build_result()` 这条
「判定模型本轮是否算回答完」的核心路径上叠加了多层后端改判与拦截，其中部分逻辑会把
本可正常返回的运行改判为错误，或改变了对每一次请求的 SDK 调用形态。

本次只针对其中两处「v1.3.0 没有、且属于不可控/易误伤」的改动做收口简化，目标是把这条
链路拉回更接近 v1.3.0 的可预期状态。明确不动 `empty_completion` / `incomplete_answer`
改判与一次性自动恢复（这些在 2026-06-16 #371 刚被强化，属于刻意投入的能力）。

## 现状代码

1. 伪工具调用（format drift）检测：
   - `core/agent_runtime.py`：`_PSEUDO_TOOL_CALL_MARKERS`、`_PSEUDO_TOOL_CALL_TAG_RE`、
     `_contains_pseudo_tool_call`、`_strip_pseudo_tool_call_tags`，以及
     `_partial_completion_note` 的 `"工具调用格式"` 分支。
   - `core/task_executor.py`：`SdkResultAccumulator._saw_pseudo_tool_call`、
     `_note_pseudo_tool_call`，并在整条消息文本、`text_delta`、**`thinking_delta`** 三处
     扫描伪标签；`build_result()` 命中后走 `_build_format_drift_result` 改判为
     `task_status="error"`、错误码 `tool_call_format_drift`。
   - 问题：检测对 **thinking 文本**也生效，模型只要在思考里出现/复述
     `<function>`、`<parameter>`、`</tool_call>` 等子串，即便已经产出了正常答案，整轮也被
     改判为错误卡片。这是「本来好答案 → 变报错」的误伤来源，属不可控因素。

2. AskUserQuestion / `can_use_tool` 开关缺失：
   - `core/task_executor.py:903` 读取 `getattr(cfg, "dataagent_ask_user_question_enabled", True)`，
     但该配置项从未在 `config.py` 中定义，因此恒为 `True`。
   - 后果：`can_use_tool` 回调对**每一次**运行都安装；进而 prompt 恒以流式生成器
     (`_single_user_prompt_stream`) 送入 SDK，而非 v1.3.0 的纯字符串。这条全量默认路径
     无法通过设计文档 `2026-06-15-askuserquestion-rendering-design.md` 声称的开关关闭。

## 方案

### 1. 移除伪工具调用（format drift）检测

完整删除上述检测与改判：标记表、正则、`_contains_pseudo_tool_call`、
`_strip_pseudo_tool_call_tags`、`_saw_pseudo_tool_call`、`_note_pseudo_tool_call`、三处扫描
调用、`build_result()` 的 format-drift 分支、`_build_format_drift_result`，以及
`_partial_completion_note` 的 `"工具调用格式"` 分支。

保留 `_partial_completion_note` / `_recover_partial_content` 本体——它们是 **v1.3.0 就有的**
超时 / 最大轮次 / provider 报错的「半成品兜底」逻辑，确定性、不改判好答案，删除反而比
v1.3.0 更激进，故只摘掉其 format-drift 专用分支。

`build_result()` 简化后：provider 报错分支 → `empty_completion`（无文本无工具）→
`incomplete_answer`（无文本有工具）→ 正常 `finished`。不再有 format-drift 这一层。

### 2. 恢复 `dataagent_ask_user_question_enabled` 配置开关

在 `config.py` 的 `Settings` 中新增 `dataagent_ask_user_question_enabled: bool = True`
（默认开，保持当前行为不变），并在 `deploy/.env.example` 文档化
`DATAAGENT_ASK_USER_QUESTION_ENABLED=true`。pydantic-settings 按字段名自动读取同名大写环境
变量，无需额外接线。

设为 `false` 时：AskUserQuestion 不再加入 `allowed_tools`，且在无写工具门控
(`needs_gating`) 的会话中 `can_use_tool` 回到 `None`、prompt 回到 v1.3.0 的纯字符串形态——
为运营方提供一个回到 v1.3.0 调用形态的逃生开关。写工具确认门控仍按各自模式独立生效，不受
本开关影响。

## 接口与契约变化

- 任务错误码 `tool_call_format_drift`：**不再产生**。前端错误卡片/重试对任意
  `status=error` 仍通用，无需改动；`useNl2SqlChat.js` 中引用该码的注释更新为通用措辞。
- 新增配置 `dataagent_ask_user_question_enabled`（默认 `true`）/ 环境变量
  `DATAAGENT_ASK_USER_QUESTION_ENABLED`。
- `_partial_completion_note` / `_recover_partial_content` 签名不变，仅去掉 format-drift 文案分支。

## 取舍

- 移除检测后，若模型把工具调用 XML 当文本/思考吐出，后端不再自动标错或清洗标签——
  这类问题交由模型与系统提示词约束处理，符合「简化、减少不可控因素，模型问题等模型修复」
  的方向。真正的空回合仍由 `empty_completion` / `incomplete_answer` 兜住，不裸奔。
- 配置开关默认保持 `true`，不改变现网默认行为，仅提供可关闭能力；如需默认回到 v1.3.0 调用
  形态，由运营方显式置 `false`。

## 范围外

- 不改 `empty_completion` / `incomplete_answer` 改判与一次性自动恢复（#371 刚强化）。
- 不改写工具权限门控、沙箱执行、流式投影等其它 v1.3.0→main 差异。
- 不新增全局标签清洗器。
