from __future__ import annotations

import asyncio

import anyio

from core.task_coordinator import TaskCoordinator


class _FakeRedis:
    def __init__(self, lease_owner: str):
        self._lease_owner = lease_owner
        self.expire_calls = 0

    async def get(self, key: str):
        return self._lease_owner

    async def expire(self, key: str, ttl: int):
        self.expire_calls += 1
        return True


def test_heartbeat_tick_renews_lease_while_queued_without_db_heartbeat():
    class FakeStore:
        def __init__(self):
            self.heartbeats = 0

        def heartbeat_task(self, task_id):
            self.heartbeats += 1

    coordinator = TaskCoordinator(store=FakeStore())
    coordinator._redis = _FakeRedis(coordinator.instance_id)
    running = asyncio.Event()

    async def run():
        queued = await coordinator._heartbeat_tick("task-1", running=running)
        running.set()
        active = await coordinator._heartbeat_tick("task-1", running=running)
        return queued, active

    queued, active = anyio.run(run)

    # Lease is renewed in both phases ...
    assert queued is True
    assert active is True
    assert coordinator._redis.expire_calls == 2
    # ... but the DB heartbeat is only written once the task is actually running.
    assert coordinator.store.heartbeats == 1


def test_heartbeat_tick_reports_lost_lease():
    class FakeStore:
        def heartbeat_task(self, task_id):
            pass

    coordinator = TaskCoordinator(store=FakeStore())
    # Lease is held by a different instance -> renewal must fail.
    coordinator._redis = _FakeRedis("another-instance")
    running = asyncio.Event()
    running.set()

    assert anyio.run(lambda: coordinator._heartbeat_tick("task-1", running=running)) is False


def test_task_coordinator_persists_only_emitted_sdk_records():
    appended: list[dict] = []

    class FakeStore:
        def append_sdk_record(self, **kwargs):
            appended.append(kwargs)

    coordinator = TaskCoordinator(store=FakeStore())

    coordinator._persist_emitted_sdk_record(
        topic_id="topic-1",
        task_id="task-1",
        record={
            "record_type": "stream",
            "event_type": "message_start",
            "turn_index": 2,
            "data": {"type": "message_start"},
        },
    )
    coordinator._persist_emitted_sdk_record(
        topic_id="topic-1",
        task_id="task-1",
        record={
            "record_type": "event",
            "event_type": "lifecycle",
            "data": {"legacy": True},
        },
    )

    assert appended == [
        {
            "task_id": "task-1",
            "topic_id": "topic-1",
            "turn_index": 2,
            "record_type": "stream",
            "event_type": "message_start",
            "data": {"type": "message_start"},
        }
    ]


def test_task_stop_reason_distinguishes_lease_loss_from_user_cancel():
    class FakeStore:
        def __init__(self):
            self.cancel_requested = False

        def is_task_cancel_requested(self, task_id):
            return self.cancel_requested

    store = FakeStore()
    coordinator = TaskCoordinator(store=store)

    async def run():
        lease_lost = asyncio.Event()
        none_reason = await coordinator._task_stop_reason("task-1", lease_lost)
        store.cancel_requested = True
        user_reason = await coordinator._task_stop_reason("task-1", lease_lost)
        lease_lost.set()
        runner_reason = await coordinator._task_stop_reason("task-1", lease_lost)
        return none_reason, user_reason, runner_reason

    assert anyio.run(run) == (None, "user_cancel", "runner_stop")


def test_recover_parked_tasks_leaves_unresolved_parked_task_untouched():
    class FakeStore:
        def __init__(self):
            self.finished = []
            self.messages = []

        def list_parked_tasks(self, *, limit=20):
            return [{"task_id": "task-1", "topic_id": "topic-1", "task_status": "waiting_permission"}]

        def is_task_cancel_requested(self, task_id):
            return False

        def has_resolved_waiting_interaction(self, task_id):
            return False

        def update_assistant_message(self, **kwargs):
            self.messages.append(kwargs)

        def finish_task(self, **kwargs):
            self.finished.append(kwargs)

    coordinator = TaskCoordinator(store=FakeStore())

    anyio.run(lambda: coordinator._recover_parked_tasks(batch_size=10))

    assert coordinator.store.messages == []
    assert coordinator.store.finished == []


def test_recover_parked_tasks_suspends_resolved_orphan():
    class FakeStore:
        def __init__(self):
            self.finished = []
            self.messages = []

        def list_parked_tasks(self, *, limit=20):
            return [{"task_id": "task-1", "topic_id": "topic-1", "task_status": "waiting_input"}]

        def is_task_cancel_requested(self, task_id):
            return False

        def has_resolved_waiting_interaction(self, task_id):
            return True

        def update_assistant_message(self, **kwargs):
            self.messages.append(kwargs)

        def finish_task(self, **kwargs):
            self.finished.append(kwargs)

    coordinator = TaskCoordinator(store=FakeStore())

    anyio.run(lambda: coordinator._recover_parked_tasks(batch_size=10))

    assert coordinator.store.messages[0]["status"] == "suspended"
    assert coordinator.store.messages[0]["error"]["code"] == "run_lost"
    assert coordinator.store.finished == [
        {
            "task_id": "task-1",
            "task_status": "suspended",
            "error": {
                "code": "run_lost",
                "message": "确认/输入已提交，但原运行器已释放，请重新发送请求继续。",
            },
        }
    ]
