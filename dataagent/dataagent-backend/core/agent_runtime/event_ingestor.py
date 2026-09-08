from __future__ import annotations

import logging
from typing import Any, Callable, Dict, List, Optional

from core.agent_runtime.contracts import (
    AgentEvent,
    AgentEventType,
    ConversationMessage,
)

logger = logging.getLogger(__name__)


class TaskEventIngestor:
    """
    Ingests neutral AgentEvents from runtime stream, tracks content state,
    and finalizes assistant message upon run completion.
    """

    def __init__(self, run_id: str, on_event: Optional[Callable[[AgentEvent], Any]] = None):
        self.run_id = run_id
        self.on_event = on_event
        self.highest_sequence: int = 0
        self.accumulated_text: List[str] = []
        self.reasoning_text: List[str] = []
        self.tool_calls: Dict[str, Dict[str, Any]] = {}
        self.is_terminal: bool = False
        self.terminal_event: Optional[AgentEvent] = None
        self.error_message: Optional[str] = None

    def ingest(self, event: AgentEvent) -> None:
        """
        Process a single AgentEvent in order.
        """
        if event.sequence <= self.highest_sequence:
            logger.warning("Duplicate/out-of-order event ignored: %s (highest: %s)", event.sequence, self.highest_sequence)
            return

        self.highest_sequence = event.sequence

        if event.type == AgentEventType.CONTENT_DELTA:
            delta = event.payload.get("delta", "")
            kind = event.payload.get("kind", "text")
            if kind == "reasoning":
                self.reasoning_text.append(delta)
            else:
                self.accumulated_text.append(delta)

        elif event.type == AgentEventType.TOOL_STARTED:
            call_id = event.payload.get("tool_call_id", "")
            if call_id:
                self.tool_calls[call_id] = {
                    "tool": event.payload.get("tool_name"),
                    "canonical_id": event.payload.get("canonical_tool_id"),
                    "input": event.payload.get("input"),
                    "status": "running",
                }

        elif event.type == AgentEventType.TOOL_COMPLETED:
            call_id = event.payload.get("tool_call_id", "")
            if call_id and call_id in self.tool_calls:
                self.tool_calls[call_id]["status"] = "completed"
                self.tool_calls[call_id]["result"] = event.payload.get("result")

        elif event.type == AgentEventType.RUN_FAILED:
            self.is_terminal = True
            self.terminal_event = event
            self.error_message = event.payload.get("message") or event.payload.get("error")

        elif event.type in (AgentEventType.RUN_COMPLETED, AgentEventType.RUN_CANCELLED, AgentEventType.RUN_SUSPENDED):
            self.is_terminal = True
            self.terminal_event = event

        if self.on_event:
            try:
                self.on_event(event)
            except Exception as e:
                logger.error("Error in on_event callback: %s", e)

    def finalize_assistant_message(self) -> Optional[ConversationMessage]:
        """
        Builds final assistant ConversationMessage once run is completed.
        Returns None if run failed or was cancelled before producing content.
        """
        if not self.is_terminal:
            return None

        if self.terminal_event and self.terminal_event.type != AgentEventType.RUN_COMPLETED:
            return None

        full_content = "".join(self.accumulated_text).strip()
        if not full_content:
            return None

        return ConversationMessage(
            role="assistant",
            content=full_content,
        )
