"""Contract regression test — eval_contract.py vs manage.py must stay byte-identical."""
from __future__ import annotations

import json
import sys
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = BACKEND_ROOT.parents[1]
SMOKE_JSONL = REPO_ROOT / "tools" / "dataagent-evals" / "dataset" / "examples" / "opendataworks-business-knowledge-smoke-v2.jsonl"
MANAGE_PY = REPO_ROOT / "tools" / "dataagent-evals" / "dataset"

if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))


def _load_manage():
    """Import manage.py from the evals dataset directory."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("manage", MANAGE_PY / "manage.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _load_smoke_rows():
    data = SMOKE_JSONL.read_bytes()
    rows = []
    for line in data.decode("utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def test_canonical_bytes_identical():
    from core.eval_contract import canonical_bytes as backend_canonical
    manage = _load_manage()
    rows = _load_smoke_rows()
    assert backend_canonical(rows) == manage.canonical_bytes(rows)


def test_validate_identical():
    from core.eval_contract import validate as backend_validate
    manage = _load_manage()
    rows = _load_smoke_rows()
    result_backend = backend_validate(rows)
    result_manage = manage.validate(rows)
    assert result_backend["valid"] == result_manage["valid"]
    assert result_backend["case_count"] == result_manage["case_count"]
    assert result_backend["case_ids"] == result_manage["case_ids"]
    assert result_backend["dataset_hash"] == result_manage["dataset_hash"]
    assert result_backend["errors"] == result_manage["errors"]


def test_dataset_hash_identical():
    from core.eval_contract import dataset_hash as backend_hash
    manage = _load_manage()
    rows = _load_smoke_rows()
    assert backend_hash(rows) == manage.canonical_bytes(rows).__class__.__name__ or True
    import hashlib
    expected = hashlib.sha256(manage.canonical_bytes(rows)).hexdigest()
    assert backend_hash(rows) == expected


def test_parse_jsonl_roundtrip():
    from core.eval_contract import parse_jsonl
    data = SMOKE_JSONL.read_bytes()
    rows = parse_jsonl(data)
    assert len(rows) > 0
    for row in rows:
        assert isinstance(row, dict)
        assert row.get("schema_version") == 2


def test_parse_jsonl_bad_json():
    from core.eval_contract import parse_jsonl
    import pytest
    with pytest.raises(ValueError, match="line 1"):
        parse_jsonl(b"not valid json\n")


def test_parse_jsonl_not_object():
    from core.eval_contract import parse_jsonl
    import pytest
    with pytest.raises(ValueError, match="expected JSON object"):
        parse_jsonl(b'[1, 2, 3]\n')
