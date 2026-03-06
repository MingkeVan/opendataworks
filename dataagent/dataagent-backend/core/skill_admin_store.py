from __future__ import annotations

import hashlib
import json
import logging
import threading
from datetime import datetime
from pathlib import Path
from typing import Any

import pymysql

from config import get_settings

logger = logging.getLogger(__name__)


def _to_iso(value: Any) -> str:
    if isinstance(value, datetime):
        return value.isoformat(timespec="seconds")
    return str(value) if value is not None else ""


def _json_default(value: Any) -> str:
    if isinstance(value, datetime):
        return value.isoformat(timespec="seconds")
    return str(value)


def _sha256(content: str) -> str:
    return hashlib.sha256((content or "").encode("utf-8")).hexdigest()


def _content_type_for_path(relative_path: str) -> str:
    suffix = Path(relative_path).suffix.lower()
    if suffix == ".json":
        return "json"
    if suffix in {".md", ".markdown"}:
        return "markdown"
    return "text"


def _category_for_path(relative_path: str) -> str:
    normalized = str(relative_path or "").replace("\\", "/").strip("/")
    if not normalized or "/" not in normalized:
        return "root"
    return normalized.split("/", 1)[0]


class SkillAdminStore:
    def __init__(self):
        self._ready = False
        self._ready_lock = threading.Lock()

    def _connect(self, database: str | None):
        cfg = get_settings()
        return pymysql.connect(
            host=cfg.mysql_host,
            port=cfg.mysql_port,
            user=cfg.mysql_user,
            password=cfg.mysql_password,
            database=database,
            charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,
            autocommit=False,
        )

    def _schema_name(self) -> str:
        cfg = get_settings()
        return cfg.session_mysql_database

    def init_schema(self):
        if self._ready:
            return
        with self._ready_lock:
            if self._ready:
                return

            schema = self._schema_name()

            conn = self._connect(database=None)
            try:
                with conn.cursor() as cur:
                    cur.execute(
                        f"CREATE DATABASE IF NOT EXISTS `{schema}` "
                        "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
                    )
                conn.commit()
            finally:
                conn.close()

            conn = self._connect(database=schema)
            try:
                with conn.cursor() as cur:
                    cur.execute(
                        """
                        CREATE TABLE IF NOT EXISTS da_agent_settings (
                            settings_key VARCHAR(32) NOT NULL PRIMARY KEY,
                            provider_id VARCHAR(64) NOT NULL DEFAULT 'openrouter',
                            model_name VARCHAR(255) NOT NULL DEFAULT 'anthropic/claude-sonnet-4.5',
                            anthropic_api_key VARCHAR(512) NULL,
                            anthropic_auth_token VARCHAR(512) NULL,
                            anthropic_base_url VARCHAR(512) NULL,
                            mysql_host VARCHAR(255) NULL,
                            mysql_port INT NULL,
                            mysql_user VARCHAR(255) NULL,
                            mysql_password VARCHAR(255) NULL,
                            mysql_database VARCHAR(255) NULL,
                            doris_host VARCHAR(255) NULL,
                            doris_port INT NULL,
                            doris_user VARCHAR(255) NULL,
                            doris_password VARCHAR(255) NULL,
                            doris_database VARCHAR(255) NULL,
                            skills_output_dir VARCHAR(512) NULL,
                            raw_json LONGTEXT NULL,
                            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """
                    )
                    cur.execute(
                        """
                        CREATE TABLE IF NOT EXISTS da_skill_document (
                            id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                            relative_path VARCHAR(255) NOT NULL,
                            file_name VARCHAR(128) NOT NULL,
                            category VARCHAR(64) NOT NULL,
                            content_type VARCHAR(32) NOT NULL,
                            current_content LONGTEXT NOT NULL,
                            current_hash CHAR(64) NOT NULL,
                            current_version_id BIGINT NULL,
                            version_count INT NOT NULL DEFAULT 0,
                            last_change_source VARCHAR(32) NOT NULL DEFAULT 'import',
                            last_change_summary VARCHAR(255) NULL,
                            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                            UNIQUE KEY uk_skill_document_path (relative_path),
                            KEY idx_skill_document_category (category),
                            KEY idx_skill_document_updated (updated_at)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """
                    )
                    cur.execute(
                        """
                        CREATE TABLE IF NOT EXISTS da_skill_document_version (
                            id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                            document_id BIGINT NOT NULL,
                            version_no INT NOT NULL,
                            change_source VARCHAR(32) NOT NULL,
                            change_summary VARCHAR(255) NULL,
                            actor VARCHAR(64) NULL,
                            content LONGTEXT NOT NULL,
                            content_hash CHAR(64) NOT NULL,
                            file_size INT NOT NULL DEFAULT 0,
                            metadata_json LONGTEXT NULL,
                            parent_version_id BIGINT NULL,
                            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            UNIQUE KEY uk_skill_doc_version (document_id, version_no),
                            KEY idx_skill_doc_version_created (document_id, created_at),
                            CONSTRAINT fk_da_skill_document_version_document
                                FOREIGN KEY (document_id) REFERENCES da_skill_document(id)
                                ON DELETE CASCADE
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """
                    )
                conn.commit()
            finally:
                conn.close()

            self._ready = True
            logger.info("Skill admin schema is ready: %s", schema)

    def _ensure_ready(self):
        if not self._ready:
            self.init_schema()

    def load_settings_record(self) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT settings_key, provider_id, model_name, anthropic_api_key, anthropic_auth_token,
                           anthropic_base_url, mysql_host, mysql_port, mysql_user, mysql_password,
                           mysql_database, doris_host, doris_port, doris_user, doris_password,
                           doris_database, skills_output_dir, updated_at
                    FROM da_agent_settings
                    WHERE settings_key = 'default'
                    LIMIT 1
                    """
                )
                row = cur.fetchone()
        finally:
            conn.close()
        return self._normalize_settings_row(row) if row else None

    def save_settings_record(self, payload: dict[str, Any]) -> dict[str, Any]:
        self._ensure_ready()
        normalized = self._normalize_settings_payload(payload)
        raw_json = json.dumps(normalized, ensure_ascii=False, default=_json_default)

        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO da_agent_settings (
                        settings_key, provider_id, model_name, anthropic_api_key, anthropic_auth_token,
                        anthropic_base_url, mysql_host, mysql_port, mysql_user, mysql_password,
                        mysql_database, doris_host, doris_port, doris_user, doris_password,
                        doris_database, skills_output_dir, raw_json
                    ) VALUES (
                        'default', %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
                    )
                    ON DUPLICATE KEY UPDATE
                        provider_id = VALUES(provider_id),
                        model_name = VALUES(model_name),
                        anthropic_api_key = VALUES(anthropic_api_key),
                        anthropic_auth_token = VALUES(anthropic_auth_token),
                        anthropic_base_url = VALUES(anthropic_base_url),
                        mysql_host = VALUES(mysql_host),
                        mysql_port = VALUES(mysql_port),
                        mysql_user = VALUES(mysql_user),
                        mysql_password = VALUES(mysql_password),
                        mysql_database = VALUES(mysql_database),
                        doris_host = VALUES(doris_host),
                        doris_port = VALUES(doris_port),
                        doris_user = VALUES(doris_user),
                        doris_password = VALUES(doris_password),
                        doris_database = VALUES(doris_database),
                        skills_output_dir = VALUES(skills_output_dir),
                        raw_json = VALUES(raw_json),
                        updated_at = CURRENT_TIMESTAMP
                    """,
                    (
                        normalized["provider_id"],
                        normalized["model"],
                        normalized["anthropic_api_key"],
                        normalized["anthropic_auth_token"],
                        normalized["anthropic_base_url"],
                        normalized["mysql_host"],
                        normalized["mysql_port"],
                        normalized["mysql_user"],
                        normalized["mysql_password"],
                        normalized["mysql_database"],
                        normalized["doris_host"],
                        normalized["doris_port"],
                        normalized["doris_user"],
                        normalized["doris_password"],
                        normalized["doris_database"],
                        normalized["skills_output_dir"],
                        raw_json,
                    ),
                )
            conn.commit()
        finally:
            conn.close()
        return self.load_settings_record() or normalized

    def list_documents(self) -> list[dict[str, Any]]:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT id, relative_path, file_name, category, content_type, current_hash,
                           current_version_id, version_count, last_change_source, last_change_summary,
                           created_at, updated_at
                    FROM da_skill_document
                    ORDER BY FIELD(category, 'root', 'methodology', 'ontology', 'knowledge', 'metadata', 'governance'),
                             relative_path
                    """
                )
                rows = cur.fetchall() or []
        finally:
            conn.close()
        return [self._normalize_document_row(row) for row in rows]

    def get_document(self, document_id: int) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT id, relative_path, file_name, category, content_type, current_content, current_hash,
                           current_version_id, version_count, last_change_source, last_change_summary,
                           created_at, updated_at
                    FROM da_skill_document
                    WHERE id = %s
                    LIMIT 1
                    """,
                    (document_id,),
                )
                row = cur.fetchone()
        finally:
            conn.close()
        return self._normalize_document_row(row, include_content=True) if row else None

    def get_document_by_path(self, relative_path: str) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT id, relative_path, file_name, category, content_type, current_content, current_hash,
                           current_version_id, version_count, last_change_source, last_change_summary,
                           created_at, updated_at
                    FROM da_skill_document
                    WHERE relative_path = %s
                    LIMIT 1
                    """,
                    (relative_path,),
                )
                row = cur.fetchone()
        finally:
            conn.close()
        return self._normalize_document_row(row, include_content=True) if row else None

    def list_versions(self, document_id: int) -> list[dict[str, Any]]:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT v.id, v.document_id, v.version_no, v.change_source, v.change_summary, v.actor,
                           v.content_hash, v.file_size, v.metadata_json, v.parent_version_id, v.created_at,
                           d.current_version_id
                    FROM da_skill_document_version v
                    INNER JOIN da_skill_document d ON d.id = v.document_id
                    WHERE v.document_id = %s
                    ORDER BY v.version_no DESC, v.id DESC
                    """,
                    (document_id,),
                )
                rows = cur.fetchall() or []
        finally:
            conn.close()
        return [self._normalize_version_row(row) for row in rows]

    def get_version(self, document_id: int, version_id: int) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT v.id, v.document_id, v.version_no, v.change_source, v.change_summary, v.actor,
                           v.content, v.content_hash, v.file_size, v.metadata_json, v.parent_version_id,
                           v.created_at, d.current_version_id
                    FROM da_skill_document_version v
                    INNER JOIN da_skill_document d ON d.id = v.document_id
                    WHERE v.document_id = %s AND v.id = %s
                    LIMIT 1
                    """,
                    (document_id, version_id),
                )
                row = cur.fetchone()
        finally:
            conn.close()
        return self._normalize_version_row(row, include_content=True) if row else None

    def save_document(
        self,
        *,
        relative_path: str,
        content: str,
        change_source: str,
        change_summary: str | None = None,
        actor: str | None = None,
        metadata: dict[str, Any] | None = None,
        parent_version_id: int | None = None,
    ) -> dict[str, Any]:
        self._ensure_ready()
        normalized_path = str(relative_path or "").replace("\\", "/").strip("/")
        if not normalized_path:
            raise ValueError("relative_path is required")

        body = content or ""
        content_hash = _sha256(body)
        metadata_json = json.dumps(metadata, ensure_ascii=False, default=_json_default) if metadata else None

        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT id, current_hash, current_version_id, version_count
                    FROM da_skill_document
                    WHERE relative_path = %s
                    FOR UPDATE
                    """,
                    (normalized_path,),
                )
                existing = cur.fetchone()

                if existing and str(existing.get("current_hash") or "") == content_hash:
                    cur.execute(
                        """
                        UPDATE da_skill_document
                        SET last_change_source = %s,
                            last_change_summary = %s,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = %s
                        """,
                        (change_source, change_summary, existing["id"]),
                    )
                    conn.commit()
                    return self.get_document(int(existing["id"])) or {}

                if existing:
                    document_id = int(existing["id"])
                    version_no = int(existing.get("version_count") or 0) + 1
                else:
                    cur.execute(
                        """
                        INSERT INTO da_skill_document (
                            relative_path, file_name, category, content_type, current_content, current_hash,
                            current_version_id, version_count, last_change_source, last_change_summary
                        ) VALUES (%s, %s, %s, %s, '', '', NULL, 0, %s, %s)
                        """,
                        (
                            normalized_path,
                            Path(normalized_path).name,
                            _category_for_path(normalized_path),
                            _content_type_for_path(normalized_path),
                            change_source,
                            change_summary,
                        ),
                    )
                    document_id = int(cur.lastrowid)
                    version_no = 1

                cur.execute(
                    """
                    INSERT INTO da_skill_document_version (
                        document_id, version_no, change_source, change_summary, actor, content,
                        content_hash, file_size, metadata_json, parent_version_id
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    """,
                    (
                        document_id,
                        version_no,
                        change_source,
                        change_summary,
                        actor,
                        body,
                        content_hash,
                        len(body.encode("utf-8")),
                        metadata_json,
                        parent_version_id,
                    ),
                )
                version_id = int(cur.lastrowid)
                cur.execute(
                    """
                    UPDATE da_skill_document
                    SET file_name = %s,
                        category = %s,
                        content_type = %s,
                        current_content = %s,
                        current_hash = %s,
                        current_version_id = %s,
                        version_count = %s,
                        last_change_source = %s,
                        last_change_summary = %s,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = %s
                    """,
                    (
                        Path(normalized_path).name,
                        _category_for_path(normalized_path),
                        _content_type_for_path(normalized_path),
                        body,
                        content_hash,
                        version_id,
                        version_no,
                        change_source,
                        change_summary,
                        document_id,
                    ),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_document(document_id) or {}

    def _normalize_settings_payload(self, payload: dict[str, Any]) -> dict[str, Any]:
        data = dict(payload or {})
        return {
            "provider_id": str(data.get("provider_id") or "openrouter").strip() or "openrouter",
            "model": str(data.get("model") or "anthropic/claude-sonnet-4.5").strip() or "anthropic/claude-sonnet-4.5",
            "anthropic_api_key": str(data.get("anthropic_api_key") or ""),
            "anthropic_auth_token": str(data.get("anthropic_auth_token") or ""),
            "anthropic_base_url": str(data.get("anthropic_base_url") or ""),
            "mysql_host": str(data.get("mysql_host") or ""),
            "mysql_port": int(data.get("mysql_port") or 3306),
            "mysql_user": str(data.get("mysql_user") or ""),
            "mysql_password": str(data.get("mysql_password") or ""),
            "mysql_database": str(data.get("mysql_database") or ""),
            "doris_host": str(data.get("doris_host") or ""),
            "doris_port": int(data.get("doris_port") or 9030),
            "doris_user": str(data.get("doris_user") or ""),
            "doris_password": str(data.get("doris_password") or ""),
            "doris_database": str(data.get("doris_database") or ""),
            "skills_output_dir": str(data.get("skills_output_dir") or ""),
        }

    def _normalize_settings_row(self, row: dict[str, Any] | None) -> dict[str, Any]:
        if not row:
            return {}
        return {
            "provider_id": str(row.get("provider_id") or "openrouter"),
            "model": str(row.get("model_name") or "anthropic/claude-sonnet-4.5"),
            "anthropic_api_key": str(row.get("anthropic_api_key") or ""),
            "anthropic_auth_token": str(row.get("anthropic_auth_token") or ""),
            "anthropic_base_url": str(row.get("anthropic_base_url") or ""),
            "mysql_host": str(row.get("mysql_host") or ""),
            "mysql_port": int(row.get("mysql_port") or 3306),
            "mysql_user": str(row.get("mysql_user") or ""),
            "mysql_password": str(row.get("mysql_password") or ""),
            "mysql_database": str(row.get("mysql_database") or ""),
            "doris_host": str(row.get("doris_host") or ""),
            "doris_port": int(row.get("doris_port") or 9030),
            "doris_user": str(row.get("doris_user") or ""),
            "doris_password": str(row.get("doris_password") or ""),
            "doris_database": str(row.get("doris_database") or ""),
            "skills_output_dir": str(row.get("skills_output_dir") or ""),
            "updated_at": _to_iso(row.get("updated_at")),
        }

    def _normalize_document_row(self, row: dict[str, Any] | None, *, include_content: bool = False) -> dict[str, Any]:
        if not row:
            return {}
        item = {
            "id": int(row.get("id") or 0),
            "relative_path": str(row.get("relative_path") or ""),
            "file_name": str(row.get("file_name") or ""),
            "category": str(row.get("category") or "root"),
            "content_type": str(row.get("content_type") or "text"),
            "current_hash": str(row.get("current_hash") or ""),
            "current_version_id": int(row.get("current_version_id") or 0) or None,
            "version_count": int(row.get("version_count") or 0),
            "last_change_source": str(row.get("last_change_source") or ""),
            "last_change_summary": str(row.get("last_change_summary") or ""),
            "created_at": _to_iso(row.get("created_at")),
            "updated_at": _to_iso(row.get("updated_at")),
        }
        if include_content:
            item["current_content"] = str(row.get("current_content") or "")
        return item

    def _normalize_version_row(self, row: dict[str, Any] | None, *, include_content: bool = False) -> dict[str, Any]:
        if not row:
            return {}
        metadata = None
        if row.get("metadata_json"):
            try:
                metadata = json.loads(str(row.get("metadata_json") or ""))
            except Exception:
                metadata = None
        item = {
            "id": int(row.get("id") or 0),
            "document_id": int(row.get("document_id") or 0),
            "version_no": int(row.get("version_no") or 0),
            "change_source": str(row.get("change_source") or ""),
            "change_summary": str(row.get("change_summary") or ""),
            "actor": str(row.get("actor") or ""),
            "content_hash": str(row.get("content_hash") or ""),
            "file_size": int(row.get("file_size") or 0),
            "metadata": metadata,
            "parent_version_id": int(row.get("parent_version_id") or 0) or None,
            "created_at": _to_iso(row.get("created_at")),
            "is_current": int(row.get("current_version_id") or 0) == int(row.get("id") or 0),
        }
        if include_content:
            item["content"] = str(row.get("content") or "")
        return item


_skill_admin_store = SkillAdminStore()


def get_skill_admin_store() -> SkillAdminStore:
    return _skill_admin_store
