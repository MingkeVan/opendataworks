from __future__ import annotations

"""
DataAgent NL2SQL API（Skills + SSE Block Stream）
"""

import logging
import uuid
from datetime import datetime
from typing import Any, AsyncIterator

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import StreamingResponse

from config import get_settings
from core.nl2sql_agent import AgentRunInput, stream_agent_reply
from core.skill_admin_service import (
    persist_admin_settings,
    resolve_runtime_provider_selection,
    resolved_chat_settings_payload,
)
from core.session_store import SessionStore, get_session_store
from core.stream_events import encode_sse
from models.schemas import (
    AssistantMessageResponse,
    MessageBlock,
    ProviderConfig,
    SendMessageRequest,
    SessionDetail,
    SessionMessage,
    SessionSummary,
    SettingsResponse,
    SettingsUpdateRequest,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/nl2sql")

MAX_HISTORY_MESSAGES = 24
MAX_HISTORY_CONTENT_CHARS = 1600
SUPPORTED_PROVIDERS = {"anthropic", "openrouter", "anyrouter", "anthropic_compatible"}


@router.get("/health")
async def api_health():
    cfg = get_settings()
    return {
        "status": "ok",
        "provider_id": cfg.llm_provider,
        "model": cfg.claude_model,
        "skills_output_dir": cfg.skills_output_dir,
    }


@router.get("/settings", response_model=SettingsResponse)
async def api_get_settings():
    return _build_settings_response()


@router.put("/settings", response_model=SettingsResponse)
async def api_update_settings(request: SettingsUpdateRequest):
    patch = request.model_dump(exclude_none=True)
    if not patch:
        raise HTTPException(status_code=400, detail="No fields to update")
    try:
        persisted = persist_admin_settings(patch)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    if persisted.get("provider_id") and persisted["provider_id"] not in SUPPORTED_PROVIDERS:
        raise HTTPException(status_code=400, detail="provider_id must be one of anthropic/openrouter/anyrouter/anthropic_compatible")
    return _build_settings_response()


@router.post("/sessions", response_model=SessionDetail)
async def api_create_session(title: str = Query(default="新会话")):
    store = _get_store()
    session_id = str(uuid.uuid4())
    session = store.create_session(session_id=session_id, title=title)
    return SessionDetail.model_validate(_normalize_session(session))


@router.get("/sessions", response_model=list[SessionSummary])
async def api_list_sessions():
    store = _get_store()
    sessions = store.list_sessions(include_messages=False)
    return [SessionSummary.model_validate(_normalize_session_summary(item)) for item in sessions]


@router.get("/sessions/{session_id}", response_model=SessionDetail)
async def api_get_session(session_id: str):
    store = _get_store()
    session = store.get_session(session_id)
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")
    return SessionDetail.model_validate(_normalize_session(session))


@router.delete("/sessions/{session_id}")
async def api_delete_session(session_id: str):
    store = _get_store()
    store.delete_session(session_id)
    return {"status": "ok"}


@router.post("/sessions/{session_id}/messages")
async def api_send_message(session_id: str, request: SendMessageRequest):
    content = str(request.content or "").strip()
    if not content:
        raise HTTPException(status_code=400, detail="content is required")

    store = _get_store()
    session = store.get_session(session_id)
    if not session:
        inferred_title = content[:30] + "..." if len(content) > 30 else content
        session = store.create_session(session_id=session_id, title=inferred_title or "新会话")

    user_message_id = f"u_{uuid.uuid4().hex[:24]}"
    store.append_user_message(session_id=session_id, message_id=user_message_id, content=content)

    session_after_user = store.get_session(session_id) or session
    messages = list(session_after_user.get("messages") or [])
    history_messages = [m for m in messages if str(m.get("message_id") or "") != user_message_id]
    history = _build_history_messages(history_messages[-MAX_HISTORY_MESSAGES:])

    run_id = f"run_{uuid.uuid4().hex[:24]}"
    assistant_message_id = f"a_{uuid.uuid4().hex[:24]}"
    try:
        resolved_target = resolve_runtime_provider_selection(request.provider_id, request.model)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    run_input = AgentRunInput(
        run_id=run_id,
        session_id=session_id,
        message_id=assistant_message_id,
        question=content,
        history=history,
        provider_id=str(resolved_target.get("provider_id") or ""),
        model=str(resolved_target.get("model") or ""),
        database_hint=str(request.database or "").strip() or None,
        debug=bool(request.debug),
    )

    if request.stream:
        return StreamingResponse(
            _stream_message_events(store=store, run_input=run_input),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "Connection": "keep-alive",
                "X-Accel-Buffering": "no",
            },
        )

    return await _collect_message_response(store=store, run_input=run_input)


