from __future__ import annotations

import difflib
import hashlib
import logging
from datetime import datetime
from pathlib import Path
from typing import Any

from config import get_settings, update_settings
from core.semantic_layer import get_semantic_layer
from core.skill_admin_store import get_skill_admin_store
from core.skills_loader import resolve_skills_root_dir, validate_skills_bundle
from core.skills_sync import ensure_static_skills_bundle

logger = logging.getLogger(__name__)

SUPPORTED_PROVIDERS = {"anthropic", "openrouter", "anyrouter", "anthropic_compatible"}
MANAGED_FILE_SUFFIXES = {".json", ".md", ".markdown", ".py"}
DEFAULT_PROVIDER_ID = "openrouter"
DEFAULT_MODEL = "anthropic/claude-sonnet-4.5"

PROVIDER_DEFINITIONS: dict[str, dict[str, Any]] = {
    "anthropic": {
        "display_name": "Anthropic",
        "provider_group": "官方模型",
        "default_base_url": "https://api.anthropic.com",
        "default_model": "claude-sonnet-4-20250514",
        "supported_models": [
            "claude-opus-4-6",
            "claude-sonnet-4-20250514",
            "claude-3-7-sonnet-20250219",
        ],
    },
    "openrouter": {
        "display_name": "OpenRouter",
        "provider_group": "聚合路由",
        "default_base_url": "https://openrouter.ai/api",
        "default_model": "anthropic/claude-sonnet-4.5",
        "supported_models": [
            "anthropic/claude-sonnet-4.5",
            "anthropic/claude-sonnet-4.6",
            "anthropic/claude-opus-4.1",
        ],
    },
    "anyrouter": {
        "display_name": "AnyRouter",
        "provider_group": "聚合路由",
        "default_base_url": "https://a-ocnfniawgw.cn-shanghai.fcapp.run",
        "default_model": "claude-opus-4-6",
        "supported_models": [
            "claude-opus-4-6",
            "claude-sonnet-4-20250514",
            "claude-3-7-sonnet-20250219",
        ],
    },
    "anthropic_compatible": {
        "display_name": "Anthropic Compatible",
        "provider_group": "自定义接入",
        "default_base_url": "",
        "default_model": "",
        "supported_models": [],
    },
}

RUNTIME_SETTING_KEYS = {
    "provider_id",
    "model",
    "anthropic_api_key",
    "anthropic_auth_token",
    "anthropic_base_url",
    "mysql_host",
    "mysql_port",
    "mysql_user",
    "mysql_password",
    "mysql_database",
    "doris_host",
    "doris_port",
    "doris_user",
    "doris_password",
    "doris_database",
    "skills_output_dir",
    "session_mysql_database",
}


def current_settings_payload() -> dict[str, Any]:
    runtime = _runtime_settings_payload()
    store = get_skill_admin_store()
    try:
        store.init_schema()
        db_payload = store.load_settings_record() or {}
    except Exception as exc:
        logger.warning("Failed to load admin settings from store: %s", exc)
        db_payload = {}
    return _merge_settings_payload(runtime, db_payload)


def _runtime_settings_payload() -> dict[str, Any]:
    cfg = get_settings()
    return {
        "provider_id": cfg.llm_provider,
        "model": cfg.claude_model,
        "anthropic_api_key": cfg.anthropic_api_key,
        "anthropic_auth_token": cfg.anthropic_auth_token,
        "anthropic_base_url": cfg.anthropic_base_url,
        "mysql_host": cfg.mysql_host,
        "mysql_port": cfg.mysql_port,
        "mysql_user": cfg.mysql_user,
        "mysql_password": cfg.mysql_password,
        "mysql_database": cfg.mysql_database,
        "doris_host": cfg.doris_host,
        "doris_port": cfg.doris_port,
        "doris_user": cfg.doris_user,
        "doris_password": cfg.doris_password,
        "doris_database": cfg.doris_database,
        "skills_output_dir": cfg.skills_output_dir,
        "session_mysql_database": cfg.session_mysql_database,
    }


def _now_iso() -> str:
    return datetime.now().isoformat(timespec="seconds")


def _normalize_provider_id(provider_id: str | None) -> str:
    value = str(provider_id or "").strip().lower()
    return value if value in SUPPORTED_PROVIDERS else DEFAULT_PROVIDER_ID


def _string_list(values: Any) -> list[str]:
    if not isinstance(values, (list, tuple, set)):
        return []
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        item = str(value or "").strip()
        if not item or item in seen:
            continue
        seen.add(item)
        result.append(item)
    return result


