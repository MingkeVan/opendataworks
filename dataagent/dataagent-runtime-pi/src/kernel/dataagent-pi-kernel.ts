import { type Agent, type AgentEvent as PiAgentEvent } from "@earendil-works/pi-agent-core";
import type {
  AgentKernel,
  AgentRunRequest,
  AgentEventSink,
  KernelRunResult,
  ResolveInteractionCommand,
  CancelRunCommand,
  RuntimeManifest,
} from "../contracts/runtime.js";
import { RunStateMachine } from "./run-state-machine.js";
import { createPiAgent } from "./pi-agent-factory.js";
import type { StreamFn } from "@earendil-works/pi-agent-core";

export interface DataAgentPiKernelOptions {
  streamFn?: StreamFn;
  version?: string;
  tools?: any[];
}

export class DataAgentPiKernel implements AgentKernel {
  public readonly kind = "pi_agent_core" as const;
  public readonly version: string;
  private defaultStreamFn?: StreamFn;
  private registeredTools: any[];

  // Active run state
  private activeAgent: Agent | null = null;
  private activeStateMachine: RunStateMachine | null = null;
  private pendingInteractions = new Map<
    string,
    (result: { block?: boolean; reason?: string; terminate?: boolean }) => void
  >();

  constructor(options: DataAgentPiKernelOptions = {}) {
    this.version = options.version || "0.85.1";
    this.defaultStreamFn = options.streamFn;
    this.registeredTools = options.tools || [];
  }

  public manifest(): RuntimeManifest {
    return {
      runtime_kind: "pi_agent_core",
      runtime_version: this.version,
      pi_agent_core_version: "0.85.1",
      pi_ai_version: "0.85.1",
      node_version: process.version,
      runtime_protocol_versions: [1],
      agent_event_protocol_versions: [1],
      context_renderer_versions: [1],
      providers: ["anthropic", "openai", "faux"],
      features: {
        streaming: true,
        reasoning: true,
        tools: true,
        tool_progress: true,
        permission_interaction: true,
        question_interaction: true,
        plan_interaction: true,
        cancel: true,
        steer: true,
        follow_up: true,
        mcp: true,
        skills: true,
      },
      limits: {
        max_turns: 30,
        max_tool_calls: 50,
        max_output_tokens: 4096,
      },
      artifact_digest: "sha256:pi-runtime-cell-v1",
    };
  }