def _build_settings_response() -> SettingsResponse:
    payload = resolved_chat_settings_payload()
    providers = [ProviderConfig.model_validate(item) for item in payload.get("providers") or []]
    return SettingsResponse(
        default_provider_id=str(payload.get("default_provider_id") or ""),
        default_model=str(payload.get("default_model") or ""),
        providers=providers,
        skills_output_dir=str(payload.get("skills_output_dir") or ""),
        mysql_host=str(payload.get("mysql_host") or ""),
        mysql_port=int(payload.get("mysql_port") or 3306),
        mysql_database=str(payload.get("mysql_database") or ""),
        doris_host=str(payload.get("doris_host") or ""),
        doris_port=int(payload.get("doris_port") or 9030),
        doris_database=str(payload.get("doris_database") or ""),
    )


async def _stream_message_events(store: SessionStore, run_input: AgentRunInput) -> AsyncIterator[str]:
    events: list[dict[str, Any]] = []
    done_event: dict[str, Any] | None = None

    try:
        async for event in stream_agent_reply(run_input):
            if event.get("type") == "done":
                done_event = event
                break
            events.append(event)
            yield encode_sse(event)
    except Exception as exc:
        logger.exception("stream run failed: run_id=%s", run_input.run_id)
        error_event = {
            "run_id": run_input.run_id,
            "session_id": run_input.session_id,
            "message_id": run_input.message_id,
            "seq": len(events) + 1,
            "type": "error",
            "ts": datetime.utcnow().isoformat(),
            "payload": {"code": "stream_failed", "message": str(exc)},
        }
        done_event = {
            "run_id": run_input.run_id,
            "session_id": run_input.session_id,
            "message_id": run_input.message_id,
            "seq": len(events) + 2,
            "type": "done",
            "ts": datetime.utcnow().isoformat(),
            "payload": {
                "status": "failed",
                "content": "模型调用失败",
                "blocks": [
                    {
                        "block_id": "error-1",
                        "type": "error",
                        "status": "failed",
                        "text": str(exc),
                        "payload": {"code": "stream_failed", "message": str(exc)},
                    }
                ],
                "error": {"code": "stream_failed", "message": str(exc)},
                "provider_id": run_input.provider_id,
                "model": run_input.model,
            },
        }
        events.append(error_event)
        yield encode_sse(error_event)

    if done_event is None:
        done_event = {
            "run_id": run_input.run_id,
            "session_id": run_input.session_id,
            "message_id": run_input.message_id,
            "seq": len(events) + 1,
            "type": "done",
            "ts": datetime.utcnow().isoformat(),
            "payload": {
                "status": "failed",
                "content": "模型未返回完成事件",
                "blocks": [],
                "error": {"code": "done_missing", "message": "模型未返回完成事件"},
                "provider_id": run_input.provider_id,
                "model": run_input.model,
            },
        }

    events.append(done_event)
    try:
        await _persist_run(store=store, run_input=run_input, events=events, done_payload=done_event.get("payload") or {})
    except Exception as exc:
        logger.exception("persist run failed: run_id=%s", run_input.run_id)
        error_event = {
            "run_id": run_input.run_id,
            "session_id": run_input.session_id,
            "message_id": run_input.message_id,
            "seq": len(events) + 1,
            "type": "error",
            "ts": datetime.utcnow().isoformat(),
            "payload": {"code": "persist_failed", "message": str(exc)},
        }
        yield encode_sse(error_event)
        return

    yield encode_sse(done_event)


async def _collect_message_response(store: SessionStore, run_input: AgentRunInput) -> AssistantMessageResponse:
    events: list[dict[str, Any]] = []
    async for event in stream_agent_reply(run_input):
        events.append(event)

    if not events:
        raise HTTPException(status_code=500, detail="Model returned empty event stream")

    done_event = next((event for event in reversed(events) if event.get("type") == "done"), None)
    if not done_event:
        raise HTTPException(status_code=500, detail="Model stream missing done event")

    return await _persist_run(store=store, run_input=run_input, events=events, done_payload=done_event.get("payload") or {})


