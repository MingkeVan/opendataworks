from __future__ import annotations

import json
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
ASSETS_DIR = ROOT / "assets"
REFERENCE_DIR = ROOT / "reference"


def load_json(name: str) -> dict[str, Any]:
    path = ASSETS_DIR / name
    return json.loads(path.read_text(encoding="utf-8"))


def write_text(name: str, content: str) -> None:
    path = REFERENCE_DIR / name
    path.write_text(content.rstrip() + "\n", encoding="utf-8")


def bullet_list(items: list[str]) -> str:
    cleaned = [str(item).strip() for item in items if str(item).strip()]
    return "、".join(cleaned) if cleaned else "无"


def build_term_index() -> str:
    term_items = list(load_json("term_explanations.json").get("items") or [])
    concept_items = list(load_json("business_concepts.json").get("items") or [])
    semantic_items = list(load_json("semantic_mappings.json").get("items") or [])

    sections = [
        "# 术语索引",
        "",
        "先结论：遇到业务术语、别名、口径不清的问题，先看本页；仍不明确时，再下钻到对应资产或追问用户。",
        "",
        "## 术语解释资产",
        ""
    ]

    for item in sorted(term_items, key=lambda x: str(x.get("term") or "")):
        term = str(item.get("term") or "").strip()
        if not term:
            continue
        sections.extend(
            [
                f"### {term}",
                f"- 别名：{bullet_list(item.get('aliases') or [])}",
                f"- 解释：{str(item.get('explanation') or '无').strip()}",
                f"- 易混术语：{bullet_list(item.get('ambiguous_terms') or [])}",
                f"- 推荐追问：{str(item.get('ask_back') or '无').strip()}",
                f"- 相关指标：{bullet_list(item.get('related_metrics') or [])}",
                f"- 相关表：{bullet_list(item.get('related_tables') or [])}",
                "- 来源：`assets/term_explanations.json`",
                "",
            ]
        )

    sections.extend(
        [
            "## 业务概念补充",
            "",
        ]
    )
    for item in sorted(concept_items, key=lambda x: str(x.get("concept") or "")):
        concept = str(item.get("concept") or "").strip()
        if not concept:
            continue
        mapping = item.get("mapping") or {}
        sections.extend(
            [
                f"### {concept}",
                f"- 说明：{str(item.get('description') or '无').strip()}",
                f"- 默认映射：`{mapping.get('table') or '-'} / {mapping.get('field') or '-'} / {mapping.get('aggregation') or '-'}`",
                "- 来源：`assets/business_concepts.json`",
                "",
            ]
        )

    sections.extend(
        [
            "## 语义映射补充",
            "",
        ]
    )
    if not semantic_items:
        sections.extend(
            [
                "- 当前仓库没有额外的语义映射扩展项。",
                "- 来源：`assets/semantic_mappings.json`",
                "",
            ]
        )
    else:
        for item in sorted(semantic_items, key=lambda x: str(x.get("business_name") or "")):
            business_name = str(item.get("business_name") or "").strip()
            if not business_name:
                continue
            sections.extend(
                [
                    f"### {business_name}",
                    f"- 同义词：{bullet_list(item.get('synonyms') or [])}",
                    f"- 候选表字段：`{item.get('table_name') or '-'} / {item.get('field_name') or '-'}`",
                    f"- 说明：{str(item.get('description') or '无').strip()}",
                    "- 来源：`assets/semantic_mappings.json`",
                    "",
                ]
            )

    return "\n".join(sections)


def build_metric_index() -> str:
    metrics = list(load_json("metrics.json").get("items") or [])
    rules = list(load_json("business_rules.json").get("items") or [])
    constraints = load_json("constraints.json")
    global_constraints = constraints.get("global") or {}

    sections = [
        "# 指标索引",
        "",
        "先结论：遇到统计、对比、趋势分析，先用本页确认指标公式、默认时间字段和关键口径约束。",
        "",
        "## 全局约束",
        "",
        f"- 最大行数保护：{global_constraints.get('row_limit_max') or '-'}",
        f"- 时区：{global_constraints.get('timezone') or '-'}",
        f"- 禁止操作：{bullet_list(global_constraints.get('forbidden_ops') or [])}",
        "- 来源：`assets/constraints.json`",
        "",
        "## 指标清单",
        "",
    ]

    for item in sorted(metrics, key=lambda x: str(x.get("name") or x.get("metric_key") or "")):
        name = str(item.get("name") or item.get("metric_key") or "").strip()
        if not name:
            continue
        sections.extend(
            [
                f"### {name}",
                f"- Metric Key：`{item.get('metric_key') or '-'}`",
                f"- 公式：`{item.get('formula') or '-'}`",
                f"- 默认时间字段：`{item.get('default_time_field') or '-'}`",
                "- 来源：`assets/metrics.json`",
                "",
            ]
        )

    sections.extend(
        [
            "## 业务规则补充",
            "",
        ]
    )
    for item in sorted(rules, key=lambda x: str(x.get("term") or "")):
        term = str(item.get("term") or "").strip()
        if not term:
            continue
        sections.extend(
            [
                f"### {term}",
                f"- 同义词：{bullet_list(item.get('synonyms') or [])}",
                f"- 规则：{str(item.get('definition') or '无').strip()}",
                "- 来源：`assets/business_rules.json`",
                "",
            ]
        )

    return "\n".join(sections)


def build_sql_example_index() -> str:
    items = list(load_json("sql_examples.json").get("items") or [])
    few_shots = list(load_json("few_shots.json").get("items") or [])

    sections = [
        "# SQL 示例索引",
        "",
        "先结论：需要 SQL 参考时，先看本页匹配场景和引擎；示例只用于校准结构，不要直接照抄到最终回答。",
        "",
        "## SQL 示例",
        "",
    ]

    for item in sorted(items, key=lambda x: (str(x.get("scenario") or ""), str(x.get("title") or ""))):
        title = str(item.get("title") or "").strip()
        if not title:
            continue
        sql = str(item.get("sql") or "").strip().splitlines()
        preview = sql[0] if sql else "-"
        sections.extend(
            [
                f"### {title}",
                f"- 场景：{str(item.get('scenario') or '-').strip()}",
                f"- 引擎：`{str(item.get('engine') or '-').strip()}`",
                f"- 问题：{str(item.get('question') or '-').strip()}",
                f"- SQL 摘要：`{preview}`",
                f"- 注意事项：{bullet_list(item.get('notes') or [])}",
                f"- 相关术语：{bullet_list(item.get('related_terms') or [])}",
                "- 来源：`assets/sql_examples.json`",
                "",
            ]
        )

    sections.extend(
        [
            "## Few-shot 提示补充",
            "",
        ]
    )
    for item in few_shots:
        question = str(item.get("question") or "").strip()
        if not question:
            continue
        sections.extend(
            [
                f"### {question}",
                f"- 标签：{bullet_list(item.get('tags') or [])}",
                f"- 答案摘要：{str(item.get('answer') or '').strip()}",
                "- 来源：`assets/few_shots.json`",
                "",
            ]
        )

    return "\n".join(sections)


def main() -> None:
    write_text("20-term-index.md", build_term_index())
    write_text("21-metric-index.md", build_metric_index())
    write_text("22-sql-example-index.md", build_sql_example_index())


if __name__ == "__main__":
    main()
