from __future__ import annotations

from fastapi import APIRouter, HTTPException

from config import get_settings
from core.skill_admin_service import (
    compare_document_versions,
    current_settings_payload,
    get_document_detail,
    list_documents,
    persist_admin_settings,
    resolve_claude_settings_path,
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
    cfg = get_settings()
    anthropic_models = [
        "claude-opus-4-6",
        "claude-sonnet-4-20250514",
        "claude-3-7-sonnet-20250219",
    ]
    openrouter_models = [
        "anthropic/claude-sonnet-4.5",
        "anthropic/claude-sonnet-4.6",
        "anthropic/claude-opus-4.1",
    ]
    anyrouter_models = [
        "claude-opus-4-6",
        "claude-sonnet-4-20250514",
        "claude-3-7-sonnet-20250219",
    ]
    compatible_models = [cfg.claude_model] if cfg.claude_model else []
    return [
        ProviderConfig(
            provider_id="anthropic",
            display_name="Anthropic",
            base_url="https://api.anthropic.com",
            api_key_set=bool(cfg.anthropic_api_key),
            auth_token_set=bool(cfg.anthropic_auth_token),
            models=anthropic_models,
            default_model="claude-sonnet-4-20250514",
        ),
        ProviderConfig(
            provider_id="openrouter",
            display_name="OpenRouter (Anthropic Compatible)",
            base_url="https://openrouter.ai/api",
            api_key_set=bool(cfg.anthropic_api_key),
            auth_token_set=bool(cfg.anthropic_auth_token),
            models=openrouter_models,
            default_model="anthropic/claude-sonnet-4.5",
        ),
        ProviderConfig(
            provider_id="anyrouter",
            display_name="AnyRouter (Anthropic Compatible)",
            base_url=cfg.anthropic_base_url or "https://a-ocnfniawgw.cn-shanghai.fcapp.run",
            api_key_set=bool(cfg.anthropic_api_key),
            auth_token_set=bool(cfg.anthropic_auth_token),
            models=anyrouter_models,
            default_model=cfg.claude_model or "claude-opus-4-6",
        ),
        ProviderConfig(
            provider_id="anthropic_compatible",
            display_name="Custom Anthropic Compatible",
            base_url=cfg.anthropic_base_url or "",
            api_key_set=bool(cfg.anthropic_api_key),
            auth_token_set=bool(cfg.anthropic_auth_token),
            models=compatible_models,
            default_model=cfg.claude_model or "",
        ),
    ]


def _build_admin_settings_response(updated_at: str = "") -> AdminSettingsResponse:
    payload = current_settings_payload()
    return AdminSettingsResponse(
        provider_id=str(payload.get("provider_id") or ""),
        model=str(payload.get("model") or ""),
        providers=_provider_catalog(),
        anthropic_api_key=str(payload.get("anthropic_api_key") or ""),
        anthropic_auth_token=str(payload.get("anthropic_auth_token") or ""),
        anthropic_base_url=str(payload.get("anthropic_base_url") or ""),
        mysql_host=str(payload.get("mysql_host") or ""),
        mysql_port=int(payload.get("mysql_port") or 3306),
        mysql_user=str(payload.get("mysql_user") or ""),
        mysql_password=str(payload.get("mysql_password") or ""),
        mysql_database=str(payload.get("mysql_database") or ""),
        doris_host=str(payload.get("doris_host") or ""),
        doris_port=int(payload.get("doris_port") or 9030),
        doris_user=str(payload.get("doris_user") or ""),
        doris_password=str(payload.get("doris_password") or ""),
        doris_database=str(payload.get("doris_database") or ""),
        skills_output_dir=str(payload.get("skills_output_dir") or ""),
        session_mysql_database=str(payload.get("session_mysql_database") or ""),
        settings_file_path=str(resolve_claude_settings_path()),
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
