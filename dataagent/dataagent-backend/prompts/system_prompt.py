from __future__ import annotations

"""
System Prompt 模板 — NL2Semantic2SQL
"""


SYSTEM_PROMPT_TEMPLATE = """你是一个专业的数据分析 SQL 助手，负责将用户的自然语言问题转化为准确的 SQL 查询。

## 你的角色
- 基于提供的数据库 Schema（DDL）、业务规则和语义知识，生成 **准确** 的 SQL
- 你必须严格使用提供的表名和字段名，**不要猜测或编造**不在 Schema 中的表和列
- 遵循企业统一的指标计算口径和业务规则

## 数据库环境
- 数据库类型: Apache Doris (兼容 MySQL 语法)
{database_context}

## 可用的数据表 Schema
以下是与用户问题最相关的表定义（DDL）：

{schema_section}

## 业务规则与计算口径
以下是与用户问题相关的业务术语和计算公式：

{rules_section}

## 语义映射
以下是业务概念到数据字段的映射关系：

{semantic_section}

## 数据血缘关系
{lineage_section}

## 参考示例
以下是类似问题的正确 SQL 示例（Few-shot）：

{examples_section}

## 输出要求
1. 返回一个 JSON 对象，包含以下字段：
   - `sql`: 生成的 SQL 查询语句
   - `explanation`: 对 SQL 的中文解释
   - `matched_tables`: 使用到的表名列表
   - `confidence`: 置信度 (0.0-1.0)

2. SQL 规范：
   - 总是使用 `LIMIT {query_limit}` 限制结果集
   - 对于聚合查询，合理使用 GROUP BY
   - 字段名使用反引号包裹
   - 表名使用反引号包裹
   - 日期筛选优先使用分区字段

3. 如果无法根据提供的 Schema 生成 SQL，请在 explanation 中说明原因，sql 字段留空字符串

请只返回 JSON，不要添加任何 markdown 代码块标记或额外说明。
"""


def build_system_prompt(
    database: str | None = None,
    schemas: list[dict] | None = None,
    rules: list[dict] | None = None,
    semantics: list | None = None,
    examples: list[dict] | None = None,
    lineage: list[dict] | None = None,
    query_limit: int = 100,
) -> str:
    """组装完整的 System Prompt"""

    # 数据库上下文
    db_ctx = f"- 当前数据库: {database}" if database else "- 数据库: 未指定"

    # Schema 区域
    if schemas:
        schema_lines = []
        for i, s in enumerate(schemas, 1):
            schema_lines.append(f"### 表 {i}: {s.get('table_name', '')}")
            schema_lines.append(s.get("ddl", ""))
            schema_lines.append("")
        schema_section = "\n".join(schema_lines)
    else:
        schema_section = "（无可用 Schema）"

    # 规则区域
    if rules:
        rule_lines = []
        for r in rules:
            term = r.get("term", "")
            definition = r.get("definition", "")
            synonyms = r.get("synonyms", [])
            syn_text = f"（同义词: {', '.join(synonyms)}）" if synonyms else ""
            rule_lines.append(f"- **{term}**{syn_text}: {definition}")
        rules_section = "\n".join(rule_lines)
    else:
        rules_section = "（无特殊业务规则）"

    # 语义映射区域
    if semantics:
        sem_lines = []
        for s in semantics:
            field_info = ""
            if hasattr(s, "table_name") and s.table_name:
                field_info += f" → 表: {s.table_name}"
            if hasattr(s, "field_name") and s.field_name:
                field_info += f".{s.field_name}"
            desc = f" ({s.description})" if hasattr(s, "description") and s.description else ""
            sem_lines.append(f"- {s.business_name}{field_info}{desc}")
        semantic_section = "\n".join(sem_lines)
    else:
        semantic_section = "（无语义映射）"

    # 血缘区域
    if lineage:
        lineage_lines = []
        for edge in lineage[:20]:
            up = edge.get("upstream_table", "?")
            down = edge.get("downstream_table", "?")
            lineage_lines.append(f"- {up} → {down}")
        lineage_section = "\n".join(lineage_lines)
    else:
        lineage_section = "（无血缘信息）"

    # Few-shot 示例区域
    if examples:
        ex_lines = []
        for i, ex in enumerate(examples, 1):
            ex_lines.append(f"### 示例 {i}")
            ex_lines.append(f"**问题**: {ex.get('question', '')}")
            ex_lines.append(f"**SQL**:")
            ex_lines.append(f"```sql\n{ex.get('answer', '')}\n```")
            ex_lines.append("")
        examples_section = "\n".join(ex_lines)
    else:
        examples_section = "（无参考示例）"

    return SYSTEM_PROMPT_TEMPLATE.format(
        database_context=db_ctx,
        schema_section=schema_section,
        rules_section=rules_section,
        semantic_section=semantic_section,
        lineage_section=lineage_section,
        examples_section=examples_section,
        query_limit=query_limit,
    )
