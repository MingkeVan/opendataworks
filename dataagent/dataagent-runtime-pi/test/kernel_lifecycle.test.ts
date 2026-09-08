import test from "node:test";
import assert from "node:assert/strict";
import { DataAgentPiKernel } from "../src/kernel/dataagent-pi-kernel.js";
import type { AgentRunRequest, AgentEventSink } from "../src/contracts/runtime.js";
import { createAssistantMessageEventStream } from "@earendil-works/pi-ai";
import type { AgentEvent } from "../src/contracts/agent-events.js";

test("DataAgentPiKernel executes run and streams normalized events", async () => {
  const streamFn = (model: any, context: any, options: any) => {
    const stream = createAssistantMessageEventStream();
    queueMicrotask(() => {
      const msg = {
        role: "assistant" as const,
        content: [{ type: "text" as const, text: "Pi Kernel Response" }],
        api: "faux" as const,
        provider: "faux",
        model: "faux-1",
        usage: { inputTokens: 10, outputTokens: 5, totalTokens: 15 },
        stopReason: "stop" as const,
        timestamp: Date.now(),
      };
      stream.push({ type: "start", partial: msg as any });
      stream.push({
        type: "text_delta",
        contentIndex: 0,
        delta: "Pi Kernel Response",
        partial: msg as any,
      });
      stream.push({ type: "done", reason: "stop", message: msg as any });
      stream.end();
    });
    return stream;
  };

  const kernel = new DataAgentPiKernel({ streamFn });

  const manifest = kernel.manifest();
  assert.equal(manifest.runtime_kind, "pi_agent_core");
  assert.equal(manifest.features.streaming, true);

  const events: AgentEvent[] = [];
  const sink: AgentEventSink = (ev) => {
    events.push(ev);
  };

  const request: AgentRunRequest = {
    runtime_protocol_version: 1,
    agent_event_protocol_version: 1,
    run_id: "run-test-1",
    task_id: "task-1",
    task_attempt_id: "att-1",
    topic_id: "topic-1",
    purpose: "interactive",
    context: {
      context_snapshot_id: "snap-1",
      policy_version: "v1",
      renderer_target: "pi_agent_core",
      system_instructions: "System prompt",
      messages: [{ role: "user", content: "hello" }],
    },
    model: {
      provider_id: "faux",
      model_id: "faux-1",
    },
    workspace: {
      workspace_root: "/tmp",
    },
    limits: {
      timeout_seconds: 60,
    },
  };

  const result = await kernel.run(request, sink);

  assert.equal(result.terminal_status, "success");
  assert.equal(result.answer, "Pi Kernel Response");
  assert.ok(events.length >= 3);
  assert.equal(events[0].type, "run.started");
  assert.equal(events[events.length - 1].type, "run.completed");
});
