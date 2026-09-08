from __future__ import annotations

import os
from typing import Any, Dict, Optional
from core.agent_runtime.security import (
    DEFAULT_CAPABILITY_AUDIENCE,
    SecurityError,
    sign_capability_token,
    verify_capability_token,
)


def get_runtime_shared_secret() -> str:
    return os.environ.get("DATAAGENT_RUNTIME_SECRET", "dataagent-default-insecure-secret")


def authenticate_capability_token(
    token: str,
    run_id: str,
    task_attempt_id: str,
    secret: Optional[str] = None,
) -> Dict[str, Any]:
    secret_key = secret or get_runtime_shared_secret()
    return verify_capability_token(
        token=token,
        expected_run_id=run_id,
        expected_task_attempt_id=task_attempt_id,
        secret_key=secret_key,
    )
