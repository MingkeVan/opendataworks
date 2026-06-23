# DataAgent Session Execution Observability Plan

**Date:** 2026-06-23
**Related design:** `docs/design/2026-06-23-dataagent-session-execution-observability-design.md`

## Objective

Clarify DataAgent session/execution/persistence ownership and add minimum observability for troubleshooting repeated SDK thinking streams without changing the current child-write architecture.

## Affected Stacks

- DataAgent backend: API routes, task submission/coordinator comments and logs, SDK block writer.
- Sandbox runner: child stderr/task-log observability tests.
- Documentation: DataAgent architecture ownership and persistence matrix.

## Tasks

1. **Architecture docs**
   - Document the current ownership boundary between frontend, main service, coordinator, execution process, runner, and persistence.
   - Include a table showing which layer writes `da_agent_topic`, `da_agent_task`, `da_agent_topic_message`, and `da_agent_sdk_record`.
   - State explicitly that child direct writes to `da_agent_sdk_record` are retained.

2. **Comment and log cleanup**
   - Correct `execute_task_stream` comments so `emit` is not described as the single SDK persistence boundary.
   - Add submission/enqueue log fields for provider, model, and execution mode.
   - Add SSE lifecycle logs for open, terminal/missing-task termination, disconnect, and close with `task_id`, `after_id`, `last_seq`, and task status.

3. **Execution-process SDK observability**
   - Add `sdk_stream.*` logs in `SdkBlockWriter` for message/block lifecycle and abnormal thinking signals.
   - Treat these as execution-process logs; in sandbox mode they are captured from child stderr by the runner and written to per-task logs.

4. **Regression tests**
   - Extend `test_sdk_block_writer.py` to assert repeated thinking segments produce `sdk_stream.thinking_repeated_segment`.
   - Extend route contract tests to assert `/sdk-events/stream` emits persisted SDK records in `seq_id` order.
   - Extend sandbox runner tests to assert child stderr containing `sdk_stream.*` is written to the task log.

5. **Verification**
   - Run focused pytest for `test_sdk_block_writer.py`, `test_routes_contract.py`, and `test_sandbox_runner_main.py`.
   - Do not claim full DataAgent E2E validation unless a real local smoke flow is run separately.

## Backout

Revert the docs and logging/test changes. No schema migration, API contract, frontend protocol, or persistence ownership changes are involved.
