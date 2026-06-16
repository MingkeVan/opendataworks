# NL2SQL 运行时收口简化 — 执行计划

配套设计：`docs/design/2026-06-16-nl2sql-runtime-simplification-design.md`

涉及栈：DataAgent backend（`core/`、`config.py`、`tests/`）、frontend（仅注释）、
infra（`deploy/.env.example`）。

## 任务

### A. 移除伪工具调用（format drift）检测
- `core/agent_runtime.py`
  - 删除 `_PSEUDO_TOOL_CALL_MARKERS`、`_PSEUDO_TOOL_CALL_TAG_RE`、`_contains_pseudo_tool_call`、
    `_strip_pseudo_tool_call_tags`。
  - `_partial_completion_note`：删除 `"工具调用格式"` 分支（回到 v1.3.0 形态）。
  - 保留 `import re`（仍被其它正则使用）。
- `core/task_executor.py`
  - 删除导入 `_contains_pseudo_tool_call`、`_strip_pseudo_tool_call_tags`。
  - 删除字段 `self._saw_pseudo_tool_call`、方法 `_note_pseudo_tool_call`。
  - 删除 `_ingest_assistant_content` 与 `_ingest_stream_event` 中的 3 处
    `_note_pseudo_tool_call(...)` 调用及相关注释。
  - 删除 `build_result()` 的 `if self._saw_pseudo_tool_call:` 分支与
    `_build_format_drift_result`。
  - 保留 `_build_incomplete_run_result` / `_build_empty_completion_result` /
    `_build_incomplete_answer_result`，更新其中提到伪标签的 docstring。
- `tests/test_agent_runtime.py`：删除 3 个 pseudo/format-drift 测试。
- `tests/test_task_executor.py`：删除 3 个 drift 测试；保留共享 `_patch_default_provider`；
  新增 1 个回归测试：thinking 中泄漏工具标签 + 有可见答案 → `finished`、无 error。
- `dataagent-frontend/.../useNl2SqlChat.js`：把引用 `tool_call_format_drift` 的注释改为通用措辞。

### B. 恢复配置开关
- `config.py`：`Settings` 新增 `dataagent_ask_user_question_enabled: bool = True`。
- `deploy/.env.example`：新增 `DATAAGENT_ASK_USER_QUESTION_ENABLED=true` 并注释含义。

### C. 文档
- 在 `docs/design/2026-06-11-nl2sql-tool-call-format-drift-design.md` 与对应 plan 顶部加
  「2026-06-16 起 format-drift 检测已移除，详见本简化设计」的注记。

## 验证
- `dataagent/dataagent-backend` 下用 `.venv-py311`：
  - `python -m pytest tests/test_agent_runtime.py tests/test_task_executor.py -q`
  - `python -c "import core.task_executor, core.agent_runtime, config"` 导入冒烟
- grep 确认全仓不再有 `format_drift` / `_pseudo` 残留引用（文档历史注记除外）。
- 前端仅注释改动，不跑重型前端构建。

## 回滚
- 单一提交，`git revert` 即可整体回退；无 schema / 迁移变更。
