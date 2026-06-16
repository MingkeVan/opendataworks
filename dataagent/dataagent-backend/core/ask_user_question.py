"""Interactive ``AskUserQuestion`` support for the NL2SQL agent.

``AskUserQuestion`` is a *built-in* SDK tool. It always resolves through the
``can_use_tool`` callback (``checkPermissions`` returns ``ask`` and the tool
``requiresUserInteraction``), and the tool produces its result from the
``answers`` carried on its (updated) input. So the host answers a question by
returning ``PermissionResultAllow(updated_input={questions, answers, ...})``.

This module owns the host side of that round-trip, mirroring the
permission-confirmation flow (``permission_wait`` / ``permission_gate``): record
a ``question_request`` block (rendered as a selection card), park the task in
``waiting_input``, wait on Redis for the user's answer, then map the answer onto
the SDK ``updated_input`` shape so the same run resumes with the selection.
"""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

from config import get_settings

logger = logging.getLogger(__name__)

# The built-in tool name as it appears in tool_use blocks and allowed_tools.
ASK_USER_QUESTION_TOOL_NAME = "AskUserQuestion"


def is_ask_user_question_tool(tool_name: str) -> bool:
    return str(tool_name or "").strip() == ASK_USER_QUESTION_TOOL_NAME


def ask_question_answer_redis_key(task_id: str, request_id: str) -> str:
    """Redis key carrying the user's answer for a waiting ``AskUserQuestion``.

    Single source of truth shared by the API answer endpoint (writer, via the
    coordinator) and the ``can_use_tool`` wait (reader), mirroring the
    permission-decision key pattern.
    """
    return f"da:task:answer:{task_id}:{request_id}"


def to_sdk_answer_input(questions: list[dict[str, Any]], answers: list[dict[str, Any]]) -> dict[str, Any]:
    """Map the UI answer payload onto the built-in tool's ``updated_input`` shape.

    The tool echoes ``answers`` (``{questionText: selectedLabel}``) into its
    result, and ``annotations[questionText].notes`` carries free-text. Returning
    only ``questions`` (no answers) yields the tool's "did not answer" result.
    """
    by_question: dict[str, dict[str, Any]] = {}
    for item in answers or []:
        if not isinstance(item, dict):
            continue
        q = str(item.get("question") or "").strip()
        if q:
            by_question[q] = item

    sdk_answers: dict[str, str] = {}
    annotations: dict[str, dict[str, str]] = {}
    for question in questions or []:
        qt = str((question or {}).get("question") or "").strip()
        if not qt:
            continue
        item = by_question.get(qt)
        if not item:
            continue
        selected = item.get("selected")
        if isinstance(selected, str):
            selected = [selected]
        picks = [str(s).strip() for s in (selected or []) if str(s).strip()]
        if picks:
            sdk_answers[qt] = "、".join(picks)
        other = str(item.get("other") or "").strip()
        if other:
            annotations[qt] = {"notes": other}

    updated: dict[str, Any] = {"questions": questions}
    if sdk_answers:
        updated["answers"] = sdk_answers
    if annotations:
        updated["annotations"] = annotations
    return updated


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
