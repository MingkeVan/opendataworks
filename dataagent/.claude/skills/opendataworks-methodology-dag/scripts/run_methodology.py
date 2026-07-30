#!/usr/bin/env python3
"""Execute one registered methodology and emit the standard tool contract.

The output uses ``kind=sql_execution`` so the existing result renderer and the
existing failure-attribution vocabulary apply unchanged; the methodology header
and the per-node trace ride along as extra fields.
"""

from __future__ import annotations

import argparse
import json
import os
import time
from pathlib import Path
from typing import Any, Dict, List, Mapping

from engine import (
    DEFAULT_NODE_TIMEOUT_SECONDS,
    DEFAULT_TOTAL_TIMEOUT_SECONDS,
    MethodologyEngine,
    MethodologyError,
)
from registry import (
    REGISTRY_DIR,
    RegistryError,
    coerce_params,
    load_registry,
    parse_params_argument,
    print_json,
)
from validate_methodology import validate_methodology

TOOL_LABEL = "方法论执行"


def _env_int(name: str, fallback: int) -> int:
    try:
        return int(str(os.getenv(name) or "").strip() or fallback)
    except ValueError:
        return fallback


def _header(methodology: Mapping[str, Any], params: Mapping[str, Any]) -> Dict[str, Any]:
    return {
        "id": methodology.get("id"),
        "version": methodology.get("version"),
        "name_zh": methodology.get("name_zh"),
        "caliber": methodology.get("caliber"),
        "owner": methodology.get("owner"),
        "params": dict(params),
    }


