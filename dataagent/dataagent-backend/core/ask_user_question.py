"""Interactive ``AskUserQuestion`` support for the NL2SQL agent.

The SDK's built-in ``AskUserQuestion`` cannot return the user's selection back
to the model in a headless deployment (``can_use_tool`` may only allow/deny).
So we expose our own in-process SDK MCP tool ``ask_user_question`` whose handler
fully owns the round-trip: it records a ``question_request`` block (rendered as a
selection card in the chat UI), parks the task in ``waiting_input``, waits on
Redis for the user's answer, records ``question_answer``, then returns the
selection as the tool result so the *same* run resumes with the answer.

Mirrors the permission-confirmation flow (``permission_wait`` /
``permission_gate``) so both share the proven pause/resume infrastructure.
"""
from __future__ import annotations

import asyncio
import json
import logging
import uuid
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable

from config import get_settings

logger = logging.getLogger(__name__)

# Server + tool identity. The SDK-qualified tool name (``mcp__<server>__<tool>``)
# is what shows up in allowed_tools, tool_use blocks, and the frontend renderer.
ASK_USER_MCP_SERVER_NAME = "ask_user"
ASK_USER_TOOL_NAME = "ask_user_question"
ASK_USER_QUALIFIED_TOOL_NAME = f"mcp__{ASK_USER_MCP_SERVER_NAME}__{ASK_USER_TOOL_NAME}"

ASK_USER_TOOL_DESCRIPTION = (
    "向用户提出一个或多个多选/单选问题以澄清需求、消解歧义或确认偏好,并等待用户在界面中选择后返回其选择。"
    "当需求不明确、存在多种合理理解、或需要用户在若干口径/范围/维度间做选择时优先使用。"
    "每个问题的 header 为不超过 12 字的简短标签;选项应互斥、简明。"
    "不要用它询问'这样可以吗/是否继续'这类确认。"
)

ASK_USER_INPUT_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "questions": {
            "type": "array",
            "minItems": 1,
            "items": {
                "type": "object",
                "properties": {
                    "question": {"type": "string", "description": "完整问题文本"},
                    "header": {"type": "string", "description": "不超过12字的简短标签"},
                    "multiSelect": {
                        "type": "boolean",
                        "description": "为 true 时允许多选,默认 false(单选)",
                    },
                    "options": {
                        "type": "array",
                        "minItems": 2,
                        "items": {
                            "type": "object",
                            "properties": {
                                "label": {"type": "string"},
                                "description": {"type": "string"},
                            },
                            "required": ["label"],
                        },
                    },
                },
                "required": ["question", "header", "options"],
            },
        }
    },
    "required": ["questions"],
}


def ask_question_answer_redis_key(task_id: str, request_id: str) -> str:
    """Redis key carrying the user's answer for a waiting ``ask_user_question``.

    Single source of truth shared by the API answer endpoint (writer, via the
    coordinator) and the tool handler's wait (reader), mirroring the permission
    decision key pattern.
    """
    return f"da:task:answer:{task_id}:{request_id}"


async def wait_for_answer(
    task_id: str,
    request_id: str,
    *,
    timeout_seconds: int,
    poll_interval_seconds: float = 1.0,
    is_cancel_requested: Any = None,
) -> list[dict[str, Any]] | None:
    """Poll Redis for the user's answer; return the answers list or ``None``.

    ``None`` means the wait ended without an answer (timeout or cancellation).
    Self-connects to Redis from settings so it works in-process and in the
    sandbox runner subprocess, exactly like :func:`permission_wait.wait_for_decision`.
    """
    cfg = get_settings()
    key = ask_question_answer_redis_key(task_id, request_id)
    try:
        import redis.asyncio as redis

        client = redis.Redis(
            host=cfg.redis_host,
            port=int(cfg.redis_port or 6379),
            password=cfg.redis_password or None,
            db=int(cfg.redis_db or 0),
            decode_responses=True,
        )
    except Exception:
        logger.exception("ask_user wait: redis unavailable; request_id=%s", request_id)
        return None

    deadline = asyncio.get_event_loop().time() + max(1, int(timeout_seconds or 600))
    try:
        while True:
            try:
                value = await client.get(key)
            except Exception:
                logger.exception("ask_user wait: redis read failed; request_id=%s", request_id)
                return None
            if value:
                return _parse_answers(value)
            if is_cancel_requested is not None:
                try:
                    cancelled = is_cancel_requested()
                    if asyncio.iscoroutine(cancelled):
                        cancelled = await cancelled
                    if cancelled:
                        return None
                except Exception:
                    pass
            if asyncio.get_event_loop().time() >= deadline:
                return None
            await asyncio.sleep(max(0.1, float(poll_interval_seconds)))
    finally:
        try:
            await client.aclose()
        except Exception:
            pass


