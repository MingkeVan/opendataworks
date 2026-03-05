from __future__ import annotations

"""
NL2SQL Agent 核心 — 使用 Claude Agent SDK 将自然语言转为 SQL
"""

import json
import logging
import os
from datetime import datetime

from config import get_settings
from core.semantic_layer import get_semantic_layer
from models.schemas import NL2SqlRequest, NL2SqlResponse, StepStatus, ThinkingStep
from prompts.system_prompt import build_system_prompt

logger = logging.getLogger(__name__)


async def generate_sql(request: NL2SqlRequest) -> NL2SqlResponse:
    """
    主入口: 接收用户自然语言问题, 经过语义检索 + Claude 生成 SQL
    """
    cfg = get_settings()
    steps: list[ThinkingStep] = []

    # Step 1: 语义检索
    steps.append(ThinkingStep(
        step_key="semantic_search",
        step_name="语义检索",
        status=StepStatus.running,
        summary="正在检索相关的表结构、业务规则和示例...",
    ))

    sl = get_semantic_layer()
    if not sl._loaded:
        try:
            sl.load(request.database)
        except Exception as e:
            logger.error("Failed to load semantic layer: %s", e)

    matched_schemas = sl.search_schema(request.question)
    matched_examples = sl.search_examples(request.question)
    matched_rules = sl.search_rules(request.question)
    matched_semantics = sl.resolve_semantics(request.question)

    steps[0].status = StepStatus.success
    steps[0].summary = (
        f"找到 {len(matched_schemas)} 个相关表, "
        f"{len(matched_examples)} 个示例, "
        f"{len(matched_rules)} 条业务规则"
    )

    # Step 2: 构建 Prompt
    steps.append(ThinkingStep(
        step_key="build_prompt",
        step_name="构建 Prompt",
        status=StepStatus.running,
        summary="正在组装系统提示词...",
    ))

    system_prompt = build_system_prompt(
        database=request.database,
        schemas=matched_schemas,
        rules=matched_rules,
        semantics=matched_semantics,
        examples=matched_examples,
        lineage=sl.lineage[:20] if sl.lineage else None,
        query_limit=cfg.query_result_limit,
    )

    # 构建对话历史
    messages = []
    for h in request.history:
        messages.append({"role": h.get("role", "user"), "content": h.get("content", "")})
    messages.append({"role": "user", "content": request.question})

    steps[1].status = StepStatus.success
    steps[1].summary = f"Prompt 构建完成 ({len(system_prompt)} chars)"

    # Step 3: 调用 Claude
    steps.append(ThinkingStep(
        step_key="call_claude",
        step_name="调用 Claude 生成 SQL",
        status=StepStatus.running,
        summary="正在调用 Claude AI 生成 SQL...",
    ))

    if not cfg.anthropic_api_key:
        steps[2].status = StepStatus.failed
        steps[2].summary = "未配置 Anthropic API Key，请在设置中配置"
        return NL2SqlResponse(
            session_id=request.session_id,
            question=request.question,
            sql="",
            explanation="未配置 Anthropic API Key。请在设置页面中配置 API Key。",
            thinking_steps=steps,
            confidence=0.0,
        )

    try:
        # 设置环境变量供 SDK 使用
        os.environ["ANTHROPIC_API_KEY"] = cfg.anthropic_api_key
        if cfg.anthropic_base_url:
            os.environ["ANTHROPIC_BASE_URL"] = cfg.anthropic_base_url

        model_name = request.model or cfg.claude_model
        steps[2].step_name = f"调用 {model_name} 生成 SQL"
        steps[2].summary = f"正在调用 {model_name} 生成 SQL..."

        response_text = await _call_llm(system_prompt, messages, cfg, model_override=model_name)

        steps[2].status = StepStatus.success
        steps[2].summary = f"{model_name} 生成完成"

    except Exception as e:
        logger.error("LLM API call failed: %s", e)
        steps[2].status = StepStatus.failed
        steps[2].summary = f"调用失败: {str(e)}"
        return NL2SqlResponse(
            session_id=request.session_id,
            question=request.question,
            sql="",
            explanation=f"调用 LLM 失败: {str(e)}",
            thinking_steps=steps,
            confidence=0.0,
        )

    # Step 4: 解析结果
    steps.append(ThinkingStep(
        step_key="parse_result",
        step_name="解析结果",
        status=StepStatus.running,
        summary="正在解析返回结果...",
    ))

    try:
        result = _parse_claude_response(response_text)
        steps[3].status = StepStatus.success
        steps[3].summary = "解析成功"

        return NL2SqlResponse(
            session_id=request.session_id,
            question=request.question,
            sql=result.get("sql", ""),
            explanation=result.get("explanation", ""),
            thinking_steps=steps,
            matched_tables=result.get("matched_tables", [t.get("table_name", "") for t in matched_schemas]),
            matched_rules=[r.get("term", "") for r in matched_rules],
            confidence=result.get("confidence", 0.0),
        )
    except Exception as e:
        logger.error("Failed to parse response: %s", e)
        steps[3].status = StepStatus.failed
        steps[3].summary = f"解析失败: {str(e)}"

        return NL2SqlResponse(
            session_id=request.session_id,
            question=request.question,
            sql="",
            explanation=f"解析返回结果失败: {str(e)}\n原始返回: {response_text[:500]}",
            thinking_steps=steps,
            confidence=0.0,
        )


