# DataAgent Evaluation OpenDataWorks E2E Verification

**Date**: 2026-07-23

**Runner**: `tools/dataagent-evals/builtin/run.py`

**Dataset**: `tools/dataagent-evals/dataset/examples/opendataworks-golden-v2.jsonl`

## Outcome

The final real end-to-end run passed all 10 cases.

| Metric | Result |
|---|---:|
| Effective pass rate | 10/10 (100%) |
| Answer accuracy | 9/10 (90%) |
| Comparable reference data accuracy | 7/7 (100%) |
| Reference comparability | 7/8 (87.5%) |
| Tool/SQL accuracy | 10/10 (100%) |
| Time accuracy | 2/2 (100%) |
| Result consistency | 10/10 (100%) |
| Average score | 9.8/10 |
| Judge failures | 0 |
| Hallucination rate | 0 |

The one non-comparable reference case used successful platform metadata
evidence rather than SQL result rows. It was retained as `not_comparable` and
was not converted into a data mismatch or hallucination.

## Per-case result

| Case | Score | Pass | Reference |
|---|---:|---:|---|
| `ODW_GOLD_001` | 10 | yes | not applicable |
| `ODW_GOLD_002` | 10 | yes | matched |
| `ODW_GOLD_003` | 10 | yes | matched |
| `ODW_GOLD_004` | 10 | yes | matched |
| `ODW_GOLD_005` | 8 | yes | not applicable |
| `ODW_GOLD_006` | 10 | yes | not comparable |
| `ODW_GOLD_007` | 10 | yes | matched |
| `ODW_GOLD_008` | 10 | yes | matched |
| `ODW_GOLD_009` | 10 | yes | matched |
| `ODW_GOLD_010` | 10 | yes | matched |

## Environment

- MySQL: `127.0.0.1:3306`
  - business schema: `opendataworks`
  - session/settings schema: `dataagent`
- Redis: `127.0.0.1:6379`, existing Podman container
- platform backend: real Java service at `127.0.0.1:8080`
- DataAgent backend: real FastAPI service at `127.0.0.1:8900`
- Python:
  `dataagent/dataagent-backend/.venv-py313/bin/python`
- Agent execution: real provider/model through profile `agent_opendataworks`
- Judge execution: real independent provider using
  `openai/gpt-4o-mini`
- No mocked task, message, SQL, reference-query, or judge calls were used.

## Runner independence

The verification set is an ordinary V2 JSONL input. The runner has no branches
for its filename, dataset ID, case-ID prefix, category, suite tag, business
table, or skill name. Equivalent SQL and tool choices are declared by the case
contract rather than embedded in runner code.

## Regression coverage

The focused evaluation suite passed `100` tests across builtin, DeepEval, and
Opik. It covers:

- all successful query evidence, including more than 20 later/split queries;
- leading SQL comments;
- field aliases, extra columns, and safe scalar composition;
- case-declared equivalent SQL fragments;
- rolling-day and calendar-month boundaries;
- empty result handling;
- reference accuracy separation and explicit enforcement;
- deterministic dimension overrides and score summation;
- dataset-label independence and three-engine semantic conformance.