def _provider_definition(provider_id: str) -> dict[str, Any]:
    return dict(PROVIDER_DEFINITIONS.get(provider_id) or PROVIDER_DEFINITIONS[DEFAULT_PROVIDER_ID])


def _default_provider_settings(provider_id: str) -> dict[str, Any]:
    definition = _provider_definition(provider_id)
    return {
        "provider_id": provider_id,
        "api_key": "",
        "auth_token": "",
        "base_url": str(definition.get("default_base_url") or ""),
        "enabled_models": [],
        "custom_models": [],
        "validation_status": "unverified",
        "validation_message": "未填写凭证",
        "validated_at": "",
    }


def _coerce_provider_settings(raw: Any) -> dict[str, dict[str, Any]]:
    if isinstance(raw, list):
        items = {}
        for entry in raw:
            if not isinstance(entry, dict):
                continue
            provider_id = _normalize_provider_id(entry.get("provider_id"))
            items[provider_id] = dict(entry)
        return items
    if isinstance(raw, dict):
        items = {}
        for provider_id, entry in raw.items():
            normalized_id = _normalize_provider_id(provider_id)
            if isinstance(entry, dict):
                items[normalized_id] = dict(entry)
        return items
    return {}


def _legacy_provider_settings(payload: dict[str, Any] | None) -> dict[str, dict[str, Any]]:
    data = dict(payload or {})
    provider_id = _normalize_provider_id(data.get("provider_id"))
    model = str(data.get("model") or "").strip()
    api_key = str(data.get("anthropic_api_key") or "").strip()
    auth_token = str(data.get("anthropic_auth_token") or "").strip()
    base_url = str(data.get("anthropic_base_url") or "").strip()

    legacy = {pid: _default_provider_settings(pid) for pid in SUPPORTED_PROVIDERS}
    target = legacy[provider_id]
    if api_key:
        target["api_key"] = api_key
    if auth_token:
        target["auth_token"] = auth_token
    if base_url:
        target["base_url"] = base_url
    if model:
        target["enabled_models"] = [model]
    return legacy


def _normalize_provider_entry(provider_id: str, payload: dict[str, Any], previous: dict[str, Any] | None = None) -> dict[str, Any]:
    definition = _provider_definition(provider_id)
    base = _default_provider_settings(provider_id)
    if previous:
        base.update(dict(previous))
    base.update(dict(payload or {}))

    enabled_models = _string_list(base.get("enabled_models") or base.get("models"))
    custom_models = _string_list(base.get("custom_models"))
    supported_models = _string_list(
        list(definition.get("supported_models") or []) + custom_models + enabled_models
    )
    base_url = str(base.get("base_url") or definition.get("default_base_url") or "").strip()
    api_key = str(base.get("api_key") or "").strip()
    auth_token = str(base.get("auth_token") or "").strip()

    status, message = _compute_provider_validation(
        provider_id,
        api_key=api_key,
        auth_token=auth_token,
        base_url=base_url,
        enabled_models=enabled_models,
    )

    validated_at = str(base.get("validated_at") or "").strip()
    if status == "verified":
        validated_at = validated_at or _now_iso()
    else:
        validated_at = ""

    return {
        "provider_id": provider_id,
        "api_key": api_key,
        "auth_token": auth_token,
        "base_url": base_url,
        "enabled_models": enabled_models,
        "custom_models": custom_models,
        "supported_models": supported_models,
        "validation_status": status,
        "validation_message": message,
        "validated_at": validated_at,
        "enabled": bool(enabled_models) and status == "verified",
    }


def _compute_provider_validation(
    provider_id: str,
    *,
    api_key: str,
    auth_token: str,
    base_url: str,
    enabled_models: list[str],
) -> tuple[str, str]:
    token_ready = bool(api_key) if provider_id == "anthropic" else bool(auth_token or api_key)
    if provider_id == "anthropic_compatible" and not str(base_url or "").strip():
        return ("unverified", "请填写兼容网关地址")
    if not token_ready:
        if provider_id == "anthropic":
            return ("unverified", "请填写 API Key")
        return ("unverified", "请填写 Token")
    if not enabled_models:
        return ("unverified", "请至少开启一个模型")
    return ("verified", "已完成本地配置校验")


