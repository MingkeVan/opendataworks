# DataAgent Evaluation Management UI — Design

**Date**: 2026-07-21
**Status**: Implemented
**Scope**: `dataagent/dataagent-backend`, `dataagent/dataagent-frontend`, `tools/dataagent-evals/builtin`

## Current State

DataAgent has a mature, file-based evaluation system:

- **Datasets**: JSONL files (`schema_version: 2`) stored in `tools/dataagent-evals/dataset/`, managed by `manage.py` with canonical serialization and SHA-256 hashing.
- **Runners**: Three independent engines (`builtin`, `deepeval`, `opik`) under `tools/dataagent-evals/`, each producing `summary.json` + `cases.jsonl` + `report.html`.
- **Trends**: `tools/dataagent-evals/history/report.py` normalizes run directories into offline HTML reports.
- **7-dimension scoring**: intent, ontology_entity, relation_scope, sql_or_tool_call, result_consistency, reasoning, answer_quality; total = 10 points.

All management, result viewing, and trending is offline/CLI-only. There is no persistent database storage for evaluation data, and no web UI for any evaluation workflow.

## Problem

- Evaluation datasets can only be managed by editing JSONL files locally — no web-based CRUD, search, or validation.
- Evaluation results are scattered across run directories with no persistent, queryable store.
- Trend analysis requires running offline scripts; no live dashboard exists.
- Sharing evaluation state across team members requires file sharing rather than a centralized service.

## Scope

**In scope (this change)**:
- Database persistence for evaluation datasets, cases, runs, and run results.
- Web UI for dataset CRUD (create, read, update, delete), JSONL import/export, and case editing.
- Web UI for viewing evaluation runs, run details, per-case results, and trend charts.
- Ingestion API for runners to report results to the database.
- Optional `--report-endpoint` flag on the builtin runner for automatic result upload.

**Out of scope**:
- Triggering evaluation runs from the UI (runners remain independent CLI tools).
- Multi-tenant or RBAC beyond the existing `require_admin` guard.
- Runner modifications beyond the optional `--report-endpoint`.

## Solution

### Architecture

```
dataagent-frontend (Vue 3 / Element Plus / ECharts)
  └─ Evaluation management pages (admin-only)
       │ axios → dataagentApi (X-ODW-Client: dataagent)
       ▼
dataagent-backend (FastAPI)
  ├─ api/eval_routes.py     APIRouter prefix=/api/v1/nl2sql-eval
  ├─ core/eval_store.py     PyMySQL CRUD (session_mysql_database)
  ├─ core/eval_contract.py  V2 schema validation (mirrors manage.py)
  ├─ core/eval_dataset_service.py  Import/export orchestration
  └─ models/schemas.py      Pydantic response models
       │
       ▼
MySQL: existing `dataagent` schema, new eval_* tables (Alembic migration)
```

### Data Model

Four new tables in the existing `dataagent` schema:

1. **`eval_dataset`** — Dataset metadata (name, description, category, tags, case_count, SHA-256 hash, status).
2. **`eval_case`** — Individual test cases; `case_json LONGTEXT` stores the complete V2 case for lossless JSONL round-trip.
3. **`eval_run`** — One evaluation run; stores summary metrics, model/engine info, pass/fail, timing. `run_id` defaults to sha256 of summary for idempotent ingestion.
4. **`eval_run_case`** — Per-case results within a run; score, pass/fail, hallucination flag, 7-dimension scores, full case JSON.

### Contract Duplication

The backend cannot import `tools/dataagent-evals/dataset/manage.py` at runtime (Docker image only contains `dataagent/dataagent-backend`). Instead, `core/eval_contract.py` reimplements the same contract (WEIGHTS, REQUIRED, validate, canonical_bytes). A regression test imports both implementations and asserts byte-level equality on the smoke dataset.

### Ingestion