def _failure(
    methodology: Mapping[str, Any] | None,
    params: Mapping[str, Any],
    *,
    message: str,
    error_code: str,
    failure_attribution: List[str],
    stop_reason: str,
    retryable: bool = False,
    trace: List[Dict[str, Any]] | None = None,
    duration_ms: int = 0,
) -> Dict[str, Any]:
    return {
        "kind": "sql_execution",
        "tool_label": TOOL_LABEL,
        "methodology": _header(methodology or {}, params),
        "sql": None,
        "columns": [],
        "rows": [],
        "row_count": 0,
        "has_more": False,
        "duration_ms": duration_ms,
        "truncated_by_size": False,
        "notice": None,
        "summary": f"方法论执行失败：{message}",
        "error": message,
        "result_state": "failed",
        "error_code": error_code,
        "failure_attribution": failure_attribution,
        "retryable": retryable,
        "stop_reason": stop_reason,
        "trace": {"executed": len(trace or []), "nodes": list(trace or [])},
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="执行一个已注册的分析方法论")
    parser.add_argument("--id", required=True, help="方法论 id")
    parser.add_argument("--params", default="", help="参数 JSON 对象")
    parser.add_argument("--registry-dir", default=str(REGISTRY_DIR))
    parser.add_argument("--mock", default="", help="mock 模式：节点名到结果的 JSON 文件，不访问任何数据存储")
    parser.add_argument(
        "--total-timeout",
        type=float,
        default=float(_env_int("DATAAGENT_METHODOLOGY_TOTAL_TIMEOUT_SECONDS", DEFAULT_TOTAL_TIMEOUT_SECONDS)),
    )
    parser.add_argument(
        "--node-timeout",
        type=float,
        default=float(_env_int("DATAAGENT_SQL_READ_TIMEOUT_SECONDS", DEFAULT_NODE_TIMEOUT_SECONDS)),
    )
    parser.add_argument("--limit", type=int, default=_env_int("DATAAGENT_QUERY_LIMIT", 1000))
    args = parser.parse_args()

    supplied: Dict[str, Any] = {}
    methodology: Dict[str, Any] | None = None
    started = time.monotonic()

    try:
        registry = load_registry(Path(args.registry_dir))
        methodology = registry.get(args.id)
        if methodology is None:
            available = ", ".join(sorted(registry)) or "（注册表为空）"
            print_json(
                _failure(
                    None,
                    {},
                    message=f"注册表中没有方法论 `{args.id}`",
                    error_code="methodology_not_found",
                    failure_attribution=["invalid_methodology"],
                    stop_reason=f"可用方法论：{available}。未命中时请回落到平台工具的常规问数链路，不要猜测 id。",
                )
            )
            return 0

        report = validate_methodology(methodology, registry=registry)
        if not report.ok:
            print_json(
                _failure(
                    methodology,
                    {},
                    message="方法论定义未通过静态校验：" + "；".join(report.errors),
                    error_code="methodology_invalid",
                    failure_attribution=["invalid_methodology"],
                    stop_reason="方法论工件本身有问题，需要修正注册表定义，不要重试。",
                )
            )
            return 0

        supplied = parse_params_argument(args.params)
        params, missing = coerce_params(list(methodology.get("params") or []), supplied)
        if missing:
            print_json(
                _failure(
                    methodology,
                    params,
                    message=f"缺少必填参数：{', '.join(missing)}",
                    error_code="param_missing",
                    failure_attribution=["missing_parameter"],
                    stop_reason=f"请只向用户追问这些参数槽位：{', '.join(missing)}；不要自行假定口径。",
                )
            )
            return 0

        mock: Dict[str, Any] = {}
        if args.mock:
            mock = json.loads(Path(args.mock).read_text(encoding="utf-8"))

        engine = MethodologyEngine(
            methodology,
            params,
            registry=registry,
            mock=mock,
            total_timeout=args.total_timeout,
            node_timeout=args.node_timeout,
            query_limit=args.limit,
        )
        result = engine.run()

    except MethodologyError as exc:
        print_json(
            _failure(
                methodology,
                supplied,
                message=str(exc),
                error_code=exc.error_code,
                failure_attribution=exc.failure_attribution or ["tool_error"],
                stop_reason=exc.stop_reason,
                retryable=exc.retryable,
                duration_ms=int((time.monotonic() - started) * 1000),
            )
        )
        return 0
    except RegistryError as exc:
        print_json(
            _failure(
                methodology,
                supplied,
                message=str(exc),
                error_code=getattr(exc, "error_code", "methodology_invalid"),
                failure_attribution=["invalid_methodology"],
                stop_reason=str(exc),
            )
        )
        return 0
    except (OSError, json.JSONDecodeError) as exc:
        print_json(
            _failure(
                methodology,
                supplied,
                message=f"读取输入失败: {exc}",
                error_code="methodology_invalid",
                failure_attribution=["tool_error"],
                stop_reason="输入文件无法读取或解析。",
            )
        )
        return 0

    duration_ms = int((time.monotonic() - started) * 1000)
    pruned = sum(1 for entry in engine.trace if entry.get("pruned_branch"))
    if not result.rows:
        execution_detail = {
            "result_state": "empty_result",
            "error_code": "empty_result",
            "failure_attribution": ["empty_result"],
            "retryable": False,
            "stop_reason": (
                "方法论已按注册口径成功执行但返回 0 行；说明当前口径下无数据，"
                "请说明口径与空结果，不要改写口径或换表试探。"
            ),
        }
        summary = "方法论执行成功，但返回 0 行结果"
    elif result.truncated_by_size:
        execution_detail = {
            "result_state": "success",
            "error_code": "result_truncated",
            "failure_attribution": [],
            "retryable": False,
            "stop_reason": result.notice or "结果过大已按体积截断；请缩小参数范围。",
        }
        summary = f"返回 {len(result.rows)} 行结果（已按体积截断）"
    else:
        execution_detail = {
            "result_state": "success",
            "error_code": None,
            "failure_attribution": [],
            "retryable": False,
            "stop_reason": "",
        }
        summary = f"返回 {len(result.rows)} 行结果"

    print_json(
        {
            "kind": "sql_execution",
            "tool_label": TOOL_LABEL,
            "methodology": _header(methodology, params),
            "engine": result.engine,
            "database": result.database,
            "sql": result.sql,
            "columns": result.columns,
            "rows": result.rows,
            "row_count": len(result.rows),
            "has_more": result.has_more,
            "duration_ms": duration_ms,
            "truncated_by_size": result.truncated_by_size,
            "notice": result.notice,
            "summary": summary,
            "error": None,
            "trace": {"executed": len(engine.trace), "pruned": pruned, "nodes": engine.trace},
            **execution_detail,
        }
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
