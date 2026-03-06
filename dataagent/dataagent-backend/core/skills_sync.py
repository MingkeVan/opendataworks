from __future__ import annotations

"""
Skills 静态模板初始化/校验（不做动态同步，不生成 legacy 文件）
"""

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from config import get_settings


def ensure_static_skills_bundle() -> dict[str, Any]:
    cfg = get_settings()
    root = _resolve_output_dir(cfg.skills_output_dir)
    paths = _ensure_structure(root)
    files: list[Path] = []

    skill_md = _write_if_absent(
        root / "SKILL.md",
        "\n".join(
            [
                "---",
                "name: dataagent-nl2sql",
                "description: Use this skill for natural-language data questions, NL2Semantic2LF2SQL conversion, SQL generation, and tool execution guidance with DataAgent knowledge files.",
                "---",
                "",
                "# DataAgent NL2Semantic2LF2SQL Skill",
                "",
                "## Scope",
                "- 智能问数流程编排与约束",
                "- 基于语义层的 LF（JSON DSL）到 SQL 编译与执行",
                "- 面向可迁移的文件化知识治理",
                "",
                "## Workflow",
                "- 1) NL 解析与语义命中",
                "- 2) 生成 LF(JSON DSL)",
                "- 3) LF 校验与约束检查",
                "- 4) 按引擎编译 SQL",
                "- 5) 工具执行并返回结果",
                "",
                "## References",
                "- `methodology/nl2semantic2lf2sql.md`",
                "- `manifest.json`",
                "",
            ]
        ),
    )
    if skill_md:
        files.append(skill_md)

    method_doc = _write_if_absent(
        paths["methodology"] / "nl2semantic2lf2sql.md",
        "\n".join(
            [
                "# NL2Semantic2LF2SQL Methodology",
                "",
                "1. 对用户问题进行语义解析，命中本体概念与业务指标。",
                "2. 产出可校验的 LF(JSON DSL)，禁止直接跳 SQL。",
                "3. 基于约束规则校验 LF（口径、一致性、安全限制）。",
                "4. 将 LF 编译为目标引擎 SQL（MySQL/Doris）。",
                "5. 通过 tool runtime 执行并返回结构化轨迹。",
                "",
            ]
        ),
    )
    if method_doc:
        files.append(method_doc)

    templates = {
        paths["ontology"] / "ontology.json": {"schema_version": "1.0", "items": []},
        paths["ontology"] / "business_concepts.json": {"schema_version": "1.0", "items": []},
        paths["ontology"] / "metrics.json": {"schema_version": "1.0", "items": []},
        paths["ontology"] / "constraints.json": {
            "schema_version": "1.0",
            "global": {
                "row_limit_max": 1000,
                "timezone": "Asia/Shanghai",
                "forbidden_ops": ["drop", "truncate", "delete", "alter", "create", "insert", "update"],
            },
            "items": [],
        },
        paths["knowledge"] / "few_shots.json": {"schema_version": "1.0", "items": []},
        paths["knowledge"] / "business_rules.json": {"schema_version": "1.0", "items": []},
        paths["metadata"] / "semantic_mappings.json": {"schema_version": "1.0", "items": []},
        paths["metadata"] / "metadata_catalog.json": {"schema_version": "1.0", "items": []},
        paths["metadata"] / "lineage_catalog.json": {"schema_version": "1.0", "items": []},
        paths["metadata"] / "source_mapping.json": {
            "schema_version": "1.0",
            "default_engine": "doris",
            "items": [],
        },
        paths["governance"] / "policies.json": {
            "schema_version": "1.0",
            "items": [
                {"policy_key": "sql_read_only", "description": "仅允许查询语句", "enabled": True},
                {"policy_key": "require_limit", "description": "查询必须包含 LIMIT 保护", "enabled": True},
            ],
        },
    }
    for path, payload in templates.items():
        if path.exists():
            continue
        files.append(_write_json(path, payload))

    manifest = {
        "schema_version": "1.0",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "description": "DataAgent static skill bundle manifest",
        "entrypoint": "SKILL.md",
        "workflow": "methodology/nl2semantic2lf2sql.md",
        "files": {
            "ontology": [
                "ontology/ontology.json",
                "ontology/business_concepts.json",
                "ontology/metrics.json",
                "ontology/constraints.json",
            ],
            "knowledge": [
                "knowledge/few_shots.json",
                "knowledge/business_rules.json",
            ],
            "metadata": [
                "metadata/semantic_mappings.json",
                "metadata/metadata_catalog.json",
                "metadata/lineage_catalog.json",
                "metadata/source_mapping.json",
            ],
            "governance": ["governance/policies.json"],
        },
    }
    files.append(_write_json(root / "manifest.json", manifest))

    readme = _write_text(
        root / "README.md",
        "\n".join(
            [
                "# DataAgent Skills Bundle",
                "",
                "该目录用于智能问数的 AI-Native 技能包（skills），仅静态维护 canonical 文件。",
                "",
                "## Canonical Directories",
                "- `methodology/`",
                "- `ontology/`",
                "- `knowledge/`",
                "- `metadata/`",
                "- `governance/`",
                "",
                "## Notes",
                "- 不再写入 legacy 平铺文件。",
                "- 不再写入 snapshots 自动快照。",
                "",
            ]
        ),
    )
    files.append(readme)

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
        "methodology": root / "methodology",
        "ontology": root / "ontology",
        "knowledge": root / "knowledge",
        "metadata": root / "metadata",
        "governance": root / "governance",
    }
    for path in paths.values():
        path.mkdir(parents=True, exist_ok=True)
    return paths


def _write_json(path: Path, payload: Any) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def _write_text(path: Path, content: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


def _write_if_absent(path: Path, content: str) -> Path | None:
    if path.exists():
        return None
    return _write_text(path, content)
