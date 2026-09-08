import test from "node:test";
import assert from "node:assert/strict";
import { PassThrough } from "node:stream";
import { CellChannel } from "../src/server/cell-channel.js";
import { RunService } from "../src/server/run-service.js";
import { DataAgentPiKernel } from "../src/kernel/dataagent-pi-kernel.js";
import { createAssistantMessageEventStream } from "@earendil-works/pi-ai";
import type { AgentRunRequest, CellProtocolFrame } from "../src/contracts/runtime.js";

test("RunService processes hello and run.start frames over channel", async () => {
  const inStream = new PassThrough();
  const outStream = new PassThrough();

  const streamFn = (model: any, context: any, options: any) => {
    const stream = createAssistantMessageEventStream();
    queueMicrotask(() => {
      const msg = {
        role: "assistant" as const,
        content: [{ type: "text" as const, text: "echo: ok" }],
        api: "faux" as const,
        provider: "faux",
        model: "faux-1",
        usage: { inputTokens: 10, outputTokens: 5, totalTokens: 15 },
        stopReason: "stop" as const,
        timestamp: Date.now(),
      };
      stream.push({ type: "start", partial: msg as any });
      stream.push({ type: "done", reason: "stop", message: msg as any });
      stream.end();
    });
    return stream;
  };

  const kernel = new DataAgentPiKernel({ streamFn });
  const channel = new CellChannel(inStream, outStream);
  const service = new RunService(channel, kernel, "cell-123");

  const outputFrames: CellProtocolFrame[] = [];
  let buffer = "";
  outStream.on("data", (chunk) => {
    buffer += chunk.toString();
    const lines = buffer.split("\n");
    buffer = lines.pop() || "";
    for (const line of lines) {
      if (line.trim()) {
        outputFrames.push(JSON.parse(line.trim()));
      }
    }
  });

  service.init();
  channel.start();

  // 1. Send hello frame
  const helloFrame: CellProtocolFrame = {
    protocol_version: 1,
    cell_id: "cell-123",
    run_id: "none",
    task_attempt_id: "none",
    frame_id: "f-hello",
    type: "hello",
    payload: {},
  };
  inStream.write(JSON.stringify(helloFrame) + "\n");

  await new Promise((r) => setTimeout(r, 20));

  const ackFrame = outputFrames.find((f) => f.type === "hello.ack");
  assert.ok(ackFrame, "must receive hello.ack");
  assert.equal((ackFrame.payload as any).manifest.runtime_kind, "pi_agent_core");

  // 2. Send run.start frame
  const request: AgentRunRequest = {
    runtime_protocol_version: 1,
    agent_event_protocol_version: 1,
    run_id: "run-stdio-1",
    task_id: "task-1",
    task_attempt_id: "att-1",
    topic_id: "topic-1",
    purpose: "interactive",
    context: {
      context_snapshot_id: "snap-1",
      policy_version: "v1",
      renderer_target: "pi_agent_core",
      system_instructions: "Sys",
      messages: [{ role: "user", content: "hi" }],
    },
    model: {
      provider_id: "faux",
      model_id: "faux-1",
    },
    workspace: {
      workspace_root: "/tmp",
    },
    limits: {
      timeout_seconds: 30,
    },
  };

  const startFrame: CellProtocolFrame = {
    protocol_version: 1,
    cell_id: "cell-123",
    run_id: "run-stdio-1",
    task_attempt_id: "att-1",
    frame_id: "f-start",
    type: "run.start",
    payload: request as any,
  };
  inStream.write(JSON.stringify(startFrame) + "\n");

  // Wait for execution to settle
  await new Promise((r) => setTimeout(r, 60));

  const acceptedFrame = outputFrames.find((f) => f.type === "run.accepted");
  assert.ok(acceptedFrame, "must receive run.accepted");

  const settledFrame = outputFrames.find((f) => f.type === "run.settled");
  assert.ok(settledFrame, "must receive run.settled");
  assert.equal((settledFrame.payload as any).terminal_status, "success");

  // Verify run.event frames were emitted
  const eventFrames = outputFrames.filter((f) => f.type === "run.event");
  assert.ok(eventFrames.length >= 2, "must emit stream events");

  channel.close();
});
