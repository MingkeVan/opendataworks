from __future__ import annotations

"""
Skills 文件加载器（四段结构：SKILL.md / reference / scripts / assets）
"""

import json
import logging
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from config import get_settings
from models.schemas import BusinessRule, QAExample, SemanticEntry

logger = logging.getLogger(__name__)


class SkillsLoadError(RuntimeError):
    pass


@dataclass
class SkillsBundle:
    root: Path
    ontology: list[dict[str, Any]]
    business_concepts: list[dict[str, Any]]
    metrics: list[dict[str, Any]]
    constraints: dict[str, Any]
    few_shots: list[QAExample]
    business_rules: list[BusinessRule]
    semantic_mappings: list[SemanticEntry]
    term_explanations: list[dict[str, Any]]
    sql_examples: list[dict[str, Any]]
    metadata_catalog: list[dict[str, Any]]
    lineage_catalog: list[dict[str, Any]]
    source_mapping: dict[str, str]
    default_engine: str

    @property
    def available_databases(self) -> set[str]:
        return {str(item.get("db_name") or "").strip() for item in self.metadata_catalog if item.get("db_name")}

    def resolve_engine(self, database: str | None) -> str:
        db = (database or "").strip()
        if db and db in self.source_mapping:
            return self.source_mapping[db]
        return self.default_engine


_bundle_lock = threading.Lock()
_bundle: SkillsBundle | None = None


def get_skills_bundle(force_reload: bool = False) -> SkillsBundle:
    global _bundle
    if _bundle is not None and not force_reload:
        return _bundle

    with _bundle_lock:
        if _bundle is not None and not force_reload:
            return _bundle
        _bundle = _load_skills_bundle()
        return _bundle


def validate_skills_bundle(*, force_reload: bool = False) -> dict[str, Any]:
    bundle = get_skills_bundle(force_reload=force_reload)
    return {
        "root": str(bundle.root),
        "metadata_tables": len(bundle.metadata_catalog),
        "business_rules": len(bundle.business_rules),
        "semantic_mappings": len(bundle.semantic_mappings),
        "few_shots": len(bundle.few_shots),
        "term_explanations": len(bundle.term_explanations),
        "sql_examples": len(bundle.sql_examples),
        "lineage_edges": len(bundle.lineage_catalog),
        "available_databases": sorted(bundle.available_databases),
        "default_engine": bundle.default_engine,
    }


def _resolve_root_dir(raw: str) -> Path:
    base = Path(__file__).resolve().parent.parent  # dataagent-backend/
    path = Path(raw or "../.claude/skills/dataagent-nl2sql")
    if path.is_absolute():
        return path
    return (base / path).resolve()


def resolve_skills_root_dir() -> Path:
    cfg = get_settings()
    return _resolve_root_dir(cfg.skills_output_dir)


def resolve_agent_project_cwd() -> Path:
    """
    Claude Agent SDK 官方技能发现依赖 cwd 下的 .claude/skills 目录。
    优先返回可发现该目录的项目根。
    """
    skills_root = resolve_skills_root_dir()
    for parent in [skills_root, *skills_root.parents]:
        if (parent / ".claude" / "skills").exists():
            return parent
    raise SkillsLoadError(
        f"skills_output_dir must be under '.claude/skills', current={skills_root}"
    )


def _read_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise SkillsLoadError(f"Missing required file: {path}")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:
        raise SkillsLoadError(f"Invalid JSON file: {path} ({e})") from e
    if not isinstance(payload, dict):
        raise SkillsLoadError(f"JSON root must be object: {path}")
    return payload


def _read_items(path: Path, *, required: bool = True) -> list[dict[str, Any]]:
    if not path.exists():
        if required:
            raise SkillsLoadError(f"Missing required file: {path}")
        return []
    payload = _read_json(path)
    items = payload.get("items")
    if not isinstance(items, list):
        raise SkillsLoadError(f"`items` must be list: {path}")
    for idx, item in enumerate(items):
        if not isinstance(item, dict):
            raise SkillsLoadError(f"`items[{idx}]` must be object: {path}")
    return items


