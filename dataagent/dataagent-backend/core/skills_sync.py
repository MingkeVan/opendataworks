from __future__ import annotations

"""
将元数据/知识/血缘同步为 skills 文件（AI Native 索引材料）
"""

import json
from pathlib import Path
from typing import Any

from config import get_settings


def sync_semantic_layer_to_skills(semantic_layer) -> dict[str, Any]:
    cfg = get_settings()
    output_dir = _resolve_output_dir(cfg.skills_output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    metadata_rows = []
    for doc in semantic_layer.ddl_index.docs:
        meta = doc.get("meta")
        if meta is None:
            continue
        metadata_rows.append(_model_dump(meta))

    rules_rows = []
    for doc in semantic_layer.rule_index.docs:
        rules_rows.append(
            {
                "term": doc.get("term"),
                "synonyms": doc.get("synonyms", []),
                "definition": doc.get("definition", ""),
            }
        )

    semantic_rows = [_model_dump(x) for x in semantic_layer.semantic_entries]
    lineage_rows = semantic_layer.lineage or []

    files = [
        _write_json(output_dir / "metadata_tables.json", metadata_rows),
        _write_json(output_dir / "business_rules.json", rules_rows),
        _write_json(output_dir / "semantic_mappings.json", semantic_rows),
        _write_json(output_dir / "lineage_edges.json", lineage_rows),
        _write_readme(output_dir, len(metadata_rows), len(rules_rows), len(semantic_rows), len(lineage_rows)),
    ]

    return {
        "output_dir": str(output_dir),
        "files": [str(p) for p in files],
        "metadata_count": len(metadata_rows),
        "rule_count": len(rules_rows),
        "semantic_count": len(semantic_rows),
        "lineage_count": len(lineage_rows),
    }


def _resolve_output_dir(raw: str) -> Path:
    base = Path(__file__).resolve().parent.parent  # dataagent-backend/
    path = Path(raw or "skills/dataagent")
    if path.is_absolute():
        return path
    return (base / path).resolve()


def _model_dump(value: Any) -> Any:
    if hasattr(value, "model_dump"):
        return value.model_dump()
    return value


def _write_json(path: Path, payload: Any) -> Path:
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return path


def _write_readme(
    output_dir: Path,
    metadata_count: int,
    rule_count: int,
    semantic_count: int,
    lineage_count: int,
) -> Path:
    readme = output_dir / "README.md"
    readme.write_text(
        "\n".join(
            [
                "# DataAgent Skills Snapshot",
                "",
                "该目录由 `dataagent-backend` 自动同步，用于 Tool Use / MCP / Agent 的可读上下文。",
                "",
                "## Files",
                "- `metadata_tables.json`: 表与字段元数据",
                "- `business_rules.json`: 业务规则与口径",
                "- `semantic_mappings.json`: 业务语义映射",
                "- `lineage_edges.json`: 数据血缘",
                "",
                "## Stats",
                f"- metadata_tables: {metadata_count}",
                f"- business_rules: {rule_count}",
                f"- semantic_mappings: {semantic_count}",
                f"- lineage_edges: {lineage_count}",
                "",
            ]
        ),
        encoding="utf-8",
    )
    return readme
