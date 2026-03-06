from __future__ import annotations

import difflib
import hashlib
import json
import logging
from pathlib import Path
from typing import Any

import pymysql

from config import get_settings, update_settings
from core.semantic_layer import get_semantic_layer
from core.skill_admin_store import get_skill_admin_store
from core.skills_exporter import build_bundle_payloads, dedup
from core.skills_loader import resolve_skills_root_dir, validate_skills_bundle
from core.skills_sync import ensure_static_skills_bundle

logger = logging.getLogger(__name__)

SUPPORTED_PROVIDERS = {"anthropic", "openrouter", "anyrouter", "anthropic_compatible"}
MANAGED_FILE_SUFFIXES = {".json", ".md", ".markdown"}


def resolve_dataagent_root_dir() -> Path:
    return Path(__file__).resolve().parents[2]


def resolve_claude_settings_path() -> Path:
    return resolve_dataagent_root_dir() / ".claude" / "settings.json"


def current_settings_payload() -> dict[str, Any]:
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


def load_json_settings_file() -> dict[str, Any]:
    path = resolve_claude_settings_path()
    if not path.exists():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        logger.warning("Failed to parse settings json %s: %s", path, exc)
        return {}
    return payload if isinstance(payload, dict) else {}


def write_json_settings_file(payload: dict[str, Any]):
    path = resolve_claude_settings_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


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

    runtime = current_settings_payload()
    file_payload = load_json_settings_file()
    db_payload = store.load_settings_record() or {}

    merged = dict(runtime)
    merged.update(file_payload)
    merged.update(db_payload)
    validate_settings_payload(merged)
    update_settings(runtime_patch_from_payload(merged))

    if not db_payload:
        store.save_settings_record(merged)

    persisted = store.load_settings_record() or merged
    write_json_settings_file(
        {
            **persisted,
            "session_mysql_database": current_settings_payload().get("session_mysql_database"),
        }
    )
    return current_settings_payload()


def persist_admin_settings(payload: dict[str, Any]) -> dict[str, Any]:
    current = current_settings_payload()
    merged = dict(current)
    merged.update({key: value for key, value in payload.items() if value is not None})
    validate_settings_payload(merged)

    update_settings(runtime_patch_from_payload(merged))
    store = get_skill_admin_store()
    saved = store.save_settings_record(merged)
    write_json_settings_file(
        {
            **saved,
            "session_mysql_database": current_settings_payload().get("session_mysql_database"),
        }
    )
    return current_settings_payload() | {"updated_at": saved.get("updated_at", "")}


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
    changed: list[dict[str, Any]] = []
    for relative_path in managed_skill_files():
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
    include_mysql_schemas = dedup([metadata_schema, knowledge_schema])

    existing_manifest: dict[str, Any] = {}
    manifest_path = resolve_skills_root_dir() / "manifest.json"
    if manifest_path.exists():
        try:
            raw = json.loads(manifest_path.read_text(encoding="utf-8"))
            if isinstance(raw, dict):
                existing_manifest = raw
        except Exception:
            existing_manifest = {}

    conn = pymysql.connect(
        host=cfg.mysql_host,
        port=cfg.mysql_port,
        user=cfg.mysql_user,
        password=cfg.mysql_password,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
        connect_timeout=10,
        read_timeout=60,
        write_timeout=30,
    )
    try:
        bundle = build_bundle_payloads(
            conn,
            metadata_schema=metadata_schema,
            knowledge_schema=knowledge_schema,
            include_mysql_schemas=include_mysql_schemas,
            default_engine="doris",
            existing_manifest=existing_manifest,
        )
    finally:
        conn.close()

    changed_documents: list[dict[str, Any]] = []
    for relative_path, payload in (bundle.get("files") or {}).items():
        content = json.dumps(payload, ensure_ascii=False, indent=2)
        write_skill_file(relative_path, content)
        existing = store.get_document_by_path(relative_path)
        current_hash = existing.get("current_hash") if existing else None
        next_hash = hashlib.sha256(content.encode("utf-8")).hexdigest()
        if existing and current_hash == next_hash:
            continue
        changed_documents.append(
            store.save_document(
                relative_path=relative_path,
                content=content,
                change_source="sync",
                change_summary=f"从 {metadata_schema}/{knowledge_schema} 手动同步",
                actor="ui",
                metadata={"source": bundle.get("source", {})},
            )
        )

    imported = sync_documents_from_disk(change_source="import", change_summary="补齐技能文件")
    refresh_skill_runtime()

    return {
        "skills_root_dir": str(resolve_skills_root_dir()),
        "metadata_schema": metadata_schema,
        "knowledge_schema": knowledge_schema,
        "stats": bundle.get("stats", {}),
        "changed_documents": changed_documents,
        "imported_documents": imported,
        "document_count": len(store.list_documents()),
    }
