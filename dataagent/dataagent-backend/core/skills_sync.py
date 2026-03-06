from __future__ import annotations

"""
Skills 静态模板初始化/校验
- 统一技能结构：SKILL.md / reference / scripts / assets
- 只补骨架，不覆盖现有内容
"""

import json
from pathlib import Path
from typing import Any

from config import get_settings


def ensure_static_skills_bundle() -> dict[str, Any]:
    cfg = get_settings()
    root = _resolve_output_dir(cfg.skills_output_dir)
    paths = _ensure_structure(root)
    files: list[Path] = []

    _remove_legacy_files(
        [
            root / "README.md",
            root / "manifest.json",
            root / "methodology/nl2semantic2lf2sql.md",
            root / "references/opendataworks-dynamic-metadata.md",
            root / "references/tool-output-contract.md",
            root / "ontology/ontology.json",
            root / "ontology/business_concepts.json",
            root / "ontology/metrics.json",
            root / "ontology/constraints.json",
            root / "knowledge/few_shots.json",
            root / "knowledge/business_rules.json",
            root / "metadata/metadata_catalog.json",
            root / "metadata/lineage_catalog.json",
            root / "metadata/semantic_mappings.json",
            root / "metadata/source_mapping.json",
            root / "governance/policies.json",
        ]
    )

    files.extend(
        filter(
            None,
            [
                _write_text_if_absent(root / "SKILL.md", _default_skill_md()),
                _write_text_if_absent(paths["reference"] / "00-skill-map.md", _default_reference_skill_map()),
                _write_text_if_absent(paths["reference"] / "10-query-playbooks.md", _default_reference_playbooks()),
                _write_text_if_absent(paths["reference"] / "11-datasource-routing.md", _default_reference_datasource()),
                _write_text_if_absent(paths["reference"] / "20-term-index.md", _default_reference_term_index()),
                _write_text_if_absent(paths["reference"] / "21-metric-index.md", _default_reference_metric_index()),
                _write_text_if_absent(paths["reference"] / "22-sql-example-index.md", _default_reference_sql_examples()),
                _write_text_if_absent(paths["reference"] / "30-tool-recipes.md", _default_reference_tools()),
                _write_text_if_absent(paths["reference"] / "40-runtime-metadata.md", _default_reference_runtime()),
                _write_text_if_absent(paths["reference"] / "50-tool-output-contract.md", _default_reference_output_contract()),
                _write_text_if_absent(paths["scripts"] / "_opendataworks_runtime.py", _default_runtime_helper()),
                _write_text_if_absent(paths["scripts"] / "inspect_metadata.py", _default_script_stub("inspect_metadata")),
                _write_text_if_absent(paths["scripts"] / "resolve_datasource.py", _default_script_stub("resolve_datasource")),
                _write_text_if_absent(paths["scripts"] / "run_sql.py", _default_script_stub("run_sql")),
                _write_text_if_absent(paths["scripts"] / "build_chart_spec.py", _default_script_stub("build_chart_spec")),
                _write_text_if_absent(paths["scripts"] / "format_answer.py", _default_script_stub("format_answer")),
                _write_text_if_absent(paths["scripts"] / "build_reference_digest.py", _default_script_stub("build_reference_digest")),
                _write_text_if_absent(paths["scripts"] / "query_opendataworks_metadata.py", _default_query_metadata_script()),
            ],
        )
    )

    templates: dict[Path, Any] = {
        paths["assets"] / "ontology.json": {"schema_version": "1.0", "items": []},
        paths["assets"] / "business_concepts.json": {"schema_version": "1.0", "items": []},
        paths["assets"] / "metrics.json": {"schema_version": "1.0", "items": []},
        paths["assets"] / "constraints.json": {
            "schema_version": "1.0",
            "global": {
                "row_limit_max": 1000,
                "timezone": "Asia/Shanghai",
                "forbidden_ops": ["drop", "truncate", "delete", "alter", "create", "insert", "update"],
            },
            "items": [],
        },
        paths["assets"] / "few_shots.json": {"schema_version": "1.0", "items": []},
        paths["assets"] / "business_rules.json": {"schema_version": "1.0", "items": []},
        paths["assets"] / "semantic_mappings.json": {"schema_version": "1.0", "items": []},
        paths["assets"] / "policies.json": {
            "schema_version": "1.0",
            "items": [
                {"policy_key": "sql_read_only", "description": "仅允许查询语句", "enabled": True},
                {"policy_key": "require_limit", "description": "查询必须包含 LIMIT 保护", "enabled": True},
            ],
        },
        paths["assets"] / "term_explanations.json": {"schema_version": "1.0", "items": []},
        paths["assets"] / "sql_examples.json": {"schema_version": "1.0", "items": []},
        paths["chart_template"] / "table.json": {
            "chart_type": "table",
            "usage": "默认保底结果表达",
            "required_fields": ["columns", "rows"],
        },
        paths["chart_template"] / "bar.json": {
            "chart_type": "bar",
            "usage": "分类对比或 TopN",
            "required_fields": ["x_field", "series", "dataset"],
        },
        paths["chart_template"] / "line.json": {
            "chart_type": "line",
            "usage": "时间趋势",
            "required_fields": ["x_field", "series", "dataset"],
        },
        paths["chart_template"] / "pie.json": {
            "chart_type": "pie",
            "usage": "少类别占比分析",
            "required_fields": ["series", "dataset"],
        },
    }
    for path, payload in templates.items():
        created = _write_json_if_absent(path, payload)
        if created:
            files.append(created)

    return {
        "output_dir": str(root),
        "files": [str(x) for x in files],
    }


