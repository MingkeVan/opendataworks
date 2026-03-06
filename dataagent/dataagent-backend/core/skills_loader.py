from __future__ import annotations

"""
Skills 文件加载器（静态技能包，严格校验）
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

SUPPORTED_ENGINES = {"mysql", "doris"}


class SkillsLoadError(RuntimeError):
    pass


@dataclass
class SkillsBundle:
    root: Path
    manifest: dict[str, Any]
    ontology: list[dict[str, Any]]
    business_concepts: list[dict[str, Any]]
    metrics: list[dict[str, Any]]
    constraints: dict[str, Any]
    few_shots: list[QAExample]
    business_rules: list[BusinessRule]
    semantic_mappings: list[SemanticEntry]
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
        "manifest.json",
        "SKILL.md",
        "methodology/nl2semantic2lf2sql.md",
        "ontology/ontology.json",
        "ontology/business_concepts.json",
        "ontology/metrics.json",
        "ontology/constraints.json",
        "knowledge/few_shots.json",
        "knowledge/business_rules.json",
        "metadata/semantic_mappings.json",
        "metadata/metadata_catalog.json",
        "metadata/lineage_catalog.json",
        "metadata/source_mapping.json",
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


def _parse_source_mapping(path: Path) -> tuple[dict[str, str], str]:
    payload = _read_json(path)
    default_engine = str(payload.get("default_engine") or "doris").strip().lower()
    if default_engine not in SUPPORTED_ENGINES:
        raise SkillsLoadError(f"Unsupported default_engine={default_engine}, file={path}")

    mapping: dict[str, str] = {}
    items = payload.get("items")
    if not isinstance(items, list):
        raise SkillsLoadError(f"`items` must be list: {path}")
    for idx, item in enumerate(items):
        if not isinstance(item, dict):
            raise SkillsLoadError(f"`items[{idx}]` must be object: {path}")
        db = str(item.get("database") or "").strip()
        engine = str(item.get("engine") or "").strip().lower()
        if not db:
            raise SkillsLoadError(f"database is empty at items[{idx}], file={path}")
        if engine not in SUPPORTED_ENGINES:
            raise SkillsLoadError(f"Unsupported engine={engine} at items[{idx}], file={path}")
        mapping[db] = engine
    return mapping, default_engine


def _validate_metadata_items(items: list[dict[str, Any]], path: Path):
    for idx, item in enumerate(items):
        table_name = str(item.get("table_name") or "").strip()
        if not table_name:
            raise SkillsLoadError(f"metadata_catalog.items[{idx}].table_name is required ({path})")
        fields = item.get("fields") or []
        if not isinstance(fields, list):
            raise SkillsLoadError(f"metadata_catalog.items[{idx}].fields must be list ({path})")


def _load_skills_bundle() -> SkillsBundle:
    cfg = get_settings()
    root = _resolve_root_dir(cfg.skills_output_dir)
    _require_files(root)

    manifest = _read_json(root / "manifest.json")
    if str(manifest.get("entrypoint") or "") != "SKILL.md":
        raise SkillsLoadError("manifest.entrypoint must be SKILL.md")

    ontology_items = _read_items(root / "ontology/ontology.json")
    concept_items = _read_items(root / "ontology/business_concepts.json")
    metric_items = _read_items(root / "ontology/metrics.json")
    constraints = _read_json(root / "ontology/constraints.json")

    few_shots = _parse_few_shots(_read_items(root / "knowledge/few_shots.json"))
    rules = _parse_business_rules(_read_items(root / "knowledge/business_rules.json"))
    semantics = _parse_semantic_mappings(_read_items(root / "metadata/semantic_mappings.json"))
    metadata_catalog = _read_items(root / "metadata/metadata_catalog.json")
    _validate_metadata_items(metadata_catalog, root / "metadata/metadata_catalog.json")
    lineage_catalog = _read_items(root / "metadata/lineage_catalog.json")
    source_mapping, default_engine = _parse_source_mapping(root / "metadata/source_mapping.json")

    logger.info(
        "Skills loaded from %s: tables=%d rules=%d semantics=%d few_shots=%d lineage=%d",
        root,
        len(metadata_catalog),
        len(rules),
        len(semantics),
        len(few_shots),
        len(lineage_catalog),
    )

    return SkillsBundle(
        root=root,
        manifest=manifest,
        ontology=ontology_items,
        business_concepts=concept_items,
        metrics=metric_items,
        constraints=constraints,
        few_shots=few_shots,
        business_rules=rules,
        semantic_mappings=semantics,
        metadata_catalog=metadata_catalog,
        lineage_catalog=lineage_catalog,
        source_mapping=source_mapping,
        default_engine=default_engine,
    )
