# Permission Confirmation Wait Plan

## Scope

Implement v1 of persistent permission/input waiting for DataAgent:

- remove confirmation/input wait deadlines;
- use MySQL as the authoritative decision/answer channel;
- keep parked task status durable;
- preserve exact continuation only while the original runner is alive;
- finalize lost-runner confirmed tasks as `suspended` / `run_lost`;
- avoid orphan automatic continuation.

## Tasks

1. Update docs and config comments.
   - Add this plan and matching design.
   - Mark `task_permission_wait_seconds` as deprecated for confirmation waits.

2. Replace Redis wait paths.
   - Update `permission_wait.wait_for_decision` to poll MySQL with no timeout.
   - Update `ask_user_question.wait_for_answer` similarly.
   - Add `TaskCancelledError` and `RunnerStoppedError` handling.
   - Keep persisted permission decisions aligned with callback verbs.

3. Make endpoint writes authoritative and idempotent.
   - Remove confirmation endpoint calls to Redis submit/read helpers.
   - Add request-id based current/resolved interaction readers.
   - Add atomic `append_*_if_waiting` methods using task row `FOR UPDATE`.
   - Reject pending writes after cancel or terminal/running status.

4. Simplify callbacks.
   - Remove `wait_seconds` parameters.
   - Remove callback-side decision/answer append.
   - Callback only consumes, sets task status back to `running`, and returns to
     the SDK.

5. Update coordinator lifecycle.
   - Replace boolean cancellation with `cancel_reason`.
   - Add parked finalization guard.
   - Add minimal parked recovery: cancel -> `task_cancelled`, decision/answer
     after runner loss -> `run_lost`, otherwise leave parked.

6. Update sandbox cancellation.
   - Child checks user cancel through MySQL.
   - Cancel route carries reason.
   - Parent watch cancel branches on reason plus DB status.

7. Tests.
   - Unit tests for no-deadline MySQL polling and decision handling.
   - Route tests for resolved idempotency, pending writes, and terminal/cancel
     rejection.
   - Coordinator tests for parked finalization guard and `run_lost`.
   - Executor tests for no `fail_after`, callback no second append, and cancel
     exception routing.
   - Sandbox tests for parked cancel, active cancel, and runner stop.

## Verification

Run:

```bash
cd dataagent/dataagent-backend
.venv-py313/bin/python -m pytest tests -q
```

When local services and provider credentials are available, run one DataAgent
smoke flow:

- trigger a write-tool permission card;
- wait beyond the old 360s timeout and confirm the task remains
  `waiting_permission`;
- approve and verify `waiting_permission -> running -> success`;
- cancel while parked and verify `suspended` / `task_cancelled`;
- simulate runner loss while parked, submit approval, and verify
  `suspended` / `run_lost`.
