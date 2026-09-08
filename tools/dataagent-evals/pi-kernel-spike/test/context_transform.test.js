import test from "node:test";
import assert from "node:assert/strict";
import { Agent } from "@earendil-works/pi-agent-core";
import { createAssistantMessageEventStream } from "@earendil-works/pi-ai";

test("transformContext and convertToLlm convert custom messages into model messages deterministically", async () => {
  let transformCalled = false;
  let convertCalled = false;
  let receivedLlmContext = null;

  const streamFn = (model, context, options) => {
    receivedLlmContext = context;
    const stream = createAssistantMessageEventStream();
    queueMicrotask(() => {
      const msg = { role: "assistant", content: [{ type: "text", text: "acknowledged" }] };
      stream.push({ type: "start", partial: msg });
      stream.push({ type: "done", reason: "stop", message: msg });
      stream.end();
    });
    return stream;
  };

  const agent = new Agent({
    initialState: {
      systemPrompt: "System instruction v1",
      model: { id: "m", name: "M", provider: "faux", api: "faux", capabilities: {} },
      thinkingLevel: "off",
      tools: [],
      messages: [],
    },
    streamFn,
    // Step 1: transformContext receives AgentMessage[] and can prune or annotate
    transformContext: async (messages, signal) => {
      transformCalled = true;
      // Filter out messages marked as internal-only
      return messages.filter((m) => !m.internalOnly);
    },
    // Step 2: convertToLlm maps AgentMessage[] to LLM-compatible Message[]
    convertToLlm: (messages) => {
      convertCalled = true;
      return messages.map((m) => {
        if (m.role === "custom_annotation") {
          return {
            role: "user",
            content: [{ type: "text", text: `[Metadata: ${m.content}]` }],
          };
        }
        return m;
      });
    },
  });

  // Inject a custom message and an internalOnly message
  await agent.prompt([
    {
      role: "custom_annotation",
      content: "table: dim_user, schema: opendataworks",
      timestamp: Date.now(),
    },
    {
      role: "user",
      content: [{ type: "text", text: "query summary" }],
      internalOnly: true, // Should be pruned by transformContext
      timestamp: Date.now(),
    },
    {
      role: "user",
      content: [{ type: "text", text: "show count" }],
      timestamp: Date.now(),
    },
  ]);

  assert.equal(transformCalled, true, "transformContext must be called");
  assert.equal(convertCalled, true, "convertToLlm must be called");

  // Verify received LLM messages
  assert.ok(receivedLlmContext, "LLM context must be passed to streamFn");
  // The internalOnly message should have been filtered out
  assert.equal(receivedLlmContext.messages.length, 2);
  assert.equal(receivedLlmContext.messages[0].role, "user");
  assert.equal(receivedLlmContext.messages[0].content[0].text, "[Metadata: table: dim_user, schema: opendataworks]");
  assert.equal(receivedLlmContext.messages[1].content[0].text, "show count");
});

test("shouldStopAfterTurn gracefully stops multi-turn loops upon condition", async () => {
  let stopCheckCount = 0;

  const tool = {
    name: "ping",
    label: "Ping",
    parameters: { type: "object", properties: {} },
    execute: async () => ({ content: [{ type: "text", text: "pong" }], details: {} }),
  };

  const streamFn = (model, context, options) => {
    const stream = createAssistantMessageEventStream();
    queueMicrotask(() => {
      const msg = {
        role: "assistant",
        content: [{ type: "toolCall", id: "call_ping", name: "ping", arguments: {} }],
      };
      stream.push({ type: "start", partial: msg });
      stream.push({ type: "done", reason: "toolUse", message: msg });
      stream.end();
    });
    return stream;
  };

  const agent = new Agent({
    initialState: {
      systemPrompt: "Test",
      model: { id: "m", name: "M", provider: "faux", api: "faux", capabilities: {} },
      thinkingLevel: "off",
      tools: [tool],
      messages: [],
    },
    streamFn,
    toolExecution: "sequential",
    shouldStopAfterTurn: async (context) => {
      stopCheckCount++;
      // Stop immediately after 1 turn to prevent infinite tool loop
      return true;
    },
  });

  await agent.prompt("start loop");

  assert.equal(stopCheckCount, 1, "shouldStopAfterTurn must be evaluated");
  assert.equal(agent.state.isStreaming, false, "loop must stop gracefully");
});
