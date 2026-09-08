import type { AgentEvent as PiAgentEvent } from "@earendil-works/pi-agent-core";
import type { AgentEvent } from "../contracts/agent-events.js";
import type { RunStateMachine } from "./run-state-machine.js";
import { Redactor } from "../observability/redaction.js";

export class PiEventNormalizer {
  private sm: RunStateMachine;
  private currentTurnId: string = "turn-0";
  private turnCounter: number = 0;

  constructor(sm: RunStateMachine) {
    this.sm = sm;
  }

  public normalize(piEvent: PiAgentEvent): AgentEvent[] {
    const events: AgentEvent[] = [];

    switch (piEvent.type) {
      case "turn_start": {
        this.turnCounter++;
        this.currentTurnId = `turn-${this.turnCounter}`;
        events.push(
          this.sm.createEvent("turn.started", {
            turn_id: this.currentTurnId,
          })
        );
        break;
      }
      case "message_update": {
        const ev = piEvent.assistantMessageEvent;
        if (ev.type === "thinking_delta") {
          events.push(
            this.sm.createEvent("content.delta", {
              turn_id: this.currentTurnId,
              content_id: `c-${ev.contentIndex}`,
              kind: "reasoning",
              delta: ev.delta,
            })
          );
        } else if (ev.type === "text_delta") {
          events.push(
            this.sm.createEvent("content.delta", {
              turn_id: this.currentTurnId,
              content_id: `c-${ev.contentIndex}`,
              kind: "answer",
              delta: ev.delta,
            })
          );
        }
        break;
      }
      case "tool_execution_start": {
        events.push(
          this.sm.createEvent("tool.started", {
            turn_id: this.currentTurnId,
            tool_call_id: piEvent.toolCallId,
            tool_name: piEvent.toolName,
            canonical_tool_id: piEvent.toolName,
            input: Redactor.redactObject(piEvent.args || {}),
          })
        );
        break;
      }
      case "tool_execution_update": {
        events.push(
          this.sm.createEvent("tool.progress", {
            turn_id: this.currentTurnId,
            tool_call_id: piEvent.toolCallId,
            tool_name: piEvent.toolName,
            progress: Redactor.redactObject(piEvent.partialResult?.details || {}),
          })
        );
        break;
      }
      case "tool_execution_end": {
        events.push(
          this.sm.createEvent("tool.completed", {
            turn_id: this.currentTurnId,
            tool_call_id: piEvent.toolCallId,
            tool_name: piEvent.toolName,
            output: Redactor.redactObject(piEvent.result?.details || {}),
            is_error: piEvent.isError,
          })
        );
        break;
      }
      case "turn_end": {
        events.push(
          this.sm.createEvent("turn.completed", {
            turn_id: this.currentTurnId,
          })
        );
        break;
      }
    }

    return events;
  }
}