def _parse_answers(value: Any) -> list[dict[str, Any]]:
    try:
        parsed = json.loads(value) if isinstance(value, str) else value
    except Exception:
        return []
    if isinstance(parsed, dict):
        parsed = parsed.get("answers")
    if not isinstance(parsed, list):
        return []
    return [item for item in parsed if isinstance(item, dict)]


def summarize_answers(answers: list[dict[str, Any]]) -> str:
    """Render the user's selection as the tool-result text fed back to the model."""
    if not answers:
        return "用户未作出选择(已超时或取消)。请基于已有信息继续,必要时使用合理的默认假设并说明。"
    lines = ["用户已作答:"]
    for item in answers:
        header = str(item.get("header") or item.get("question") or "").strip()
        selected = item.get("selected")
        if isinstance(selected, str):
            selected = [selected]
        picks = [str(s).strip() for s in (selected or []) if str(s).strip()]
        other = str(item.get("other") or "").strip()
        if other:
            picks.append(f"其他:{other}")
        rendered = "、".join(picks) if picks else "(未选择)"
        lines.append(f"- [{header}] → {rendered}")
    return "\n".join(lines)


def _sdk_tool_helpers():
    """Return (tool, create_sdk_mcp_server) or (None, None) when SDK is absent."""
    try:
        from claude_agent_sdk import create_sdk_mcp_server, tool

        return tool, create_sdk_mcp_server
    except Exception:
        return None, None


def build_ask_user_mcp_server(
    *,
    sdk_writer: Any,
    store: Any,
    task_id: str,
    wait_seconds: int,
    is_cancel_requested: Callable[[], Awaitable[bool] | bool] | None = None,
) -> tuple[dict[str, Any] | None, str]:
    """Build the in-process ``ask_user`` MCP server config and its qualified tool name.

    Returns ``(server_config, qualified_tool_name)``; ``server_config`` is ``None``
    when the SDK is unavailable, in which case the caller must not advertise the
    tool in ``allowed_tools``.
    """
    tool_decorator, create_server = _sdk_tool_helpers()
    if tool_decorator is None or create_server is None:
        return None, ASK_USER_QUALIFIED_TOOL_NAME

    @tool_decorator(ASK_USER_TOOL_NAME, ASK_USER_TOOL_DESCRIPTION, ASK_USER_INPUT_SCHEMA)
    async def _ask_user_question(args: dict[str, Any]) -> dict[str, Any]:
        questions = args.get("questions") if isinstance(args, dict) else None
        if not isinstance(questions, list) or not questions:
            return {
                "content": [{"type": "text", "text": "questions 不能为空。"}],
                "is_error": True,
            }

        request_id = uuid.uuid4().hex
        try:
            sdk_writer.append_question_request(request_id=request_id, questions=questions)
            store.set_task_status(task_id, "waiting_input")
        except Exception:
            logger.exception("ask_user: failed to record question request task_id=%s", task_id)
            return {
                "content": [{"type": "text", "text": "无法发起提问,请基于已有信息继续。"}],
                "is_error": True,
            }

        answers = await wait_for_answer(
            task_id,
            request_id,
            timeout_seconds=wait_seconds,
            is_cancel_requested=is_cancel_requested,
        )
        answers = answers or []
        try:
            append_answer = getattr(store, "append_question_answer_record", None)
            if callable(append_answer):
                append_answer(
                    task_id=task_id,
                    request_id=request_id,
                    answers=answers,
                    answered_at=datetime.now(timezone.utc).isoformat(timespec="seconds"),
                )
            else:
                sdk_writer.append_question_answer(
                    request_id=request_id,
                    answers=answers,
                    answered_at=datetime.now(timezone.utc).isoformat(timespec="seconds"),
                )
            store.set_task_status(task_id, "running")
        except Exception:
            logger.exception("ask_user: failed to record question answer task_id=%s", task_id)

        return {"content": [{"type": "text", "text": summarize_answers(answers)}]}

    server = create_server(name=ASK_USER_MCP_SERVER_NAME, tools=[_ask_user_question])
    return server, ASK_USER_QUALIFIED_TOOL_NAME
