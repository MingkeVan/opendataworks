from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import sys
from dataclasses import asdict
from pathlib import Path
from typing import Any

from core.task_executor import TaskExecutionInput, TaskExecutionResult, _execute_task_stream_local


logging.basicConfig(level=logging.INFO, stream=sys.stderr)
logger = logging.getLogger(__name__)


def _result_error_code(result: TaskExecutionResult) -> str:
    error = result.error
    if isinstance(error, dict):
        return str(error.get("code") or "")
    return ""


def _load_payload() -> dict[str, Any]:
    raw = str(os.environ.get("DATAAGENT_TASK_PAYLOAD_B64") or "").strip()
    if raw:
        return json.loads(base64.b64decode(raw).decode("utf-8"))
    stdin_payload = sys.stdin.buffer.read()
    if not stdin_payload:
        raise RuntimeError("task payload is required on stdin")
    return json.loads(stdin_payload.decode("utf-8"))


async def _execute_and_emit(params: TaskExecutionInput) -> TaskExecutionResult:
    async def emit(record: dict[str, Any]) -> None:
        print(json.dumps({"type": "record", "record": record}, ensure_ascii=False), flush=True)

    try:
        logger.info(
            "sandbox.task.start task_id=%s topic_id=%s provider_id=%s model=%s "
            "execution_mode=%s resume_session=%s timeout_seconds=%s "
            "sql_read_timeout_seconds=%s workspace=%s",
            params.task_id,
            params.topic_id,
            params.provider_id,
            params.model,
            params.execution_mode,
            bool(params.resume_session_id),
            params.timeout_seconds,
            params.sql_read_timeout_seconds,
            Path.cwd(),
        )
        result = await _execute_task_stream_local(
            params,
            emit=emit,
            is_cancel_requested=lambda: False,
            prepared_workspace_dir=Path.cwd(),
        )
    except Exception as exc:
        logger.exception("sandbox task crashed task_id=%s", params.task_id)
        result = TaskExecutionResult(
            task_status="error",
            content=str(exc),
            error={"code": "sandbox_task_error", "message": str(exc)},
            provider_id=params.provider_id,
            model=params.model,
        )

    logger.info(
        "sandbox.task.result task_id=%s topic_id=%s task_status=%s error_code=%s "
        "content_len=%s session_id_set=%s",
        params.task_id,
        params.topic_id,
        result.task_status,
        _result_error_code(result),
        len(result.content or ""),
        bool(result.session_id),
    )
    print(json.dumps({"type": "result", "result": asdict(result)}, ensure_ascii=False), flush=True)
    return result


async def _main() -> int:
    params = TaskExecutionInput(**_load_payload())
    result = await _execute_and_emit(params)
    return 0 if result.task_status != "error" else 1


async def _stdin_reader() -> asyncio.StreamReader:
    loop = asyncio.get_event_loop()
    reader = asyncio.StreamReader()
    protocol = asyncio.StreamReaderProtocol(reader)
    await loop.connect_read_pipe(lambda: protocol, sys.stdin)
    return reader


async def _serve_loop() -> int:
    """Serve multiple newline-delimited payloads on a long-lived child.

    The runner switches the child into this mode by setting
    ``DATAAGENT_SANDBOX_CHILD_IDLE_TIMEOUT``. Each payload runs one task and
    prints exactly one ``result`` line. The child exits on stdin EOF or when no
    new payload arrives within the idle timeout, which protects against an
    orphaned child outliving its runner.
    """
    raw_timeout = str(os.environ.get("DATAAGENT_SANDBOX_CHILD_IDLE_TIMEOUT") or "").strip()
    try:
        idle_timeout = float(raw_timeout)
    except ValueError:
        idle_timeout = 0.0
    timeout = idle_timeout if idle_timeout > 0 else None

    reader = await _stdin_reader()
    while True:
        try:
            line = await asyncio.wait_for(reader.readline(), timeout=timeout)
        except asyncio.TimeoutError:
            logger.info("sandbox child idle timeout reached; exiting serve loop")
            return 0
        if not line:
            return 0
        text = line.decode("utf-8", errors="replace").strip()
        if not text:
            continue
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            logger.warning("sandbox child received non-json payload line; skipping")
            continue
        params = TaskExecutionInput(**payload)
        logger.info(
            "sandbox.child.payload_received task_id=%s topic_id=%s execution_mode=%s",
            params.task_id,
            params.topic_id,
            params.execution_mode,
        )
        await _execute_and_emit(params)


if __name__ == "__main__":
    if str(os.environ.get("DATAAGENT_SANDBOX_CHILD_IDLE_TIMEOUT") or "").strip():
        raise SystemExit(asyncio.run(_serve_loop()))
    raise SystemExit(asyncio.run(_main()))
