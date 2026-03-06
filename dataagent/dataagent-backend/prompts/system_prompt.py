from __future__ import annotations

"""
System Prompt 模板 — NL2Semantic2LF2SQL
"""


SYSTEM_PROMPT_TEMPLATE = """你是企业级智能问数助手。你必须遵守以下流程：
1) 先理解用户意图并命中语义层；
2) 产出 LF(JSON DSL)；
3) 仅在可回答为查询时生成 LF.action=query，否则使用 explain_only。
4) 若 Skill tool 可用，优先调用与 NL2SQL 相关 skill 后再回答。

## 严格约束
- 只使用提供的表和字段，禁止臆造。
- 只允许只读查询。
- query 类型必须带 LIMIT，且不能超过 {row_limit_max}。

## 当前上下文
{database_context}

## 可用 Schema
{schema_section}

## 业务规则
{rules_section}

## 语义映射
{semantic_section}

## 血缘
{lineage_section}

## few-shot
{examples_section}

## 输出格式（只返回 JSON，不要 markdown）
{{
  "trace": [
    {{"stage":"步骤名(自由命名)","status":"success|failed|running|skipped","summary":"该步在做什么与得到什么"}}
  ],
  "analysis": "公开可展示的推理摘要",
  "lf": {{
    "action": "query 或 explain_only",
    "database": "数据库名，可空",
    "table": "表名，query 必填",
    "select": [{{"field":"字段","agg":"sum|avg|count|max|min|null","alias":"别名可空"}}],
    "filters": [{{"field":"字段","op":"=|!=|>|>=|<|<=|in|not_in|like|between","value":"值或数组"}}],
    "group_by": ["字段"],
    "order_by": [{{"field":"字段","direction":"asc|desc"}}],
    "limit": 100,
    "notes": "可空"
  }},
  "content": "对用户的直接回复（中文）"
}}
"""


def build_system_prompt(
    *,
    database: str | None,
    schemas: list[dict] | None,
    rules: list[dict] | None,
    semantics: list | None,
    examples: list[dict] | None,
    lineage: list[dict] | None,
    row_limit_max: int,
) -> str:
    db_ctx = f"- 当前数据库: {database}" if database else "- 当前数据库: 自动推断"

    if schemas:
        lines = []
        for idx, schema in enumerate(schemas, start=1):
            lines.append(f"### 表{idx}: {schema.get('table_name', '')}")
            lines.append(schema.get("ddl", ""))
            lines.append("")
        schema_section = "\n".join(lines)
    else:
        schema_section = "（无可用 Schema）"

    if rules:
        lines = []
        for rule in rules:
            term = rule.get("term", "")
            definition = rule.get("definition", "")
            synonyms = rule.get("synonyms", []) or []
            syn_text = f"（同义词: {', '.join(synonyms)}）" if synonyms else ""
            lines.append(f"- {term}{syn_text}: {definition}")
        rules_section = "\n".join(lines)
    else:
        rules_section = "（无规则）"

    if semantics:
        lines = []
        for item in semantics:
            table_name = getattr(item, "table_name", None)
            field_name = getattr(item, "field_name", None)
            desc = getattr(item, "description", None) or ""
            suffix = ""
            if table_name:
                suffix = f" -> {table_name}"
                if field_name:
                    suffix += f".{field_name}"
            lines.append(f"- {item.business_name}{suffix} {desc}".strip())
        semantic_section = "\n".join(lines)
    else:
        semantic_section = "（无语义映射）"

    if lineage:
        lines = []
        for edge in lineage[:20]:
            up = edge.get("upstream_table", "?")
            down = edge.get("downstream_table", "?")
            lines.append(f"- {up} -> {down}")
        lineage_section = "\n".join(lines)
    else:
        lineage_section = "（无血缘）"

    if examples:
        lines = []
        for idx, item in enumerate(examples, start=1):
            lines.append(f"示例{idx} 问题: {item.get('question', '')}")
            lines.append(f"示例{idx} SQL: {item.get('answer', '')}")
        examples_section = "\n".join(lines)
    else:
        examples_section = "（无 few-shot）"

    return SYSTEM_PROMPT_TEMPLATE.format(
        database_context=db_ctx,
        schema_section=schema_section,
        rules_section=rules_section,
        semantic_section=semantic_section,
        lineage_section=lineage_section,
        examples_section=examples_section,
        row_limit_max=row_limit_max,
    )
