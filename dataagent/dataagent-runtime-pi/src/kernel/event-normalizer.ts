/**
 * Pi AgentEvent -> neutral AgentEvent.
 *
 * This is the *only* place events are produced. Inlining a second copy of this
 * mapping next to the agent subscription is how the two drift: a duplicate that
 * emitted "turn_start" instead of "turn.started" would be rejected outright by
 * the Python contract (AgentEventType is a closed enum) and every turn event
 * would silently vanish.
 *
 * Payload keys are chosen to match what the record projections and the frontend
 * render adapter already read — `output`/`is_error`, not `result`/`error` — so a
 * Pi turn renders through the same components as an SDK turn.
 */

import type { AgentEvent as PiAgentEvent } from "@earendil-works/pi-agent-core";
import type { NeutralAgentEvent } from "../protocol/frames.js";
import type { RunStateMachine } from "./run-state-machine.js";
import { redact } from "../observability/redaction.js";

export class EventNormalizer {
  private turnCounter = 0;
  private currentTurnId = "turn-0";
  private contentCounter = 0;

  constructor(private readonly sm: RunStateMachine) {}

  public get turnId(): string {
    return this.currentTurnId;
  }

  public normalize(piEvent: PiAgentEvent): NeutralAgentEvent[] {
    const events: Array<NeutralAgentEvent | null> = [];

    switch (piEvent.type) {
      case "turn_start": {
        this.turnCounter += 1;
        this.currentTurnId = `turn-${this.turnCounter}`;
        this.contentCounter = 0;
        events.push(this.sm.createEvent("turn.started", { turn_id: this.currentTurnId }));
        break;
      }
      case "message_update": {
        const ev = piEvent.assistantMessageEvent as { type?: string; delta?: string; contentIndex?: number };
        const kind = ev?.type === "thinking_delta" ? "reasoning" : ev?.type === "text_delta" ? "answer" : null;
        if (kind && ev.delta) {
          const index = typeof ev.contentIndex === "number" ? ev.contentIndex : this.contentCounter;
          events.push(
            this.sm.createEvent("content.delta", {
              turn_id: this.currentTurnId,
              content_id: `c-${index}`,
              kind,
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
            input: redact(piEvent.args ?? {}),
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
            progress: redact(piEvent.partialResult ?? {}),
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
            output: redact(piEvent.result ?? null),
            is_error: Boolean(piEvent.isError),
          })
        );
        break;
      }
      case "turn_end": {
        events.push(this.sm.createEvent("turn.completed", { turn_id: this.currentTurnId }));
        break;
      }
      default:
        break;
    }

    return events.filter((e): e is NeutralAgentEvent => e !== null);
  }
}
