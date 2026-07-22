# DataAgent Online Evaluation Design

> **V2 status (2026-07-20):** This document now defines the builtin engine of
> the three-engine Evaluation V2. Builtin, DeepEval, and Opik are deliberately
> independent implementations. They share only the external V2 dataset
> contract, metric semantics, golden conformance fixtures, and output contract;
> they do not share runtime authentication, HTTP execution, evidence extraction,
> scoring, or report code.

## Current State

DataAgent already exposes an HTTP task chain for intelligent-query execution:

- `GET /api/v1/nl2sql/health`
- `GET /api/v1/nl2sql-admin/settings`
- `POST /api/v1/nl2sql/topics`
- `POST /api/v1/nl2sql/tasks/deliver-message`
- `GET /api/v1/nl2sql/tasks/{task_id}`
- `GET /api/v1/nl2sql/tasks/{task_id}/events`
- `GET /api/v1/nl2sql/topics/{topic_id}/messages`

The offline deployment package already copies `deploy/`, `scripts/`, DataAgent settings, and editable Skills into `deploy/dataagent-runtime/`. Evaluation tooling is packaged at the offline-package root under `tools/dataagent-evals/` so it remains outside the DataAgent runtime directory. Private Skill content and private case datasets are manually deployed and are not committed to GitHub.

The business-domain Skill evaluation source lives outside this repository as a private asset. It defines private business-domain cases and acceptance thresholds, but OpenDataWorks should only carry the generic executable online evaluation module.

## Problem

The current evaluation material is documentation-only. After an offline package is deployed in an intranet environment, operators need a repeatable manual command that:

- runs externally supplied domain-specific evaluation cases through the real DataAgent HTTP task chain
- captures task status, events, final assistant messages, SQL/tool evidence, errors, duration, and usage
- uses an independently configured judge model endpoint for scoring so evaluation code does not enter the DataAgent runtime
- produces durable offline artifacts for review and release gating

The solution must not move business-domain-specific behavior into generic DataAgent runtime modules.

## Scope

In scope:

- Keep private JSONL evaluation datasets outside GitHub and require `--dataset` at runtime.
- Add a stdlib-only builtin runner under `tools/dataagent-evals/builtin/`.
- Add independent judge endpoint configuration for the runner.
- Add a standalone builtin eval image `opendataworks-dataagent-evals-builtin:<tag>`.
- Add offline-package copy support for `tools/dataagent-evals/`.
- Add documentation for online/offline execution.
- Add focused runner and runtime-boundary tests.

Out of scope for the first version:

- Persisting evaluation results in MySQL.
- Scheduling recurring evaluations.
- Changing `load-package-and-start.sh` startup behavior.
- Adding a frontend UI for evaluation runs.
- Persisting or baking judge-provider secrets into images or offline packages.
- Adding DataAgent backend routes or runtime modules for evaluation.

## Dataset Contract

Evaluation V2 requires `schema_version=2`. The canonical private dataset stays
outside this repository. Public tooling contains only the generic schema,
anonymous fixtures, validator, and derived-suite generator. The V2 contract is
defined in `tools/dataagent-evals/dataset/README.md`.

V1 input is not executed by the three V2 runners. A standalone migration command
converts V1 JSONL to V2, while the history importer continues to understand old
report artifacts.

The dataset is an external JSONL file supplied through `--dataset` or
`DATAAGENT_EVAL_DATASET`. Every case includes `schema_version=2`, identity and
suite tags, `expected_semantics`, `expected_time`, `expected_tools`,
`expected_sql`, `expected_result`, `expected_answer`, `limits`, `scoring`, and
`veto_rules`, plus either `question` or `turns`.

`question` is submitted as a single-turn case. `turns` is a list of user messages submitted sequentially in one topic for real multi-turn evaluation; when `turns` is present, `question` may remain as a short report title instead of the submitted prompt text.

`expected_result.reference_query` and `judge_guidance` are optional extensions.
When a successfully executed reference query returns zero rows, zero rows become
the runtime truth for that case even if the static `allow_empty` default is
false. A matching empty Agent result therefore passes; an empty Agent result
still fails when the reference query contains rows. Reference-query execution
errors remain infrastructure failures and are never converted into empty truth.

The source document's scoring model is normalized to a 10-point rubric:

- `intent`: 1
- `ontology_entity`: 1
- `relation_scope`: 1
- `sql_or_tool_call`: 2
- `result_consistency`: 2
- `reasoning`: 2
- `answer_quality`: 1

上述七维评分中的 `result_consistency` 只判断最终回答是否忠实引用 Agent
工具结果。若用例提供 `expected_result.reference_query`，runner 还会通过只读
查询代理独立执行参考 SQL，生成不占七维分值的 `data_accuracy` 硬门禁与汇总
指标；没有参考 SQL 时该指标为 `N/A`。参考 SQL 执行失败归类为评测基础设施
失败，不计入模型准确率。

## Judge Contract

The builtin runner calls an external Anthropic-compatible judge endpoint directly. Judge connection settings are supplied at runtime through CLI options or environment variables:

