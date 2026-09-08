import test from "node:test";
import assert from "node:assert/strict";
import { Agent } from "@earendil-works/pi-agent-core";
import { createAssistantMessageEventStream } from "@earendil-works/pi-ai";

/**
 * Creates a mock stream function producing text and thinking events.
 */
function createMockStreamFn(events) {
  return (model, context, options) => {
    const stream = createAssistantMessageEventStream();
    queueMicrotask(() => {
      for (const ev of events) {
        stream.push(ev);
      }
      stream.end();
    });
    return stream;
  };
}

test("Agent lifecycle and streaming events with flush barrier", async () => {
  const emittedEvents = [];
  const subscriberDelays = [];

  const streamFn = createMockStreamFn([
    {
      type: "start",
      partial: { role: "assistant", content: [] },
    },
    {
      type: "thinking_start",
      contentIndex: 0,
      partial: { role: "assistant", content: [{ type: "thinking", thinking: "" }] },
    },
    {
      type: "thinking_delta",
      contentIndex: 0,
      delta: "Step 1: analyzing query...",
      partial: { role: "assistant", content: [{ type: "thinking", thinking: "Step 1: analyzing query..." }] },
    },
    {
      type: "thinking_end",
      contentIndex: 0,
      content: "Step 1: analyzing query...",
      partial: { role: "assistant", content: [{ type: "thinking", thinking: "Step 1: analyzing query..." }] },
    },
    {
      type: "text_start",
      contentIndex: 1,
      partial: {
        role: "assistant",
        content: [
          { type: "thinking", thinking: "Step 1: analyzing query..." },
          { type: "text", text: "" },
        ],
      },
    },
    {
      type: "text_delta",
      contentIndex: 1,
      delta: "smoke-ok",
      partial: {
        role: "assistant",
        content: [
          { type: "thinking", thinking: "Step 1: analyzing query..." },
          { type: "text", text: "smoke-ok" },
        ],
      },
    },
    {
      type: "text_end",
      contentIndex: 1,
      content: "smoke-ok",
      partial: {
        role: "assistant",
        content: [
          { type: "thinking", thinking: "Step 1: analyzing query..." },
          { type: "text", text: "smoke-ok" },
        ],
      },
    },
    {
      type: "done",
      reason: "stop",
      message: {
        role: "assistant",
        content: [
          { type: "thinking", thinking: "Step 1: analyzing query..." },
          { type: "text", text: "smoke-ok" },
        ],
      },
    },
  ]);

  const mockModel = {
    id: "mock-model",
    name: "Mock Model",
    provider: "faux",
    api: "faux",
    capabilities: {},
  };

  const agent = new Agent({
    initialState: {
      systemPrompt: "You are a test agent.",
      model: mockModel,
      thinkingLevel: "medium",
      tools: [],
      messages: [],
    },
    streamFn,
  });

  // Test subscription and awaited flush barrier
  let barrierSettled = false;
  agent.subscribe(async (event) => {
    emittedEvents.push(event.type);
    if (event.type === "agent_end") {
      // simulate async event persistence/flush
      await new Promise((r) => setTimeout(r, 20));
      barrierSettled = true;
    }
  });

  await agent.prompt("hello");

  // Verify all expected lifecycle events are emitted in correct order
  assert.ok(emittedEvents.includes("agent_start"), "must emit agent_start");
  assert.ok(emittedEvents.includes("turn_start"), "must emit turn_start");
  assert.ok(emittedEvents.includes("message_start"), "must emit message_start");
  assert.ok(emittedEvents.includes("message_update"), "must emit message_update");
  assert.ok(emittedEvents.includes("message_end"), "must emit message_end");
  assert.ok(emittedEvents.includes("turn_end"), "must emit turn_end");
  assert.ok(emittedEvents.includes("agent_end"), "must emit agent_end");

  // Verify subscriber was awaited as a flush barrier
  assert.equal(barrierSettled, true, "agent_end awaited subscriber must settle before prompt() resolves");

  // Verify agent transcript
  assert.equal(agent.state.messages.length, 2, "transcript should have user + assistant");
  assert.equal(agent.state.messages[0].role, "user");
  assert.equal(agent.state.messages[1].role, "assistant");
  assert.equal(agent.state.messages[1].content[1].text, "smoke-ok");
});
