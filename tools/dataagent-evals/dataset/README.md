# DataAgent Evaluation Dataset V2

Evaluation runners accept JSONL records with `schema_version: 2`. This directory
contains only the public contract, anonymous examples, and dataset lifecycle
tools. Private business cases must remain outside this repository.

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

The repository-local
`examples/opendataworks-business-knowledge-smoke-v2.jsonl` suite targets the
`opendataworks-business-knowledge` skill and can be used for real local smoke
tests without the private architecture-ontology database.

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
