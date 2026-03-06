from __future__ import annotations

from fastapi import APIRouter, HTTPException

from core.skill_admin_service import (
    compare_document_versions,
    current_settings_payload,
    get_document_detail,
    list_documents,
    list_provider_configs,
    persist_admin_settings,
    rollback_document,
    save_document_content,
    sync_from_opendataworks,
)
from core.skills_loader import resolve_skills_root_dir
from models.schemas import (
    AdminSettingsResponse,
    AdminSettingsUpdateRequest,
    ProviderConfig,
    SkillDocumentCompareRequest,
    SkillDocumentCompareResponse,
    SkillDocumentDetail,
    SkillDocumentSummary,
    SkillDocumentUpdateRequest,
    SkillSyncResponse,
)

router = APIRouter(prefix="/api/v1/dataagent")


def _provider_catalog() -> list[ProviderConfig]:
    return [ProviderConfig.model_validate(item) for item in list_provider_configs(enabled_only=False)]


def _build_admin_settings_response(updated_at: str = "") -> AdminSettingsResponse:
    payload = current_settings_payload()
    return AdminSettingsResponse(
        provider_id=str(payload.get("provider_id") or ""),
        model=str(payload.get("model") or ""),
        providers=_provider_catalog(),
        anthropic_api_key="",
        anthropic_auth_token="",
        anthropic_base_url=str(payload.get("anthropic_base_url") or ""),
        mysql_host=str(payload.get("mysql_host") or ""),
        mysql_port=int(payload.get("mysql_port") or 3306),
        mysql_user=str(payload.get("mysql_user") or ""),
        mysql_password="",
        mysql_database=str(payload.get("mysql_database") or ""),
        doris_host=str(payload.get("doris_host") or ""),
        doris_port=int(payload.get("doris_port") or 9030),
        doris_user=str(payload.get("doris_user") or ""),
        doris_password="",
        doris_database=str(payload.get("doris_database") or ""),
        skills_output_dir=str(payload.get("skills_output_dir") or ""),
        session_mysql_database=str(payload.get("session_mysql_database") or ""),
        settings_file_path="",
        settings_local_file_path="",
        skills_root_dir=str(resolve_skills_root_dir()),
        updated_at=updated_at,
    )


@router.get("/settings", response_model=AdminSettingsResponse)
async def get_admin_settings():
    return _build_admin_settings_response()


@router.put("/settings", response_model=AdminSettingsResponse)
async def update_admin_settings(request: AdminSettingsUpdateRequest):
    try:
        saved = persist_admin_settings(request.model_dump(exclude_none=True))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return _build_admin_settings_response(updated_at=str(saved.get("updated_at") or ""))


@router.get("/skills/documents", response_model=list[SkillDocumentSummary])
async def get_skill_documents():
    return [SkillDocumentSummary.model_validate(item) for item in list_documents()]


@router.get("/skills/documents/{document_id}", response_model=SkillDocumentDetail)
async def get_skill_document(document_id: int):
    document = get_document_detail(document_id)
    if not document:
        raise HTTPException(status_code=404, detail="document not found")
    return SkillDocumentDetail.model_validate(document)


@router.put("/skills/documents/{document_id}", response_model=SkillDocumentDetail)
async def update_skill_document(document_id: int, request: SkillDocumentUpdateRequest):
    try:
        document = save_document_content(document_id, request.content, request.change_summary)
    except ValueError as exc:
        message = str(exc)
        status_code = 404 if "not found" in message else 400
        raise HTTPException(status_code=status_code, detail=message) from exc
    return SkillDocumentDetail.model_validate(document)


@router.post("/skills/documents/{document_id}/compare", response_model=SkillDocumentCompareResponse)
async def compare_skill_document(document_id: int, request: SkillDocumentCompareRequest):
    try:
        result = compare_document_versions(
            document_id,
            left_version_id=request.left_version_id,
            right_version_id=request.right_version_id,
        )
    except ValueError as exc:
        message = str(exc)
        status_code = 404 if "not found" in message else 400
        raise HTTPException(status_code=status_code, detail=message) from exc
    return SkillDocumentCompareResponse.model_validate(result)


@router.post("/skills/documents/{document_id}/versions/{version_id}/rollback", response_model=SkillDocumentDetail)
async def rollback_skill_document(document_id: int, version_id: int):
    try:
        document = rollback_document(document_id, version_id)
    except ValueError as exc:
        message = str(exc)
        status_code = 404 if "not found" in message else 400
        raise HTTPException(status_code=status_code, detail=message) from exc
    return SkillDocumentDetail.model_validate(document)


@router.post("/skills/sync", response_model=SkillSyncResponse)
async def sync_skill_documents():
    try:
        result = sync_from_opendataworks()
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return SkillSyncResponse.model_validate(result)