async def _call_llm(system_prompt: str, messages: list[dict], cfg, model_override: str = None) -> str:
    """调用 LLM — 切换至官方 claude-agent-sdk"""
    try:
        from claude_agent_sdk import query as claude_query, ClaudeAgentOptions

        # 构建请求参数
        actual_model = model_override or cfg.claude_model
        
        env_vars = {"ANTHROPIC_API_KEY": cfg.anthropic_api_key}
        if cfg.anthropic_base_url:
            env_vars["ANTHROPIC_BASE_URL"] = cfg.anthropic_base_url

        # 将历史字典转换为对话文本格式给 query() 
        prompt_text = ""
        for m in messages:
            role = "用户" if m["role"] == "user" else "助手"
            prompt_text += f"[{role}]: {m['content']}\n\n"

        options = ClaudeAgentOptions(
            system_prompt=system_prompt,
            model=actual_model,
            env=env_vars
        )

        logger.info("Calling LLM via Agent SDK model=%s base_url=%s", actual_model, cfg.anthropic_base_url or "default")

        result_parts = []
        async for message in claude_query(prompt=prompt_text.strip(), options=options):
            # 获取 AssistantMessage 中的 TextBlock 内容
            if hasattr(message, "content") and isinstance(message.content, list):
                for block in message.content:
                    if hasattr(block, "type") and block.type == "text" and hasattr(block, "text"):
                        result_parts.append(block.text)
            elif hasattr(message, "content") and isinstance(message.content, str):
                # 兼容可能的普通文本返回
                result_parts.append(message.content)

        return "".join(result_parts)

    except ImportError:
        raise RuntimeError(
            "claude-agent-sdk package is not installed. "
            "Please install: pip install claude-agent-sdk"
        )


def _parse_claude_response(text: str) -> dict:
    """解析 Claude 返回的 JSON"""
    text = text.strip()

    # 去除可能的 markdown 代码块
    if text.startswith("```"):
        lines = text.split("\n")
        if lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].strip() == "```":
            lines = lines[:-1]
        text = "\n".join(lines)

    # 尝试直接解析
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    # 尝试在文本中寻找 JSON
    import re
    json_match = re.search(r"\{[\s\S]*\}", text)
    if json_match:
        try:
            return json.loads(json_match.group())
        except json.JSONDecodeError:
            pass

    # 无法解析，提取可能的 SQL
    sql_match = re.search(r"(?:SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP)[\s\S]+?;", text, re.IGNORECASE)
    return {
        "sql": sql_match.group() if sql_match else "",
        "explanation": text[:500],
        "matched_tables": [],
        "confidence": 0.3,
    }
