"""Evaluation management API — dataset CRUD, JSONL import/export, run ingestion, trends."""
from __future__ import annotations

import json
import uuid
from urllib.parse import quote

from fastapi import APIRouter, Depends, File, HTTPException, Query, UploadFile
from fastapi.responses import Response

from core.auth import require_admin
from core.eval_contract import canonical_bytes, dataset_hash, parse_jsonl, validate
from core.eval_dataset_service import export_dataset, import_dataset
from core.eval_store import get_eval_store
from models.schemas import (
    EvalCaseDetail,
    EvalCaseSummary,
    EvalCaseUpsertRequest,
    EvalCasesReplaceRequest,
    EvalDatasetCreateRequest,
    EvalDatasetSummary,
    EvalDatasetUpdateRequest,
    EvalImportResponse,
    EvalRunCaseDetail,
    EvalRunCaseSummary,
    EvalRunDetail,
    EvalRunIngestRequest,
    EvalRunSummary,
    EvalTrendPoint,
)

router = APIRouter(prefix="/api/v1/nl2sql-eval", dependencies=[Depends(require_admin)])


# ---- Datasets ----

@router.get("/datasets", response_model=list[EvalDatasetSummary])
async def list_datasets(status: str = Query("", description="Filter by status")):
    return get_eval_store().list_datasets(status=status)


@router.post("/datasets", response_model=EvalDatasetSummary, status_code=201)
async def create_dataset(request: EvalDatasetCreateRequest):
    store = get_eval_store()
    dataset_id = f"ds-{uuid.uuid4().hex[:12]}"
    return store.create_dataset(
        dataset_id=dataset_id,
        name=request.name,
        description=request.description,
        category=request.category,
        suite_tags=request.suite_tags,
    )


@router.get("/datasets/{dataset_id}", response_model=EvalDatasetSummary)
async def get_dataset(dataset_id: str):
    ds = get_eval_store().get_dataset(dataset_id)
    if not ds:
        raise HTTPException(status_code=404, detail="dataset not found")
    return ds


@router.put("/datasets/{dataset_id}", response_model=EvalDatasetSummary)
async def update_dataset(dataset_id: str, request: EvalDatasetUpdateRequest):
    store = get_eval_store()
    if not store.get_dataset(dataset_id):
        raise HTTPException(status_code=404, detail="dataset not found")
    patch = {k: v for k, v in request.model_dump().items() if v is not None}
    return store.update_dataset(dataset_id, patch)


@router.delete("/datasets/{dataset_id}", status_code=204)
async def delete_dataset(dataset_id: str):
    if not get_eval_store().delete_dataset(dataset_id):
        raise HTTPException(status_code=404, detail="dataset not found")


# ---- Dataset import/export ----

@router.post("/datasets/imports", response_model=EvalImportResponse)
async def import_dataset_jsonl(file: UploadFile = File(...)):
    content = await file.read()
    name = (file.filename or "imported").removesuffix(".jsonl").removesuffix(".json")
    try:
        ds = import_dataset(name, content)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return EvalImportResponse(
        dataset_id=ds["dataset_id"],
        name=ds["name"],
        case_count=ds.get("case_count", 0),
        dataset_hash=ds.get("dataset_hash", ""),
    )


@router.get("/datasets/{dataset_id}/export")
async def export_dataset_jsonl(dataset_id: str):
    try:
        filename, data = export_dataset(dataset_id)
    except ValueError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    ascii_name = filename.encode("ascii", "replace").decode("ascii")
    disposition = f"attachment; filename=\"{ascii_name}\"; filename*=UTF-8''{quote(filename)}"
    return Response(
        content=data,
        media_type="application/x-ndjson",
        headers={"Content-Disposition": disposition},
    )


# ---- Cases ----

@router.get("/datasets/{dataset_id}/cases", response_model=list[EvalCaseSummary])
async def list_cases(dataset_id: str):
    store = get_eval_store()
    if not store.get_dataset(dataset_id):
        raise HTTPException(status_code=404, detail="dataset not found")
    return store.list_cases(dataset_id)


@router.get("/datasets/{dataset_id}/cases/{case_id}", response_model=EvalCaseDetail)
async def get_case(dataset_id: str, case_id: str):
    row = get_eval_store().get_case(dataset_id, case_id)
    if not row:
        raise HTTPException(status_code=404, detail="case not found")
    if isinstance(row.get("case_json"), str):
        try:
            row["case_json"] = json.loads(row["case_json"])
        except (json.JSONDecodeError, TypeError):
            pass
    return row


