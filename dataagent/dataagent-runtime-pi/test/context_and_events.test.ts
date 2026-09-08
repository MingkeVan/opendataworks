import test from "node:test";
import assert from "node:assert/strict";
import { TokenBudget } from "../src/context/token-budget.js";
import { MessageConverter } from "../src/context/message-converter.js";
import { PiContextRenderer } from "../src/context/pi-context-renderer.js";
import { Redactor } from "../src/observability/redaction.js";
import { RunStateMachine } from "../src/kernel/run-state-machine.js";
import { PiEventNormalizer } from "../src/kernel/pi-event-normalizer.js";

test("TokenBudget estimates tokens and checks against limit", () => {
  const budget = new TokenBudget(100);
  const messages = [
    { content: "short text query" },
    { content: "another message" },
  ];
  const res = budget.checkBudget(messages);
  assert.equal(res.withinBudget, true);
  assert.ok(res.estimated > 0);
  assert.ok(res.estimated < 100);

  const overflow = new TokenBudget(5);
  const overflowRes = overflow.checkBudget(messages);
  assert.equal(overflowRes.withinBudget, false);
});

test("PiContextRenderer produces deterministic digest for identical ContextBundle", () => {
  const bundle = {
    context_snapshot_id: "snap-123",
    policy_version: "v1",
    renderer_target: "pi_agent_core" as const,
    system_instructions: "You are OpenDataWorks intelligent assistant.",
    messages: [
      { role: "user" as const, content: "Show me recent tables" },
    ],
    locale: "zh-CN",
    timezone: "Asia/Shanghai",
    enabled_skills: ["dataagent-nl2sql"],
  };

  const r1 = PiContextRenderer.render(bundle);
  const r2 = PiContextRenderer.render(bundle);

  assert.equal(r1.rendered_digest, r2.rendered_digest, "identical bundles must yield identical digest");
  assert.ok(r1.systemPrompt.includes("Locale=zh-CN"));
  assert.ok(r1.systemPrompt.includes("Enabled Skills: dataagent-nl2sql"));
  assert.equal(r1.messages.length, 1);
});

test("MessageConverter filters internal and notification messages from LLM input", () => {
  const agentMessages: any[] = [
    { role: "user", content: [{ type: "text", text: "hello" }] },
    { role: "notification", content: "internal alert", internalOnly: true },
    { role: "assistant", content: [{ type: "text", text: "world" }] },
  ];

  const llmMessages = MessageConverter.convertToLlm(agentMessages);
  assert.equal(llmMessages.length, 2);
  assert.equal(llmMessages[0].role, "user");
  assert.equal(llmMessages[1].role, "assistant");
});

test("Redactor scrubs passwords, api keys, and bearer tokens", () => {
  const secretText = "Connecting with password=SuperSecretPassword123 and api_key=sk-1234567890abcdef123456";
  const redacted = Redactor.redactString(secretText);
  assert.ok(!redacted.includes("SuperSecretPassword123"));
  assert.ok(!redacted.includes("sk-1234567890abcdef123456"));
  assert.ok(redacted.includes("***REDACTED***"));

  const secretObj = {
    user: "admin",
    password: "raw_password",
    nested: {
      api_token: "secret_token_val",
      normal: "public_value",
    },
  };
  const redactedObj = Redactor.redactObject(secretObj);
  assert.equal(redactedObj.password, "***REDACTED***");
  assert.equal(redactedObj.nested.api_token, "***REDACTED***");
  assert.equal(redactedObj.nested.normal, "public_value");
});

test("PiEventNormalizer converts Pi events to neutral AgentEvents with redaction", () => {
  const sm = new RunStateMachine("run-ev-1", "task-1", "att-1");
  sm.markStarted();
  const normalizer = new PiEventNormalizer(sm);

  // 1. Turn start
  const turnEvs = normalizer.normalize({ type: "turn_start" });
  assert.equal(turnEvs.length, 1);
  assert.equal(turnEvs[0].type, "turn.started");
  assert.equal(turnEvs[0].payload.turn_id, "turn-1");

  // 2. Text delta
  const deltaEvs = normalizer.normalize({
    type: "message_update",
    message: { role: "assistant", content: [] } as any,
    assistantMessageEvent: {
      type: "text_delta",
      contentIndex: 0,
      delta: "result line",
      partial: {} as any,
    },
  });
  assert.equal(deltaEvs.length, 1);
  assert.equal(deltaEvs[0].type, "content.delta");
  assert.equal(deltaEvs[0].payload.delta, "result line");

  // 3. Tool execution with sensitive argument
  const toolEvs = normalizer.normalize({
    type: "tool_execution_start",
    toolCallId: "call-1",
    toolName: "Bash",
    args: { command: "curl -H 'Authorization: token=my_secret_token' http://api" },
  });
  assert.equal(toolEvs.length, 1);
  assert.equal(toolEvs[0].type, "tool.started");
  assert.ok(!JSON.stringify(toolEvs[0].payload.input).includes("my_secret_token"));
});
