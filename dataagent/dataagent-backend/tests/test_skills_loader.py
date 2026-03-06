from __future__ import annotations

import json
from pathlib import Path

import pytest

from config import get_settings, update_settings
from core.skills_loader import SkillsLoadError, get_skills_bundle


def _write_json(path: Path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def _build_minimum_bundle(root: Path):
    root.mkdir(parents=True, exist_ok=True)
    (root / "SKILL.md").write_text("# skill", encoding="utf-8")
    (root / "methodology").mkdir(parents=True, exist_ok=True)
    (root / "methodology/nl2semantic2lf2sql.md").write_text("# workflow", encoding="utf-8")
    _write_json(root / "manifest.json", {"entrypoint": "SKILL.md"})
    _write_json(root / "ontology/ontology.json", {"items": []})
    _write_json(root / "ontology/business_concepts.json", {"items": []})
    _write_json(root / "ontology/metrics.json", {"items": []})
    _write_json(root / "ontology/constraints.json", {"global": {"row_limit_max": 1000}, "items": []})
    _write_json(root / "knowledge/few_shots.json", {"items": []})
    _write_json(root / "knowledge/business_rules.json", {"items": []})
    _write_json(root / "metadata/semantic_mappings.json", {"items": []})
    _write_json(root / "metadata/metadata_catalog.json", {"items": []})
    _write_json(root / "metadata/lineage_catalog.json", {"items": []})
    _write_json(root / "metadata/source_mapping.json", {"default_engine": "doris", "items": []})


@pytest.fixture(autouse=True)
def restore_skills_dir():
    original = get_settings().skills_output_dir
    try:
        yield
    finally:
        update_settings({"skills_output_dir": original})


def test_loader_fails_when_required_file_missing(tmp_path: Path):
    root = tmp_path / "skills"
    _build_minimum_bundle(root)
    (root / "metadata/source_mapping.json").unlink()
    update_settings({"skills_output_dir": str(root)})
    with pytest.raises(SkillsLoadError):
        get_skills_bundle(force_reload=True)


def test_loader_fails_when_metadata_item_missing_table_name(tmp_path: Path):
    root = tmp_path / "skills"
    _build_minimum_bundle(root)
    _write_json(root / "metadata/metadata_catalog.json", {"items": [{"db_name": "opendataworks", "fields": []}]})
    update_settings({"skills_output_dir": str(root)})
    with pytest.raises(SkillsLoadError):
        get_skills_bundle(force_reload=True)
