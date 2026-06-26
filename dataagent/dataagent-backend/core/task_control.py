from __future__ import annotations

from typing import Literal

CancelReason = Literal["user_cancel", "runner_stop"]
PARKED_TASK_STATUSES = {"waiting_permission", "waiting_input"}


class TaskCancelledError(Exception):
    """Raised when the user explicitly cancels a task."""


class RunnerStoppedError(Exception):
    """Raised when the execution runner/lease stops while a task is active."""
