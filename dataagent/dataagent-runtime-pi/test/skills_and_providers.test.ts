import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import os from "node:os";

import { ProviderConfig } from "../src/providers/provider-config.js";
import { CredentialResolver } from "../src/providers/credential-resolver.js";
import { ModelRegistry } from "../src/providers/model-registry.js";
import { SkillLoader } from "../src/skills/skill-loader.js";
import { PortalMcpClient } from "../src/mcp/portal-mcp-client.js";

test("ProviderConfig resolves Anthropic and custom headers correctly", () => {
  const resolved = ProviderConfig.resolve(
    {
      provider_id: "anthropic",
      model_id: "claude-3-5-sonnet",
      options: { base_url: "https://api.anthropic.com" },
    },
    "sk-ant-test-key",
  );

  assert.equal(resolved.providerId, "anthropic");
  assert.equal(resolved.apiKey, "sk-ant-test-key");
  assert.equal(resolved.headers["x-api-key"], "sk-ant-test-key");
  assert.equal(resolved.headers["anthropic-version"], "2023-06-01");
  assert.equal(resolved.baseUrl, "https://api.anthropic.com");
});

test("CredentialResolver registers, resolves, and safely cleans up secrets", () => {
  const resolver = new CredentialResolver();
  const runId = "run-secret-1";

  resolver.register(runId, {
    api_key: "ephemeral-key-123",
    mcp_tokens: { "portal-mcp": "tok-abc" },
    custom_headers: { "X-Custom": "val" },
  });

  assert.equal(resolver.resolveApiKey(runId), "ephemeral-key-123");
  assert.equal(resolver.resolveMcpToken(runId, "portal-mcp"), "tok-abc");

  resolver.cleanup(runId);
  assert.equal(resolver.resolveApiKey(runId), undefined);
  assert.equal(resolver.resolveMcpToken(runId, "portal-mcp"), undefined);
});

test("ModelRegistry creates mock streamFn and streams answer", async () => {
  const config = ProviderConfig.resolve({
    provider_id: "mock",
    model_id: "test-model",
  });

  const streamFn = ModelRegistry.createStreamFn(config);
  const stream: any = await Promise.resolve(streamFn({} as any, {} as any));

  const events: any[] = [];
  for await (const event of stream) {
    events.push(event);
  }

  assert.ok(events.length >= 3);
  assert.equal(events[0].type, "start");
  const doneEvent = events.find((e) => e.type === "done");
  assert.ok(doneEvent);
  assert.equal(doneEvent.reason, "stop");
  assert.ok(doneEvent.message.content[0].text.includes("Mock answer"));
});

test("SkillLoader loads SKILL.md and supplies runtime environment", async () => {
  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), "odw-skill-test-"));
  const skillDir = path.join(tmpDir, "dataagent-nl2sql");
  await fs.mkdir(skillDir, { recursive: true });
  await fs.writeFile(path.join(skillDir, "SKILL.md"), "# NL2SQL Skill Instructions\nDo intelligent query.");

  const loader = new SkillLoader([
    { name: "dataagent-nl2sql", root_path: skillDir },
  ]);

  const instructions = await loader.loadSkillInstructions("dataagent-nl2sql");
  assert.ok(instructions.includes("Do intelligent query"));

  const env = loader.getSkillEnv("dataagent-nl2sql", "/usr/bin/python3");
  assert.equal(env.DATAAGENT_PYTHON_BIN, "/usr/bin/python3");
  assert.equal(env.DATAAGENT_SKILL_ROOT, skillDir);

  await fs.rm(tmpDir, { recursive: true, force: true });
});

test("PortalMcpClient verifies allowed tools and executes", async () => {
  const client = new PortalMcpClient([
    {
      name: "portal",
      transport: "stdio",
      tool_allowlist: ["query_metadata", "get_lineage"],
    },
  ]);

  assert.equal(client.isToolAllowed("portal", "query_metadata"), true);
  assert.equal(client.isToolAllowed("portal", "drop_table"), false);

  const res: any = await client.callTool({
    serverName: "portal",
    toolName: "query_metadata",
    arguments: { table: "orders" },
  });
  assert.equal(res.success, true);
});