async def _persist_run(
    *,
    store: SessionStore,
    run_input: AgentRunInput,
    events: list[dict[str, Any]],
    done_payload: dict[str, Any],
) -> AssistantMessageResponse:
    status = str(done_payload.get("status") or "success")
    content = str(done_payload.get("content") or "")
    blocks = done_payload.get("blocks")
    if not isinstance(blocks, list):
        blocks = []
    error_payload = done_payload.get("error")
    error: dict[str, Any] | None = error_payload if isinstance(error_payload, dict) else None
    provider_id = str(done_payload.get("provider_id") or run_input.provider_id)
    model = str(done_payload.get("model") or run_input.model)

    store.save_assistant_message(
        session_id=run_input.session_id,
        message_id=run_input.message_id,
        run_id=run_input.run_id,
        content=content,
        status=status,
        blocks=blocks,
        error=error,
        provider_id=provider_id,
        model=model,
    )
    store.save_run_events(
        session_id=run_input.session_id,
        message_id=run_input.message_id,
        run_id=run_input.run_id,
        events=events,
    )

    saved = _load_message_from_session(store, run_input.session_id, run_input.message_id) or {
        "message_id": run_input.message_id,
        "role": "assistant",
        "status": status,
        "run_id": run_input.run_id,
        "content": content,
        "blocks": blocks,
        "error": error,
        "provider_id": provider_id,
        "model": model,
        "created_at": datetime.utcnow().isoformat(),
    }
    return _to_assistant_message_response(saved)


def _load_message_from_session(store: SessionStore, session_id: str, message_id: str) -> dict[str, Any] | None:
    session = store.get_session(session_id)
    if not session:
        return None
    for message in session.get("messages") or []:
        if str(message.get("message_id") or "") == message_id:
            return message
    return None


def _to_assistant_message_response(message: dict[str, Any]) -> AssistantMessageResponse:
    blocks: list[MessageBlock] = []
    for block in message.get("blocks") or []:
        if isinstance(block, dict):
            blocks.append(MessageBlock.model_validate(block))

    return AssistantMessageResponse(
        role="assistant",
        message_id=str(message.get("message_id") or ""),
        run_id=str(message.get("run_id") or ""),
        status=str(message.get("status") or "success"),
        content=str(message.get("content") or ""),
        blocks=blocks,
        error=message.get("error") if isinstance(message.get("error"), dict) else None,
        provider_id=str(message.get("provider_id") or ""),
        model=str(message.get("model") or ""),
        created_at=str(message.get("created_at") or ""),
    )


def _normalize_session_summary(session: dict[str, Any]) -> dict[str, Any]:
    return {
        "session_id": str(session.get("session_id") or ""),
        "title": str(session.get("title") or "新会话"),
        "message_count": int(session.get("message_count") or 0),
        "last_message_preview": str(session.get("last_message_preview") or ""),
        "created_at": str(session.get("created_at") or ""),
        "updated_at": str(session.get("updated_at") or ""),
    }


def _normalize_session(session: dict[str, Any]) -> dict[str, Any]:
    messages = []
    for message in session.get("messages") or []:
        if not isinstance(message, dict):
            continue
        payload = {
            "message_id": str(message.get("message_id") or ""),
            "role": str(message.get("role") or "assistant"),
            "content": str(message.get("content") or ""),
            "status": str(message.get("status") or "success"),
            "run_id": str(message.get("run_id") or "") or None,
            "blocks": message.get("blocks") if isinstance(message.get("blocks"), list) else [],
            "error": message.get("error") if isinstance(message.get("error"), dict) else None,
            "provider_id": str(message.get("provider_id") or "") or None,
            "model": str(message.get("model") or "") or None,
            "created_at": str(message.get("created_at") or ""),
        }
        messages.append(SessionMessage.model_validate(payload).model_dump())

    return {
        "session_id": str(session.get("session_id") or ""),
        "title": str(session.get("title") or "新会话"),
        "messages": messages,
        "created_at": str(session.get("created_at") or ""),
        "updated_at": str(session.get("updated_at") or ""),
    }


def _build_history_messages(messages: list[dict[str, Any]]) -> list[dict[str, str]]:
    history: list[dict[str, str]] = []
    for item in messages:
        role = str(item.get("role") or "")
        if role not in {"user", "assistant"}:
            continue
        content = str(item.get("content") or "").strip()
        if not content:
            continue
        if len(content) > MAX_HISTORY_CONTENT_CHARS:
            content = content[:MAX_HISTORY_CONTENT_CHARS] + "..."
        history.append({"role": role, "content": content})
    return history


def _get_store() -> SessionStore:
    store = get_session_store()
    try:
        store.init_schema()
    except Exception as exc:
        logger.exception("session store init failed")
        raise HTTPException(status_code=500, detail=f"Session store unavailable: {exc}") from exc
    return store
