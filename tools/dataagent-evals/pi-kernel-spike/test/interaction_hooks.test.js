import test from "node:test";
import assert from "node:assert/strict";
import { Agent } from "@earendil-works/pi-agent-core";
import { createAssistantMessageEventStream, Type } from "@earendil-works/pi-ai";

test("beforeToolCall async suspension allows human-in-the-loop approval and resumption", async () => {
  let toolExecuted = false;
  let interactionRequested = false;
  let resolveInteraction = null;

  const writeTool = {
    name: "drop_table",
    label: "Drop Table",
    description: "Drops a database table",
    parameters: Type.Object({
      table: Type.String(),
    }),
    execute: async (toolCallId, params) => {
      toolExecuted = true;
      return {
        content: [{ type: "text", text: `Table ${params.table} dropped.` }],
        details: { dropped: true },
      };
    },
  };

  let turn = 0;
  const streamFn = (model, context, options) => {
    turn++;
    const stream = createAssistantMessageEventStream();
    queueMicrotask(() => {
      if (turn === 1) {
        const assistantMsg = {
          role: "assistant",
          content: [
            {
              type: "toolCall",
              id: "call_drop",
              name: "drop_table",
              arguments: { table: "temp_logs" },
            },
          ],
        };
        stream.push({ type: "start", partial: assistantMsg });
        stream.push({ type: "done", reason: "toolUse", message: assistantMsg });
      } else {
        const finalMsg = {
          role: "assistant",
          content: [{ type: "text", text: "Table temp_logs has been dropped successfully." }],
        };
        stream.push({ type: "start", partial: finalMsg });
        stream.push({ type: "done", reason: "stop", message: finalMsg });
      }
      stream.end();
    });
    return stream;
  };

  const agent = new Agent({
    initialState: {
      systemPrompt: "You are a database admin.",
      model: { id: "m", name: "M", provider: "faux", api: "faux", capabilities: {} },
      thinkingLevel: "off",
      tools: [writeTool],
      messages: [],
    },
    streamFn,
    toolExecution: "sequential",
    beforeToolCall: async (context, signal) => {
      if (context.toolCall.name === "drop_table") {
        interactionRequested = true;
        // Suspends until user approves externally
        return new Promise((resolve, reject) => {
          resolveInteraction = resolve;
          signal?.addEventListener("abort", () => reject(new Error("aborted")));
        });
      }
      return undefined;
    },
  });

  // Start prompt in background
  const promptPromise = agent.prompt("drop table temp_logs");

  // Wait a short moment to ensure agent reaches beforeToolCall
  await new Promise((r) => setTimeout(r, 20));

  // Verify interaction was requested and tool has NOT executed yet
  assert.equal(interactionRequested, true, "beforeToolCall must be triggered");
  assert.equal(toolExecuted, false, "tool must NOT execute while interaction is pending");
  assert.equal(typeof resolveInteraction, "function", "external resolver must be available");

  // Now simulate user approving the action: resolve with undefined (allow)
  resolveInteraction(undefined);

  // Await completion of prompt
  await promptPromise;

  // Verify tool executed and final answer reached
  assert.equal(toolExecuted, true, "tool must execute after interaction is resolved with allow");
  assert.equal(agent.state.messages[agent.state.messages.length - 1].role, "assistant");
  assert.equal(
    agent.state.messages[agent.state.messages.length - 1].content[0].text,
    "Table temp_logs has been dropped successfully."
  );
});

test("beforeToolCall denial blocks execution and cleanly terminates turn", async () => {
  let toolExecuted = false;

  const dangerousTool = {
    name: "delete_database",
    label: "Delete Database",
    description: "Deletes the entire database",
    parameters: Type.Object({ name: Type.String() }),
    execute: async () => {
      toolExecuted = true;
      return { content: [{ type: "text", text: "deleted" }], details: {} };
    },
  };

  const streamFn = (model, context, options) => {
    const stream = createAssistantMessageEventStream();
    queueMicrotask(() => {
      const assistantMsg = {
        role: "assistant",
        content: [
          {
            type: "toolCall",
            id: "call_del",
            name: "delete_database",
            arguments: { name: "prod" },
          },
        ],
      };
      stream.push({ type: "start", partial: assistantMsg });
      stream.push({ type: "done", reason: "toolUse", message: assistantMsg });
      stream.end();
    });
    return stream;
  };

  const agent = new Agent({
    initialState: {
      systemPrompt: "You are an admin.",
      model: { id: "m", name: "M", provider: "faux", api: "faux", capabilities: {} },
      thinkingLevel: "off",
      tools: [dangerousTool],
      messages: [],
    },
    streamFn,
    toolExecution: "sequential",
    beforeToolCall: async (context) => {
      // Deny with termination hint
      return {
        block: true,
        reason: "Operation denied: user lacks permissions to delete database 'prod'",
        terminate: true,
      };
    },
  });

  await agent.prompt("delete prod");

  assert.equal(toolExecuted, false, "dangerous tool must NEVER execute when blocked");

  // Verify toolResult contains the denial reason
  const toolResultMsg = agent.state.messages.find((m) => m.role === "toolResult");
  assert.ok(toolResultMsg, "transcript must contain error toolResult");
  assert.equal(toolResultMsg.isError, true);
  assert.ok(
    toolResultMsg.content[0].text.includes("Operation denied: user lacks permissions"),
    "error text must match block reason"
  );
});

test("afterToolCall redacts sensitive output and overrides results", async () => {
  const queryTool = {
    name: "read_secrets",
    label: "Read Secrets",
    description: "Reads user sensitive data",
    parameters: Type.Object({ id: Type.String() }),
    execute: async () => {
      return {
        content: [{ type: "text", text: "password=super_secret_123" }],
        details: { token: "secret_token_abc" },
      };
    },
  };

  let turn = 0;
  const streamFn = (model, context, options) => {
    turn++;
    const stream = createAssistantMessageEventStream();
    queueMicrotask(() => {
      if (turn === 1) {
        const assistantMsg = {
          role: "assistant",
          content: [{ type: "toolCall", id: "call_sec", name: "read_secrets", arguments: { id: "1" } }],
        };
        stream.push({ type: "start", partial: assistantMsg });
        stream.push({ type: "done", reason: "toolUse", message: assistantMsg });
      } else {
        const finalMsg = { role: "assistant", content: [{ type: "text", text: "done" }] };
        stream.push({ type: "start", partial: finalMsg });
        stream.push({ type: "done", reason: "stop", message: finalMsg });
      }
      stream.end();
    });
    return stream;
  };

  const agent = new Agent({
    initialState: {
      systemPrompt: "Test",
      model: { id: "m", name: "M", provider: "faux", api: "faux", capabilities: {} },
      thinkingLevel: "off",
      tools: [queryTool],
      messages: [],
    },
    streamFn,
    afterToolCall: async (context) => {
      // Redact sensitive content
      return {
        content: [{ type: "text", text: "password=***REDACTED***" }],
        details: { token: "***REDACTED***" },
      };
    },
  });

  await agent.prompt("get secret");

  const toolResultMsg = agent.state.messages.find((m) => m.role === "toolResult");
  assert.ok(toolResultMsg);
  assert.equal(toolResultMsg.content[0].text, "password=***REDACTED***");
  assert.equal(toolResultMsg.details.token, "***REDACTED***");
});
