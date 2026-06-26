from __future__ import annotations

import asyncio

import pytest

from core import ask_user_question, permission_wait
from core.task_control import RunnerStoppedError, TaskCancelledError


def test_wait_for_decision_returns_persisted_decision(monkeypatch):
    class Store:
        def get_resolved_permission_decision(self, *, task_id: str, request_id: str):
            return "allow"

    monkeypatch.setattr(permission_wait, "get_topic_task_store", lambda: Store())

    result = asyncio.run(permission_wait.wait_for_decision("task-1", "req-1", poll_interval_seconds=0.1))

    assert result == "allow"


def test_wait_for_decision_raises_on_cancel_reason():
    with pytest.raises(TaskCancelledError):
        asyncio.run(
            permission_wait.wait_for_decision(
                "task-1",
                "req-1",
                poll_interval_seconds=0.1,
                cancel_reason=lambda: "user_cancel",
            )
        )

    with pytest.raises(RunnerStoppedError):
        asyncio.run(
            permission_wait.wait_for_decision(
                "task-1",
                "req-1",
                poll_interval_seconds=0.1,
                cancel_reason=lambda: "runner_stop",
            )
        )


def test_wait_for_answer_returns_persisted_answers(monkeypatch):
    answers = [{"question": "维度?", "selected": ["按天"]}]

    class Store:
        def get_resolved_question_answer(self, *, task_id: str, request_id: str):
            return answers

    from core import topic_task_store

    monkeypatch.setattr(topic_task_store, "get_topic_task_store", lambda: Store())

    result = asyncio.run(ask_user_question.wait_for_answer("task-1", "req-1", poll_interval_seconds=0.1))

    assert result == answers


def test_wait_for_answer_raises_on_cancel_reason():
    with pytest.raises(TaskCancelledError):
        asyncio.run(
            ask_user_question.wait_for_answer(
                "task-1",
                "req-1",
                poll_interval_seconds=0.1,
                cancel_reason=lambda: "user_cancel",
            )
        )

    with pytest.raises(RunnerStoppedError):
        asyncio.run(
            ask_user_question.wait_for_answer(
                "task-1",
                "req-1",
                poll_interval_seconds=0.1,
                cancel_reason=lambda: "runner_stop",
            )
        )