def _resolve_output_dir(raw: str) -> Path:
    base = Path(__file__).resolve().parent.parent
    path = Path(raw or "../.claude/skills/dataagent-nl2sql")
    if path.is_absolute():
        return path
    return (base / path).resolve()


def _ensure_structure(root: Path) -> dict[str, Path]:
    paths = {
        "root": root,
        "reference": root / "reference",
        "scripts": root / "scripts",
        "assets": root / "assets",
        "chart_template": root / "assets" / "chart-template",
    }
    for path in paths.values():
        path.mkdir(parents=True, exist_ok=True)
    return paths


def _write_json(path: Path, payload: Any) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def _write_json_if_absent(path: Path, payload: Any) -> Path | None:
    if path.exists():
        return None
    return _write_json(path, payload)


def _write_text(path: Path, content: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content.rstrip() + "\n", encoding="utf-8")
    return path


def _write_text_if_absent(path: Path, content: str) -> Path | None:
    if path.exists():
        return None
    return _write_text(path, content)


def _remove_legacy_files(paths: list[Path]):
    for path in paths:
        if path.exists() and path.is_file():
            path.unlink()


def _default_skill_md() -> str:
    return """---
name: dataagent-nl2sql
description: Use this skill for Chinese data questions, statistics, comparison, trend analysis, term explanation, SQL examples, and chart-oriented answer planning across MySQL and Doris datasources.
---

# DataAgent NL2SQL Skill

## 适用范围

- 数据问答、统计、对比、趋势、占比、明细、诊断
- 术语解释、SQL 示例
- 表格、条形图、折线图、饼图

## 固定阅读顺序

1. `reference/00-skill-map.md`
2. `reference/10-query-playbooks.md`
3. 按需阅读 `reference/11/20/21/22/30/40/50`
4. 仍有缺口时再下钻 `assets/*` 或执行 `scripts/*`

## 固定执行顺序

1. 先判问题类型
2. 术语、指标、数据库不清则先追问
3. 表字段不清先 `inspect_metadata.py`
4. 引擎不清再 `resolve_datasource.py`
5. SQL 明确后才 `run_sql.py`
6. 结果适合图表时再 `build_chart_spec.py`
"""


def _default_reference_skill_map() -> str:
    return """# 技能地图

先结论：先分类，再看摘要，再决定是否执行脚本。

- 统计：先看 `10-query-playbooks.md`、`21-metric-index.md`
- 对比：先看 `10-query-playbooks.md`、`21-metric-index.md`
- 趋势：先看 `10-query-playbooks.md`、`21-metric-index.md`
- 占比：先看 `10-query-playbooks.md`、`20-term-index.md`
- 术语解释：先看 `20-term-index.md`
- SQL 示例：先看 `22-sql-example-index.md`
"""


def _default_reference_playbooks() -> str:
    return """# 场景 Playbooks

先结论：统计默认表格，对比默认条形图，趋势默认折线图，占比默认饼图。

- 统计：确认指标与时间范围
- 对比：确认对比维度、指标、时间范围
- 趋势：确认指标、时间粒度、时间范围
- 占比：确认分类维度和指标
- 明细：确认对象、过滤条件、字段
- 诊断：确认异常指标和对比基线
"""


def _default_reference_datasource() -> str:
    return """# 数据源路由

先结论：所有问数只允许单源路由。

- 平台元数据查询走 MySQL
- 业务事实与汇总查询由 `resolve_datasource.py` 判断 MySQL 或 Doris
- 不做跨源联查
"""


def _default_reference_term_index() -> str:
    return """# 术语索引

先结论：遇到术语、别名、口径不清时先看本页；仍不清晰再下钻 `assets/term_explanations.json`。
"""


