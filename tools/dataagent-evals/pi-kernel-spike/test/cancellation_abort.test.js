import test from "node:test";
import assert from "node:assert/strict";
import { Agent } from "@earendil-works/pi-agent-core";
import { createAssistantMessageEventStream, Type } from "@earendil-works/pi-ai";

test("agent.abort() cancels streaming mid-turn", async () => {
  let streamAborted = false;

  const streamFn = (model, context, options) => {
    const stream = createAssistantMessageEventStream();
    let counter = 0;
    const interval = setInterval(() => {
      counter++;
      if (counter < 5) {
        stream.push({
          type: "text_delta",
          contentIndex: 0,
          delta: `chunk_${counter} `,
          partial: { role: "assistant", content: [{ type: "text", text: `chunk_${counter}` }] },
        });
      }
    }, 20);

    // Watch options signal if available
    options?.signal?.addEventListener("abort", () => {
      streamAborted = true;
      clearInterval(interval);
      stream.push({
        type: "done",
        reason: "aborted",
        message: { role: "assistant", content: [{ type: "text", text: "aborted" }], stopReason: "aborted" },
      });
      stream.end();
    });

    return stream;
  };

  const agent = new Agent({
    initialState: {
      systemPrompt: "Test",
      model: { id: "m", name: "M", provider: "faux", api: "faux", capabilities: {} },
      thinkingLevel: "off",
      tools: [],
      messages: [],
    },
    streamFn,
  });

  let agentEndEmitted = false;
  agent.subscribe((event) => {
    if (event.type === "agent_end") {
      agentEndEmitted = true;
    }
  });

  const runPromise = agent.prompt("generate long output");

  // Wait 30ms then abort
  await new Promise((r) => setTimeout(r, 30));
  agent.abort();

  await runPromise;

  assert.equal(agentEndEmitted, true, "agent_end must still be emitted upon abort");
  assert.equal(agent.state.isStreaming, false, "streaming flag must be false");
});

test("agent.abort() cancels pending beforeToolCall interaction", async () => {
  let abortSignalFired = false;

  const testTool = {
    name: "slow_action",
    label: "Slow Action",
    parameters: Type.Object({}),
    execute: async () => ({ content: [{ type: "text", text: "ok" }], details: {} }),
  };

  const streamFn = (model, context, options) => {
    const stream = createAssistantMessageEventStream();
    queueMicrotask(() => {
      if (options?.signal?.aborted) {
        const abortedMsg = { role: "assistant", content: [], stopReason: "aborted" };
        stream.push({ type: "done", reason: "aborted", message: abortedMsg });
        stream.end();
        return;
      }
      const assistantMsg = {
        role: "assistant",
        content: [{ type: "toolCall", id: "call_slow", name: "slow_action", arguments: {} }],
      };
      stream.push({ type: "start", partial: assistantMsg });
      stream.push({ type: "done", reason: "toolUse", message: assistantMsg });
      stream.end();
    });
    return stream;
  };

  const agent = new Agent({
    initialState: {
      systemPrompt: "Test",
      model: { id: "m", name: "M", provider: "faux", api: "faux", capabilities: {} },
      thinkingLevel: "off",
      tools: [testTool],
      messages: [],
    },
    streamFn,
    toolExecution: "sequential",
    beforeToolCall: async (context, signal) => {
      if (signal?.aborted) {
        abortSignalFired = true;
        return { block: true, reason: "Cancelled by user abort", terminate: true };
      }
      return new Promise((resolve) => {
        signal?.addEventListener(
          "abort",
          () => {
            abortSignalFired = true;
            resolve({ block: true, reason: "Cancelled by user abort", terminate: true });
          },
          { once: true }
        );
      });
    },
  });

  const runPromise = agent.prompt("do slow action");

  await new Promise((r) => setTimeout(r, 20));
  assert.equal(agent.signal?.aborted, false, "signal not yet aborted");

  // User cancels task
  agent.abort();

  await runPromise;

  assert.equal(abortSignalFired, true, "abort signal must be delivered to beforeToolCall");
  assert.equal(agent.state.isStreaming, false);
});
