# DataAgent Opik Evaluations

This is the third, independent DataAgent Evaluation V2 runner. It does not
import runtime, authentication, evidence, scoring, or report code from the
builtin or DeepEval runners.

The Python SDK and self-hosted service are pinned to `2.1.32`. Do not replace
the pin with `latest`; validate an upgrade as a separate change.

## Local dry-run

```bash
python tools/dataagent-evals/opik/run.py --dry-run \
  --dataset tools/dataagent-evals/dataset/examples/opendataworks-business-knowledge-smoke-v2.jsonl
```

Dry-run validates V2 without requiring Opik. A real run requires the pinned SDK
and a running Opik service:

```bash
python -m pip install -r tools/dataagent-evals/opik/requirements.txt
DATAAGENT_EVAL_AUTH_TOKEN=... \
DATAAGENT_EVAL_JUDGE_BASE_URL=http://judge.internal \
DATAAGENT_EVAL_JUDGE_TOKEN=... \
DATAAGENT_EVAL_JUDGE_MODEL=fixed-judge \
bash scripts/run-dataagent-opik-evals.sh \
  --base-url http://127.0.0.1:8900 \
  --opik-base-url http://127.0.0.1:5173/api \
  --agent-id agent_eval \
  --agent-snapshot-path dataagent/.claude/skills/opendataworks-business-knowledge \
  --dataset tools/dataagent-evals/dataset/examples/opendataworks-business-knowledge-smoke-v2.jsonl
```

Each case is stored as an Opik Dataset item and executed inside an Opik
Experiment Trace. DataAgent HTTP calls and the judge are recorded as child
Spans. The custom metric writes the seven V2 dimensions, the hard gate,
result consistency, and optional reference-SQL data accuracy. Authentication
tokens are never written to the Dataset, Trace metadata, or local reports.

The runner also writes the standard `run.json`, `summary.json`, `cases.jsonl`,
`report.md`, `report.html`, raw evidence, and dataset snapshot. Exit codes are
0 for passing, 1 for quality-gate failure, and 2 for authentication, Opik, or
other evaluation-infrastructure failure.
