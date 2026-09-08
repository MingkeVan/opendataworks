/**
 * Monotonic sequencing and terminal-event enforcement for one run.
 *
 * The Python side drops any event whose sequence is not strictly increasing, so
 * gaps or repeats here mean lost events downstream.
 *
 * A post-terminal event is *dropped*, not thrown: these are produced inside
 * async agent-event listeners, and throwing there surfaces as an unhandled
 * rejection that kills the process after the run has already settled correctly.
 */

import type { AgentEventType, NeutralAgentEvent } from "../protocol/frames.js";

const TERMINAL_TYPES: ReadonlySet<AgentEventType> = new Set<AgentEventType>([
  "run.completed",
  "run.failed",
  "run.cancelled",
  "run.suspended",
]);

export class RunStateMachine {
  private sequence = 0;
  private terminalEmitted = false;

  constructor(
    private readonly runId: string,
    private readonly taskId: string,
    private readonly taskAttemptId: string
  ) {}

  public get lastSequence(): number {
    return this.sequence;
  }

  public get isSettled(): boolean {
    return this.terminalEmitted;
  }

  /** Returns null when the event must be suppressed. */
  public createEvent(type: AgentEventType, payload: Record<string, unknown>): NeutralAgentEvent | null {
    if (this.terminalEmitted) {
      return null;
    }
    this.sequence += 1;
    if (TERMINAL_TYPES.has(type)) {
      this.terminalEmitted = true;
    }
    return {
      event_id: `ev-${this.runId}-${this.sequence}`,
      run_id: this.runId,
      task_id: this.taskId,
      task_attempt_id: this.taskAttemptId,
      sequence: this.sequence,
      timestamp: new Date().toISOString(),
      type,
      payload,
    };
  }
}
