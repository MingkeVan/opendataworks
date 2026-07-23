# DataAgent Evaluation Reliability Design

**Date**: 2026-07-23
**Status**: Implemented
**Scope**: `tools/dataagent-evals/{builtin,deepeval,opik}`, evaluation V2 contract, reports, and tests

## Current State

The three DataAgent evaluation engines execute the same V2 cases through the
real topic/task/message/tool chain. Each case is scored by:

- deterministic tool, SQL-fragment, time, empty-result, and answer/result checks;
- an independent read-only reference query;
- an LLM judge over the case, final answer, tool events, SQL, charts, and
  deterministic checks.

The V2 dataset documentation defines reference-query accuracy as a separate
metric from final-answer/result consistency.

## Problem

Archived online runs and local regression scenarios exposed evaluation
failures that are not Agent reasoning failures:

1. A reference-row mismatch is currently copied into `auto_rule_check.passed`
   and therefore hard-fails a case, even though the documented contract says
   reference data accuracy is a separate metric.
2. Reference rows are compared with any one Agent query result using a strict
   whole-row comparison. Equivalent projections, extra descriptive columns,
   split queries, and alternative valid aggregations are reported as wrong.
3. SQL-fragment checks select one "best" query. Cases that correctly use
   separate producer/consumer or upstream/downstream queries fail because no
   single statement contains every fragment.
4. Executed SQL beginning with `--` or block comments is not recognized.
5. `rolling_calendar_days` and `calendar_month_comparison`, used by the source
   suite, are not actually validated. The existing fallback only verifies that
   the time field occurs somewhere in SQL.
6. Free-text failure heuristics produce false attributions:
   `供.*执行` matches ordinary text such as "提供查询函数并执行"; words such
   as "不存在" can describe set-difference logic rather than an empty query;
   ontology table templates such as `{timeDim}` are marked as leaked runtime
   placeholders.
7. The judge receives the reference mismatch inside the automatic hard-check
   result and sometimes ignores later successful queries, converting a
   diagnostic mismatch into unsupported zero scores or hallucination flags.
   Structural checks that can be decided by code are also unnecessarily left
   to probabilistic judge interpretation.
8. `result_consistency_rate` is tautologically true when
   `answer_result_fields` is empty.
9. The declared gates are not the gates used for the final recommendation.
   Most thresholds are ignored, `reasoning_average` is absent, its configured
   threshold exceeds the dimension maximum, and the implementation instead
   requires every case to pass.

## Scope

### In scope

- Keep reference data accuracy separate from per-case pass/fail by default.
- Allow a case to opt into strict reference enforcement explicitly.
- Add structural comparability and projection-aware reference diagnostics.
- Check required SQL fragments across all successful target queries.
- Recognize leading SQL comments.
- Implement the time-range kinds used by V2 datasets.
- Remove known false-positive failure heuristics.
- Give the judge compact structured query evidence, but keep deterministic
  reference-query diagnostics out of the judge input.
- Make consistency metrics and final gates match their documented meanings.
- Keep builtin, DeepEval, and Opik behavior conformant.

### Out of scope

- Hardcoding any dataset's ontology IDs, table names, skill names, labels, or
  rules into shared runners.
- Rewriting source business datasets inside the evaluation runner.
- Claiming online coverage for any runtime, provider, or business database that
  was not actually exercised.
- Treating one reference SQL statement as the only valid Agent implementation.

## Solution

### 1. Separate oracle diagnostics from case hard gates

`expected_result.reference_query` remains independently executable. Its result
is stored under `reference_data_accuracy`, but a mismatch does not mutate
`auto_rule_check.passed` unless the case explicitly sets:

```json
{
  "expected_result": {
    "reference_query": {
      "enforce_case_gate": true
    }
  }
}
```

This restores the documented separation:

- `result_consistency`: final answer agrees with Agent tool evidence;
- `data_accuracy`: a comparable Agent result agrees with the independent
  reference result.

### 2. Report comparability before accuracy

Reference comparison returns one of:

- `matched`: comparable result found and values match;
- `mismatched`: structurally comparable result found but values differ;
- `not_comparable`: no single Agent result has a compatible row count/shape;
- `not_applicable`: no reference query.

For `unordered_values`, field aliases and extra descriptive columns are handled
through value projection. Cases can define `comparison_fields` to make the
intended projection explicit. Multiple one-row/one-value query results may be
composed only when their value multiset exactly matches one multi-field
reference row; arbitrary result sets are never silently unioned.