@router.put("/datasets/{dataset_id}/cases", response_model=EvalDatasetSummary)
async def replace_cases(dataset_id: str, request: EvalCasesReplaceRequest):
    store = get_eval_store()
    ds = store.get_dataset(dataset_id)
    if not ds:
        raise HTTPException(status_code=404, detail="dataset not found")
    result = validate(request.cases)
    if not result["valid"]:
        raise HTTPException(status_code=400, detail="; ".join(result["errors"][:20]))
    store.replace_cases(dataset_id, request.cases, result["dataset_hash"])
    return store.get_dataset(dataset_id)


@router.put("/datasets/{dataset_id}/cases/{case_id}", response_model=EvalCaseDetail)
async def upsert_case(dataset_id: str, case_id: str, request: EvalCaseUpsertRequest):
    store = get_eval_store()
    ds = store.get_dataset(dataset_id)
    if not ds:
        raise HTTPException(status_code=404, detail="dataset not found")
    case = request.case_json
    if str(case.get("case_id") or "") != case_id:
        raise HTTPException(status_code=400, detail="case_id in path and body must match")
    all_cases = store.get_case_full_json(dataset_id)
    replaced = False
    merged = []
    for c in all_cases:
        if str(c.get("case_id") or "") == case_id:
            merged.append(case)
            replaced = True
        else:
            merged.append(c)
    if not replaced:
        merged.append(case)
    validation = validate(merged)
    if not validation["valid"]:
        raise HTTPException(status_code=400, detail="; ".join(validation["errors"][:20]))
    h = dataset_hash(merged)
    result = store.upsert_case(dataset_id, case, h, len(merged))
    if isinstance(result.get("case_json"), str):
        try:
            result["case_json"] = json.loads(result["case_json"])
        except (json.JSONDecodeError, TypeError):
            pass
    return result


@router.delete("/datasets/{dataset_id}/cases/{case_id}", status_code=204)
async def delete_case(dataset_id: str, case_id: str):
    store = get_eval_store()
    ds = store.get_dataset(dataset_id)
    if not ds:
        raise HTTPException(status_code=404, detail="dataset not found")
    all_cases = store.get_case_full_json(dataset_id)
    remaining = [c for c in all_cases if str(c.get("case_id") or "") != case_id]
    h = dataset_hash(remaining)
    if not store.delete_case(dataset_id, case_id, h, len(remaining)):
        raise HTTPException(status_code=404, detail="case not found")


# ---- Runs ----

@router.post("/runs", response_model=EvalRunSummary, status_code=201)
async def ingest_run(request: EvalRunIngestRequest):
    store = get_eval_store()
    run = store.ingest_run(
        request.summary,
        request.cases,
        run_id=request.run_id or "",
        started_at=request.started_at or "",
    )
    return run


@router.get("/runs", response_model=list[EvalRunSummary])
async def list_runs(
    dataset_id: str = Query(""),
    evaluation_engine: str = Query(""),
    limit: int = Query(100, ge=1, le=500),
    offset: int = Query(0, ge=0),
):
    return get_eval_store().list_runs(
        dataset_id=dataset_id,
        evaluation_engine=evaluation_engine,
        limit=limit,
        offset=offset,
    )


@router.get("/runs/{run_id}", response_model=EvalRunDetail)
async def get_run(run_id: str):
    run = get_eval_store().get_run(run_id)
    if not run:
        raise HTTPException(status_code=404, detail="run not found")
    for key in ("metrics_json", "summary_json"):
        if isinstance(run.get(key), str):
            try:
                run[key] = json.loads(run[key])
            except (json.JSONDecodeError, TypeError):
                pass
    return run


@router.get("/runs/{run_id}/cases", response_model=list[EvalRunCaseSummary])
async def list_run_cases(run_id: str):
    if not get_eval_store().get_run(run_id):
        raise HTTPException(status_code=404, detail="run not found")
    rows = get_eval_store().list_run_cases(run_id)
    for row in rows:
        if isinstance(row.get("dimension_scores_json"), str):
            try:
                row["dimension_scores_json"] = json.loads(row["dimension_scores_json"])
            except (json.JSONDecodeError, TypeError):
                pass
    return rows


@router.get("/runs/{run_id}/cases/{case_id}", response_model=EvalRunCaseDetail)
async def get_run_case(run_id: str, case_id: str):
    row = get_eval_store().get_run_case_detail(run_id, case_id)
    if not row:
        raise HTTPException(status_code=404, detail="run case not found")
    for key in ("dimension_scores_json", "case_json"):
        if isinstance(row.get(key), str):
            try:
                row[key] = json.loads(row[key])
            except (json.JSONDecodeError, TypeError):
                pass
    return row


# ---- Trends ----

@router.get("/trends", response_model=list[EvalTrendPoint])
async def get_trends(
    dataset_id: str = Query(""),
    evaluation_engine: str = Query(""),
    limit: int = Query(50, ge=1, le=200),
):
    return get_eval_store().trend_series(
        dataset_id=dataset_id,
        evaluation_engine=evaluation_engine,
        limit=limit,
    )
