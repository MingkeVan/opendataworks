import type { AgentEvent, AgentEventType } from "../contracts/agent-events.js";

export type RunStatus = "idle" | "starting" | "running" | "settling" | "settled";

export class RunStateMachine {
  private currentStatus: RunStatus = "idle";
  private currentSequence: number = 0;
  private terminalEmitted: boolean = false;
  private readonly runId: string;
  private readonly taskId: string;
  private readonly taskAttemptId: string;

  constructor(runId: string, taskId: string, taskAttemptId: string) {
    this.runId = runId;
    this.taskId = taskId;
    this.taskAttemptId = taskAttemptId;
  }

  public get status(): RunStatus {
    return this.currentStatus;
  }

  public get lastSequence(): number {
    return this.currentSequence;
  }

  public isSettled(): boolean {
    return this.currentStatus === "settled";
  }

  public markStarted(): void {
    if (this.currentStatus !== "idle") {
      throw new Error(`Cannot start run: current status is ${this.currentStatus}`);
    }
    this.currentStatus = "running";
  }

  public createEvent<T extends Record<string, unknown>>(
    type: AgentEventType,
    payload: T,
    timestamp: string = new Date().toISOString()
  ): AgentEvent<T> {
    if (this.terminalEmitted) {
      process.stderr.write(
        `[RunStateMachine] Warning: event '${type}' emitted after terminal event. Dropping.\n`
      );
      throw new Error(`Protocol violation: cannot emit '${type}' after terminal event in run ${this.runId}`);
    }

    this.currentSequence++;
    const isTerminal =
      type === "run.completed" ||
      type === "run.failed" ||
      type === "run.cancelled" ||
      type === "run.suspended";

    if (isTerminal) {
      this.terminalEmitted = true;
      this.currentStatus = "settled";
    }

    return {
      event_id: `ev-${this.runId}-${this.currentSequence}`,
      run_id: this.runId,
      task_id: this.taskId,
      task_attempt_id: this.taskAttemptId,
      sequence: this.currentSequence,
      timestamp,
      type,
      payload,
    };
  }

  public settle(): void {
    this.currentStatus = "settled";
  }
}