def _require_files(root: Path):
    required = [
        "SKILL.md",
        "reference/00-skill-map.md",
        "reference/10-query-playbooks.md",
        "reference/11-datasource-routing.md",
        "reference/20-term-index.md",
        "reference/21-metric-index.md",
        "reference/22-sql-example-index.md",
        "reference/30-tool-recipes.md",
        "reference/40-runtime-metadata.md",
        "reference/50-tool-output-contract.md",
        "scripts/_opendataworks_runtime.py",
        "scripts/inspect_metadata.py",
        "scripts/resolve_datasource.py",
        "scripts/run_sql.py",
        "scripts/build_chart_spec.py",
        "scripts/format_answer.py",
        "scripts/build_reference_digest.py",
        "scripts/query_opendataworks_metadata.py",
        "assets/ontology.json",
        "assets/business_concepts.json",
        "assets/metrics.json",
        "assets/constraints.json",
        "assets/few_shots.json",
        "assets/business_rules.json",
        "assets/semantic_mappings.json",
        "assets/policies.json",
        "assets/term_explanations.json",
        "assets/sql_examples.json",
        "assets/chart-template/table.json",
        "assets/chart-template/bar.json",
        "assets/chart-template/line.json",
        "assets/chart-template/pie.json",
    ]
    for rel in required:
        path = root / rel
        if not path.exists():
            raise SkillsLoadError(f"Missing required file: {path}")


def _parse_semantic_mappings(items: list[dict[str, Any]]) -> list[SemanticEntry]:
    result: list[SemanticEntry] = []
    for item in items:
        name = str(item.get("business_name") or "").strip()
        if not name:
            continue
        synonyms = item.get("synonyms") or []
        if not isinstance(synonyms, list):
            synonyms = []
        result.append(
            SemanticEntry(
                business_name=name,
                table_name=(item.get("table_name") or None),
                field_name=(item.get("field_name") or None),
                synonyms=[str(x).strip() for x in synonyms if str(x).strip()],
                description=(item.get("description") or None),
            )
        )
    return result


def _parse_business_rules(items: list[dict[str, Any]]) -> list[BusinessRule]:
    rules: list[BusinessRule] = []
    for item in items:
        term = str(item.get("term") or "").strip()
        if not term:
            continue
        synonyms = item.get("synonyms") or []
        if not isinstance(synonyms, list):
            synonyms = []
        rules.append(
            BusinessRule(
                term=term,
                synonyms=[str(x).strip() for x in synonyms if str(x).strip()],
                definition=(item.get("definition") or None),
            )
        )
    return rules


def _parse_few_shots(items: list[dict[str, Any]]) -> list[QAExample]:
    examples: list[QAExample] = []
    for item in items:
        question = str(item.get("question") or "").strip()
        answer = str(item.get("answer") or "").strip()
        if not question or not answer:
            continue
        tags = item.get("tags") or []
        if not isinstance(tags, list):
            tags = []
        examples.append(
            QAExample(
                question=question,
                answer=answer,
                tags=[str(x).strip() for x in tags if str(x).strip()],
            )
        )
    return examples


def _load_skills_bundle() -> SkillsBundle:
    cfg = get_settings()
    root = _resolve_root_dir(cfg.skills_output_dir)
    _require_files(root)

    ontology_items = _read_items(root / "assets/ontology.json")
    concept_items = _read_items(root / "assets/business_concepts.json")
    metric_items = _read_items(root / "assets/metrics.json")
    constraints = _read_json(root / "assets/constraints.json")

    few_shots = _parse_few_shots(_read_items(root / "assets/few_shots.json"))
    rules = _parse_business_rules(_read_items(root / "assets/business_rules.json"))
    semantics = _parse_semantic_mappings(_read_items(root / "assets/semantic_mappings.json"))
    term_explanations = _read_items(root / "assets/term_explanations.json")
    sql_examples = _read_items(root / "assets/sql_examples.json")
    metadata_catalog: list[dict[str, Any]] = []
    lineage_catalog: list[dict[str, Any]] = []
    source_mapping: dict[str, str] = {}
    default_engine = "doris"

    logger.info(
        "Skills loaded from %s: rules=%d semantics=%d few_shots=%d dynamic_metadata=skill-script",
        root,
        len(rules),
        len(semantics),
        len(few_shots),
    )

    return SkillsBundle(
        root=root,
        ontology=ontology_items,
        business_concepts=concept_items,
        metrics=metric_items,
        constraints=constraints,
        few_shots=few_shots,
        business_rules=rules,
        semantic_mappings=semantics,
        term_explanations=term_explanations,
        sql_examples=sql_examples,
        metadata_catalog=metadata_catalog,
        lineage_catalog=lineage_catalog,
        source_mapping=source_mapping,
        default_engine=default_engine,
    )
