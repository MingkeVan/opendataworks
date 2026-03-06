from __future__ import annotations

"""
NL2SQL Agent（Skills-first, stream-first）
"""

import json
import logging
import os
import re
from dataclasses import dataclass
from typing import Any, AsyncIterator
from urllib.parse import urlparse

import anyio

from config import get_settings
from core.skills_loader import resolve_agent_project_cwd
from core.sql_executor import execute_sql
from core.stream_events import EventSequencer

logger = logging.getLogger(__name__)


SQL_FENCE_RE = re.compile(r"```sql\s*([\s\S]*?)```", re.IGNORECASE)
SQL_INLINE_RE = re.compile(r"(?is)\b(select|with|show|desc|describe|explain)\b[\s\S]{8,}")
READ_ONLY_PREFIXES = ("SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN")


@dataclass
class AgentRunInput:
    run_id: str
    session_id: str
    message_id: str
    question: str
    history: list[dict[str, str]]
    provider_id: str
    model: str
    resolved_database: str | None
    debug: bool = False


async def stream_agent_reply(params: AgentRunInput) -> AsyncIterator[dict[str, Any]]:
    cfg = get_settings()
    provider_id = _normalize_provider_id(params.provider_id, cfg.anthropic_base_url)
    model = (params.model or cfg.claude_model or "").strip()
    if not model:
        model = _default_model_for_provider(provider_id)

    sequencer = EventSequencer(
        run_id=params.run_id,
        session_id=params.session_id,
        message_id=params.message_id,
    )

    text_started = False
    thinking_started = False
    saw_partial_stream = False
    main_text = ""
    thinking_text = ""
    result_subtype = ""
    result_error = ""

    block_order: list[str] = []
    blocks: dict[str, dict[str, Any]] = {}
    tool_block_by_tool_id: dict[str, str] = {}

    def _ensure_block(block_id: str, block_type: str) -> dict[str, Any]:
        if block_id not in blocks:
            blocks[block_id] = {
                "block_id": block_id,
                "type": block_type,
                "status": "streaming",
                "text": "",
                "payload": {},
            }
            block_order.append(block_id)
        return blocks[block_id]

    def _emit(event_type: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        return sequencer.next(event_type, payload)

    def _serialize_blocks() -> list[dict[str, Any]]:
        serialized: list[dict[str, Any]] = []
        for block_id in block_order:
            block = dict(blocks.get(block_id) or {})
            if not block:
                continue
            if block.get("type") in {"main_text", "thinking"} and not block.get("text"):
                continue
            serialized.append(block)
        return serialized

    def _partial_block_id(index_value: Any) -> str:
        if isinstance(index_value, int):
            return f"cb-{index_value}"
        return f"cb-{sequencer.seq + 1}"

    def _map_partial_block_type(raw_type: str) -> str:
        lower = str(raw_type or "").strip().lower()
        if lower == "text":
            return "main_text"
        if lower == "thinking":
            return "thinking"
        if lower in {"tool_use", "tooluse"}:
            return "tool_use"
        if lower in {"tool_result", "toolresult"}:
            return "tool_result"
        return "raw"

    prompt = _build_prompt(params.history, params.question)
    system_prompt = _build_system_prompt(params.resolved_database)

    if params.debug:
        yield _emit(
            "raw",
            {
                "kind": "prompt_preview",
                "provider_id": provider_id,
                "model": model,
                "prompt_preview": _clip_text(prompt, 8000),
                "system_prompt_preview": _clip_text(system_prompt, 2000),
            },
        )

    start_payload = {
        "provider_id": provider_id,
        "model": model,
        "resolved_database": params.resolved_database,
    }
    yield _emit("llm_response_created", start_payload)

    logger.info(
        "run.start run_id=%s session_id=%s message_id=%s provider=%s model=%s",
        params.run_id,
        params.session_id,
        params.message_id,
        provider_id,
        model,
    )

    try:
        from claude_agent_sdk import ClaudeAgentOptions, query as claude_query
    except ImportError as e:
        reason = "claude-agent-sdk 未安装"
        error_payload = {
            "code": "sdk_not_installed",
            "message": reason,
            "detail": str(e),
        }
        err_block = _ensure_block("error-1", "error")
        err_block["status"] = "failed"
        err_block["text"] = reason
        err_block["payload"] = error_payload
        yield _emit("error", error_payload)
        done_payload = _build_done_payload(
            status="failed",
            content=reason,
            blocks=_serialize_blocks(),
            sql="",
            execution=None,
            error=error_payload,
            resolved_database=params.resolved_database,
            provider_id=provider_id,
            model=model,
        )
        yield _emit("done", done_payload)
        return

    env_payload = _build_provider_env(provider_id, cfg)
    for key, value in env_payload.items():
        os.environ[key] = value

    project_cwd = resolve_agent_project_cwd()
    # 避免受用户主目录 ~/.claude/settings.json 干扰，provider/base_url 完全由后端配置与 env 控制
    setting_sources = ["project"]
    allowed_tools = ["Skill"]

    options = ClaudeAgentOptions(
        system_prompt=system_prompt,
        model=model,
        cwd=str(project_cwd),
        setting_sources=setting_sources,
        permission_mode="bypassPermissions",
        max_turns=max(1, int(cfg.agent_max_turns)),
        allowed_tools=allowed_tools,
        # 关键：开启 SDK partial stream，才能拿到 content_block_delta 等细粒度增量
        include_partial_messages=True,
        env={
            "ANTHROPIC_AUTH_TOKEN": env_payload.get("ANTHROPIC_AUTH_TOKEN", ""),
            "ANTHROPIC_API_KEY": env_payload.get("ANTHROPIC_API_KEY", ""),
            "ANTHROPIC_BASE_URL": env_payload.get("ANTHROPIC_BASE_URL", ""),
        },
        stderr=lambda line: logger.error(
            "sdk.stderr run_id=%s provider=%s model=%s %s",
            params.run_id,
            provider_id,
            model,
            str(line or "").rstrip(),
        ),
    )
    timeout_seconds = max(10, int(cfg.agent_timeout_seconds))
    logger.info(
        "run.config run_id=%s provider=%s model=%s cwd=%s setting_sources=%s allowed_tools=%s timeout_hint=%s base_url=%s",
        params.run_id,
        provider_id,
        model,
        project_cwd,
        ",".join(setting_sources),
        ",".join(allowed_tools),
        timeout_seconds,
        _safe_base_url(env_payload.get("ANTHROPIC_BASE_URL")),
    )

    try:
        # 在同一协程内做总超时，避免 wait_for 引发 SDK cancel scope 跨任务异常
        with anyio.fail_after(timeout_seconds):
            async for msg in claude_query(prompt=prompt, options=options):
                msg_type = type(msg).__name__
                msg_subtype = str(getattr(msg, "subtype", "") or "")
                if msg_type == "ResultMessage":
                    result_subtype = msg_subtype
                    result_raw = getattr(msg, "result", None)
                    if result_raw is not None:
                        result_error = _clip_text(_safe_stringify(result_raw), 2000)

                if msg_type == "StreamEvent":
                    raw_event = getattr(msg, "event", None)
                    if isinstance(raw_event, dict):
                        event_type = str(raw_event.get("type") or "").strip()
                        if event_type:
                            saw_partial_stream = True

                            # 同步维护 main/thinking 聚合文本，供 done/sql/execution 复用
                            if event_type == "content_block_start":
                                block_index = raw_event.get("index")
                                block_payload = raw_event.get("content_block")
                                if isinstance(block_payload, dict):
                                    block_id = _partial_block_id(block_index)
                                    mapped_type = _map_partial_block_type(str(block_payload.get("type") or ""))
                                    block = _ensure_block(block_id, mapped_type)
                                    block["type"] = mapped_type
                                    block["status"] = "streaming"
                                    block["payload"] = dict(block_payload)
                                    if mapped_type == "tool_use":
                                        if block_payload.get("id") is not None:
                                            block["tool_id"] = str(block_payload.get("id"))
                                        if block_payload.get("name") is not None:
                                            block["tool_name"] = str(block_payload.get("name"))
                                        block["input"] = block_payload.get("input")
                                    if mapped_type == "tool_result":
                                        if block_payload.get("tool_use_id") is not None:
                                            block["tool_id"] = str(block_payload.get("tool_use_id"))
                                        if block_payload.get("name") is not None:
                                            block["tool_name"] = str(block_payload.get("name"))
                                        block["output"] = block_payload.get("content")

                            if event_type == "content_block_delta":
                                delta = raw_event.get("delta")
                                block_index = raw_event.get("index")
                                block_id = _partial_block_id(block_index)
                                block = _ensure_block(block_id, "raw")
                                block["status"] = "streaming"
                                if isinstance(delta, dict):
                                    delta_type = str(delta.get("type") or "")
                                    if delta_type == "text_delta":
                                        piece = str(delta.get("text") or "")
                                        if piece:
                                            block["type"] = "main_text"
                                            block["text"] = str(block.get("text") or "") + piece
                                            main_text += piece
                                            text_started = True
                                    elif delta_type == "thinking_delta":
                                        piece = str(delta.get("thinking") or "")
                                        if piece:
                                            block["type"] = "thinking"
                                            block["text"] = str(block.get("text") or "") + piece
                                            thinking_text += piece
                                            thinking_started = True
                                    elif delta_type == "input_json_delta":
                                        partial = str(delta.get("partial_json") or "")
                                        if partial:
                                            payload = dict(block.get("payload") or {})
                                            payload["partial_json"] = str(payload.get("partial_json") or "") + partial
                                            block["payload"] = payload
                                            claude_type = _map_partial_block_type(
                                                str((payload.get("type") or payload.get("claude_type") or "")).lower()
                                            )
                                            if claude_type == "tool_use":
                                                block["type"] = "tool_use"
                                                block["input"] = payload["partial_json"]
                                            if claude_type == "tool_result":
                                                block["type"] = "tool_result"
                                                block["output"] = payload["partial_json"]

                            if event_type == "content_block_stop":
                                block_id = _partial_block_id(raw_event.get("index"))
                                block = _ensure_block(block_id, "raw")
                                if block.get("status") != "failed":
                                    block["status"] = "success"

                            if event_type == "message_stop":
                                for block in blocks.values():
                                    if block.get("status") in {"streaming", "pending", "in_progress"}:
                                        block["status"] = "success"

                            # 把 Claude 原生流式事件透传给前端（放在 payload）
                            yield _emit(event_type, raw_event)
                    continue

                if params.debug:
                    yield _emit(
                        "raw",
                        {
                            "kind": "stream_message",
                            "message_type": msg_type,
                            "subtype": msg_subtype,
                        },
                    )

                content = getattr(msg, "content", None)
                if isinstance(content, str):
                    merged, delta = _append_delta(main_text, content)
                    if delta:
                        if not text_started:
                            text_block = _ensure_block("main-text", "main_text")
                            text_block["status"] = "streaming"
                            text_started = True
                            yield _emit("text.start", {"block_id": "main-text"})
                        main_text = merged
                        text_block = _ensure_block("main-text", "main_text")
                        text_block["text"] = main_text
                        yield _emit("text.delta", {"block_id": "main-text", "text": delta})
                    continue

                if isinstance(content, list):
                    # 已有 partial stream 时，AssistantMessage 多为汇总快照，跳过避免重复事件
                    if saw_partial_stream and (main_text or thinking_text):
                        continue
                    for block in content:
                        block_type, block_text, block_payload = _extract_block(block)
                        lower_type = block_type.lower()

                        if "tooluse" in lower_type or lower_type in {"tool_use", "tooluseblock"}:
                            tool_id = str(block_payload.get("id") or block_payload.get("tool_id") or f"tool-{sequencer.seq + 1}")
                            tool_name = str(block_payload.get("name") or block_payload.get("tool_name") or "Skill")
                            tool_input = block_payload.get("input")

                            block_id = tool_block_by_tool_id.get(tool_id)
                            if not block_id:
                                block_id = f"tool-{tool_id}"
                                tool_block_by_tool_id[tool_id] = block_id
                            tool_block = _ensure_block(block_id, "tool")
                            tool_block["status"] = "pending"
                            tool_block["tool_id"] = tool_id
                            tool_block["tool_name"] = tool_name
                            tool_block["input"] = tool_input
                            tool_block["payload"] = {"tool_id": tool_id, "tool_name": tool_name, "input": tool_input}

                            yield _emit(
                                "tool.pending",
                                {
                                    "block_id": block_id,
                                    "tool_id": tool_id,
                                    "tool_name": tool_name,
                                    "input": tool_input,
                                },
                            )
                            tool_block["status"] = "in_progress"
                            yield _emit(
                                "tool.in_progress",
                                {
                                    "block_id": block_id,
                                    "tool_id": tool_id,
                                    "tool_name": tool_name,
                                },
                            )
                            continue

                        if "toolresult" in lower_type or lower_type in {"tool_result", "toolresultblock"}:
                            tool_id = str(block_payload.get("tool_use_id") or block_payload.get("tool_id") or block_payload.get("id") or "")
                            tool_output = block_payload.get("content")
                            block_id = tool_block_by_tool_id.get(tool_id) if tool_id else None
                            if not block_id:
                                block_id = f"tool-{tool_id or sequencer.seq + 1}"
                            tool_block = _ensure_block(block_id, "tool")
                            tool_block["status"] = "success"
                            if tool_id:
                                tool_block["tool_id"] = tool_id
                            tool_block["output"] = tool_output
                            if not tool_block.get("tool_name"):
                                tool_block["tool_name"] = str(block_payload.get("name") or "Skill")
                            tool_block["payload"] = {
                                "tool_id": tool_block.get("tool_id"),
                                "tool_name": tool_block.get("tool_name"),
                                "output": tool_output,
                            }

                            yield _emit(
                                "tool.complete",
                                {
                                    "block_id": block_id,
                                    "tool_id": tool_block.get("tool_id"),
                                    "tool_name": tool_block.get("tool_name"),
                                    "output": tool_output,
                                },
                            )
                            continue

                        if "thinking" in lower_type or "reasoning" in lower_type:
                            if block_text:
                                merged, delta = _append_delta(thinking_text, block_text)
                                if delta:
                                    if not thinking_started:
                                        thinking_block = _ensure_block("thinking-main", "thinking")
                                        thinking_block["status"] = "streaming"
                                        thinking_started = True
                                        yield _emit("thinking.start", {"block_id": "thinking-main"})
                                    thinking_text = merged
                                    thinking_block = _ensure_block("thinking-main", "thinking")
                                    thinking_block["text"] = thinking_text
                                    yield _emit("thinking.delta", {"block_id": "thinking-main", "text": delta})
                            continue

                        if block_text:
                            merged, delta = _append_delta(main_text, block_text)
                            if delta:
                                if not text_started:
                                    text_block = _ensure_block("main-text", "main_text")
                                    text_block["status"] = "streaming"
                                    text_started = True
                                    yield _emit("text.start", {"block_id": "main-text"})
                                main_text = merged
                                text_block = _ensure_block("main-text", "main_text")
                                text_block["text"] = main_text
                                yield _emit("text.delta", {"block_id": "main-text", "text": delta})

                        if params.debug and block_payload:
                            yield _emit(
                                "raw",
                                {
                                    "kind": "stream_block",
                                    "block_type": block_type,
                                    "preview": _clip_text(_safe_stringify(block_payload), 1200),
                                },
                            )

    except Exception as e:
        reason = _format_exception_reason(e)
        logger.exception(
            "run.error run_id=%s provider=%s model=%s reason=%s",
            params.run_id,
            provider_id,
            model,
            reason,
        )
        error_payload = {
            "code": "model_call_failed",
            "message": reason,
            "exception_type": e.__class__.__name__,
        }
        err_block = _ensure_block("error-1", "error")
        err_block["status"] = "failed"
        err_block["text"] = reason
        err_block["payload"] = error_payload
        yield _emit("error", error_payload)

        done_payload = _build_done_payload(
            status="failed",
            content=main_text.strip() or reason,
            blocks=_serialize_blocks(),
            sql="",
            execution=None,
            error=error_payload,
            resolved_database=params.resolved_database,
            provider_id=provider_id,
            model=model,
        )
        yield _emit("done", done_payload)
        return

    if thinking_started and not saw_partial_stream:
        thinking_block = _ensure_block("thinking-main", "thinking")
        thinking_block["status"] = "success"
        yield _emit("thinking.complete", {"block_id": "thinking-main", "text": thinking_text})

    if text_started and not saw_partial_stream:
        text_block = _ensure_block("main-text", "main_text")
        text_block["status"] = "success"
        yield _emit("text.complete", {"block_id": "main-text", "text": main_text})

    execution = None
    sql = _extract_sql_from_text(main_text)
    if sql and _is_read_only_sql(sql):
        execution_result = execute_sql(sql=sql, database=params.resolved_database)
        execution = execution_result.model_dump()

    status = "success"
    error_payload = None
    if result_subtype.startswith("error"):
        status = "failed"
        reason = _result_subtype_to_reason(result_subtype, result_error)
        error_payload = {
            "code": result_subtype,
            "message": reason,
            "detail": result_error,
        }
        err_block = _ensure_block("error-subtype", "error")
        err_block["status"] = "failed"
        err_block["text"] = reason
        err_block["payload"] = error_payload
        yield _emit("error", error_payload)

    blocks_payload = _serialize_blocks()
    final_content = main_text.strip() or "已完成。"

    yield _emit(
        "block_complete",
        {
            "status": status,
            "has_sql": bool(sql),
            "has_execution": bool(execution),
            "block_count": len(blocks_payload),
        },
    )

    done_payload = _build_done_payload(
        status=status,
        content=final_content,
        blocks=blocks_payload,
        sql=sql,
        execution=execution,
        error=error_payload,
        resolved_database=params.resolved_database,
        provider_id=provider_id,
        model=model,
    )
    yield _emit("done", done_payload)

    logger.info(
        "run.done run_id=%s status=%s provider=%s model=%s sql=%s blocks=%d",
        params.run_id,
        status,
        provider_id,
        model,
        bool(sql),
        len(blocks_payload),
    )


def _build_prompt(history: list[dict[str, str]], question: str) -> str:
    lines: list[str] = []
    for item in history:
        role = "用户" if item.get("role") == "user" else "助手"
        content = str(item.get("content") or "").strip()
        if not content:
            continue
        lines.append(f"[{role}]: {content}")
    lines.append(f"[用户]: {question}")
    return "\n\n".join(lines)


def _build_system_prompt(resolved_database: str | None) -> str:
    db_hint = resolved_database or "自动推断"
    return (
        "你是 DataAgent 智能问数助手。\\n"
        "- 优先使用 Skill 工具完成数据问题分析与 SQL 生成。\\n"
        "- 对闲聊/问候请直接自然回复，不要强制输出 JSON。\\n"
        "- 如果给出 SQL，请使用 ```sql 代码块``` 包裹。\\n"
        "- 仅允许只读查询（SELECT/WITH/SHOW/EXPLAIN/DESC）。\\n"
        f"- 当前候选数据库: {db_hint}。"
    )


def _extract_block(block: Any) -> tuple[str, str, dict[str, Any]]:
    if isinstance(block, dict):
        block_type = str(block.get("type") or "unknown")
        text = _extract_text_from_payload(block)
        return block_type, text, block

    block_type = str(getattr(block, "type", type(block).__name__) or "unknown")
    payload: dict[str, Any] = {}
    for key in ("id", "name", "input", "tool_id", "tool_use_id", "text", "thinking", "content", "result"):
        value = getattr(block, key, None)
        if value is not None:
            payload[key] = value

    text = _extract_text_from_payload(payload)
    if not text:
        maybe_text = getattr(block, "text", None)
        if isinstance(maybe_text, str):
            text = maybe_text
    return block_type, text, payload


def _extract_text_from_payload(payload: dict[str, Any]) -> str:
    for key in ("text", "thinking", "content", "result"):
        value = payload.get(key)
        if isinstance(value, str) and value.strip():
            return value
        if isinstance(value, list):
            parts: list[str] = []
            for item in value:
                if isinstance(item, str):
                    parts.append(item)
                elif isinstance(item, dict) and isinstance(item.get("text"), str):
                    parts.append(str(item.get("text")))
            if parts:
                return "\n".join(parts)
    return ""


def _append_delta(current: str, incoming: str) -> tuple[str, str]:
    new = str(incoming or "")
    if not new:
        return current, ""
    if not current:
        return new, new
    if new == current:
        return current, ""
    if new.startswith(current):
        return new, new[len(current):]
    if current.endswith(new):
        return current, ""
    return current + new, new


def _extract_sql_from_text(text: str) -> str:
    raw = str(text or "").strip()
    if not raw:
        return ""

    fence_match = SQL_FENCE_RE.search(raw)
    if fence_match:
        sql = fence_match.group(1).strip()
        return _clean_sql(sql)

    inline_match = SQL_INLINE_RE.search(raw)
    if inline_match:
        sql = inline_match.group(0).strip()
        return _clean_sql(sql)

    return ""


def _clean_sql(sql: str) -> str:
    lines = [line.rstrip() for line in str(sql or "").splitlines()]
    cleaned = "\n".join(lines).strip()
    if cleaned.endswith(";"):
        cleaned = cleaned[:-1].strip()
    return cleaned


def _is_read_only_sql(sql: str) -> bool:
    upper = str(sql or "").lstrip().upper()
    return upper.startswith(READ_ONLY_PREFIXES)


def _normalize_provider_id(raw: str | None, base_url: str | None = None) -> str:
    value = str(raw or "").strip().lower()
    if value in {"anthropic", "openrouter", "anyrouter", "anthropic_compatible"}:
        return value
    base = str(base_url or "").lower()
    if "openrouter.ai" in base:
        return "openrouter"
    if "anyrouter" in base or ".fcapp.run" in base:
        return "anyrouter"
    if base:
        return "anthropic_compatible"
    return "anthropic"


def _build_provider_env(provider_id: str, cfg) -> dict[str, str]:
    api_key = str(cfg.anthropic_api_key or "").strip()
    auth_token = str(cfg.anthropic_auth_token or "").strip()
    base_url = str(cfg.anthropic_base_url or "").strip()

    if provider_id == "openrouter":
        return {
            "ANTHROPIC_AUTH_TOKEN": auth_token or api_key,
            "ANTHROPIC_API_KEY": "",
            "ANTHROPIC_BASE_URL": base_url or "https://openrouter.ai/api",
        }

    if provider_id == "anyrouter":
        return {
            "ANTHROPIC_AUTH_TOKEN": auth_token or api_key,
            "ANTHROPIC_API_KEY": "",
            "ANTHROPIC_BASE_URL": base_url or "https://a-ocnfniawgw.cn-shanghai.fcapp.run",
        }

    if provider_id == "anthropic_compatible":
        return {
            "ANTHROPIC_AUTH_TOKEN": auth_token or api_key,
            "ANTHROPIC_API_KEY": "",
            "ANTHROPIC_BASE_URL": base_url,
        }

    return {
        "ANTHROPIC_AUTH_TOKEN": "",
        "ANTHROPIC_API_KEY": api_key,
        "ANTHROPIC_BASE_URL": base_url,
    }


def _default_model_for_provider(provider_id: str) -> str:
    if provider_id == "openrouter":
        return "anthropic/claude-sonnet-4.5"
    if provider_id == "anyrouter":
        return "claude-opus-4-6"
    return "claude-sonnet-4-20250514"


def _result_subtype_to_reason(subtype: str, detail: str) -> str:
    st = str(subtype or "").strip()
    if st == "error_max_turns":
        return "模型在最大轮次限制内未完成输出"
    if st.startswith("error"):
        return "模型会话异常结束"
    if detail:
        return detail
    return "模型会话未正常结束"


def _collect_exception_parts(error: Exception) -> list[str]:
    parts: list[str] = []
    seen: set[str] = set()
    current: BaseException | None = error
    depth = 0
    while current is not None and depth < 8:
        depth += 1
        text = str(current or "").strip() or current.__class__.__name__
        if text not in seen:
            seen.add(text)
            parts.append(text)
        current = current.__cause__ or current.__context__
    return parts


def _format_exception_reason(error: Exception) -> str:
    parts = _collect_exception_parts(error)
    if not parts:
        return error.__class__.__name__

    lowered = [p.lower() for p in parts]
    if any(("timeout" in x) or ("timed out" in x) or ("wouldblock" in x) for x in lowered):
        return "请求超时，模型服务在限定时间内未返回"
    if any("cancel" in x for x in lowered):
        return "请求被取消"
    if any(("ssl" in x) or ("certificate" in x) or ("handshake" in x) for x in lowered):
        return "模型网关 TLS 握手失败或证书无效"
    if any(("cloudflare" in x and "1001" in x) or ("error code: 1001" in x) for x in lowered):
        return "模型网关域名未解析（Cloudflare 1001）"

    return parts[0]


def _build_done_payload(
    *,
    status: str,
    content: str,
    blocks: list[dict[str, Any]],
    sql: str,
    execution: dict[str, Any] | None,
    error: dict[str, Any] | None,
    resolved_database: str | None,
    provider_id: str,
    model: str,
) -> dict[str, Any]:
    return {
        "status": status,
        "content": content,
        "blocks": blocks,
        "sql": sql,
        "execution": execution,
        "error": error,
        "resolved_database": resolved_database,
        "provider_id": provider_id,
        "model": model,
    }


def _safe_stringify(value: Any) -> str:
    if isinstance(value, str):
        return value
    try:
        return json.dumps(value, ensure_ascii=False)
    except Exception:
        return str(value)


def _clip_text(text: str, max_chars: int) -> str:
    raw = str(text or "")
    if len(raw) <= max_chars:
        return raw
    return raw[:max_chars] + f"...(truncated,total={len(raw)})"


def _safe_base_url(raw_url: str | None) -> str:
    text = str(raw_url or "").strip()
    if not text:
        return ""
    try:
        parsed = urlparse(text)
        if parsed.scheme and parsed.netloc:
            return f"{parsed.scheme}://{parsed.netloc}"
    except Exception:
        pass
    return text[:200]
