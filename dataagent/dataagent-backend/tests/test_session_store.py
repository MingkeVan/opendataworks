from __future__ import annotations

import sys
import types
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

if "pymysql" not in sys.modules:
    sys.modules["pymysql"] = types.SimpleNamespace(
        connect=lambda *args, **kwargs: None,
        cursors=types.SimpleNamespace(DictCursor=object),
        connections=types.SimpleNamespace(Connection=object),
    )

from core.session_store import SessionStore


def test_init_schema_is_runtime_noop():
    store = SessionStore()
    assert store._ready is False

    store.init_schema()

    assert store._ready is True
