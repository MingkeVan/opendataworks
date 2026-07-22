# DataAgent DeepEval Independent Evaluation Design

> **V2 status (2026-07-20):** DeepEval is one of three independent evaluation
> engines. It does not import execution, authentication, evidence, scoring, or
> reporting code from builtin or Opik. Commonality is limited to contracts and
> golden test fixtures.

## Current State

The repository has a builtin DataAgent evaluation module under `tools/dataagent-evals/builtin/`. It drives the real `/api/v1/nl2sql/*` task chain and writes JSONL, JSON, and Markdown reports through image `opendataworks-dataagent-evals-builtin:<tag>`. Private case datasets are supplied at runtime and are not committed to GitHub.

The module provides an independent DeepEval-based path for comparing evaluation
ergonomics without changing DataAgent runtime behavior.

## Problem

DeepEval should be available as a separate evaluation engine, but its Python dependency tree must not be installed into `dataagent-backend` or the deployed DataAgent runtime. Operators should be able to run it manually from an offline package.

## Solution

- Add `tools/dataagent-evals/deepeval/` as a standalone DeepEval runner module.
- Keep private domain datasets external and require `--dataset` or `DATAAGENT_EVAL_DATASET`.
- Run DataAgent cases through the existing HTTP task chain, persisted SDK event
  API, and convert final answers plus evidence into DeepEval `LLMTestCase`
  instances.
- Use a custom DeepEval metric to call an independently configured Anthropic-compatible judge endpoint and preserve the existing 10-point rubric, veto rules, and failure attribution.
- Package DeepEval dependencies only in `opendataworks-dataagent-evals-deepeval:<tag>`.
- Drive the judge metric with a local loop rather than DeepEval's `evaluate()`. `evaluate()` couples each run to telemetry and Confident AI cloud calls that fail or hang on intranet deployments after every case has already run, which crashed the runner before any report was written. The runner only needs each metric to call the judge, so it iterates the test cases directly as the single primary path. DeepEval telemetry and update checks are opted out at import time so the package never phones home. The only external dependency for judging is the judge endpoint, which can be an internal Anthropic-compatible gateway.
- Always write the report once cases have run. A single failing judge call is recorded as a failed judge for that case; if the judging step fails late, `summary.json` records `judging_error` and the runner exits non-zero instead of dropping the report.

## Entrypoints

Manual entrypoint:

```bash
bash scripts/run-dataagent-deepeval-evals.sh --base-url http://127.0.0.1:8900 --dataset /path/to/private-cases.jsonl
```

Judge configuration:

- `DATAAGENT_EVAL_JUDGE_BASE_URL`
- `DATAAGENT_EVAL_JUDGE_TOKEN`
- `DATAAGENT_EVAL_JUDGE_MODEL`

Outputs match the builtin runner:

- `run.json`
- `cases.jsonl`
- `summary.json`
- `report.md`
- `report.html`
- `raw/<case_id>.json`

Optional `expected_result.reference_query` SQL is independently executed through
the read-only DataAgent query proxy. DeepEval reports reference-SQL
`data_accuracy` separately from answer/tool `result_consistency`; missing
reference SQL remains `N/A`. A successful zero-row reference result dynamically
marks a matching zero-row Agent result as expected; SQL, transport, and
authorization failures remain infrastructure failures rather than empty data.

## Tradeoffs

The DeepEval runner owns its complete HTTP task-driving, authentication, evidence,
metric, and report implementation. This deliberate duplication keeps all three
engines independently comparable and prevents either DeepEval or Opik dependencies
from entering the builtin image.
