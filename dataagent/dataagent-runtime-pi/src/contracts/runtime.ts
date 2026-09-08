import type { AgentEvent } from "./agent-events.js";

export type Purpose = "interactive" | "background" | "eval";

export interface ConversationMessage {
  message_id?: string;
  role: "user" | "assistant" | "system" | "custom";
  content: string;
  attachments?: unknown[];
  created_at?: string;
}

export interface ContextBundle {
  context_snapshot_id: string;
  history_watermark?: string;
  policy_version: string;
  renderer_target: "pi_agent_core";
  system_instructions: string;
  messages: ConversationMessage[];
  attachments?: unknown[];
  artifacts?: unknown[];
  enabled_skills?: string[];
  tool_catalog_digest?: string;
  data_scope?: Record<string, unknown>;
  locale?: string;
  timezone?: string;
  content_digest?: string;
}

export interface ModelTarget {
  provider_id: string;
  model_id: string;
  endpoint_ref?: string;
  region?: string;
  options?: Record<string, unknown>;
}

export interface WorkspaceSpec {
  workspace_root: string;
  scratch_root?: string;
  read_only?: boolean;
}

export interface SkillSpec {
  name: string;
  root_path: string;
}

export interface McpServerSpec {
  name: string;
  transport: "stdio" | "streamable_http";
  endpoint?: string;
  token?: string;
  tool_allowlist?: string[];
}

export interface ExecutionPolicySnapshot {
  policy_version?: string;
  require_plan_approval?: boolean;
  require_write_confirmation?: boolean;
  allowed_tools?: string[];
}

export interface RunLimits {
  timeout_seconds: number;
  idle_timeout_seconds?: number;
  max_turns?: number;
  max_tool_calls?: number;
  max_output_tokens?: number;
}

export interface SecretEnvelope {
  api_key?: string;
  mcp_tokens?: Record<string, string>;
  custom_headers?: Record<string, string>;
}

export interface AgentRunRequest {
  runtime_protocol_version: 1;
  agent_event_protocol_version: 1;
  run_id: string;
  task_id: string;
  task_attempt_id: string;
  topic_id: string;
  purpose: Purpose;
  context: ContextBundle;
  model: ModelTarget;
  workspace: WorkspaceSpec;
  skills?: SkillSpec[];
  mcp_servers?: McpServerSpec[];
  policy?: ExecutionPolicySnapshot;
  limits: RunLimits;
  secret_envelope?: SecretEnvelope;
}

export interface RuntimeManifest {
  runtime_kind: "pi_agent_core";
  runtime_version: string;
  pi_agent_core_version: string;
  pi_ai_version: string;
  node_version: string;
  runtime_protocol_versions: number[];
  agent_event_protocol_versions: number[];
  context_renderer_versions: number[];
  providers: string[];
  features: {
    streaming: boolean;
    reasoning: boolean;
    tools: boolean;
    tool_progress: boolean;
    permission_interaction: boolean;
    question_interaction: boolean;
    plan_interaction: boolean;
    cancel: boolean;
    steer: boolean;
    follow_up: boolean;
    mcp: boolean;
    skills: boolean;
  };
  limits: Record<string, unknown>;
  artifact_digest: string;
}

export type CellFrameType =
  | "hello"
  | "hello.ack"
  | "run.start"
  | "run.accepted"
  | "run.event"
  | "run.heartbeat"
  | "interaction.resolve"
  | "run.steer"
  | "run.follow_up"
  | "run.cancel"
  | "run.settled"
  | "cell.shutdown"
  | "protocol.error";

export interface CellProtocolFrame<T = Record<string, unknown>> {
  protocol_version: 1;
  cell_id: string;
  run_id: string;
  task_attempt_id: string;
  frame_id: string;
  type: CellFrameType;
  payload: T;
}

export interface KernelRunResult {
  run_id: string;
  task_attempt_id: string;
  terminal_status: "success" | "failed" | "cancelled" | "suspended";
  last_sequence: number;
  answer?: string;
  usage?: Record<string, unknown>;
  error?: string;
}

export type AgentEventSink = (event: AgentEvent) => Promise<void> | void;

export interface ResolveInteractionCommand {
  run_id: string;
  interaction_id: string;
  decision: "allow" | "deny" | "answered" | "timeout" | "cancelled";
  answer?: string;
  selected_options?: string[];
}

export interface CancelRunCommand {
  run_id: string;
  reason?: string;
}

export interface AgentKernel {
  readonly kind: "pi_agent_core";
  readonly version: string;
  manifest(): RuntimeManifest;
  run(request: AgentRunRequest, sink: AgentEventSink): Promise<KernelRunResult>;
  resolveInteraction(command: ResolveInteractionCommand): Promise<void>;
  cancel(command: CancelRunCommand): Promise<void>;
}
