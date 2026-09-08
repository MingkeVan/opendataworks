import test from "node:test";
import assert from "node:assert/strict";
import { Agent } from "@earendil-works/pi-agent-core";
import { createAssistantMessageEventStream, Type } from "@earendil-works/pi-ai";

test("Sequential tool execution preserves strict ordering and progress callbacks", async () => {
  const executionTrace = [];
  const eventTrace = [];

  // Define two tools
  const tool1 = {
    name: "read_query",
    label: "Read Query",
    description: "Reads input query parameters",
    parameters: Type.Object({
      table: Type.String(),
    }),
    execute: async (toolCallId, params, signal, onUpdate) => {
      executionTrace.push(`tool1_start:${params.table}`);
      // Send progress update
      if (onUpdate) {
        onUpdate({
          content: [{ type: "text", text: "Scanning metadata..." }],
          details: { progress: 50 },
        });
      }
      await new Promise((r) => setTimeout(r, 20));
      executionTrace.push(`tool1_end:${params.table}`);
      return {
        content: [{ type: "text", text: `Table metadata for ${params.table} found.` }],
        details: { count: 100 },
      };
    },
  };

  const tool2 = {
    name: "execute_sql",
    label: "Execute SQL",
    description: "Executes a SQL query",
    parameters: Type.Object({
      sql: Type.String(),
    }),
    execute: async (toolCallId, params, signal, onUpdate) => {
      executionTrace.push(`tool2_start:${params.sql}`);
      await new Promise((r) => setTimeout(r, 10));
      executionTrace.push(`tool2_end:${params.sql}`);
      return {
        content: [{ type: "text", text: `Result for ${params.sql}: 42 rows.` }],
        details: { rowCount: 42 },
      };
    },
  };

  let turn = 0;
  const streamFn = (model, context, options) => {
    turn++;
    const stream = createAssistantMessageEventStream();
    queueMicrotask(() => {
      if (turn === 1) {
        // Turn 1: Emit TWO tool calls in one assistant message
        const assistantMsg = {
          role: "assistant",
          content: [
            {
              type: "toolCall",
              id: "call_1",
              name: "read_query",
              arguments: { table: "users" },
            },
            {
              type: "toolCall",
              id: "call_2",
              name: "execute_sql",
              arguments: { sql: "SELECT * FROM users" },
            },
          ],
        };
        stream.push({ type: "start", partial: assistantMsg });
        stream.push({ type: "done", reason: "toolUse", message: assistantMsg });
      } else {
        // Turn 2: Final response after receiving tool results
        const finalMsg = {
          role: "assistant",
          content: [{ type: "text", text: "Found 42 users in table users." }],
        };
        stream.push({ type: "start", partial: finalMsg });
        stream.push({
          type: "text_start",
          contentIndex: 0,
          partial: finalMsg,
        });
        stream.push({
          type: "text_delta",
          contentIndex: 0,
          delta: "Found 42 users in table users.",
          partial: finalMsg,
        });
        stream.push({
          type: "text_end",
          contentIndex: 0,
          content: "Found 42 users in table users.",
          partial: finalMsg,
        });
        stream.push({ type: "done", reason: "stop", message: finalMsg });
      }
      stream.end();
    });
    return stream;
  };

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
      thinkingLevel: "off",
      tools: [tool1, tool2],
      messages: [],
    },
    streamFn,
    toolExecution: "sequential", // Strictly sequential
  });

  agent.subscribe((event) => {
    eventTrace.push(event.type);
    if (event.type === "tool_execution_update") {
      eventTrace.push(`progress:${event.partialResult?.details?.progress}`);
    }
  });

  await agent.prompt("check users");

  // Verify strict sequential ordering: tool 1 must end BEFORE tool 2 starts!
  assert.deepEqual(executionTrace, [
    "tool1_start:users",
    "tool1_end:users",
    "tool2_start:SELECT * FROM users",
    "tool2_end:SELECT * FROM users",
  ]);

  // Verify progress update event was emitted
  assert.ok(eventTrace.includes("tool_execution_update"), "must emit tool_execution_update");
  assert.ok(eventTrace.includes("progress:50"), "must deliver partial progress");

  // Verify final transcript contains user, assistant (tool calls), tool 1 result, tool 2 result, assistant (final answer)
  assert.equal(agent.state.messages.length, 5);
  assert.equal(agent.state.messages[0].role, "user");
  assert.equal(agent.state.messages[1].role, "assistant"); // tool calls
  assert.equal(agent.state.messages[2].role, "toolResult"); // tool 1 result
  assert.equal(agent.state.messages[3].role, "toolResult"); // tool 2 result
  assert.equal(agent.state.messages[4].role, "assistant"); // final answer
});