  public async run(request: AgentRunRequest, sink: AgentEventSink): Promise<KernelRunResult> {
    if (this.activeAgent) {
      throw new Error(`Cell already has an active run: ${this.activeStateMachine?.status}`);
    }

    const sm = new RunStateMachine(request.run_id, request.task_id, request.task_attempt_id);
    this.activeStateMachine = sm;

    // Helper to dispatch event through state machine and sink
    const emitEvent = async (type: any, payload: any) => {
      const event = sm.createEvent(type, payload);
      await sink(event);
    };

    const streamFn = this.defaultStreamFn;
    if (!streamFn) {
      throw new Error("No streamFn configured for Pi Kernel");
    }

    let turnCount = 0;
    let accumulatedAnswer = "";

    const agent = createPiAgent({
      request,
      streamFn,
      tools: this.registeredTools,
      beforeToolCall: async (context, signal) => {
        // Check if policy requires permission
        const toolName = context.toolCall.name;
        const requiresConfirmation =
          request.policy?.require_write_confirmation &&
          (toolName === "Bash" || toolName.startsWith("write_") || toolName.startsWith("drop_"));

        if (!requiresConfirmation) {
          return undefined;
        }

        const interactionId = `act-${request.run_id}-${Date.now()}`;
        await emitEvent("interaction.requested", {
          turn_id: `turn-${turnCount}`,
          interaction_id: interactionId,
          kind: "permission",
          prompt: `Confirm execution of tool '${toolName}'?`,
          context_preview: { toolCall: context.toolCall },
        });

        if (signal?.aborted) {
          return { block: true, reason: "Operation aborted", terminate: true };
        }

        return new Promise((resolve) => {
          this.pendingInteractions.set(interactionId, resolve);
          signal?.addEventListener(
            "abort",
            () => {
              this.pendingInteractions.delete(interactionId);
              resolve({ block: true, reason: "Cancelled by user abort", terminate: true });
            },
            { once: true }
          );
        });
      },
      afterToolCall: async (context) => {
        return undefined;
      },
    });

    this.activeAgent = agent;
    sm.markStarted();
    await emitEvent("run.started", {});

    // Subscribe to Pi Agent events
    agent.subscribe(async (piEvent: PiAgentEvent) => {
      switch (piEvent.type) {
        case "turn_start": {
          turnCount++;
          await emitEvent("turn_start", { turn_id: `turn-${turnCount}` });
          break;
        }
        case "message_update": {
          const ev = piEvent.assistantMessageEvent;
          if (ev.type === "thinking_delta") {
            await emitEvent("content.delta", {
              turn_id: `turn-${turnCount}`,
              content_id: `c-${ev.contentIndex}`,
              kind: "reasoning",
              delta: ev.delta,
            });
          } else if (ev.type === "text_delta") {
            accumulatedAnswer += ev.delta;
            await emitEvent("content.delta", {
              turn_id: `turn-${turnCount}`,
              content_id: `c-${ev.contentIndex}`,
              kind: "answer",
              delta: ev.delta,
            });
          }
          break;
        }
        case "tool_execution_start": {
          await emitEvent("tool.started", {
            turn_id: `turn-${turnCount}`,
            tool_call_id: piEvent.toolCallId,
            tool_name: piEvent.toolName,
            canonical_tool_id: piEvent.toolName,
            input: piEvent.args,
          });
          break;
        }
        case "tool_execution_update": {
          await emitEvent("tool.progress", {
            turn_id: `turn-${turnCount}`,
            tool_call_id: piEvent.toolCallId,
            tool_name: piEvent.toolName,
            progress: piEvent.partialResult?.details ?? {},
          });
          break;
        }
        case "tool_execution_end": {
          await emitEvent("tool.completed", {
            turn_id: `turn-${turnCount}`,
            tool_call_id: piEvent.toolCallId,
            tool_name: piEvent.toolName,
            output: piEvent.result?.details ?? {},
            is_error: piEvent.isError,
          });
          break;
        }
        case "turn_end": {
          await emitEvent("turn.completed", { turn_id: `turn-${turnCount}` });
          break;
        }
        case "agent_end": {
          // agent_end is the final barrier
          break;
        }
      }
    });

    try {
      // Build prompt from context messages or initial prompt
      const promptInput = request.context.messages.length > 0
        ? request.context.messages.map((m) => ({
            role: m.role as any,
            content: [{ type: "text" as const, text: m.content }],
            timestamp: Date.now(),
          }))
        : "Hello";

      await agent.prompt(promptInput as any);

      // Terminal event
      let terminalStatus: "success" | "cancelled" | "failed" = "success";
      if (agent.signal?.aborted) {
        terminalStatus = "cancelled";
        await emitEvent("run.cancelled", { reason: "User aborted" });
      } else {
        await emitEvent("run.completed", {
          terminal_status: "success",
          answer: accumulatedAnswer,
        });
      }

      return {
        run_id: request.run_id,
        task_attempt_id: request.task_attempt_id,
        terminal_status: terminalStatus,
        last_sequence: sm.lastSequence,
        answer: accumulatedAnswer,
      };
    } catch (err: any) {
      const errorMsg = err instanceof Error ? err.message : String(err);
      await emitEvent("run.failed", {
        terminal_status: "failed",
        error_code: "EXECUTION_ERROR",
        error_message: errorMsg,
      });

      return {
        run_id: request.run_id,
        task_attempt_id: request.task_attempt_id,
        terminal_status: "failed",
        last_sequence: sm.lastSequence,
        error: errorMsg,
      };
    } finally {
      this.activeAgent = null;
      this.activeStateMachine = null;
      this.pendingInteractions.clear();
    }
  }

  public async resolveInteraction(command: ResolveInteractionCommand): Promise<void> {
    const resolver = this.pendingInteractions.get(command.interaction_id);
    if (!resolver) {
      throw new Error(`Unknown or already resolved interaction: ${command.interaction_id}`);
    }
    this.pendingInteractions.delete(command.interaction_id);

    if (command.decision === "allow") {
      resolver(undefined as any);
    } else {
      resolver({ block: true, reason: command.answer || "Denied by user", terminate: true });
    }
  }

  public async cancel(command: CancelRunCommand): Promise<void> {
    if (this.activeAgent) {
      this.activeAgent.abort();
    }
  }
}
