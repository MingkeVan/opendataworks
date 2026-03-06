from __future__ import annotations

import json
import sys
import types
from datetime import datetime

from fastapi.testclient import TestClient

if "pymysql" not in sys.modules:
    sys.modules["pymysql"] = types.SimpleNamespace(
        connect=lambda *args, **kwargs: None,
        cursors=types.SimpleNamespace(DictCursor=object),
    )

import api.routes as routes
from main import app


class _FakeStore:
    def __init__(self):
        self.sessions: dict[str, dict] = {}
        self.events: list[dict] = []

    def init_schema(self):
        return None

    def create_session(self, session_id: str, title: str):
        now = datetime.now().isoformat(timespec="seconds")
        session = {
            "session_id": session_id,
            "title": title,
            "messages": [],
            "message_count": 0,
            "last_message_preview": "",
            "created_at": now,
            "updated_at": now,
        }
        self.sessions[session_id] = session
        return session

    def update_session_title(self, session_id: str, title: str):
        self.sessions[session_id]["title"] = title

    def get_session(self, session_id: str):
        return self.sessions.get(session_id)

    def list_sessions(self, include_messages: bool = False):
        rows = []
        for session in self.sessions.values():
            item = dict(session)
            if not include_messages:
                item["messages"] = []
            rows.append(item)
        return rows

    def delete_session(self, session_id: str):
        self.sessions.pop(session_id, None)

    def append_user_message(self, *, session_id: str, message_id: str, content: str):
        session = self.sessions[session_id]
        session["messages"].append(
            {
                "message_id": message_id,
                "role": "user",
                "content": content,
                "status": "success",
                "created_at": datetime.now().isoformat(timespec="seconds"),
            }
        )
        session["message_count"] += 1
        session["last_message_preview"] = content[:120]
        return session["messages"][-1]

    def save_assistant_message(
        self,
        *,
        session_id: str,
        message_id: str,
        run_id: str,
        content: str,
        status: str,
        blocks: list[dict],
        sql: str,
        execution: dict | None,
        error: dict | None,
        resolved_database: str | None,
        provider_id: str,
        model: str,
    ):
        session = self.sessions[session_id]
        message = {
            "message_id": message_id,
            "role": "assistant",
            "run_id": run_id,
            "content": content,
            "status": status,
            "blocks": blocks,
            "sql": sql,
            "execution": execution,
            "error": error,
            "resolved_database": resolved_database,
            "provider_id": provider_id,
            "model": model,
            "created_at": datetime.now().isoformat(timespec="seconds"),
        }
        session["messages"].append(message)
        session["message_count"] += 1
        session["last_message_preview"] = content[:120]
        return message

    def save_run_events(self, *, session_id: str, message_id: str, run_id: str, events: list[dict]):
        self.events.append(
            {
                "session_id": session_id,
                "message_id": message_id,
                "run_id": run_id,
                "events": list(events),
            }
        )


async def _fake_stream_agent_reply(run_input):
    yield {
        "run_id": run_input.run_id,
        "session_id": run_input.session_id,
        "message_id": run_input.message_id,
        "seq": 1,
        "type": "llm_response_created",
        "ts": datetime.now().isoformat(timespec="seconds"),
        "payload": {
            "provider_id": run_input.provider_id,
            "model": run_input.model,
            "resolved_database": run_input.resolved_database,
        },
    }
    yield {
        "run_id": run_input.run_id,
        "session_id": run_input.session_id,
        "message_id": run_input.message_id,
        "seq": 2,
        "type": "text.start",
        "ts": datetime.now().isoformat(timespec="seconds"),
        "payload": {"block_id": "main-text"},
    }
    yield {
        "run_id": run_input.run_id,
        "session_id": run_input.session_id,
        "message_id": run_input.message_id,
        "seq": 3,
        "type": "text.delta",
        "ts": datetime.now().isoformat(timespec="seconds"),
        "payload": {"block_id": "main-text", "text": "你好"},
    }
    yield {
        "run_id": run_input.run_id,
        "session_id": run_input.session_id,
        "message_id": run_input.message_id,
        "seq": 4,
        "type": "done",
        "ts": datetime.now().isoformat(timespec="seconds"),
        "payload": {
            "status": "success",
            "content": "你好",
            "blocks": [{"block_id": "main-text", "type": "main_text", "status": "success", "text": "你好"}],
            "sql": "",
            "execution": None,
            "error": None,
            "resolved_database": "opendataworks",
            "provider_id": run_input.provider_id,
            "model": run_input.model,
        },
    }


def _extract_sse_events(raw_text: str) -> list[dict]:
    events = []
    chunks = [item for item in raw_text.split("\n\n") if item.strip()]
    for chunk in chunks:
        data_lines = [line[5:].strip() for line in chunk.split("\n") if line.startswith("data:")]
        if not data_lines:
            continue
        payload = json.loads("\n".join(data_lines))
        events.append(payload)
    return events


def test_session_message_stream_and_non_stream_contract(monkeypatch):
    fake_store = _FakeStore()
    fake_store.create_session("s1", "新会话")

    monkeypatch.setattr(routes, "get_session_store", lambda: fake_store)
    monkeypatch.setattr(routes, "stream_agent_reply", _fake_stream_agent_reply)
    monkeypatch.setattr(routes, "_resolve_database", lambda **kwargs: "opendataworks")

    client = TestClient(app)

    non_stream = client.post(
        "/api/v1/nl2sql/sessions/s1/messages",
        json={
            "content": "你好",
            "provider_id": "openrouter",
            "model": "anthropic/claude-sonnet-4.5",
            "stream": False,
        },
    )
    assert non_stream.status_code == 200
    non_stream_data = non_stream.json()

    stream = client.post(
        "/api/v1/nl2sql/sessions/s1/messages",
        json={
            "content": "你好",
            "provider_id": "openrouter",
            "model": "anthropic/claude-sonnet-4.5",
            "stream": True,
        },
    )
    assert stream.status_code == 200
    stream_events = _extract_sse_events(stream.text)
    assert stream_events
    assert stream_events[-1]["type"] == "done"
    stream_done = stream_events[-1]["payload"]

    expected_keys = {
        "role",
        "message_id",
        "run_id",
        "status",
        "content",
        "blocks",
        "sql",
        "execution",
        "error",
        "resolved_database",
        "provider_id",
        "model",
        "created_at",
    }
    assert expected_keys.issubset(set(non_stream_data.keys()))
    assert non_stream_data["content"] == stream_done["content"]
    assert non_stream_data["status"] == stream_done["status"]
    assert non_stream_data["provider_id"] == stream_done["provider_id"]
    assert non_stream_data["model"] == stream_done["model"]


def test_ask_endpoint_removed():
    client = TestClient(app)
    response = client.post("/api/v1/nl2sql/ask", json={"content": "hello"})
    assert response.status_code == 404