Runners produce `summary.json` + `cases.jsonl`. The `POST /runs` endpoint accepts these as JSON body. Ingestion is idempotent: duplicate `run_id` returns the existing record without re-inserting. `started_at` uses a three-level fallback: explicit parameter → parsed from `run_label` (`%Y%m%d-%H%M%S`) → `ingested_at`.

### Frontend

Four new views under `src/views/evaluation/`:
- **EvaluationSetsView**: Dataset table with CRUD, search, JSONL import (el-upload), export (blob download).
- **EvaluationSetDetailView**: Case list with search, inline JSON editor (CodeMirror via TextCodeEditor) for creating/editing V2 cases.
- **EvaluationResultsView**: Run list with filters (dataset, engine) + ECharts trend line chart (average_score, accuracies, hallucination_rate over time).
- **EvaluationRunDetailView**: Summary metric cards, detailed descriptions table, metrics tree, per-case results table with expandable 7-dimension scores and full JSON viewer.

All routes are `adminOnly`, consistent with existing admin pages.

## Interfaces

### API Routes (`/api/v1/nl2sql-eval`, all `require_admin`)

| Method | Path | Description |
|--------|------|-------------|
| GET | /datasets | List datasets |
| POST | /datasets | Create dataset |
| GET/PUT/DELETE | /datasets/{id} | Dataset CRUD |
| POST | /datasets/imports | Import JSONL |
| GET | /datasets/{id}/export | Export JSONL |
| GET | /datasets/{id}/cases | List cases |
| GET/PUT/DELETE | /datasets/{id}/cases/{case_id} | Case CRUD |
| PUT | /datasets/{id}/cases | Bulk replace cases |
| POST | /runs | Ingest run |
| GET | /runs | List runs |
| GET | /runs/{run_id} | Run detail |
| GET | /runs/{run_id}/cases | Run case list |
| GET | /runs/{run_id}/cases/{case_id} | Run case detail |
| GET | /trends | Trend series |

### Dataset Hash Refresh

Any case mutation (upsert, delete, replace) triggers `canonical_bytes` recomputation of `dataset_hash` and `case_count` on `eval_dataset`, keeping metadata consistent with actual content.

### Dataset ID Resolution on Ingestion

Runner `summary.dataset_id` is the JSONL **filename stem** (e.g. `opendataworks-business-knowledge-smoke-v2`), not a UI-generated `ds-*` UUID. On ingestion, if the submitted `dataset_id` does not match any `eval_dataset.dataset_id`, the system performs a reverse lookup by `dataset_hash`: when the runner's `dataset_hash` matches an existing dataset's hash, `dataset_id` is overwritten with the UI dataset's ID. This enables "filter runs by dataset" to work across both UI-managed and CLI-managed datasets. When no hash match is found, the original `dataset_id` is preserved as-is (soft association).

### Case Upsert Validation

Single-case upsert (`PUT /datasets/{id}/cases/{case_id}`) runs the full V2 `validate()` on the merged case list before persisting, consistent with bulk `replace_cases`. This prevents invalid cases from entering the database via the primary UI editing path. The merged list preserves the original insertion order (in-place replacement) so that `dataset_hash` matches the export order (`ORDER BY id`).

## Tradeoffs

1. **Contract duplication vs runtime import**: Duplication adds maintenance cost but avoids Docker build complexity and keeps the runner independent. Regression test (`tests/test_eval_contract.py`) mitigates drift risk.
2. **Storing full case_json in DB**: Increases storage but enables lossless JSONL export round-trips without lossy reconstruction from normalized columns.
3. **Ingestion API vs direct DB writes from runners**: Keeps runners stateless and deployment-agnostic; adds an HTTP call but preserves the "three engines are independent" philosophy.
4. **No UI-triggered evaluation**: Keeps MVP scope manageable; runners remain CLI tools with their own configuration and credentials.
5. **No explicit request body size limit on ingestion**: The `POST /runs` endpoint does not enforce an application-level body size limit. Large runs (many cases) are bounded by the upstream reverse proxy and uvicorn's default limits. This is acceptable for the current single-tenant deployment; a future multi-tenant scenario may need explicit limits.
