from __future__ import annotations

"""
DataAgent NL2SQL API（Skills + SSE Block Stream）
"""

import logging
import uuid
from datetime import datetime
from typing import Any, AsyncIterator, Optional

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from config import get_settings, update_settings
from core.nl2sql_agent import AgentRunInput, stream_agent_reply
from core.semantic_layer import get_semantic_layer
from core.session_store import SessionStore, get_session_store
from core.sql_executor import execute_sql
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
    SqlExecutionResult,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/nl2sql")

MAX_HISTORY_MESSAGES = 24
MAX_HISTORY_CONTENT_CHARS = 1600
SUPPORTED_PROVIDERS = {"anthropic", "openrouter", "anyrouter", "anthropic_compatible"}


class ExecuteSqlRequest(BaseModel):
    sql: str
    database: Optional[str] = None
    limit: Optional[int] = None


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
    return _build_settings_response(get_settings())


@router.put("/settings", response_model=SettingsResponse)
async def api_update_settings(request: SettingsUpdateRequest):
    patch = request.model_dump(exclude_none=True)
    if not patch:
        raise HTTPException(status_code=400, detail="No fields to update")

    mapped_patch: dict[str, Any] = {}
    provider_id = patch.get("provider_id")
    if provider_id is not None:
        provider_id = str(provider_id).strip().lower()
        if provider_id not in SUPPORTED_PROVIDERS:
            raise HTTPException(status_code=400, detail="provider_id must be one of anthropic/openrouter/anyrouter/anthropic_compatible")
        mapped_patch["llm_provider"] = provider_id

    model = patch.get("model")
    if model is not None:
        mapped_patch["claude_model"] = str(model).strip()

    passthrough_keys = {
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
    }
    for key in passthrough_keys:
        if key in patch:
            mapped_patch[key] = patch[key]

    if "skills_output_dir" in mapped_patch:
        normalized = str(mapped_patch["skills_output_dir"] or "").replace("\\", "/")
        if "/.claude/skills/" not in normalized and not normalized.startswith(".claude/skills/"):
            raise HTTPException(
                status_code=400,
                detail="skills_output_dir must be under .claude/skills",
            )

    cfg = update_settings(mapped_patch)
    return _build_settings_response(cfg)


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


