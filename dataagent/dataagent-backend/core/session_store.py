from __future__ import annotations

"""
NL2SQL 会话存储（MySQL 持久化）
使用独立 schema，避免与业务元数据 schema 混用。
"""

import json
import logging
import threading
from datetime import datetime

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
                        CREATE TABLE IF NOT EXISTS aq_chat_session (
                            session_id VARCHAR(64) NOT NULL PRIMARY KEY,
                            title VARCHAR(255) NOT NULL,
                            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                            KEY idx_updated_at (updated_at)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """
                    )
                    cur.execute(
                        """
                        CREATE TABLE IF NOT EXISTS aq_chat_message (
                            id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                            session_id VARCHAR(64) NOT NULL,
                            role VARCHAR(16) NOT NULL,
                            content LONGTEXT NOT NULL,
                            payload_json LONGTEXT NULL,
                            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            KEY idx_session_time (session_id, created_at, id),
                            CONSTRAINT fk_chat_message_session
                                FOREIGN KEY (session_id) REFERENCES aq_chat_session(session_id)
                                ON DELETE CASCADE
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """
                    )
                conn.commit()
            finally:
                conn.close()

            self._ready = True
            logger.info("Session store schema is ready: %s", schema)

    def _ensure_ready(self):
        if not self._ready:
            self.init_schema()

    def create_session(self, session_id: str, title: str) -> dict:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO aq_chat_session (session_id, title)
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
                    "UPDATE aq_chat_session SET title = %s, updated_at = CURRENT_TIMESTAMP WHERE session_id = %s",
                    (title, session_id),
                )
            conn.commit()
        finally:
            conn.close()

    def append_message(self, session_id: str, role: str, content: str, payload: dict | None = None):
        self._ensure_ready()
        payload_json = json.dumps(payload, ensure_ascii=False, default=_json_default) if payload else None
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO aq_chat_message (session_id, role, content, payload_json)
                    VALUES (%s, %s, %s, %s)
                    """,
                    (session_id, role, content or "", payload_json),
                )
                cur.execute(
                    "UPDATE aq_chat_session SET updated_at = CURRENT_TIMESTAMP WHERE session_id = %s",
                    (session_id,),
                )
            conn.commit()
        finally:
            conn.close()

    def delete_session(self, session_id: str):
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM aq_chat_session WHERE session_id = %s", (session_id,))
            conn.commit()
        finally:
            conn.close()

    def _load_messages_map(self, session_ids: list[str]) -> dict[str, list[dict]]:
        if not session_ids:
            return {}

        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            placeholders = ",".join(["%s"] * len(session_ids))
            sql = (
                "SELECT id, session_id, role, content, payload_json, created_at "
                f"FROM aq_chat_message WHERE session_id IN ({placeholders}) "
                "ORDER BY session_id ASC, id ASC"
            )
            with conn.cursor() as cur:
                cur.execute(sql, session_ids)
                rows = cur.fetchall()
        finally:
            conn.close()

        result: dict[str, list[dict]] = {}
        for row in rows:
            msg = {
                "role": row.get("role"),
                "content": row.get("content") or "",
                "timestamp": _to_iso(row.get("created_at")),
            }
            payload_raw = row.get("payload_json")
            if payload_raw:
                try:
                    payload = json.loads(payload_raw)
                    if isinstance(payload, dict):
                        msg.update(payload)
                except json.JSONDecodeError:
                    logger.warning("Invalid payload_json in message id=%s", row.get("id"))
            sid = row.get("session_id")
            result.setdefault(sid, []).append(msg)

        return result

    def _load_message_stats(self, session_ids: list[str]) -> dict[str, dict]:
        if not session_ids:
            return {}

        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            placeholders = ",".join(["%s"] * len(session_ids))
            with conn.cursor() as cur:
                cur.execute(
                    f"""
                    SELECT session_id, COUNT(*) AS message_count, MAX(id) AS last_message_id
                    FROM aq_chat_message
                    WHERE session_id IN ({placeholders})
                    GROUP BY session_id
                    """,
                    session_ids,
                )
                stat_rows = cur.fetchall()

                last_ids = [r.get("last_message_id") for r in stat_rows if r.get("last_message_id")]
                preview_map: dict[int, str] = {}
                if last_ids:
                    id_placeholders = ",".join(["%s"] * len(last_ids))
                    cur.execute(
                        f"SELECT id, content FROM aq_chat_message WHERE id IN ({id_placeholders})",
                        last_ids,
                    )
                    for row in cur.fetchall():
                        content = (row.get("content") or "").strip()
                        preview_map[row["id"]] = content[:120] + ("..." if len(content) > 120 else "")
        finally:
            conn.close()

        result: dict[str, dict] = {}
        for row in stat_rows:
            sid = row.get("session_id")
            result[sid] = {
                "message_count": int(row.get("message_count") or 0),
                "last_message_preview": preview_map.get(row.get("last_message_id"), ""),
            }
        return result

    def list_sessions(self, include_messages: bool = False) -> list[dict]:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT session_id, title, created_at, updated_at
                    FROM aq_chat_session
                    ORDER BY updated_at DESC
                    """
                )
                sessions = cur.fetchall()
        finally:
            conn.close()

        session_ids = [s["session_id"] for s in sessions]
        messages_map = self._load_messages_map(session_ids) if include_messages else {}
        stats_map = self._load_message_stats(session_ids)

        result = []
        for s in sessions:
            sid = s["session_id"]
            stat = stats_map.get(sid, {})
            result.append(
                {
                    "session_id": sid,
                    "title": s.get("title") or "新会话",
                    "messages": messages_map.get(sid, []),
                    "message_count": stat.get("message_count", 0),
                    "last_message_preview": stat.get("last_message_preview", ""),
                    "created_at": _to_iso(s.get("created_at")),
                    "updated_at": _to_iso(s.get("updated_at")),
                }
            )
        return result

    def get_session(self, session_id: str) -> dict | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT session_id, title, created_at, updated_at
                    FROM aq_chat_session
                    WHERE session_id = %s
                    LIMIT 1
                    """,
                    (session_id,),
                )
                session = cur.fetchone()
        finally:
            conn.close()

        if not session:
            return None

        messages = self._load_messages_map([session_id]).get(session_id, [])
        return {
            "session_id": session_id,
            "title": session.get("title") or "新会话",
            "messages": messages,
            "created_at": _to_iso(session.get("created_at")),
            "updated_at": _to_iso(session.get("updated_at")),
        }


_session_store = SessionStore()


def get_session_store() -> SessionStore:
    return _session_store
