# DataAgent evaluation history

`report.py` is a read-only importer for source run directories. It writes
normalized copies under a separate history root and never edits an old run.

```bash
python tools/dataagent-evals/history/report.py \
  --scan-root /path/to/private/evaluation-runs \
  --history-root /path/to/generated/history
```

Outputs include `index.jsonl`, normalized per-run `run.json`, `summary.json`,
`cases.jsonl`, `report.md`, `report.html`, plus self-contained `trend.html` and
`engine-comparison.html`.

Legacy metrics are retained as `legacy_metrics`. Only completion, persisted
status, elapsed time, and Token values that can be reconstructed from case
evidence become derived metrics. Missing values stay `N/A`; runs without the
complete V2 compatibility key are isolated and are never compared as a model
trend. The 2026-05-15 50-case suite is explicitly marked legacy.
