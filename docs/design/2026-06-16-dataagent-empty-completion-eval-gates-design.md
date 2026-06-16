# DataAgent Empty Completion And Eval Gates Design

## Current State

DataAgent converts a clean model turn with no visible answer and no tool call into an `empty_completion` task error, then attempts one recovery turn when a session id is available. If a tool did run and the model ends with no visible answer, the task currently finishes with the fallback content `已完成。`.

The DeepEval and builtin eval runners also collect automatic rule failures such as missing required SQL fragments, but those failures are only reported as attribution. A case can still pass when the judge score is high and no veto rule is triggered.

Provider-side SDK failures may include a visible provider error message while the SDK result subtype remains `success`. In that mixed terminal state the task status is `error`, but the stored `error.code` can be misleadingly set to `success`.

## Problem

Recent architecture-governance evals showed two failure modes:

- Thinking-only turns can end before any real tool call or visible answer. When no recoverable session is available, the recovery path is skipped.
- Tool-backed turns can return `finished` with `已完成。`, hiding an incomplete analysis from users and evals.
- Eval reports can show a passing case with `missing_sql_fragment` or `missing_tool` attribution, making the pass/fail contract inconsistent.
- Provider failures can persist `task_status=error` with `error.code=success`, making terminal error records harder to diagnose.

## Scope

In scope:

- DataAgent task result classification for empty model turns.
- Empty-completion recovery fallback when no session id is available.
- Builtin and DeepEval eval runner auto-rule pass/fail semantics.
- Provider error code normalization when the SDK supplies a provider error message.
- Unit tests for the changed contracts.

Out of scope:

- Model/provider tuning.
- Full architecture-governance smoke reruns against live services.
- Dataset rubric edits.

## Solution

DataAgent will keep the existing no-tool `empty_completion` behavior, but will attempt the recovery prompt even when no resume session is available. In that case the recovery turn starts without `resume`, which is safe because no tool ran in the failed initial turn.

For tool-backed turns with no visible answer, DataAgent will return a task error with `code=incomplete_answer` instead of `finished` plus `已完成。`. This avoids silently accepting a partial execution. The recovery retry remains limited to no-tool empty completions to avoid repeating tool side effects.

Eval runners will treat missing required SQL fragments and missing expected tool names as automatic rule failures. `case_passed` will require `auto_rule_check.passed=true` in addition to judge score and veto gates.

When a provider error message is present, DataAgent will not reuse a `success` result subtype as the terminal error code. It will normalize that combination to `provider_error` while preserving explicit non-success provider subtypes such as `error_api`.

## Tradeoffs

Marking empty tool-backed turns as errors may surface more failures than before, but those failures already produced unusable answers. This is preferable to persisting misleading successful task states.

Starting recovery without a resume session loses prior hidden reasoning, but the initial turn had no visible answer and no tool call, so there is no execution state to preserve.

## Verification

Targeted unit tests cover:

- no-session empty completion recovery starts a second SDK turn without resume
- empty completion remains an error if recovery is still empty
- tool-backed empty final text returns `incomplete_answer`
- provider error messages with `success` subtype normalize to `provider_error`
- eval auto rules fail on missing SQL fragments or expected tools
- eval `case_passed` respects auto-rule failure
