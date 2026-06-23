# DataAgent Session Execution Observability Design

**Date:** 2026-06-23
**Status:** Active
**Scope:** DataAgent backend session, sandbox execution, SDK stream persistence, and SSE observability

## Current State

DataAgent Chat V2 is split across a main FastAPI service, a task coordinator, an execution path, an optional sandbox runner, and the frontend SSE consumer.

The important current behavior is intentional for this design:

- The main service creates topics, tasks, user messages, assistant placeholder messages, and terminal assistant messages.
- The execution process writes Claude SDK stream records through `SdkBlockWriter` into `da_agent_sdk_record`.
- In sandbox mode, that execution process is usually the task child container, so the child can write `da_agent_sdk_record` directly.
- The frontend never reads child stdout directly. It connects to the main service SSE endpoint, and the main service reads persisted `da_agent_sdk_record` rows.
- The sandbox runner manages child lifecycle, cancellation, stdout protocol messages, stderr capture, and per-task logs.

## Problem

The runtime behavior is workable, but the architecture is not explicit enough for incident analysis. A repeated thinking stream can appear in the frontend while the `.claude` JSONL transcript has no duplicate completed assistant message. Without a clear ownership map and correlated logs, it is easy to confuse:

- frontend rendering;
- main service SSE replay;
- runner stdout/stderr forwarding;
- child process SDK streaming;
- DB persistence.

## Goals

- Preserve the current child-executes-and-can-write-DB architecture.
- Make the ownership boundary clear in docs and code comments.
- Add low-risk observability so a single `topic_id/task_id` can be traced from submission, execution, SDK stream persistence, runner logs, and SSE delivery.
- Keep HTTP APIs, DB schema, frontend protocol, and sandbox execution behavior unchanged.

## Ownership Matrix

| Layer | Primary responsibility | Writes session DB | Reads session DB | Streams to user |
| --- | --- | --- | --- | --- |
| Frontend | Render chat and SDK blocks | No | No direct DB read | Consumes main-service SSE |
| Main service API | Topic/task/message API and SSE endpoint | `da_agent_topic`, `da_agent_task`, `da_agent_topic_message`; permission/user decisions through API endpoints | `da_agent_*`, especially `da_agent_sdk_record` for SSE | Yes, from persisted SDK rows |
| Task coordinator | Queue pickup, leases, final result handling | Task running/terminal status, final assistant message, topic current state | Tasks, topics, history | Indirect, through API SSE |
| Execution process | Run Claude SDK and tools | `da_agent_sdk_record` via `SdkBlockWriter`; current permission/question wait status updates | Task/session settings as needed | No direct user stream |
| Sandbox runner | Start/reuse/cancel child, capture child logs | No business session table writes by design | No business session reads by design | Protocol forwarding only |
| Persistence layer | Store normalized session state and raw SDK records | N/A | N/A | N/A |

## Data Flow

1. `POST /tasks/deliver-message` creates a task and topic messages in the main service.
2. The task coordinator marks the task running and calls `execute_task_stream`.
3. `execute_task_stream` runs locally or delegates to the sandbox runner.
4. The local execution path constructs `SdkBlockWriter(get_topic_task_store(), task_id, topic_id)`.
5. `SdkBlockWriter` writes raw SDK stream/tool/terminal/permission/question records to `da_agent_sdk_record`.
6. In sandbox mode, child stderr is captured by the runner and mirrored into `<topic>/logs/<task>.log`.
7. The frontend connects to `/tasks/{task_id}/sdk-events/stream`.
8. The main service polls `da_agent_sdk_record` by `seq_id` and emits SSE events to the frontend.
9. The coordinator persists the terminal assistant message and final task status after execution returns.

## Observability

The observability model is intentionally split by ownership:

- Main service logs cover submission, enqueue, coordinator execution, final message persistence, final task status, and SSE stream open/terminate/disconnect/close.
- Execution-process logs cover SDK iterator progress and SDK stream shape, including turn-level SDK message counts, message/block lifecycle, large thinking blocks, many thinking deltas, long thinking duration, repeated thinking segments, and terminal records with unclosed blocks.
- Runner logs and task logs capture child stderr, so sandbox-mode `sdk_stream.*` execution logs remain searchable by `task_id`.

All new logs should include `task_id`; logs that know the topic should also include `topic_id`.

For loop diagnosis:

- If `task.sdk_turn.progress` grows while `sdk_stream.thinking_repeated_segment` grows for the same `task_id`, the child is receiving repeated SDK messages from the upstream SDK/model stream.
- If DB `seq_id` advances but `task.sdk_turn.progress` does not, investigate execution-side synthetic writes or a missing progress log path.
- If the same `seq_id` is rendered repeatedly without new DB rows, investigate SSE replay or frontend rendering.
- If `.claude` JSONL lacks the repeated thinking while `da_agent_sdk_record` contains it, treat that as expected for a cancelled/incomplete streaming turn; JSONL is not the live delta source.

## Non-Goals

- Do not move SDK record writes from child to main service.
- Do not introduce a new event bus or single-writer persistence protocol.
- Do not change DB schema, HTTP APIs, frontend SSE payload shape, or the `.claude` JSONL transcript.
- Do not treat `.claude` JSONL as the authoritative source for incomplete streamed thinking; `da_agent_sdk_record` is the live stream source.

## Verification

- Unit test `SdkBlockWriter` observability for repeated thinking segments.
- Route contract test that SSE reads persisted `da_agent_sdk_record` rows in `seq_id` order.
- Sandbox runner test that child stderr containing `sdk_stream.*` is persisted into the task log with task context.
