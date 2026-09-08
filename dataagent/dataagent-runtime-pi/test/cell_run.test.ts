/**
 * Cell run lifecycle: event shape, terminal guarantees, tool gating.
 *
 * Uses a stub StreamFn rather than a real provider so the assertions are about
 * this Cell's behaviour, not a model's.
 */

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createAssistantMessageEventStream } from "@earendil-works/pi-ai";
import { Cell } from "../src/kernel/cell.js";
import type { CellInitPayload, NeutralAgentEvent } from "../src/protocol/frames.js";

function workspace(): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "odw-cell-"));
  return dir;
}

function initPayload(root: string, overrides: Partial<CellInitPayload> = {}): CellInitPayload {
  return {
    run_id: "task-1",
    task_id: "task-1",
    topic_id: "topic-1",
    system_prompt: "sys",
    messages: [{ role: "user", content: "hi" }],
    model: { provider_id: "faux", model_id: "faux-1" },
    workspace: { project_cwd: root },
    boundary_policy: {
      policy_version: 1,
      profile: "pi_agent_core",
      workspace_root: root,
      allowed_roots: [root],
      allowed_executables: [],
      discard_sinks: ["/dev/null"],
      tool_path_keys: { Read: ["file_path"], LS: ["path"] },
      operator_chars: "();<>|&",
      tool_result_root: null,
      readonly_commands: [],
    },
    skills: [],
    runtime_env: {},
    limits: {
      total_timeout_seconds: 30,
      idle_timeout_seconds: 10,
      max_turns: 30,
      max_tool_calls: 50,
    },
    ...overrides,
  };
}

/** A StreamFn that emits one text answer and stops. */
function textStreamFactory(text: string) {
  return () => ({
    model: {} as never,
    streamFn: (() => {
      const stream = createAssistantMessageEventStream();
      queueMicrotask(() => {
        const message = {
          role: "assistant" as const,
          content: [{ type: "text" as const, text }],
          api: "faux" as const,
          provider: "faux",
          model: "faux-1",
          usage: { inputTokens: 1, outputTokens: 1, totalTokens: 2 },
          stopReason: "stop" as const,
          timestamp: Date.now(),
        };
        stream.push({ type: "start", partial: message as never });
        stream.push({ type: "text_start", contentIndex: 0, partial: message as never });
        for (const chunk of text.split(" ")) {
          stream.push({ type: "text_delta", contentIndex: 0, delta: chunk, partial: message as never });
        }
        stream.push({ type: "done", reason: "stop", message: message as never });
        stream.end();
      });
      return stream;
    }) as never,
  });
}

async function runCell(
  init: CellInitPayload,
  factory: ReturnType<typeof textStreamFactory>
): Promise<{ events: NeutralAgentEvent[]; result: Awaited<ReturnType<Cell["run"]>> }> {
  const events: NeutralAgentEvent[] = [];
  const cell = new Cell(factory as never);
  const result = await cell.run(init, (event) => events.push(event));
  return { events, result };
}

test("emits run.started first and exactly one terminal event", async () => {
  const root = workspace();
  const { events, result } = await runCell(initPayload(root), textStreamFactory("hello"));

  assert.equal(result.terminal_status, "success");
  assert.equal(events[0].type, "run.started");

  const terminals = events.filter((e) =>
    ["run.completed", "run.failed", "run.cancelled", "run.suspended"].includes(e.type)
  );
  assert.equal(terminals.length, 1, "exactly one terminal event");
  assert.equal(terminals[0].type, "run.completed");
  assert.equal(terminals[0], events[events.length - 1], "terminal event must be last");
});

test("sequences are contiguous and start at 1", async () => {
  const root = workspace();
  const { events } = await runCell(initPayload(root), textStreamFactory("hello"));

  // The Python writer drops any event whose sequence is not strictly greater
  // than the last, so a gap or a repeat here loses events downstream.
  assert.deepEqual(
    events.map((e) => e.sequence),
    events.map((_, index) => index + 1)
  );
});

test("uses dotted event type names from the closed contract enum", async () => {
  const root = workspace();
  const { events } = await runCell(initPayload(root), textStreamFactory("hello"));

  // "turn_start" (underscore) is not a member of the neutral contract; the
  // Python side rejects it outright and the turn silently disappears.
  for (const event of events) {
    assert.ok(event.type.includes("."), `event type '${event.type}' must be dotted`);
    assert.ok(!event.type.includes("_"), `event type '${event.type}' must not be snake_case`);
  }
});

test("content deltas carry kind and content_id", async () => {
  const root = workspace();
  const { events } = await runCell(initPayload(root), textStreamFactory("hello world"));

  const deltas = events.filter((e) => e.type === "content.delta");
  assert.ok(deltas.length > 0, "expected at least one content delta");
  for (const delta of deltas) {
    assert.ok(["answer", "reasoning"].includes(String(delta.payload.kind)));
    assert.ok(String(delta.payload.content_id).startsWith("c-"));
  }
});

test("a model stream failure is reported as failed, not success", async () => {
  // Regression guard. agent.prompt() *resolves* when the model stream fails --
  // the failure lands on agent.state.errorMessage instead of rejecting -- so a
  // Cell that only watches for a thrown error reports this run as a success
  // carrying no answer, and a broken turn is persisted as a good one.
  const root = workspace();
  const failing = () => ({
    model: {} as never,
    streamFn: (() => {
      throw new Error("provider exploded");
    }) as never,
  });

  const { events, result } = await runCell(initPayload(root), failing as never);

  assert.equal(result.terminal_status, "failed");
  const last = events[events.length - 1];
  assert.equal(last.type, "run.failed");
  assert.equal(last.payload.error_code, "PI_MODEL_ERROR");
  assert.match(String(last.payload.message), /provider exploded/);
});

test("a thrown setup error is reported as an execution failure", async () => {
  // The other failure path: the model factory itself throws, before the agent
  // loop exists. It must still terminate the run rather than escape.
  const root = workspace();
  const { events, result } = await runCell(
    initPayload(root),
    (() => {
      throw new Error("factory exploded");
    }) as never
  );

  assert.equal(result.terminal_status, "failed");
  const last = events[events.length - 1];
  assert.equal(last.type, "run.failed");
  assert.equal(last.payload.error_code, "PI_EXECUTION_ERROR");
  assert.match(String(last.payload.message), /factory exploded/);
});

test("an unsupported provider fails the run rather than hanging it", async () => {
  const root = workspace();
  const { events, result } = await runCell(
    initPayload(root, { model: { provider_id: "nope", model_id: "x" } }),
    (() => {
      throw new Error("Pi 运行时不支持 provider 'nope'");
    }) as never
  );

  assert.equal(result.terminal_status, "failed");
  assert.equal(events[events.length - 1].type, "run.failed");
});