- `--judge-base-url` / `DATAAGENT_EVAL_JUDGE_BASE_URL`
- `--judge-token` / `DATAAGENT_EVAL_JUDGE_TOKEN`
- `--judge-model` / `DATAAGENT_EVAL_JUDGE_MODEL`
- `--judge-timeout-seconds` / `DATAAGENT_EVAL_JUDGE_TIMEOUT_SECONDS`

The judge request includes:

- case definition
- user question
- final assistant answer
- task status and errors
- tool events
- SQL/chart/spec outputs extracted by the runner
- automatic rule-check result

The judge response text must contain a JSON object with:

- 10-point score
- per-dimension scores
- hallucination flag
- triggered veto rules
- failure attribution
- short comment
- `judge_failed` and `raw_output` when the judge model could not return valid JSON

DataAgent backend does not expose an eval judge API and does not resolve judge credentials. If the model output is not valid JSON, the runner retries once with a stricter repair prompt. If parsing still fails, the case is marked `judge_failed=true` with score `0`.

## Runner Contract

User entrypoint:

`bash scripts/run-dataagent-evals.sh --base-url http://127.0.0.1:8900 --dataset /path/to/private-cases.jsonl`

Python runner:

`tools/dataagent-evals/builtin/run.py`

Compatibility shim:

`scripts/run-dataagent-evals.py`

Runner constraints:

- stdlib-only Python
- Docker/Podman wrapper defaults to `opendataworks-dataagent-evals-builtin:<tag>`
- required external dataset: `--dataset /path/to/private-cases.jsonl`
- default output: `reports/dataagent-evals/<timestamp>/`

Runner arguments:

- `--base-url`
- `--dataset`
- `--output-dir`
- repeatable `--case`
- `--provider-id`
- `--model`
- `--timeout-seconds`
- `--concurrency`
- `--judge-base-url`
- `--judge-token`
- `--judge-model`
- `--judge-timeout-seconds`
- `--dry-run`
- `--environment-label`
- `--run-label`
- `--history-root`
- `--agent-snapshot-path`

Authentication uses `DATAAGENT_EVAL_AUTH_TOKEN`. The value is passed only as an
`Authorization: Bearer` header and is never printed or persisted. Every DataAgent
business request also carries `X-ODW-Client: dataagent`.

When `--concurrency > 1`, the runner submits and polls multiple cases in parallel while preserving dataset order in the written outputs.

## Runner Flow

1. Load and validate the dataset.
2. In `--dry-run`, create the output directory and write validation artifacts without calling services.
3. Preflight online services:
   - `GET /api/v1/nl2sql/auth/config`
   - `GET /api/v1/nl2sql/health`
   - `GET /api/v1/nl2sql/runtime-config`
   - when authentication is enabled, `GET /api/v1/nl2sql/auth/me` and require
     an administrator identity
4. For each case:
   - create a topic
   - submit the question through `/api/v1/nl2sql/tasks/deliver-message`
   - poll task status and events until terminal status or timeout
   - fetch topic messages and extract the final assistant response
   - extract tool calls, SQL fragments, chart/spec-like payloads, errors, duration, and usage
   - run automatic rule checks
   - call the configured external judge model endpoint
   - write raw case artifact and JSONL case result
5. Write:
   - `run.json`
   - `cases.jsonl`
   - `summary.json`
   - `report.md`
   - `report.html`
   - `raw/<case_id>.json`

## Acceptance Metrics

The V2 report exposes completion rate, weighted business-assertion accuracy,
effective pass rate, semantic/tool/SQL/answer dimensions, result consistency,
and optional reference-SQL data accuracy. Every percentage includes numerator
and denominator; timing includes average/P50/P90/P95, while turns, tools and
Token counts include average/P95. A case passes only when all applicable hard
gates and the fixed judge threshold pass.

Any triggered veto rule marks the case failed. If any veto appears in the run, the report must explicitly say `不建议上线`.

## Failure Handling

Runner exit codes:

- `0`: report generated and acceptance gates passed
- `1`: report generated but acceptance gates failed
- `2`: authentication, preflight, reference-query, Opik/platform, dataset,
  argument, or filesystem infrastructure error

Judge failures are case-level failures but do not stop the whole run unless required judge configuration is missing before execution starts.

## Tradeoffs

The first version does not persist results to MySQL. File artifacts are simpler for offline package use and avoid schema migrations for a manually-triggered gate.

The runner extracts evidence from task state, messages, and persisted SDK events.
Only SQL found in a successful query tool invocation can satisfy an execution
assertion; SQL printed in the final answer is evidence for neither execution nor
result truth.

The runner keeps business-domain case semantics in the external private dataset
and judge prompt, not in generic task execution modules. Evaluation tools are
packaged under root `tools/dataagent-evals/`, not under
`deploy/dataagent-runtime/`, to keep test tooling separate from DataAgent runtime
assets. Builtin is not the canonical implementation for the other engines: all
three engines must independently satisfy the same conformance fixtures.
