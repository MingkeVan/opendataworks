from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Optional


DEFAULT_WORKSPACE_BASE = "/tmp/dataagent/workspaces"


def sanitize_topic_id(topic_id: str) -> str:
    cleaned = re.sub(r"[^a-zA-Z0-9_\-]", "_", str(topic_id).strip())
    return cleaned or "default"


def prepare_topic_workspace(
    topic_id: str,
    base_dir: Optional[str | Path] = None,
) -> Path:
    """
    Prepare and sanitize topic workspace directory.
    Ensures scratch directory exists.
    """
    base = Path(base_dir or os.environ.get("DATAAGENT_TOPIC_WORKSPACE_ROOT", DEFAULT_WORKSPACE_BASE))
    clean_id = sanitize_topic_id(topic_id)
    ws_path = (base / clean_id).resolve()
    
    ws_path.mkdir(parents=True, exist_ok=True)
    (ws_path / "scratch").mkdir(parents=True, exist_ok=True)
    
    return ws_path
