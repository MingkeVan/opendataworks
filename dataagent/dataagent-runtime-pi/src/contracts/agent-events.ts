/**
 * Neutral Agent Event Protocol v1
 */

export type AgentEventType =
  | "run.started"
  | "turn.started"
  | "content.started"
  | "content.delta"
  | "content.completed"
  | "tool.started"
  | "tool.progress"
  | "tool.completed"
  | "interaction.requested"
  | "interaction.resolved"
  | "usage.updated"
  | "turn.completed"
  | "run.completed"
  | "run.failed"
  | "run.cancelled"
  | "run.suspended";

export interface AgentEvent<T = Record<string, unknown>> {
  event_id: string;
  run_id: string;
  task_id: string;
  task_attempt_id: string;
  sequence: number;
  timestamp: string;
  type: AgentEventType;
  payload: T;
}

export type ContentKind = "answer" | "reasoning";

export interface ContentPayload {
  turn_id: string;
  content_id: string;
  kind: ContentKind;
  delta?: string;
  text?: string;
}

export interface ToolStartedPayload {
  turn_id: string;
  tool_call_id: string;
  tool_name: string;
  canonical_tool_id: string;
  input: Record<string, unknown>;
}

export interface ToolProgressPayload {
  turn_id: string;
  tool_call_id: string;
  tool_name: string;
  progress: Record<string, unknown>;
}

export interface ToolCompletedPayload {
  turn_id: string;
  tool_call_id: string;
  tool_name: string;
  output: Record<string, unknown>;
  is_error: boolean;
}

export interface InteractionRequestedPayload {
  turn_id: string;
  interaction_id: string;
  kind: "permission" | "question" | "plan";
  prompt: string;
  options?: string[];
  context_preview?: Record<string, unknown>;
  timeout_seconds?: number;
}

export interface InteractionResolvedPayload {
  turn_id: string;
  interaction_id: string;
  decision: "allow" | "deny" | "answered" | "timeout" | "cancelled";
  answer?: string;
  selected_options?: string[];
}

export interface UsagePayload {
  turn_id: string;
  input_tokens?: number;
  output_tokens?: number;
  total_tokens?: number;
}

export interface RunCompletedPayload {
  terminal_status: "success";
  answer?: string;
  usage?: Record<string, unknown>;
  result_summary?: string;
}

export interface RunFailedPayload {
  terminal_status: "failed";
  error_code: string;
  error_message: string;
}

export interface RunCancelledPayload {
  terminal_status: "cancelled";
  reason?: string;
}

export interface RunSuspendedPayload {
  terminal_status: "suspended";
  reason?: string;
}
