/**
 * One run on one Cell.
 *
 * Owns the agent lifecycle for a single cell.init and guarantees the control
 * plane always sees exactly one terminal event, whatever happens: normal
 * completion, model error, policy denial, cancel, or a limit being hit.
 */

import { Agent } from "@earendil-works/pi-agent-core";
import type { AgentEvent as PiAgentEvent, StreamFn } from "@earendil-works/pi-agent-core";
import type { Api, Model } from "@earendil-works/pi-ai";
import type { CellInitPayload, NeutralAgentEvent } from "../protocol/frames.js";
import { RunStateMachine } from "./run-state-machine.js";
import { EventNormalizer } from "./event-normalizer.js";
import { WorkspaceBoundaryEnforcer, type BoundaryPolicy } from "../policy/workspace-boundary-enforcer.js";
import { createTools } from "../tools/tool-registry.js";
import { logDiagnostic } from "../protocol/channel.js";

export type EventSink = (event: NeutralAgentEvent) => void;

export interface RunModelFactory {
  (providerId: string, modelId: string): { model: Model<Api>; streamFn: StreamFn };
}

export interface CellRunResult {
  terminal_status: "success" | "failed" | "cancelled";
  last_sequence: number;
  error?: string;
}

export class Cell {
  private agent: Agent | null = null;
  private cancelled = false;

  constructor(private readonly modelFactory: RunModelFactory) {}

  public cancel(): void {
    this.cancelled = true;
    this.agent?.abort();
  }

  public async run(init: CellInitPayload, sink: EventSink): Promise<CellRunResult> {
    const sm = new RunStateMachine(init.run_id, init.task_id, init.run_id);
    const normalizer = new EventNormalizer(sm);

    const emit = (event: NeutralAgentEvent | null) => {
      if (event) {
        sink(event);
      }
    };

    emit(sm.createEvent("run.started", { topic_id: init.topic_id }));

    let toolCalls = 0;
    let turnCount = 0;
    let limitDenial: string | null = null;

    try {
      const { model, streamFn } = this.modelFactory(init.model.provider_id, init.model.model_id);
      const boundary = new WorkspaceBoundaryEnforcer(init.boundary_policy as unknown as BoundaryPolicy);
      const tools = createTools({
        boundary,
        workspaceRoot: init.workspace.project_cwd,
        runtimeEnv: init.runtime_env ?? {},
      });

      const agent = new Agent({
        initialState: {
          systemPrompt: init.system_prompt,
          model,
          tools: tools as never,
          messages: [],
        },
        streamFn,
        // DataAgent tools mutate shared workspace state and issue SQL; running
        // them concurrently would make ordering — and therefore the boundary
        // decisions and the event stream — nondeterministic.
        toolExecution: "sequential",
        beforeToolCall: async (context) => {
          toolCalls += 1;
          if (init.limits.max_tool_calls > 0 && toolCalls > init.limits.max_tool_calls) {
            limitDenial = `已达到单轮工具调用上限 ${init.limits.max_tool_calls}`;
            return { block: true, reason: limitDenial, terminate: true };
          }
          // Second line of defence. The tools enforce the boundary themselves,
          // but a tool added later must not be able to skip it by forgetting.
          const toolName = String(context.toolCall?.name ?? "");
          const args = (context.toolCall?.arguments ?? {}) as Record<string, unknown>;
          const reason = boundary.validate(toolName, args);
          if (reason) {
            emit(
              sm.createEvent("tool.denied", {
                turn_id: normalizer.turnId,
                tool_name: toolName,
                reason,
              })
            );
            return { block: true, reason };
          }
          return undefined;
        },
        shouldStopAfterTurn: () => {
          if (init.limits.max_turns <= 0) {
            return false;
          }
          return sm.lastSequence > 0 && turnCount >= init.limits.max_turns;
        },
      });

      this.agent = agent;

      agent.subscribe((piEvent: PiAgentEvent) => {
        if (piEvent.type === "turn_start") {
          turnCount += 1;
        }
        for (const event of normalizer.normalize(piEvent)) {
          emit(event);
        }
      });

      await agent.prompt(
        init.messages.map((message) => ({
          role: message.role === "assistant" ? ("assistant" as const) : ("user" as const),
          content: [{ type: "text" as const, text: message.content }],
          timestamp: Date.now(),
        })) as never
      );

      if (this.cancelled || agent.signal?.aborted) {
        emit(sm.createEvent("run.cancelled", { reason: "cancelled by control plane" }));
        return { terminal_status: "cancelled", last_sequence: sm.lastSequence };
      }
      if (limitDenial) {
        emit(sm.createEvent("run.failed", { error_code: "PI_LIMIT_EXCEEDED", message: limitDenial }));
        return { terminal_status: "failed", last_sequence: sm.lastSequence, error: limitDenial };
      }

      // agent.prompt() resolves rather than rejects when the model stream
      // fails: the failure is recorded on the agent state as errorMessage.
      // Reporting success here would persist a successful-looking turn that
      // carries no answer, so the state has to be consulted explicitly.
      const modelError = agent.state.errorMessage;
      if (modelError) {
        emit(sm.createEvent("run.failed", { error_code: "PI_MODEL_ERROR", message: modelError }));
        return { terminal_status: "failed", last_sequence: sm.lastSequence, error: modelError };
      }

      emit(sm.createEvent("run.completed", { terminal_status: "success" }));
      return { terminal_status: "success", last_sequence: sm.lastSequence };
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      logDiagnostic(`run failed: ${message}`);
      // Cancel surfaces as an abort rejection from the agent loop; it is a
      // cancellation, not a failure, and must not be reported as an error.
      if (this.cancelled) {
        emit(sm.createEvent("run.cancelled", { reason: "cancelled by control plane" }));
        return { terminal_status: "cancelled", last_sequence: sm.lastSequence };
      }
      emit(sm.createEvent("run.failed", { error_code: "PI_EXECUTION_ERROR", message }));
      return { terminal_status: "failed", last_sequence: sm.lastSequence, error: message };
    } finally {
      this.agent = null;
    }
  }
}