`data_accuracy` uses only comparable cases as its denominator.
`data_comparability_rate` reports comparable reference cases over all
successfully executed reference cases.

### 3. Evaluate all successful SQL evidence

- Strip leading line and block comments before recognizing SELECT/CTE SQL.
- Match required SQL fragments against the combined set of successful SQL
  statements.
- Validate time rules statement-by-statement, requiring one statement to
  contain both the target time field and a valid range.
- Keep one relevant query only for target-result emptiness and deterministic
  answer/result checks.

### 4. Make failure attribution evidence-based

- Emit `unexpected_empty_result` only from structured target rows.
- Emit `sql_only` only for explicit "not executed / please execute" language;
  do not match generic "provide ... execute" prose.
- Treat runtime slot placeholders such as `{target_date}` as leaks, but do not
  classify ontology physical-table templates `{timeDim}` or `{period}` as
  unresolved user-facing parameters.

### 5. Keep deterministic and semantic judging separate

Add structured query summaries containing SQL, row count, columns, result state,
and a small row preview. SQL execution, required fragments, time boundaries,
empty results, structural comparability, explicit result-field consistency, and
reference-result matching are deterministic checks.

Remove `reference_query` and `reference_data_accuracy` from the LLM judge input
entirely. The judge only handles the parts that are not safely reducible to
structural comparison:

- intent and entity/relation semantic equivalence;
- reasoning quality;
- answer clarity and completeness;
- semantic consistency between answer claims and all successful query evidence
  when explicit `answer_result_fields` cannot decide it.

The normalized judge result is post-processed deterministically:

- `sql_or_tool_call` is overwritten from successful tool execution, SQL
  fragments, forbidden patterns, and time checks;
- `result_consistency` is overwritten when explicit `answer_result_fields`
  made the check deterministic;
- the total score is recomputed from the final dimensions.

A model-supplied total that disagrees with a complete dimension vector is kept
as `judge_score_inconsistent` diagnostics. It is not a judge failure because
the deterministic dimension sum is authoritative.

Hallucination still requires an unsupported or contradicted factual claim; it
cannot be inferred from an independent reference-query diagnostic that the
judge cannot see.

### 6. Align metrics and release gates

- Deterministic result consistency applies only when
  `answer_result_fields` is non-empty.
- Otherwise, summary result consistency uses the judge's
  `result_consistency` dimension.
- Business accuracy never substitutes reference-query accuracy for
  answer/result consistency.
- Add `reasoning_average` on its actual 0-2 scale and use a threshold of `1.6`.
- Build explicit `gate_results` from the declared thresholds.
- Require completion, no judge failures, and no vetoes in addition to the
  threshold gates; do not silently require a 100% per-case pass rate.

### 7. Keep the runner dataset-independent

- Dataset filenames, IDs, categories, and suite tags are report metadata only.
- The runner contains no business table names, skill names, or dataset-specific
  allowlists.
- Equivalent predicates and tools are declared in case data through `any_of`
  SQL fragments and `allowed_alternative_groups`.
- Verification and production datasets run through the same executable and
  scoring path; only the input and output paths differ.

## Interfaces

Optional V2 reference-query fields:

| Field | Type | Meaning |
|---|---|---|
| `comparison_fields` | `string[]` | Explicit projection used for reference comparison |
| `enforce_case_gate` | `boolean` | Whether a comparable reference mismatch hard-fails the case; default `false` |
| SQL fragment `any_of` / `all_of` | `object` | Case-declared equivalent SQL fragments |

New/clarified result fields:

- `reference_data_accuracy.status`
- `reference_data_accuracy.comparable`
- `reference_data_accuracy.expected_columns`
- `reference_data_accuracy.actual_columns`
- `reference_data_accuracy.mismatch_reason`
- `metrics.data_comparability_rate`
- `metrics.reasoning_average`
- `gate_results`

## Tradeoffs

- Projection-aware comparison reduces false negatives but still cannot prove
  equivalence between arbitrary SQL programs. Incomparable cases stay visible
  instead of being guessed as pass or fail.
- Reference mismatches no longer fail every case by default. Strict datasets
  can opt in per case after defining a comparison contract that is known to be
  stable.
- The three engines keep intentionally duplicated code. Cross-engine contract
  tests remain the guard against semantic drift.
