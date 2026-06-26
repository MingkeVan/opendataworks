# Permission Confirmation Wait Design

## Context

DataAgent pauses an SDK run when a write/high-risk tool needs permission, or when
`AskUserQuestion` needs an answer. Today that pause is still inside two timeout
paths:

- the SDK turn is wrapped by a total wall-clock `anyio.fail_after(...)`;
- permission/input waits use a bounded Redis wait and fail closed on Redis errors.

If the runner exits while the task is parked, the coordinator also finalizes the
task without checking the current persisted state. A task in
`waiting_permission` or `waiting_input` can therefore be overwritten as terminal.

## Goal

In v1, confirmation/input is a durable task state, not an execution-resource
lifetime. A task may stay in `waiting_permission` or `waiting_input` until the
user acts or cancels.

When the runner is alive, the user's decision is consumed by the original SDK
callback and the run continues in place. When the runner has already been
released, v1 does not attempt automatic continuation because the SDK cannot
inject a tool result into a lost callback. The task is finalized as
`suspended` with `run_lost` after the user submits a decision/answer.

## Non-Goals

v1 intentionally does not implement:

- orphan automatic continuation;
- `create_continuation_task`;
- parent handoff/CAS linkage transfer;
- `finish_task(propagate_downstream=False)`;
- resume-plus-continuation prompts;
- early session persistence only for orphan continuation;
- Redis fast-path delivery or Redis delivery TTLs for confirmation.

Normal follow-up after a completed run is not an orphan path. It remains a new
task that resumes the persisted SDK session.

## Design

### Durable Waiting

`can_use_tool` and `AskUserQuestion` keep writing `permission_request` and
`question_request` SDK records and set task status to `waiting_permission` or
`waiting_input`.

The wait functions no longer have deadlines. They poll MySQL for the resolved
decision/answer and retry transient MySQL errors instead of failing closed.

Permission records persist canonical values (`allowed`/`denied`), while SDK
callbacks need callback verbs (`allow`/`deny`). The wait path maps
`allowed -> allow` and `denied -> deny`.

### MySQL as Authority

The API endpoint is the only writer of durable decision/answer records. The SDK
callback only consumes those records, moves the task back to `running`, and
returns the corresponding allow/deny or updated input to the SDK.

Redis `submit_*`/`read_*` helpers are removed from the confirmation main path.
`task_permission_wait_seconds` is no longer a confirmation wait timeout.

### Idempotent Endpoints

Endpoints resolve by `(task_id, request_id)`.

- If the request is already resolved, return the recorded result regardless of
  task status.
- If the request is still pending, append only when the task is still in the
  corresponding waiting state and `cancel_requested_at IS NULL`.
- Unknown request IDs return conflict.

The append operation is atomic. It locks the task row with
`SELECT ... FOR UPDATE`, verifies task state/cancel state, derives pending state
from SDK records, and writes the decision/answer in one transaction.

### Cancellation and Runner Stop

Cancellation is split by reason:

- `user_cancel`: user explicitly cancels the task; wait/message loop raises
  `TaskCancelledError` and finalizes as `suspended` / `task_cancelled`.
- `runner_stop`: lease loss or execution resource stop; wait/message loop raises
  `RunnerStoppedError`. For parked tasks, coordinator finalization is suppressed
  so the task remains `waiting_*`. For active running tasks, the task finalizes
  as `suspended` / `runner_stopped`.

Sandbox cancellation is reasoned:

- `user_cancel + waiting_*`: do not kill the container; let the child observe the
  cancel request and finalize cleanly;
- `user_cancel + running`: keep the fast parent-side cancel/kill behavior;
- `runner_stop`: stop the execution resource.

In-process cancellation depends on the SDK loop returning to a check point; it
is not guaranteed to interrupt a stuck SDK await immediately.

### Parked Finalization Guard

Before successful or crash finalization, the coordinator fresh-reads the task.
If the status is still `waiting_permission` or `waiting_input` and the result is
a benign runner-stop/crash path, it skips assistant-message update and
`finish_task`, releases the lease, and leaves the task parked.

Explicit user cancellation is not blocked by this guard.

### Lost Runner

The recovery loop adds a parked-task scan:

- waiting task + no lease + cancel requested -> `suspended` / `task_cancelled`;
- waiting task + no lease + durable decision/answer -> `suspended` / `run_lost`;
- waiting task + no lease + no user action -> remain parked.

## Frontend Behavior

No frontend code change is expected in v1.

- Normal confirmation/answer writes a decision/answer record, so the card closes.
- `run_lost` happens after the decision/answer was recorded; the card is already
  closed and the task shows the terminal error.
- `cancel` does not write a decision/answer; the historical card may remain
  pending but is disabled once the task stream is terminal.

## Tradeoffs

Removing the SDK turn wall-clock timeout means active runs are no longer bounded
by that timer. In production sandbox/container mode, parent-side cancellation can
still stop the execution resource. In in-process mode, cancellation is cooperative
with SDK message loop progress.
