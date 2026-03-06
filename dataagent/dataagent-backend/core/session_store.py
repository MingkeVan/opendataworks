from __future__ import annotations

"""
NL2SQL 会话存储（MySQL 持久化）
- 新表前缀: da_*
- 旧 aq_* 表保留但不再写入
"""

import json
import logging
import threading
from datetime import datetime
from typing import Any

import pymysql
from config import get_settings

logger = logging.getLogger(__name__)


def _to_iso(value) -> str:
    if isinstance(value, datetime):
        return value.isoformat(timespec="seconds")
    return str(value) if value is not None else ""


def _json_default(value):
    if isinstance(value, datetime):
        return value.isoformat(timespec="seconds")
    return str(value)


def _safe_json_load(raw: str | None) -> Any:
    if not raw:
        return None
    try:
        return json.loads(raw)
    except Exception:
        return None


class SessionStore:
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
            self._ready = True
            logger.info("Session store is ready; schema is expected to be managed by Alembic")

    def _ensure_ready(self):
        if not self._ready:
            self.init_schema()

    def create_session(self, session_id: str, title: str) -> dict[str, Any]:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO da_chat_session (session_id, title)
                    VALUES (%s, %s)
                    ON DUPLICATE KEY UPDATE title = VALUES(title), updated_at = CURRENT_TIMESTAMP
                    """,
                    (session_id, title),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_session(session_id) or {}

    def update_session_title(self, session_id: str, title: str):
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "UPDATE da_chat_session SET title = %s, updated_at = CURRENT_TIMESTAMP WHERE session_id = %s",
                    (title, session_id),
                )
            conn.commit()
        finally:
            conn.close()

    def append_user_message(self, *, session_id: str, message_id: str, content: str) -> dict[str, Any]:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO da_chat_message (
                        message_id, session_id, role, status, content
                    ) VALUES (%s, %s, 'user', 'success', %s)
                    """,
                    (message_id, session_id, content or ""),
                )
                cur.execute(
                    "UPDATE da_chat_session SET updated_at = CURRENT_TIMESTAMP WHERE session_id = %s",
                    (session_id,),
                )
            conn.commit()
        finally:
            conn.close()
        return {
            "message_id": message_id,
            "role": "user",
            "content": content or "",
            "status": "success",
        }

    def save_assistant_message(
        self,
        *,
        session_id: str,
        message_id: str,
        run_id: str,
        content: str,
        status: str,
        blocks: list[dict[str, Any]],
        error: dict[str, Any] | None,
        provider_id: str,
        model: str,
    ) -> dict[str, Any]:
        self._ensure_ready()
        error_json = json.dumps(error, ensure_ascii=False, default=_json_default) if error else None

        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO da_chat_message (
                        message_id, session_id, role, status, run_id, content,
                        error_json, provider_id, model_name
                    ) VALUES (%s, %s, 'assistant', %s, %s, %s, %s, %s, %s)
                    ON DUPLICATE KEY UPDATE
                        status = VALUES(status),
                        run_id = VALUES(run_id),
                        content = VALUES(content),
                        error_json = VALUES(error_json),
                        provider_id = VALUES(provider_id),
                        model_name = VALUES(model_name),
                        updated_at = CURRENT_TIMESTAMP
                    """,
                    (
                        message_id,
                        session_id,
                        status,
                        run_id,
                        content or "",
                        error_json,
                        provider_id,
                        model,
                    ),
                )

                cur.execute("DELETE FROM da_chat_block WHERE message_id = %s", (message_id,))
                for idx, block in enumerate(blocks):
                    block_id = str(block.get("block_id") or f"b_{idx}")
                    block_type = str(block.get("type") or "unknown")
                    block_status = str(block.get("status") or "success")
                    content_json = json.dumps(block, ensure_ascii=False, default=_json_default)
                    cur.execute(
                        """
                        INSERT INTO da_chat_block (message_id, block_id, block_type, status, content_json, seq)
                        VALUES (%s, %s, %s, %s, %s, %s)
                        """,
                        (message_id, block_id, block_type, block_status, content_json, idx),
                    )

                cur.execute(
                    "UPDATE da_chat_session SET updated_at = CURRENT_TIMESTAMP WHERE session_id = %s",
                    (session_id,),
                )
            conn.commit()
        finally:
            conn.close()

        return {
            "message_id": message_id,
            "role": "assistant",
            "run_id": run_id,
            "status": status,
            "content": content or "",
            "blocks": blocks,
            "error": error,
            "provider_id": provider_id,
            "model": model,
        }

    def save_run_events(
        self,
        *,
        session_id: str,
        message_id: str,
        run_id: str,
        events: list[dict[str, Any]],
    ):
        if not events:
            return
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                for event in events:
                    seq = int(event.get("seq") or 0)
                    event_type = str(event.get("type") or "")
                    payload = event.get("payload") or {}
                    payload_json = json.dumps(payload, ensure_ascii=False, default=_json_default)
                    cur.execute(
                        """
                        INSERT INTO da_chat_event (run_id, session_id, message_id, seq, event_type, payload_json)
                        VALUES (%s, %s, %s, %s, %s, %s)
                        """,
                        (run_id, session_id, message_id, seq, event_type, payload_json),
                    )
            conn.commit()
        finally:
            conn.close()

    def delete_session(self, session_id: str):
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM da_chat_session WHERE session_id = %s", (session_id,))
            conn.commit()
        finally:
            conn.close()

    def _load_blocks_map(self, message_ids: list[str]) -> dict[str, list[dict[str, Any]]]:
        if not message_ids:
            return {}

        conn = self._connect(database=self._schema_name())
        try:
            placeholders = ",".join(["%s"] * len(message_ids))
            with conn.cursor() as cur:
                cur.execute(
                    (
                        "SELECT message_id, content_json FROM da_chat_block "
                        f"WHERE message_id IN ({placeholders}) ORDER BY message_id ASC, seq ASC"
                    ),
                    message_ids,
                )
                rows = cur.fetchall()
        finally:
            conn.close()

        blocks_map: dict[str, list[dict[str, Any]]] = {}
        for row in rows:
            msg_id = str(row.get("message_id") or "")
            block = _safe_json_load(row.get("content_json"))
            if not isinstance(block, dict):
                continue
            blocks_map.setdefault(msg_id, []).append(block)
        return blocks_map

    def _load_messages(self, session_ids: list[str]) -> dict[str, list[dict[str, Any]]]:
        if not session_ids:
            return {}

        conn = self._connect(database=self._schema_name())
        try:
            placeholders = ",".join(["%s"] * len(session_ids))
            with conn.cursor() as cur:
                cur.execute(
                    (
                        "SELECT message_id, session_id, role, status, run_id, content, error_json, "
                        "provider_id, model_name, created_at "
                        "FROM da_chat_message "
                        f"WHERE session_id IN ({placeholders}) "
                        "ORDER BY created_at ASC, message_id ASC"
                    ),
                    session_ids,
                )
                rows = cur.fetchall()
        finally:
            conn.close()

        message_ids = [str(row.get("message_id") or "") for row in rows]
        blocks_map = self._load_blocks_map(message_ids)

        result: dict[str, list[dict[str, Any]]] = {}
        for row in rows:
            msg_id = str(row.get("message_id") or "")
            error = _safe_json_load(row.get("error_json"))
            message = {
                "message_id": msg_id,
                "role": row.get("role"),
                "status": row.get("status") or "success",
                "run_id": row.get("run_id"),
                "content": row.get("content") or "",
                "blocks": blocks_map.get(msg_id, []),
                "error": error if isinstance(error, dict) else None,
                "provider_id": row.get("provider_id"),
                "model": row.get("model_name"),
                "created_at": _to_iso(row.get("created_at")),
            }
            sid = str(row.get("session_id") or "")
            result.setdefault(sid, []).append(message)

        return result

    def _load_message_stats(self, session_ids: list[str]) -> dict[str, dict[str, Any]]:
        if not session_ids:
            return {}

        conn = self._connect(database=self._schema_name())
        try:
            placeholders = ",".join(["%s"] * len(session_ids))
            with conn.cursor() as cur:
                cur.execute(
                    f"""
                    SELECT session_id, COUNT(*) AS message_count, MAX(created_at) AS latest_time
                    FROM da_chat_message
                    WHERE session_id IN ({placeholders})
                    GROUP BY session_id
                    """,
                    session_ids,
                )
                stat_rows = cur.fetchall()

                cur.execute(
                    (
                        "SELECT m.session_id, m.content FROM da_chat_message m "
                        "INNER JOIN ("
                        f"  SELECT session_id, MAX(created_at) AS latest_time FROM da_chat_message WHERE session_id IN ({placeholders}) GROUP BY session_id"
                        ") t ON m.session_id = t.session_id AND m.created_at = t.latest_time"
                    ),
                    session_ids,
                )
                preview_rows = cur.fetchall()
        finally:
            conn.close()

        preview_map: dict[str, str] = {}
        for row in preview_rows:
            sid = str(row.get("session_id") or "")
            content = str(row.get("content") or "").strip()
            preview_map[sid] = content[:120] + ("..." if len(content) > 120 else "")

        result: dict[str, dict[str, Any]] = {}
        for row in stat_rows:
            sid = str(row.get("session_id") or "")
            result[sid] = {
                "message_count": int(row.get("message_count") or 0),
                "last_message_preview": preview_map.get(sid, ""),
            }
        return result

    def list_sessions(self, include_messages: bool = False) -> list[dict[str, Any]]:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT session_id, title, created_at, updated_at
                    FROM da_chat_session
                    ORDER BY updated_at DESC
                    """
                )
                rows = cur.fetchall()
        finally:
            conn.close()

        session_ids = [str(row.get("session_id") or "") for row in rows]
        messages_map = self._load_messages(session_ids) if include_messages else {}
        stats_map = self._load_message_stats(session_ids)

        result = []
        for row in rows:
            sid = str(row.get("session_id") or "")
            stats = stats_map.get(sid, {})
            result.append(
                {
                    "session_id": sid,
                    "title": row.get("title") or "新会话",
                    "messages": messages_map.get(sid, []),
                    "message_count": int(stats.get("message_count") or 0),
                    "last_message_preview": stats.get("last_message_preview") or "",
                    "created_at": _to_iso(row.get("created_at")),
                    "updated_at": _to_iso(row.get("updated_at")),
                }
            )
        return result

    def get_session(self, session_id: str) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT session_id, title, created_at, updated_at
                    FROM da_chat_session
                    WHERE session_id = %s
                    LIMIT 1
                    """,
                    (session_id,),
                )
                row = cur.fetchone()
        finally:
            conn.close()

        if not row:
            return None

        messages = self._load_messages([session_id]).get(session_id, [])
        return {
            "session_id": str(row.get("session_id") or ""),
            "title": row.get("title") or "新会话",
            "messages": messages,
            "created_at": _to_iso(row.get("created_at")),
            "updated_at": _to_iso(row.get("updated_at")),
        }


_session_store = SessionStore()


def get_session_store() -> SessionStore:
    return _session_store