def _merge_provider_settings(
    current: dict[str, dict[str, Any]] | None,
    patch: dict[str, dict[str, Any]] | None,
    *,
    legacy_payload: dict[str, Any] | None = None,
) -> dict[str, dict[str, Any]]:
    merged: dict[str, dict[str, Any]] = _legacy_provider_settings(legacy_payload)
    for provider_id in SUPPORTED_PROVIDERS:
        if current and provider_id in current:
            merged[provider_id] = _normalize_provider_entry(provider_id, current[provider_id], merged.get(provider_id))

    for provider_id, entry in (patch or {}).items():
        current_entry = dict(merged.get(provider_id) or _default_provider_settings(provider_id))
        update = dict(entry or {})

        if "api_key" in update:
            api_key = str(update.get("api_key") or "").strip()
            if api_key:
                current_entry["api_key"] = api_key
        if "auth_token" in update:
            auth_token = str(update.get("auth_token") or "").strip()
            if auth_token:
                current_entry["auth_token"] = auth_token
        if "base_url" in update:
            current_entry["base_url"] = str(update.get("base_url") or "").strip()
        if "enabled_models" in update:
            current_entry["enabled_models"] = _string_list(update.get("enabled_models"))
        if "custom_models" in update:
            current_entry["custom_models"] = _string_list(update.get("custom_models"))
        if update.get("enabled") is False:
            current_entry["enabled_models"] = []

        merged[provider_id] = _normalize_provider_entry(provider_id, current_entry, merged.get(provider_id))

    return {
        provider_id: _normalize_provider_entry(provider_id, merged.get(provider_id) or {}, None)
        for provider_id in SUPPORTED_PROVIDERS
    }


def _merge_settings_payload(current: dict[str, Any] | None, patch: dict[str, Any] | None) -> dict[str, Any]:
    base = dict(current or {})
    update = dict(patch or {})

    for key, value in update.items():
        if key in {"provider_settings", "providers"} or value is None:
            continue
        if key in {"anthropic_api_key", "anthropic_auth_token", "mysql_password", "doris_password"} and not str(value or "").strip():
            continue
        base[key] = value

    current_provider_settings = _coerce_provider_settings(base.get("provider_settings"))
    patch_provider_settings = _coerce_provider_settings(update.get("provider_settings") or update.get("providers"))
    provider_settings = _merge_provider_settings(
        current_provider_settings,
        patch_provider_settings,
        legacy_payload=base | update,
    )

    provider_id = _normalize_provider_id(base.get("provider_id"))
    provider_profile = provider_settings.get(provider_id) or _default_provider_settings(provider_id)
    preferred_model = str(base.get("model") or "").strip()
    if preferred_model and preferred_model not in provider_profile["supported_models"]:
        provider_profile["custom_models"] = _string_list(provider_profile["custom_models"] + [preferred_model])
        provider_settings[provider_id] = _normalize_provider_entry(provider_id, provider_profile)
        provider_profile = provider_settings[provider_id]

    enabled_models = list(provider_profile.get("enabled_models") or [])
    model = preferred_model or (enabled_models[0] if enabled_models else "")
    if model and model not in provider_profile["supported_models"]:
        model = ""
    if not model:
        model = enabled_models[0] if enabled_models else str(_provider_definition(provider_id).get("default_model") or DEFAULT_MODEL)

    runtime_provider = provider_settings.get(provider_id) or _default_provider_settings(provider_id)
    flattened = {
        "provider_id": provider_id,
        "model": model,
        "anthropic_api_key": str(runtime_provider.get("api_key") or ""),
        "anthropic_auth_token": str(runtime_provider.get("auth_token") or ""),
        "anthropic_base_url": str(runtime_provider.get("base_url") or ""),
        "mysql_host": str(base.get("mysql_host") or ""),
        "mysql_port": int(base.get("mysql_port") or 3306),
        "mysql_user": str(base.get("mysql_user") or ""),
        "mysql_password": str(base.get("mysql_password") or ""),
        "mysql_database": str(base.get("mysql_database") or ""),
        "doris_host": str(base.get("doris_host") or ""),
        "doris_port": int(base.get("doris_port") or 9030),
        "doris_user": str(base.get("doris_user") or ""),
        "doris_password": str(base.get("doris_password") or ""),
        "doris_database": str(base.get("doris_database") or ""),
        "skills_output_dir": str(base.get("skills_output_dir") or ""),
        "session_mysql_database": str(base.get("session_mysql_database") or ""),
        "provider_settings": provider_settings,
    }
    flattened["validated_provider_id"] = provider_id if runtime_provider.get("enabled") else ""
    flattened["validated_model"] = model if model in runtime_provider.get("enabled_models", []) and runtime_provider.get("enabled") else ""
    flattened["provider_validation_status"] = str(runtime_provider.get("validation_status") or "unverified")
    flattened["provider_validation_message"] = str(runtime_provider.get("validation_message") or "")
    flattened["provider_validated_at"] = str(runtime_provider.get("validated_at") or "")
    return flattened


