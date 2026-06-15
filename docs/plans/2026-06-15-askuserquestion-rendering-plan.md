# AskUserQuestion 渲染与选择回填实施计划

- 配套设计: `docs/design/2026-06-15-askuserquestion-rendering-design.md`
- 分支: `claude/askuserquestion-tool-rendering-7jregf`

## 任务

### 后端 (`dataagent/dataagent-backend`)

1. `core/ask_user_question.py`(新增)
   - `ask_question_answer_redis_key(task_id, request_id)`
   - `wait_for_answer(task_id, request_id, *, timeout_seconds, ...)`:自连接 Redis
     轮询,返回答案 JSON 或 `None`(超时/取消)。
   - `build_ask_user_mcp_server(*, sdk_writer, store, task_id, wait_seconds,
     is_cancel_requested)`:返回 `(server, tool_qualified_name)`,工具处理函数完成
     记录/暂停/等待/恢复/返回答案文本。
2. `core/sdk_block_writer.py`:`append_question_request` / `append_question_answer`。
3. `core/topic_task_store.py`:投影 `question_request`/`question_answer`;
   `get_pending_question_request_id`;`append_question_answer_record`。
4. `core/task_coordinator.py`:`submit_question_answer` / `read_question_answer`。
5. `core/task_executor.py`:构建并挂载 ask_user MCP server,加入 `allowed_tools`。
6. `models/schemas.py`:`QuestionAnswerRequest` / `QuestionAnswerResponse`。
7. `api/routes.py`:`POST /{task_id}/question-answer`。

### 前端 (`dataagent/dataagent-frontend`)

8. `src/views/intelligence/v2StreamParser.js`:`question_request`/`question_answer`。
9. `src/views/intelligence/QuestionSelectionCard.vue`(新增)。
10. `src/views/intelligence/NL2SqlChatV2.vue`:模板分支 + `handleQuestionAnswer`。
11. `src/api/nl2sql.js`:`submitQuestionAnswer`。

### 技能

12. `dataagent/.claude/skills/dataagent-nl2sql/SKILL.md`:提问引导。

## 验证

- `nvm use` 后 `npm --prefix dataagent/dataagent-frontend run build`(或相关单测)。
- 后端投影契约 `pytest tests/test_sdk_block_projection_contract.py`。
- 端到端冒烟:本地 Redis+MySQL+Provider 下走一次提问→选择→恢复;若环境不可用,
  在报告中标明未跑通层。

## 回滚

- 工具默认通过运行时按需挂载;移除挂载即停用,SDK 记录向后兼容(未知 record_type
  前端忽略)。
- 涉及文件均为新增分支逻辑,回滚为还原本批提交。
