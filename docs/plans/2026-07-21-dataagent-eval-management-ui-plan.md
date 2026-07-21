# DataAgent Evaluation Management UI — Plan

**Date**: 2026-07-21
**Design**: `docs/design/2026-07-21-dataagent-eval-management-ui-design.md`
**Branch**: `claude/evaluation-set-management-ui-gr7aru`

## Tasks

### 1. Database Migration
- **File**: `dataagent/dataagent-backend/alembic/versions/20260721_000021_add_eval_tables.py`
- Create `eval_dataset`, `eval_case`, `eval_run`, `eval_run_case` tables
- `down_revision = "20260720_000020"` (current head)
- Uses `_has_table()` guard pattern from existing migrations

### 2. Backend Core Modules
- **`core/eval_contract.py`**: V2 schema validation mirroring `manage.py` (WEIGHTS, REQUIRED, validate, canonical_bytes, parse_jsonl)
- **`core/eval_store.py`**: EvalStore class with `_conn()` context manager (mirrors `topic_task_store` pattern); dataset/case/run CRUD; idempotent ingestion; trend series
- **`core/eval_dataset_service.py`**: Import (parse + validate + create), export (canonical_bytes), hash refresh

### 3. Backend API Layer
- **`api/eval_routes.py`**: APIRouter `prefix=/api/v1/nl2sql-eval`, `dependencies=[Depends(require_admin)]`; dataset CRUD + import/export + case CRUD + run ingestion + trends
- **`models/schemas.py`**: Append Pydantic models (EvalDatasetSummary, EvalCaseSummary, EvalRunSummary, EvalTrendPoint, etc.)
- **`main.py`**: Wire `eval_router`

### 4. Runner Integration
- **`tools/dataagent-evals/builtin/run.py`**: Add `--report-endpoint` / `DATAAGENT_EVAL_REPORT_ENDPOINT` arg; stdlib urllib POST on completion; best-effort (failure warns only)

### 5. Frontend
- **`src/api/dataagent.js`**: Add 15 eval API methods to `dataagentApi`
- **`src/router/index.js`**: Add 4 child routes (evaluations, evaluations/:datasetId, evaluation-results, evaluation-results/:runId)
- **`src/views/intelligence/IntelligentQueryView.vue`**: Add sidebar menu items + icon imports
- **`src/views/evaluation/EvaluationSetsView.vue`**: Dataset list with CRUD, import, export
- **`src/views/evaluation/EvaluationSetDetailView.vue`**: Case list with CodeMirror JSON editor
- **`src/views/evaluation/EvaluationResultsView.vue`**: Run list + ECharts trend chart
- **`src/views/evaluation/EvaluationRunDetailView.vue`**: Run summary + per-case table + detail dialog

### 6. Documentation
- `docs/design/2026-07-21-dataagent-eval-management-ui-design.md`
- `docs/plans/2026-07-21-dataagent-eval-management-ui-plan.md`

## Touched Files

| File | Action |
|------|--------|
| `dataagent/dataagent-backend/alembic/versions/20260721_000021_add_eval_tables.py` | New |
| `dataagent/dataagent-backend/core/eval_contract.py` | New |
| `dataagent/dataagent-backend/core/eval_store.py` | New |
| `dataagent/dataagent-backend/core/eval_dataset_service.py` | New |
| `dataagent/dataagent-backend/api/eval_routes.py` | New |
| `dataagent/dataagent-backend/models/schemas.py` | Modified (appended eval models) |
| `dataagent/dataagent-backend/main.py` | Modified (wired eval_router) |
| `tools/dataagent-evals/builtin/run.py` | Modified (added --report-endpoint) |
| `dataagent/dataagent-frontend/src/api/dataagent.js` | Modified (added eval API methods) |
| `dataagent/dataagent-frontend/src/router/index.js` | Modified (added eval routes) |
| `dataagent/dataagent-frontend/src/views/intelligence/IntelligentQueryView.vue` | Modified (added menu items) |
| `dataagent/dataagent-frontend/src/views/evaluation/EvaluationSetsView.vue` | New |
| `dataagent/dataagent-frontend/src/views/evaluation/EvaluationSetDetailView.vue` | New |
| `dataagent/dataagent-frontend/src/views/evaluation/EvaluationResultsView.vue` | New |
| `dataagent/dataagent-frontend/src/views/evaluation/EvaluationRunDetailView.vue` | New |
| `docs/design/2026-07-21-dataagent-eval-management-ui-design.md` | New |
| `docs/plans/2026-07-21-dataagent-eval-management-ui-plan.md` | New |

## Verification

- **Frontend build**: `npm --prefix dataagent/dataagent-frontend run build` passes
- **Backend contract test**: `eval_contract` validate/canonical_bytes matches `manage.py` on smoke dataset
- **Backend syntax**: All new Python files parse without errors
- **End-to-end smoke** (when local MySQL + backend available): Import JSONL → list datasets → export → byte equality; ingest run → list runs → trend data

## Rollback

- Drop migration: `alembic downgrade 20260720_000020` removes all eval_* tables
- Frontend routes are lazy-loaded and admin-gated; removing the router entries restores previous state
- Runner `--report-endpoint` is opt-in with no default; removing the flag has zero impact on existing workflows