def runtime_patch_from_payload(payload: dict[str, Any]) -> dict[str, Any]:
    patch: dict[str, Any] = {}
    if "provider_id" in payload:
        patch["llm_provider"] = payload.get("provider_id")
    if "model" in payload:
        patch["claude_model"] = payload.get("model")
    passthrough = {
        "anthropic_api_key",
        "anthropic_auth_token",
        "anthropic_base_url",
        "mysql_host",
        "mysql_port",
        "mysql_user",
        "mysql_password",
        "mysql_database",
        "doris_host",
        "doris_port",
        "doris_user",
        "doris_password",
        "doris_database",
        "skills_output_dir",
        "session_mysql_database",
    }
    for key in passthrough:
        if key in payload:
            patch[key] = payload.get(key)
    return patch


def validate_settings_payload(payload: dict[str, Any]):
    provider_id = str(payload.get("provider_id") or "").strip().lower()
    if provider_id and provider_id not in SUPPORTED_PROVIDERS:
        raise ValueError("provider_id must be one of anthropic/openrouter/anyrouter/anthropic_compatible")

    raw_skills_dir = str(payload.get("skills_output_dir") or "").replace("\\", "/")
    if raw_skills_dir and "/.claude/skills/" not in raw_skills_dir and not raw_skills_dir.startswith(".claude/skills/"):
        raise ValueError("skills_output_dir must be under .claude/skills")


def bootstrap_admin_settings() -> dict[str, Any]:
    store = get_skill_admin_store()
    store.init_schema()

    runtime = _runtime_settings_payload()
    db_payload = store.load_settings_record() or {}
    merged = _merge_settings_payload(runtime, db_payload)
    validate_settings_payload(merged)
    update_settings(runtime_patch_from_payload(merged))

    if not db_payload:
        store.save_settings_record(merged)
        persisted = merged
    else:
        persisted = store.load_settings_record() or merged
    return _merge_settings_payload(runtime, persisted)


def persist_admin_settings(payload: dict[str, Any]) -> dict[str, Any]:
    current = current_settings_payload()
    merged = _merge_settings_payload(current, payload)
    validate_settings_payload(merged)

    update_settings(runtime_patch_from_payload(merged))
    store = get_skill_admin_store()
    saved = store.save_settings_record(merged)
    resolved = _merge_settings_payload(_runtime_settings_payload(), saved)
    return resolved | {"updated_at": saved.get("updated_at", "")}


def list_provider_configs(*, payload: dict[str, Any] | None = None, enabled_only: bool = False) -> list[dict[str, Any]]:
    resolved = payload or current_settings_payload()
    provider_settings = _coerce_provider_settings(resolved.get("provider_settings"))
    configs: list[dict[str, Any]] = []

    for provider_id in SUPPORTED_PROVIDERS:
        definition = _provider_definition(provider_id)
        item = _normalize_provider_entry(provider_id, provider_settings.get(provider_id) or {})
        if enabled_only and not item.get("enabled"):
            continue
        configs.append(
            {
                "provider_id": provider_id,
                "display_name": str(definition.get("display_name") or provider_id),
                "provider_group": str(definition.get("provider_group") or ""),
                "base_url": str(item.get("base_url") or ""),
                "api_key_set": bool(item.get("api_key")),
                "auth_token_set": bool(item.get("auth_token")),
                "models": list(item.get("enabled_models") or []),
                "supported_models": list(item.get("supported_models") or []),
                "custom_models": list(item.get("custom_models") or []),
                "default_model": (
                    (item.get("enabled_models") or [None])[0]
                    or str(definition.get("default_model") or "")
                ),
                "enabled": bool(item.get("enabled")),
                "validation_status": str(item.get("validation_status") or "unverified"),
                "validation_message": str(item.get("validation_message") or ""),
            }
        )

    configs.sort(key=lambda item: (item["provider_group"], item["display_name"]))
    return configs


