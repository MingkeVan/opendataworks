# DataAgent Evaluation Dataset V2

Evaluation runners accept JSONL records with `schema_version: 2`. This directory
contains the public contract, public examples, and dataset lifecycle tools.

## Required case shape

- identity: `schema_version`, `case_id`, `category`, `case_type`, `suite_tags`
- prompt: `question` or non-empty `turns`
- expectations: `expected_semantics`, `expected_time`, `expected_tools`,
  `expected_sql`, `expected_result`, `expected_answer`
- controls: `limits`, `scoring`, `veto_rules`

The seven scoring dimensions and maximum weights are fixed:

`intent=1`, `ontology_entity=1`, `relation_scope=1`,
`sql_or_tool_call=2`, `result_consistency=2`, `reasoning=2`,
`answer_quality=1`. `total_score` must equal the dimension sum (`10`).

Only successful query tool evidence can satisfy `expected_sql.execution_required`.
SQL displayed in the final answer never counts as execution evidence.

`expected_result.reference_query` is optional. When present, a runner executes
that read-only SQL independently through DataAgent's query proxy and compares
its rows with the successful Agent query result. This produces the separate
`data_accuracy` metric. `result_consistency_rate` continues to mean that the
final answer agrees with the Agent tool result. If no reference query is
defined, data accuracy is reported as `N/A`, never as zero. A reference-query
execution failure is an evaluation-infrastructure failure rather than a model
quality failure.

Reference comparison first establishes whether one successful Agent result is
structurally comparable. `data_accuracy` uses only comparable cases as its
denominator; `data_comparability_rate` reports comparable reference cases over
all reference cases. `unordered_values` may project away extra Agent columns
when every reference column is present. Use `comparison_fields` when the
intended projection must be explicit.

A comparable reference mismatch is diagnostic by default and does not fail the
case or enter the LLM judge input. A case may set `enforce_case_gate: true` only
when its reference query and comparison contract are stable enough to be a hard
oracle. Required SQL fragments, time boundaries, empty results, explicit answer
fields, reference comparability, and reference values are deterministic checks;
the LLM judge is reserved for semantic equivalence, reasoning, answer quality,
and answer/result consistency that cannot be decided from explicit fields.

Entries in `expected_sql.tables`, `fields`, `predicates`, and `aggregations`
may be strings or explicit alternative groups such as
`{"any_of": ["is_deleted = 0", "is_deleted != 1"]}`. Equivalent SQL forms
must be declared in case data; runners must not hardcode domain-specific
aliases, predicates, tool names, dataset IDs, or suite tags.

Example datasets are ordinary V2 inputs. Their filenames, case IDs, categories,
and suite tags never alter runner logic. Formal and verification datasets must
use the same runner and metric semantics.

## Commands

Validate and regenerate subsets from a core file:

```bash
python tools/dataagent-evals/dataset/manage.py generate \
  --core /absolute/path/arch-governance-core.jsonl \
  --manifest /absolute/path/arch-governance-manifest.json
```

Convert a V1 source into a V2 file for manual review:

```bash
python tools/dataagent-evals/dataset/manage.py migrate-v1 \
  --input old.jsonl --output migrated-v2.jsonl
```

Migration never overwrites the input. Domain-specific corrections must be made
in the private V2 core before running `generate`.