def _default_reference_metric_index() -> str:
    return """# 指标索引

先结论：遇到统计、对比、趋势问题，先确认指标公式、默认时间字段和约束。
"""


def _default_reference_sql_examples() -> str:
    return """# SQL 示例索引

先结论：示例只用于校准结构，不要直接照抄到最终回答。
"""


def _default_reference_tools() -> str:
    return """# 工具 Recipes

先结论：脚本调用必须按“先澄清、再定位、后执行”的顺序进行。

- `inspect_metadata.py`：定位数据库、表、字段、血缘
- `resolve_datasource.py`：判断目标引擎与数据源
- `run_sql.py`：执行只读 SQL
- `build_chart_spec.py`：根据结果决定是否出图
- `format_answer.py`：整理最终中文结论
"""


def _default_reference_runtime() -> str:
    return """# 运行时元数据与数据源说明

先结论：需要动态补齐库表结构、血缘、数据源时，优先执行脚本，而不是静态快照。
"""


def _default_reference_output_contract() -> str:
    return """# 工具输出契约

先结论：表格保底来自 `sql_execution`，图表来自 `chart_spec`。

- `metadata_snapshot`
- `datasource_resolution`
- `sql_execution`
- `python_execution`
- `chart_spec`
"""


def _default_runtime_helper() -> str:
    return """from __future__ import annotations

# OpenDataWorks 运行时帮助函数留在真实 skill 包里，这里只提供最小骨架。
"""


def _default_script_stub(name: str) -> str:
    return f"""from __future__ import annotations


def main() -> None:
    raise SystemExit("{name}.py is a placeholder. Replace it with the real skill implementation.")


if __name__ == "__main__":
    main()
"""


def _default_query_metadata_script() -> str:
    return '''from __future__ import annotations

import argparse
import json
import os

import pymysql


def main() -> None:
    parser = argparse.ArgumentParser(description="Query OpenDataWorks metadata tables")
    parser.add_argument("--kind", choices=["tables", "lineage", "datasource"], required=True)
    parser.add_argument("--database", default="")
    parser.add_argument("--host", default=os.getenv("ODW_MYSQL_HOST", "localhost"))
    parser.add_argument("--port", type=int, default=int(os.getenv("ODW_MYSQL_PORT", "3306")))
    parser.add_argument("--user", default=os.getenv("ODW_MYSQL_USER", "root"))
    parser.add_argument("--password", default=os.getenv("ODW_MYSQL_PASSWORD", ""))
    parser.add_argument("--schema", default=os.getenv("ODW_MYSQL_DATABASE", "opendataworks"))
    args = parser.parse_args()

    conn = pymysql.connect(
        host=args.host,
        port=args.port,
        user=args.user,
        password=args.password,
        database=args.schema,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor,
    )
    try:
        with conn.cursor() as cur:
            if args.kind == "tables":
                cur.execute(
                    """
                    SELECT dt.id, dt.cluster_id, dt.db_name, dt.table_name, dt.table_comment,
                           df.field_name, df.field_type, df.field_comment
                    FROM data_table dt
                    LEFT JOIN data_field df ON df.table_id = dt.id AND df.deleted = 0
                    WHERE dt.deleted = 0
                    ORDER BY dt.db_name, dt.table_name, df.field_order, df.id
                    """
                )
            elif args.kind == "lineage":
                cur.execute(
                    """
                    SELECT dl.id, dl.lineage_type,
                           ut.db_name AS upstream_db, ut.table_name AS upstream_table,
                           dt.db_name AS downstream_db, dt.table_name AS downstream_table
                    FROM data_lineage dl
                    LEFT JOIN data_table ut ON ut.id = dl.upstream_table_id AND ut.deleted = 0
                    LEFT JOIN data_table dt ON dt.id = dl.downstream_table_id AND dt.deleted = 0
                    WHERE dl.deleted = 0
                    ORDER BY dl.id
                    """
                )
            else:
                cur.execute(
                    """
                    SELECT dt.db_name, dt.cluster_id, dc.source_type, dc.fe_host, dc.fe_port,
                           dc.username, dc.password, du.readonly_username, du.readonly_password
                    FROM data_table dt
                    LEFT JOIN doris_cluster dc ON dc.id = dt.cluster_id AND dc.deleted = 0
                    LEFT JOIN doris_database_users du ON du.cluster_id = dt.cluster_id AND du.database_name = dt.db_name
                    WHERE dt.deleted = 0 AND (%s = '' OR dt.db_name = %s)
                    ORDER BY dt.db_name, dt.cluster_id
                    """,
                    (args.database, args.database),
                )
            print(json.dumps(cur.fetchall(), ensure_ascii=False, indent=2))
    finally:
        conn.close()


if __name__ == "__main__":
    main()
'''
