#!/usr/bin/env python3
"""stdio ↔ Streamable HTTP 的 MCP 转发桥。

Claude CLI 以 stdio 子进程方式启动本脚本，本脚本把收到的 JSON-RPC 帧原样 POST 到
``portal-mcp``，再把响应写回 stdout。

为什么需要它：生产基线 CLI 2.1.205 会把 HTTP ``404`` / 特定 session ``400`` 识别成
``McpSessionExpiredError``，并在 HTTP/claudeai-proxy transport 上把 ``-32000 Connection
closed`` 识别成同类错误；HTTP POST 还受 60s 默认超时约束。本桥让 CLI 使用 stdio，并把
远端 HTTP 失败归一化为 JSON-RPC ``-32603``，从而既不把 HTTP 404/400 泄漏成 stale-session
信号，也不进入 HTTP-only 的 connection-closed 与 60s fetch 路径。
详见 docs/design/2026-08-14-portal-mcp-stdio-bridge-design.md。

本桥是协议无关的转发器：不认识任何 portal 工具，也不复制任何工具 schema，工具契约仍然
只由 portal-mcp 定义。

环境变量（由 dataagent-backend 在构造 MCP server 配置时注入）：

- ``PORTAL_MCP_BRIDGE_URL``：portal-mcp 的 Streamable HTTP 端点（须以 ``/`` 结尾）
- ``PORTAL_MCP_BRIDGE_HEADERS``：附加请求头的 JSON 对象（前门 token、数据范围）
- ``PORTAL_MCP_BRIDGE_TIMEOUT_SECONDS``：单次请求读超时秒数
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
from typing import Any

import httpx

LOG_PREFIX = "portal-mcp-bridge"
# 转发失败一律回 JSON-RPC 内部错误：CLI 会把它渲染成工具级错误交给模型，模型可以改写
# 重试，整个 run 不会中断。刻意不复用 HTTP 404/特定 session 400，也不复用
# -32000/"Connection closed"——这些都会触发 CLI 的 stale-session 判定。
INTERNAL_ERROR_CODE = -32603
# 只有「请求尚未送达服务端」的连接类错误才重试，因此对写工具重试同样安全。
CONNECT_RETRY_BACKOFF_SECONDS = (0.2, 0.5)
DEFAULT_TIMEOUT_SECONDS = 600.0
CONNECT_TIMEOUT_SECONDS = 10.0
# 服务端异常体只用于诊断，截断后放进 error.message，避免把整页 HTML 灌进模型上下文。
ERROR_BODY_PREVIEW_CHARS = 500
MCP_PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"


def _log(message: str) -> None:
    # stdout 是 MCP 协议通道，任何非协议输出都会破坏帧。日志只能走 stderr，
    # CLI 以 stderr:"pipe" 收集后写入 MCP 日志。
    print(f"[{LOG_PREFIX}] {message}", file=sys.stderr, flush=True)


def _load_headers(raw: str) -> dict[str, str]:
    raw = str(raw or "").strip()
    if not raw:
        raise ValueError("缺少 PORTAL_MCP_BRIDGE_HEADERS")
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ValueError(f"PORTAL_MCP_BRIDGE_HEADERS 不是合法 JSON: {exc}") from exc
    if not isinstance(parsed, dict) or not parsed:
        raise ValueError("PORTAL_MCP_BRIDGE_HEADERS 必须是非空 JSON 对象")
    return {str(key): str(value) for key, value in parsed.items()}


def _load_timeout(raw: str) -> float:
    try:
        value = float(str(raw or "").strip())
    except ValueError:
        return DEFAULT_TIMEOUT_SECONDS
    return value if value > 0 else DEFAULT_TIMEOUT_SECONDS


def _error_response(message_id: Any, message: str) -> dict[str, Any]:
    return {
        "jsonrpc": "2.0",
        "id": message_id,
        "error": {"code": INTERNAL_ERROR_CODE, "message": message},
    }


def _parse_sse_payloads(body: str) -> list[Any]:
    """从 SSE 响应体里抽出 ``data:`` 负载。

    Streamable HTTP 规范允许服务端用 JSON 或 SSE 回复 POST。portal-mcp 固定
    ``json_response=True``，此分支是为了让桥对规范内的另一种合法响应也成立。
    """
    payloads: list[Any] = []
    data_lines: list[str] = []

    def flush_event() -> None:
        if not data_lines:
            return
        chunk = "\n".join(data_lines)
        try:
            payloads.append(json.loads(chunk))
        except json.JSONDecodeError as exc:
            _log(f"SSE data 段不是合法 JSON，已跳过: {exc}")

    for line in body.splitlines():
        if not line:
            flush_event()
            data_lines.clear()
            continue
        if not line.startswith("data:"):
            continue
        value = line[len("data:") :]
        if value.startswith(" "):
            value = value[1:]
        data_lines.append(value)
    flush_event()
    return payloads


def _responses_from_http(response: httpx.Response) -> list[Any]:
    content_type = str(response.headers.get("content-type") or "").split(";")[0].strip().lower()
    if content_type == "text/event-stream":
        return _parse_sse_payloads(response.text)
    body = response.text.strip()
    if not body:
        # 202 + 空体 = 通知/响应已被接收，没有需要回写的内容。
        return []
    payload = json.loads(body)
    return payload if isinstance(payload, list) else [payload]


class PortalMcpBridge:
    def __init__(
        self,
        client: httpx.AsyncClient,
        url: str,
        headers: dict[str, str] | None = None,
    ) -> None:
        self._client = client
        self._url = url
        self._headers = {
            "Content-Type": "application/json",
            # 规范要求客户端同时声明两种响应形态。
            "Accept": "application/json, text/event-stream",
            **(headers or {}),
        }
        self._stdout_lock = asyncio.Lock()
        self._session_header_warned = False
        self._protocol_version: str | None = None

    async def _write(self, payload: Any) -> None:
        line = json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n"
        async with self._stdout_lock:
            await asyncio.to_thread(self._write_blocking, line)

    async def _write_error(self, message_id: Any, message: str) -> None:
        try:
            await self._write(_error_response(message_id, message))
        except Exception as exc:  # stdout 已不可写时只能记录，不能再递归回复。
            _log(f"写入错误响应失败: {exc.__class__.__name__}: {exc}")

    @staticmethod
    def _write_blocking(line: str) -> None:
        sys.stdout.write(line)
        sys.stdout.flush()

    def _request_headers(self, message: Any) -> dict[str, str]:
        headers = dict(self._headers)
        method = str(message.get("method") or "") if isinstance(message, dict) else ""
        # The protocol-version header is defined for requests after negotiation.
        # A repeated initialize must start clean instead of carrying a stale version.
        if self._protocol_version and method != "initialize":
            headers[MCP_PROTOCOL_VERSION_HEADER] = self._protocol_version
        return headers

    def _capture_protocol_version(self, method: str, payloads: list[Any]) -> None:
        if method != "initialize":
            return
        for payload in payloads:
            if not isinstance(payload, dict):
                continue
            result = payload.get("result")
            if not isinstance(result, dict):
                continue
            value = result.get("protocolVersion")
            if isinstance(value, str) and value.strip():
                self._protocol_version = value.strip()
                return

    async def _post(self, message: Any) -> httpx.Response:
        headers = self._request_headers(message)
        attempts = len(CONNECT_RETRY_BACKOFF_SECONDS) + 1
        for attempt in range(attempts):
            try:
                return await self._client.post(self._url, headers=headers, json=message)
            except (httpx.ConnectError, httpx.ConnectTimeout) as exc:
                if attempt == attempts - 1:
                    raise
                delay = CONNECT_RETRY_BACKOFF_SECONDS[attempt]
                _log(f"连接 portal-mcp 失败({exc.__class__.__name__})，{delay}s 后重试")
                await asyncio.sleep(delay)
        raise AssertionError("unreachable")

    async def handle_message(self, message: Any) -> None:
        has_id = isinstance(message, dict) and "id" in message
        message_id = message.get("id") if isinstance(message, dict) else None
        method = str(message.get("method") or "") if isinstance(message, dict) else ""
        # 是否已经为本请求写出过响应帧，用于避免同一个 id 收到两条响应。
        answered = False
        if method == "initialize":
            # A repeated negotiation must not leave the previous version active when
            # the new initialize fails or returns an invalid result.
            self._protocol_version = None
        try:
            response = await self._post(message)
        except httpx.HTTPError as exc:
            _log(f"转发 {method or '<unknown>'} 失败: {exc.__class__.__name__}: {exc}")
            if has_id:
                await self._write_error(
                    message_id, f"portal-mcp 请求失败: {exc.__class__.__name__}: {exc}"
                )
            return
        except Exception as exc:
            _log(f"转发 {method or '<unknown>'} 失败: {exc.__class__.__name__}: {exc}")
            if has_id:
                await self._write_error(
                    message_id, f"portal-mcp 桥内部错误: {exc.__class__.__name__}: {exc}"
                )
            return
        try:
            if not self._session_header_warned and "mcp-session-id" in response.headers:
                # portal-mcp 固化为 stateless_http=True，正常不会下发该头。出现即说明服务端
                # 配置漂移，需要人工处理；桥不引入第二条有状态分支来掩盖它。
                self._session_header_warned = True
                _log("portal-mcp 返回了 mcp-session-id，服务端可能已不是无状态模式")

            if response.status_code >= 400:
                preview = response.text[:ERROR_BODY_PREVIEW_CHARS]
                _log(f"portal-mcp 返回 HTTP {response.status_code}: {preview}")
                if has_id:
                    await self._write_error(
                        message_id, f"portal-mcp 返回 HTTP {response.status_code}: {preview}"
                    )
                return

            payloads = _responses_from_http(response)
            if has_id and not payloads:
                await self._write_error(message_id, "portal-mcp 对请求返回了空响应")
                return

            self._capture_protocol_version(method, payloads)
            # 先原样转发所有帧，再判断本请求是否拿到了响应：响应体里可能同时夹带服务端
            # 通知，本请求没等到响应不是丢掉那些通知的理由。
            for payload in payloads:
                if (
                    has_id
                    and isinstance(payload, dict)
                    and "id" in payload
                    and payload.get("id") == message_id
                ):
                    answered = True
                await self._write(payload)
            if has_id and not answered:
                await self._write_error(message_id, "portal-mcp 未返回对应请求的 JSON-RPC 响应")
        except json.JSONDecodeError as exc:
            _log(f"portal-mcp 响应体不是合法 JSON: {exc}")
            if has_id and not answered:
                await self._write_error(message_id, f"portal-mcp 响应体不是合法 JSON: {exc}")
        except Exception as exc:
            _log(f"处理 {method or '<unknown>'} 失败: {exc.__class__.__name__}: {exc}")
            # answered 守卫：响应已经写出后再补一条 error 会让同一个 id 出现两个响应。
            if has_id and not answered:
                await self._write_error(
                    message_id, f"portal-mcp 桥内部错误: {exc.__class__.__name__}: {exc}"
                )

    async def run(self, stdin: Any) -> None:
        pending: set[asyncio.Task[None]] = set()

        def observe_task(task: asyncio.Task[None]) -> None:
            pending.discard(task)
            if task.cancelled():
                return
            exc = task.exception()
            if exc is not None:
                _log(f"消息任务异常退出: {exc.__class__.__name__}: {exc}")

        while True:
            try:
                line = await asyncio.to_thread(stdin.readline)
            except (OSError, ValueError) as exc:
                # 读失败和解码失败必须区别对待：一帧解码失败可以跳过继续读下一帧，但字节流
                # 本身读不动之后没法重新对齐，`continue` 会变成 100% CPU 的空转死循环
                # （EBADF 这类错误是持续性的），比进程直接退出更糟。这里按 EOF 处理：
                # 收尾在途请求后干净退出，CLI 侧能明确观察到 stdio server 消失。
                # ValueError 覆盖 stdin 被关闭后的 "readline of closed file"。
                _log(f"stdin 读取失败，桥退出: {exc.__class__.__name__}: {exc}")
                break
            if not line:
                break
            try:
                text = line.decode("utf-8").strip() if isinstance(line, bytes) else line.strip()
            except UnicodeError as exc:
                _log(f"stdin 帧不是合法 UTF-8，已丢弃: {exc}")
                continue
            if not text:
                continue
            try:
                message = json.loads(text)
            except (json.JSONDecodeError, UnicodeError) as exc:
                # CLI 不会发出非法帧；真出现时丢弃并记录，回复一个 id 未知的错误只会
                # 让对端更困惑。
                _log(f"收到非法 JSON 帧，已丢弃: {exc}")
                continue
            # 每帧一个 task：JSON-RPC 按 id 匹配，允许乱序回复，并行工具调用不必互相等待。
            task = asyncio.create_task(self.handle_message(message))
            pending.add(task)
            task.add_done_callback(observe_task)
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)


async def _main() -> int:
    url = str(os.getenv("PORTAL_MCP_BRIDGE_URL") or "").strip()
    if not url:
        _log("缺少 PORTAL_MCP_BRIDGE_URL，无法启动")
        return 2

    try:
        headers = _load_headers(os.getenv("PORTAL_MCP_BRIDGE_HEADERS", ""))
    except ValueError as exc:
        _log(f"{exc}，无法启动")
        return 2

    timeout = _load_timeout(os.getenv("PORTAL_MCP_BRIDGE_TIMEOUT_SECONDS", ""))
    limits = httpx.Limits(max_keepalive_connections=0)
    async with httpx.AsyncClient(
        timeout=httpx.Timeout(timeout, connect=CONNECT_TIMEOUT_SECONDS),
        # 禁用连接复用：空闲连接被服务端回收后复用即失败，是 Streamable HTTP 客户端最
        # 常见的伪 "session" 故障源（encode/httpx#2056）。容器内新建连接的成本可忽略，
        # 换取服务端 keepalive 取值不再参与正确性。
        limits=limits,
    ) as client:
        bridge = PortalMcpBridge(client, url, headers)
        # 从二进制流逐行解码，确保一个非法 UTF-8 帧不会让 TextIOWrapper 预读并丢弃
        # 后续合法帧。
        await bridge.run(getattr(sys.stdin, "buffer", sys.stdin))
    return 0


if __name__ == "__main__":  # pragma: no cover - 进程入口
    try:
        sys.exit(asyncio.run(_main()))
    except KeyboardInterrupt:
        sys.exit(0)
