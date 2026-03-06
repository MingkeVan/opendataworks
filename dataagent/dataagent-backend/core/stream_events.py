from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any


TERMINAL_EVENT_TYPES = {"done"}


@dataclass
class EventSequencer:
    run_id: str
    session_id: str
    message_id: str
    seq: int = 0

    def next(self, event_type: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        self.seq += 1
        return {
            "run_id": self.run_id,
            "session_id": self.session_id,
            "message_id": self.message_id,
            "seq": self.seq,
            "type": event_type,
            "ts": datetime.now(timezone.utc).isoformat(),
            "payload": payload or {},
        }


def encode_sse(event: dict[str, Any]) -> str:
    return f"data: {json.dumps(event, ensure_ascii=False, separators=(',', ':'))}\n\n"
