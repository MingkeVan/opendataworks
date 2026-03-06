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
    (root / "reference").mkdir(parents=True, exist_ok=True)
    for name in [
        "00-skill-map.md",
        "10-query-playbooks.md",
        "11-datasource-routing.md",
        "20-term-index.md",
        "21-metric-index.md",
        "22-sql-example-index.md",
        "30-tool-recipes.md",
        "40-runtime-metadata.md",
        "50-tool-output-contract.md",
    ]:
        (root / "reference" / name).write_text(f"# {name}\n", encoding="utf-8")
    (root / "scripts").mkdir(parents=True, exist_ok=True)
    for name in [
        "_opendataworks_runtime.py",
        "inspect_metadata.py",
        "resolve_datasource.py",
        "run_sql.py",
        "build_chart_spec.py",
        "format_answer.py",
        "build_reference_digest.py",
        "query_opendataworks_metadata.py",
    ]:
        (root / "scripts" / name).write_text("print('ok')\n", encoding="utf-8")
    _write_json(root / "assets/ontology.json", {"items": []})
    _write_json(root / "assets/business_concepts.json", {"items": []})
    _write_json(root / "assets/metrics.json", {"items": []})
    _write_json(root / "assets/constraints.json", {"global": {"row_limit_max": 1000}, "items": []})
    _write_json(root / "assets/few_shots.json", {"items": []})
    _write_json(root / "assets/business_rules.json", {"items": []})
    _write_json(root / "assets/semantic_mappings.json", {"items": []})
    _write_json(root / "assets/policies.json", {"items": []})
    _write_json(root / "assets/term_explanations.json", {"items": []})
    _write_json(root / "assets/sql_examples.json", {"items": []})
    _write_json(root / "assets/chart-template/table.json", {"chart_type": "table"})
    _write_json(root / "assets/chart-template/bar.json", {"chart_type": "bar"})
    _write_json(root / "assets/chart-template/line.json", {"chart_type": "line"})
    _write_json(root / "assets/chart-template/pie.json", {"chart_type": "pie"})


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
    (root / "scripts/query_opendataworks_metadata.py").unlink()
    update_settings({"skills_output_dir": str(root)})
    with pytest.raises(SkillsLoadError):
        get_skills_bundle(force_reload=True)


def test_loader_succeeds_without_static_metadata_snapshots(tmp_path: Path):
    root = tmp_path / "skills"
    _build_minimum_bundle(root)
    update_settings({"skills_output_dir": str(root)})
    bundle = get_skills_bundle(force_reload=True)
    assert bundle.metadata_catalog == []
    assert bundle.lineage_catalog == []
    assert bundle.source_mapping == {}