@router.post("/execute", response_model=SqlExecutionResult)
async def api_execute_sql(request: ExecuteSqlRequest):
    return execute_sql(
        sql=request.sql,
        database=request.database,
        limit=request.limit,
    )


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

    resolved_database = _resolve_database(
        question=content,
        explicit_database=request.database,
        history_messages=history_messages,
    )

    run_id = f"run_{uuid.uuid4().hex[:24]}"
    assistant_message_id = f"a_{uuid.uuid4().hex[:24]}"
    provider_id = (request.provider_id or get_settings().llm_provider or "").strip() or "openrouter"
    model = (request.model or get_settings().claude_model or "").strip() or "anthropic/claude-sonnet-4.5"
    debug = bool(request.debug)

    run_input = AgentRunInput(
        run_id=run_id,
        session_id=session_id,
        message_id=assistant_message_id,
        question=content,
        history=history,
        provider_id=provider_id,
        model=model,
        resolved_database=resolved_database,
        debug=debug,
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

    response = await _collect_message_response(store=store, run_input=run_input)
    return response


def _build_settings_response(cfg) -> SettingsResponse:
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

    providers = [
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
            display_name="Anthropic Compatible",
            base_url=cfg.anthropic_base_url or "",
            api_key_set=bool(cfg.anthropic_api_key),
            auth_token_set=bool(cfg.anthropic_auth_token),
            models=compatible_models,
            default_model=cfg.claude_model or "",
        ),
    ]

    return SettingsResponse(
        default_provider_id=cfg.llm_provider,
        default_model=cfg.claude_model,
        providers=providers,
        skills_output_dir=cfg.skills_output_dir,
        mysql_host=cfg.mysql_host,
        mysql_port=cfg.mysql_port,
        mysql_database=cfg.mysql_database,
        doris_host=cfg.doris_host,
        doris_port=cfg.doris_port,
        doris_database=cfg.doris_database,
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
    except Exception as e:
        logger.exception("stream run failed: run_id=%s", run_input.run_id)
        error_event = {
            "run_id": run_input.run_id,
            "session_id": run_input.session_id,
            "message_id": run_input.message_id,
            "seq": len(events) + 1,
            "type": "error",
            "ts": datetime.utcnow().isoformat(),
            "payload": {"code": "stream_failed", "message": str(e)},
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
                        "text": str(e),
                        "payload": {"code": "stream_failed", "message": str(e)},
                    }
                ],
                "sql": "",
                "execution": None,
                "error": {"code": "stream_failed", "message": str(e)},
                "resolved_database": run_input.resolved_database,
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
                "sql": "",
                "execution": None,
                "error": {"code": "done_missing", "message": "模型未返回完成事件"},
                "resolved_database": run_input.resolved_database,
                "provider_id": run_input.provider_id,
                "model": run_input.model,
            },
        }

    events.append(done_event)
    try:
        await _persist_run(store=store, run_input=run_input, events=events, done_payload=done_event.get("payload") or {})
    except Exception as e:
        logger.exception("persist run failed: run_id=%s", run_input.run_id)
        error_event = {
            "run_id": run_input.run_id,
            "session_id": run_input.session_id,
            "message_id": run_input.message_id,
            "seq": len(events) + 1,
            "type": "error",
            "ts": datetime.utcnow().isoformat(),
            "payload": {"code": "persist_failed", "message": str(e)},
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

    payload = done_event.get("payload") or {}
    saved = await _persist_run(store=store, run_input=run_input, events=events, done_payload=payload)
    return saved


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
    sql = str(done_payload.get("sql") or "")
    execution_payload = done_payload.get("execution")
    execution: dict[str, Any] | None = execution_payload if isinstance(execution_payload, dict) else None
    error_payload = done_payload.get("error")
    error: dict[str, Any] | None = error_payload if isinstance(error_payload, dict) else None
    resolved_database = done_payload.get("resolved_database")
    if resolved_database is not None:
        resolved_database = str(resolved_database).strip() or None
    provider_id = str(done_payload.get("provider_id") or run_input.provider_id)
    model = str(done_payload.get("model") or run_input.model)

    store.save_assistant_message(
        session_id=run_input.session_id,
        message_id=run_input.message_id,
        run_id=run_input.run_id,
        content=content,
        status=status,
        blocks=blocks,
        sql=sql,
        execution=execution,
        error=error,
        resolved_database=resolved_database,
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
        "sql": sql,
        "execution": execution,
        "error": error,
        "resolved_database": resolved_database,
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
    execution_payload = message.get("execution")
    execution = None
    if isinstance(execution_payload, dict):
        execution = SqlExecutionResult.model_validate(execution_payload)

    blocks: list[MessageBlock] = []
    for block in message.get("blocks") or []:
        if not isinstance(block, dict):
            continue
        blocks.append(MessageBlock.model_validate(block))

    return AssistantMessageResponse(
        role="assistant",
        message_id=str(message.get("message_id") or ""),
        run_id=str(message.get("run_id") or ""),
        status=str(message.get("status") or "success"),
        content=str(message.get("content") or ""),
        blocks=blocks,
        sql=str(message.get("sql") or ""),
        execution=execution,
        error=message.get("error") if isinstance(message.get("error"), dict) else None,
        resolved_database=str(message.get("resolved_database") or "") or None,
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
        msg = {
            "message_id": str(message.get("message_id") or ""),
            "role": str(message.get("role") or "assistant"),
            "content": str(message.get("content") or ""),
            "status": str(message.get("status") or "success"),
            "run_id": str(message.get("run_id") or "") or None,
            "blocks": message.get("blocks") if isinstance(message.get("blocks"), list) else [],
            "sql": str(message.get("sql") or ""),
            "execution": message.get("execution") if isinstance(message.get("execution"), dict) else None,
            "error": message.get("error") if isinstance(message.get("error"), dict) else None,
            "resolved_database": str(message.get("resolved_database") or "") or None,
            "provider_id": str(message.get("provider_id") or "") or None,
            "model": str(message.get("model") or "") or None,
            "created_at": str(message.get("created_at") or ""),
        }
        messages.append(SessionMessage.model_validate(msg).model_dump())

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

        if role == "assistant":
            content = str(item.get("content") or "").strip()
            sql = str(item.get("sql") or "").strip()
            merged = content
            if sql:
                merged = f"{merged}\n\nSQL:\n{sql}".strip()
        else:
            merged = str(item.get("content") or "").strip()

        if not merged:
            continue
        if len(merged) > MAX_HISTORY_CONTENT_CHARS:
            merged = merged[:MAX_HISTORY_CONTENT_CHARS] + "..."
        history.append({"role": role, "content": merged})
    return history


def _resolve_database(
    *,
    question: str,
    explicit_database: str | None,
    history_messages: list[dict[str, Any]] | None = None,
) -> str | None:
    manual = _normalize_database_name(explicit_database)
    if manual:
        return manual

    semantic_layer = get_semantic_layer()
    if not semantic_layer._loaded:
        try:
            semantic_layer.load()
        except Exception as e:
            logger.warning("semantic layer load failed: %s", e)

    try:
        inferred_db, _ = semantic_layer.infer_database(question, top_k=8)
        if inferred_db:
            return inferred_db
    except Exception as e:
        logger.warning("database inference failed: %s", e)

    history = history_messages or []
    for msg in reversed(history):
        if str(msg.get("role") or "") != "assistant":
            continue
        resolved = _normalize_database_name(msg.get("resolved_database"))
        if resolved:
            return resolved
    return None


def _normalize_database_name(value: Any) -> str | None:
    text = str(value or "").strip()
    return text or None


def _get_store() -> SessionStore:
    store = get_session_store()
    try:
        store.init_schema()
    except Exception as e:
        logger.exception("session store init failed")
        raise HTTPException(status_code=500, detail=f"Session store unavailable: {e}") from e
    return store
