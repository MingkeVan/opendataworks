from __future__ import annotations

import sys
import types
from pathlib import Path

from fastapi.testclient import TestClient

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

if "pymysql" not in sys.modules:
    sys.modules["pymysql"] = types.SimpleNamespace(
        connect=lambda *args, **kwargs: None,
        cursors=types.SimpleNamespace(DictCursor=object),
        connections=types.SimpleNamespace(Connection=object),
    )

import api.admin_routes as admin_routes
from main import app


def test_admin_settings_contract(monkeypatch):
    monkeypatch.setattr(
        admin_routes,
        "current_settings_payload",
        lambda: {
            "provider_id": "openrouter",
            "model": "anthropic/claude-sonnet-4.5",
            "anthropic_api_key": "k",
            "anthropic_auth_token": "t",
            "anthropic_base_url": "https://example.com",
            "mysql_host": "127.0.0.1",
            "mysql_port": 3306,
            "mysql_user": "root",
            "mysql_password": "pwd",
            "mysql_database": "opendataworks",
            "doris_host": "127.0.0.1",
            "doris_port": 9030,
            "doris_user": "root",
            "doris_password": "pwd",
            "doris_database": "ods",
            "skills_output_dir": "../.claude/skills/dataagent-nl2sql",
            "session_mysql_database": "dataagent",
        },
    )
    monkeypatch.setattr(admin_routes, "resolve_claude_settings_path", lambda: "/tmp/settings.json")
    monkeypatch.setattr(admin_routes, "resolve_skills_root_dir", lambda: "/tmp/.claude/skills/dataagent-nl2sql")
    monkeypatch.setattr(
        admin_routes,
        "_provider_catalog",
        lambda: [
            admin_routes.ProviderConfig(
                provider_id="openrouter",
                display_name="OpenRouter",
                models=["anthropic/claude-sonnet-4.5"],
                default_model="anthropic/claude-sonnet-4.5",
            )
        ],
    )
    monkeypatch.setattr(
        admin_routes,
        "persist_admin_settings",
        lambda payload: {"updated_at": "2026-03-06T12:00:00"},
    )

    client = TestClient(app)

    response = client.get("/api/v1/dataagent/settings")
    assert response.status_code == 200
    assert response.json()["provider_id"] == "openrouter"

    update = client.put(
        "/api/v1/dataagent/settings",
        json={"provider_id": "openrouter", "model": "anthropic/claude-sonnet-4.5"},
    )
    assert update.status_code == 200
    assert update.json()["updated_at"] == "2026-03-06T12:00:00"


def test_skill_document_routes_contract(monkeypatch):
    summary = {
        "id": 1,
        "relative_path": "metadata/metadata_catalog.json",
        "file_name": "metadata_catalog.json",
        "category": "metadata",
        "content_type": "json",
        "current_hash": "hash",
        "current_version_id": 3,
        "version_count": 3,
        "last_change_source": "sync",
        "last_change_summary": "manual sync",
        "created_at": "2026-03-06T10:00:00",
        "updated_at": "2026-03-06T12:00:00",
    }
    detail = {
        **summary,
        "current_content": "{\"schema_version\":\"1.0\"}",
        "versions": [
            {
                "id": 3,
                "document_id": 1,
                "version_no": 3,
                "change_source": "sync",
                "change_summary": "manual sync",
                "actor": "ui",
                "content_hash": "hash",
                "file_size": 20,
                "metadata": None,
                "parent_version_id": None,
                "created_at": "2026-03-06T12:00:00",
                "is_current": True,
            }
        ],
    }

    monkeypatch.setattr(admin_routes, "list_documents", lambda: [summary])
    monkeypatch.setattr(admin_routes, "get_document_detail", lambda document_id: detail if document_id == 1 else None)
    monkeypatch.setattr(admin_routes, "save_document_content", lambda document_id, content, change_summary=None: detail)
    monkeypatch.setattr(
        admin_routes,
        "compare_document_versions",
        lambda document_id, left_version_id=None, right_version_id=None: {
            "document_id": document_id,
            "left_label": "V2",
            "right_label": "当前版本",
            "left_content": "{}",
            "right_content": "{\"schema_version\":\"1.0\"}",
            "diff_text": "--- V2\n+++ 当前版本",
            "added_lines": 1,
            "removed_lines": 1,
            "changed_lines": 2,
        },
    )
    monkeypatch.setattr(admin_routes, "rollback_document", lambda document_id, version_id: detail)
    monkeypatch.setattr(
        admin_routes,
        "sync_from_opendataworks",
        lambda: {
            "skills_root_dir": "/tmp/.claude/skills/dataagent-nl2sql",
            "metadata_schema": "opendataworks",
            "knowledge_schema": "dataagent",
            "stats": {
                "metadata_tables": 1,
                "lineage_edges": 0,
                "semantic_mappings": 0,
                "few_shots": 0,
                "business_rules": 0,
            },
            "changed_documents": [summary],
            "imported_documents": [],
            "document_count": 1,
        },
    )

    client = TestClient(app)

    list_response = client.get("/api/v1/dataagent/skills/documents")
    assert list_response.status_code == 200
    assert list_response.json()[0]["relative_path"] == "metadata/metadata_catalog.json"

    detail_response = client.get("/api/v1/dataagent/skills/documents/1")
    assert detail_response.status_code == 200
    assert detail_response.json()["versions"][0]["version_no"] == 3

    save_response = client.put(
        "/api/v1/dataagent/skills/documents/1",
        json={"content": "{\"schema_version\":\"1.0\"}", "change_summary": "save"},
    )
    assert save_response.status_code == 200
    assert save_response.json()["id"] == 1

    compare_response = client.post(
        "/api/v1/dataagent/skills/documents/1/compare",
        json={"left_version_id": 2},
    )
    assert compare_response.status_code == 200
    assert compare_response.json()["changed_lines"] == 2

    rollback_response = client.post("/api/v1/dataagent/skills/documents/1/versions/2/rollback")
    assert rollback_response.status_code == 200
    assert rollback_response.json()["id"] == 1

    sync_response = client.post("/api/v1/dataagent/skills/sync")
    assert sync_response.status_code == 200
    assert sync_response.json()["document_count"] == 1