def resolved_chat_settings_payload() -> dict[str, Any]:
    resolved = current_settings_payload()
    providers = list_provider_configs(payload=resolved, enabled_only=True)
    default_provider_id = _normalize_provider_id(resolved.get("provider_id"))
    if not any(item["provider_id"] == default_provider_id for item in providers):
        default_provider_id = providers[0]["provider_id"] if providers else ""

    default_model = ""
    for provider in providers:
        if provider["provider_id"] == default_provider_id:
            models = list(provider.get("models") or [])
            preferred = str(resolved.get("model") or "").strip()
            default_model = preferred if preferred in models else (models[0] if models else "")
            break

    return {
        "default_provider_id": default_provider_id,
        "default_model": default_model,
        "providers": providers,
        "skills_output_dir": str(resolved.get("skills_output_dir") or ""),
        "mysql_host": str(resolved.get("mysql_host") or ""),
        "mysql_port": int(resolved.get("mysql_port") or 3306),
        "mysql_database": str(resolved.get("mysql_database") or ""),
        "doris_host": str(resolved.get("doris_host") or ""),
        "doris_port": int(resolved.get("doris_port") or 9030),
        "doris_database": str(resolved.get("doris_database") or ""),
    }


def resolve_runtime_provider_selection(provider_id: str | None, model: str | None) -> dict[str, Any]:
    resolved = current_settings_payload()
    normalized_provider_id = _normalize_provider_id(provider_id or resolved.get("provider_id"))
    provider_settings = _coerce_provider_settings(resolved.get("provider_settings"))
    provider = _normalize_provider_entry(normalized_provider_id, provider_settings.get(normalized_provider_id) or {})

    if not provider.get("enabled"):
        raise ValueError("所选供应商未通过校验，或尚未开启任何模型")

    enabled_models = list(provider.get("enabled_models") or [])
    selected_model = str(model or "").strip()
    if not selected_model:
        selected_model = enabled_models[0] if enabled_models else ""
    if selected_model not in enabled_models:
        raise ValueError("所选模型未加入已验证候选")

    return {
        "provider_id": normalized_provider_id,
        "model": selected_model,
        "api_key": str(provider.get("api_key") or ""),
        "auth_token": str(provider.get("auth_token") or ""),
        "base_url": str(provider.get("base_url") or ""),
    }


def managed_skill_files() -> list[str]:
    ensure_static_skills_bundle()
    root = resolve_skills_root_dir()
    files: list[str] = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if path.suffix.lower() not in MANAGED_FILE_SUFFIXES:
            continue
        files.append(path.relative_to(root).as_posix())
    files.sort()
    return files


def sync_documents_from_disk(*, change_source: str = "import", change_summary: str = "发现磁盘文件") -> list[dict[str, Any]]:
    store = get_skill_admin_store()
    root = resolve_skills_root_dir()
    managed_paths = managed_skill_files()
    managed_path_set = set(managed_paths)
    for document in store.list_documents():
        relative_path = str(document.get("relative_path") or "")
        if relative_path and relative_path not in managed_path_set:
            store.delete_document_by_path(relative_path)

    changed: list[dict[str, Any]] = []
    for relative_path in managed_paths:
        file_path = root / relative_path
        content = file_path.read_text(encoding="utf-8")
        existing = store.get_document_by_path(relative_path)
        current_hash = existing.get("current_hash") if existing else None
        next_hash = hashlib.sha256(content.encode("utf-8")).hexdigest()
        if existing and current_hash == next_hash:
            continue
        saved = store.save_document(
            relative_path=relative_path,
            content=content,
            change_source=change_source,
            change_summary=change_summary,
            actor="system",
        )
        changed.append(saved)
    return changed


def list_documents() -> list[dict[str, Any]]:
    sync_documents_from_disk()
    return get_skill_admin_store().list_documents()


def get_document_detail(document_id: int) -> dict[str, Any] | None:
    sync_documents_from_disk()
    store = get_skill_admin_store()
    document = store.get_document(document_id)
    if not document:
        return None
    document["versions"] = store.list_versions(document_id)
    return document


def validate_document_content(relative_path: str, content: str):
    suffix = Path(relative_path).suffix.lower()
    if suffix == ".json":
        try:
            payload = json.loads(content)
        except Exception as exc:
            raise ValueError(f"JSON 文件格式错误: {exc}") from exc
        if not isinstance(payload, dict):
            raise ValueError("JSON 文件根节点必须是对象")


