from __future__ import annotations

import os
from typing import Literal

SUPPORTED_RUNTIMES = ("pi_agent_core",)
DEFAULT_RUNTIME: Literal["pi_agent_core"] = "pi_agent_core"


class DeploymentLockError(Exception):
    """Raised when runtime deployment lock is violated or invalid."""
    pass


class DeploymentLock:
    """
    Guarantees single active runtime deployment.
    Prevents per-request or runtime switching.
    """

    @classmethod
    def get_active_runtime(cls) -> str:
        active = os.environ.get("DATAAGENT_RUNTIME_KIND", DEFAULT_RUNTIME).strip().lower()
        if active not in SUPPORTED_RUNTIMES:
            raise DeploymentLockError(
                f"Unsupported runtime '{active}'. Active deployment must be one of: {SUPPORTED_RUNTIMES}"
            )
        return active

    @classmethod
    def verify_request_runtime(cls, requested_runtime: str) -> None:
        active = cls.get_active_runtime()
        if requested_runtime != active:
            raise DeploymentLockError(
                f"Runtime switching is forbidden by deployment lock. Active: '{active}', Requested: '{requested_runtime}'"
            )
