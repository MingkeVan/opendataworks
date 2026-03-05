from __future__ import annotations

"""
FastAPI 路由 — NL2SQL 服务 HTTP API
"""

import logging
import uuid

from fastapi import APIRouter, HTTPException

from config import get_settings, update_settings
from core.nl2sql_agent import generate_sql
from core.semantic_layer import get_semantic_layer
from core.session_store import get_session_store
from core.sql_executor import execute_sql
from core.tool_runtime import ToolRuntimeError, invoke_tool, list_tools
from models.schemas import (
    ExecuteSqlRequest,
    NL2SqlRequest,
    NL2SqlResponse,
    SettingsResponse,
    SettingsUpdateRequest,
    SqlExecutionResult,
    ToolInvokeRequest,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/nl2sql")


# ---------- NL2SQL 核心 ----------

@router.post("/generate", response_model=NL2SqlResponse)
async def api_generate_sql(request: NL2SqlRequest):
    """自然语言 → SQL"""
    try:
        result = await generate_sql(request)
        return result
    except Exception as e:
        logger.exception("generate_sql failed")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/execute", response_model=SqlExecutionResult)
async def api_execute_sql(request: ExecuteSqlRequest):
    """执行 SQL"""
    try:
        result = execute_sql(
            sql=request.sql,
            database=request.database,
            limit=request.limit,
        )
        return result
    except Exception as e:
        logger.exception("execute_sql failed")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/ask")
async def api_ask(request: NL2SqlRequest):
    """
    一站式接口: 生成 SQL + 自动执行 + 返回结果
    前端可以用这个接口实现 "输入问题 → 看到结果" 的完整流程
    """
    try:
        # 1. 生成 SQL
        gen_result = await generate_sql(request)
        assistant_sql = (gen_result.sql or "").strip()
        assistant_explanation = (gen_result.explanation or "").strip()
        if not assistant_explanation:
            if assistant_sql:
                assistant_explanation = "已生成 SQL，可直接执行。"
            else:
                assistant_explanation = "已完成分析，但未生成可展示结果，请换个问法重试。"

        response: dict = {
            "question": request.question,
            "sql": assistant_sql,
            "explanation": assistant_explanation,
            "thinking_steps": [s.model_dump() for s in gen_result.thinking_steps],
            "matched_tables": gen_result.matched_tables,
            "matched_rules": gen_result.matched_rules,
            "confidence": gen_result.confidence,
            "execution": None,
        }

        # 2. 如果生成了有效 SQL, 自动执行
        if assistant_sql:
            exec_result = execute_sql(
                sql=assistant_sql,
                database=request.database,
            )
            response["execution"] = exec_result.model_dump()
            if exec_result.error and not response["explanation"]:
                response["explanation"] = f"SQL 生成成功，但执行失败：{exec_result.error}"

        return response

    except Exception as e:
        logger.exception("ask failed")
        raise HTTPException(status_code=500, detail=str(e))


# ---------- 语义层管理 ----------

@router.post("/reload")
async def api_reload_semantic(database: str | None = None):
    """重新加载语义层索引"""
    try:
        sl = get_semantic_layer()
        sl.reload(database)
        return {
            "status": "ok",
            "ddl_count": len(sl.ddl_index.docs),
            "qa_count": len(sl.qa_index.docs),
            "rule_count": len(sl.rule_index.docs),
            "semantic_count": len(sl.semantic_entries),
            "skills_sync": sl.skills_sync_info,
        }
    except Exception as e:
        logger.exception("reload failed")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/schema")
async def api_list_schema(database: str | None = None):
    """列出已索引的表结构概览"""
    sl = get_semantic_layer()
    if not sl._loaded:
        try:
            sl.load(database)
        except Exception:
            pass

    tables = []
    for doc in sl.ddl_index.docs:
        meta = doc.get("meta")
        if meta:
            tables.append({
                "table_name": meta.table_name,
                "table_comment": meta.table_comment,
                "db_name": meta.db_name,
                "layer": meta.layer,
                "field_count": len(meta.fields),
            })
    return {"tables": tables, "total": len(tables)}


# ---------- 设置管理 ----------

@router.get("/settings", response_model=SettingsResponse)
async def api_get_settings():
    """获取当前配置（隐去密码）"""
    cfg = get_settings()
    return SettingsResponse(
        anthropic_api_key_set=bool(cfg.anthropic_api_key),
        claude_model=cfg.claude_model,
        mysql_host=cfg.mysql_host,
        mysql_port=cfg.mysql_port,
        mysql_database=cfg.mysql_database,
        knowledge_mysql_database=cfg.knowledge_mysql_database,
        session_mysql_database=cfg.session_mysql_database,
        doris_host=cfg.doris_host,
        doris_port=cfg.doris_port,
        doris_database=cfg.doris_database,
        tool_runtime_mode=cfg.tool_runtime_mode,
        mcp_http_endpoint=cfg.mcp_http_endpoint,
        mcp_http_timeout_seconds=cfg.mcp_http_timeout_seconds,
        skills_output_dir=cfg.skills_output_dir,
    )


@router.put("/settings", response_model=SettingsResponse)
async def api_update_settings(request: SettingsUpdateRequest):
    """更新配置（前端 Settings 页面调用）"""
    patch = request.model_dump(exclude_none=True)
    if not patch:
        raise HTTPException(status_code=400, detail="No fields to update")

    cfg = update_settings(patch)
    return SettingsResponse(
        anthropic_api_key_set=bool(cfg.anthropic_api_key),
        claude_model=cfg.claude_model,
        mysql_host=cfg.mysql_host,
        mysql_port=cfg.mysql_port,
        mysql_database=cfg.mysql_database,
        knowledge_mysql_database=cfg.knowledge_mysql_database,
        session_mysql_database=cfg.session_mysql_database,
        doris_host=cfg.doris_host,
        doris_port=cfg.doris_port,
        doris_database=cfg.doris_database,
        tool_runtime_mode=cfg.tool_runtime_mode,
        mcp_http_endpoint=cfg.mcp_http_endpoint,
        mcp_http_timeout_seconds=cfg.mcp_http_timeout_seconds,
        skills_output_dir=cfg.skills_output_dir,
    )


@router.get("/tools")
async def api_list_tools():
    cfg = get_settings()
    return {
        "runtime_mode": cfg.tool_runtime_mode,
        "mcp_http_configured": bool(cfg.mcp_http_endpoint),
        "tools": list_tools(),
    }


@router.post("/tools/invoke")
async def api_invoke_tool(request: ToolInvokeRequest):
    try:
        result = invoke_tool(request.tool_name, request.payload)
        return {"tool_name": request.tool_name, "result": result}
    except ToolRuntimeError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.exception("tool invoke failed")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/skills/sync")
async def api_sync_skills(database: str | None = None):
    try:
        sl = get_semantic_layer()
        if not sl._loaded:
            sl.load(database)
        elif database:
            sl.reload(database)
        sync_info = sl.sync_skills()
        return {"status": "ok", **sync_info}
    except Exception as e:
        logger.exception("skills sync failed")
        raise HTTPException(status_code=500, detail=str(e))


# ---------- 会话管理（MySQL 持久化，独立 schema） ----------

MAX_HISTORY_MESSAGES = 20
MAX_HISTORY_CONTENT_CHARS = 1200


def _build_history_content(message: dict) -> str:
    role = message.get("role")
    if role == "assistant":
        explanation = (message.get("explanation") or "").strip()
        sql = (message.get("sql") or "").strip()
        if explanation and sql:
            content = f"{explanation}\nSQL:\n{sql}"
        elif explanation:
            content = explanation
        elif sql:
            content = f"SQL:\n{sql}"
        else:
            content = (message.get("content") or "").strip()
    else:
        content = (message.get("content") or "").strip()

    if len(content) > MAX_HISTORY_CONTENT_CHARS:
        return content[:MAX_HISTORY_CONTENT_CHARS] + "..."
    return content


def _get_store():
    store = get_session_store()
    try:
        store.init_schema()
        return store
    except Exception as e:
        logger.exception("session store init failed")
        raise HTTPException(status_code=500, detail=f"Session store unavailable: {str(e)}")


@router.post("/sessions")
async def api_create_session(title: str = "新会话"):
    """创建会话"""
    session_id = str(uuid.uuid4())
    store = _get_store()
    return store.create_session(session_id=session_id, title=title)


@router.get("/sessions")
async def api_list_sessions(include_messages: bool = False):
    """列出所有会话"""
    store = _get_store()
    return store.list_sessions(include_messages=include_messages)


@router.get("/sessions/{session_id}")
async def api_get_session(session_id: str):
    """获取会话详情"""
    store = _get_store()
    session = store.get_session(session_id)
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")
    return session


@router.delete("/sessions/{session_id}")
async def api_delete_session(session_id: str):
    """删除会话"""
    store = _get_store()
    store.delete_session(session_id)
    return {"status": "ok"}


@router.post("/sessions/{session_id}/messages")
async def api_send_message(session_id: str, request: NL2SqlRequest):
    """向会话发送消息并获取 AI 回复"""
    store = _get_store()
    session = store.get_session(session_id)
    if not session:
        inferred_title = request.question[:20] + "..." if len(request.question) > 20 else request.question
        session = store.create_session(session_id=session_id, title=inferred_title)

    store.append_message(
        session_id=session_id,
        role="user",
        content=request.question,
    )
    session = store.get_session(session_id) or session

    # 注入历史到请求
    history_messages = session["messages"][:-1][-MAX_HISTORY_MESSAGES:]
    request.history = []
    for m in history_messages:
        role = m.get("role")
        if role not in {"user", "assistant"}:
            continue
        content = _build_history_content(m)
        if not content:
            continue
        request.history.append({"role": role, "content": content})
    request.session_id = session_id

    # 生成 SQL
    gen_result = await generate_sql(request)
    assistant_sql = (gen_result.sql or "").strip()
    assistant_explanation = (gen_result.explanation or "").strip()
    if not assistant_explanation and not assistant_sql:
        assistant_explanation = "已完成分析，但未生成可展示结果，请换个问法重试。"

    # 自动执行
    execution = None
    if assistant_sql:
        execution = execute_sql(
            sql=assistant_sql,
            database=request.database,
        )

    # 拼装助手消息
    assistant_content = assistant_explanation
    if assistant_sql:
        assistant_content += f"\n\n```sql\n{assistant_sql}\n```"

    assistant_msg = {
        "role": "assistant",
        "content": assistant_content,
        "sql": assistant_sql,
        "explanation": assistant_explanation,
        "thinking_steps": [s.model_dump() for s in gen_result.thinking_steps],
        "matched_tables": gen_result.matched_tables,
        "matched_rules": gen_result.matched_rules,
        "confidence": gen_result.confidence,
        "execution": execution.model_dump() if execution else None,
    }
    store.append_message(
        session_id=session_id,
        role="assistant",
        content=assistant_content,
        payload={
            "sql": assistant_msg["sql"],
            "explanation": assistant_msg["explanation"],
            "thinking_steps": assistant_msg["thinking_steps"],
            "matched_tables": assistant_msg["matched_tables"],
            "matched_rules": assistant_msg["matched_rules"],
            "confidence": assistant_msg["confidence"],
            "execution": assistant_msg["execution"],
        },
    )

    # 自动更新标题
    if session["title"] == "新会话" or session["title"].startswith("新会话"):
        q = request.question
        store.update_session_title(session_id, q[:30] + "..." if len(q) > 30 else q)

    saved = store.get_session(session_id)
    if saved and saved.get("messages"):
        return saved["messages"][-1]
    return assistant_msg


# ---------- 健康检查 ----------

@router.get("/health")
async def api_health():
    """健康检查"""
    cfg = get_settings()
    return {
        "status": "ok",
        "api_key_set": bool(cfg.anthropic_api_key),
        "model": cfg.claude_model,
    }