def write_skill_file(relative_path: str, content: str):
    root = resolve_skills_root_dir()
    path = (root / relative_path).resolve()
    if root not in path.parents and path != root:
        raise ValueError("invalid skill file path")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def refresh_skill_runtime():
    try:
        validate_skills_bundle(force_reload=True)
        get_semantic_layer().reload()
    except Exception as exc:
        logger.warning("Skill runtime refresh failed: %s", exc)


def save_document_content(document_id: int, content: str, change_summary: str | None = None) -> dict[str, Any]:
    store = get_skill_admin_store()
    document = store.get_document(document_id)
    if not document:
        raise ValueError("document not found")
    validate_document_content(document["relative_path"], content)
    write_skill_file(document["relative_path"], content)
    saved = store.save_document(
        relative_path=document["relative_path"],
        content=content,
        change_source="edit",
        change_summary=change_summary or "前端保存",
        actor="ui",
    )
    refresh_skill_runtime()
    detail = store.get_document(int(saved["id"])) or saved
    detail["versions"] = store.list_versions(int(saved["id"]))
    return detail


def rollback_document(document_id: int, version_id: int) -> dict[str, Any]:
    store = get_skill_admin_store()
    document = store.get_document(document_id)
    if not document:
        raise ValueError("document not found")
    version = store.get_version(document_id, version_id)
    if not version:
        raise ValueError("version not found")
    write_skill_file(document["relative_path"], version["content"])
    saved = store.save_document(
        relative_path=document["relative_path"],
        content=version["content"],
        change_source="rollback",
        change_summary=f"回滚到 V{version['version_no']}",
        actor="ui",
        parent_version_id=version_id,
    )
    refresh_skill_runtime()
    detail = store.get_document(int(saved["id"])) or saved
    detail["versions"] = store.list_versions(int(saved["id"]))
    return detail


def _resolve_compare_side(document: dict[str, Any], *, version_id: int | None, side: str) -> tuple[str, str]:
    store = get_skill_admin_store()
    if version_id is None:
        return ("当前版本", document["current_content"])
    version = store.get_version(int(document["id"]), version_id)
    if not version:
        raise ValueError(f"{side} version not found")
    return (f"V{version['version_no']}", version["content"])


def compare_document_versions(
    document_id: int,
    *,
    left_version_id: int | None = None,
    right_version_id: int | None = None,
) -> dict[str, Any]:
    store = get_skill_admin_store()
    document = store.get_document(document_id)
    if not document:
        raise ValueError("document not found")
    left_label, left_content = _resolve_compare_side(document, version_id=left_version_id, side="left")
    right_label, right_content = _resolve_compare_side(document, version_id=right_version_id, side="right")

    diff_lines = list(
        difflib.unified_diff(
            left_content.splitlines(),
            right_content.splitlines(),
            fromfile=left_label,
            tofile=right_label,
            lineterm="",
        )
    )
    added_lines = sum(1 for line in diff_lines if line.startswith("+") and not line.startswith("+++"))
    removed_lines = sum(1 for line in diff_lines if line.startswith("-") and not line.startswith("---"))

    return {
        "document_id": document_id,
        "left_label": left_label,
        "right_label": right_label,
        "left_content": left_content,
        "right_content": right_content,
        "diff_text": "\n".join(diff_lines),
        "added_lines": added_lines,
        "removed_lines": removed_lines,
        "changed_lines": added_lines + removed_lines,
    }


def sync_from_opendataworks() -> dict[str, Any]:
    ensure_static_skills_bundle()
    store = get_skill_admin_store()
    cfg = get_settings()
    metadata_schema = cfg.mysql_database or "opendataworks"
    knowledge_schema = cfg.session_mysql_database or "dataagent"
    imported = sync_documents_from_disk(change_source="refresh", change_summary="刷新技能文件索引")
    refresh_skill_runtime()
    runtime_stats = validate_skills_bundle(force_reload=True)

    return {
        "skills_root_dir": str(resolve_skills_root_dir()),
        "metadata_schema": metadata_schema,
        "knowledge_schema": knowledge_schema,
        "stats": {
            "metadata_tables": int(runtime_stats.get("metadata_tables") or 0),
            "business_rules": int(runtime_stats.get("business_rules") or 0),
            "semantic_mappings": int(runtime_stats.get("semantic_mappings") or 0),
            "few_shots": int(runtime_stats.get("few_shots") or 0),
            "lineage_edges": int(runtime_stats.get("lineage_edges") or 0),
        },
        "changed_documents": [],
        "imported_documents": imported,
        "document_count": len(store.list_documents()),
    }
