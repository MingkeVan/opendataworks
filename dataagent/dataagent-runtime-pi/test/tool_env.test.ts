/**
 * The shell environment handed to a model-driven command.
 *
 * The Cell's own environment carries provider API keys and database
 * credentials. Passing a copy of it to `bash -c` would make every one of them
 * readable with `env`, by a command the model chose. These tests pin the
 * allowlist so that stays impossible.
 */

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { buildShellEnv, runShell, createTools } from "../src/tools/tool-registry.js";
import { WorkspaceBoundaryEnforcer, type BoundaryPolicy } from "../src/policy/workspace-boundary-enforcer.js";

const SECRET_ENV = {
  PATH: "/usr/bin:/bin",
  HOME: "/home/agent",
  DATAAGENT_PYTHON_BIN: "/usr/bin/python3",
  DATAAGENT_SKILL_ROOT: "/skills/nl2sql",
  ANTHROPIC_API_KEY: "sk-secret-anthropic",
  OPENAI_API_KEY: "sk-secret-openai",
  MYSQL_PASSWORD: "dataagent123",
  DATAAGENT_RUNTIME_SECRET: "shared-hmac-secret",
  AWS_SECRET_ACCESS_KEY: "aws-secret",
} as NodeJS.ProcessEnv;

test("shell env carries only allowlisted variables", () => {
  const env = buildShellEnv(SECRET_ENV, {});

  assert.equal(env.PATH, "/usr/bin:/bin");
  assert.equal(env.HOME, "/home/agent");
  assert.equal(env.DATAAGENT_PYTHON_BIN, "/usr/bin/python3");
  assert.equal(env.DATAAGENT_SKILL_ROOT, "/skills/nl2sql");
});

test("provider and database credentials never reach the shell", () => {
  const env = buildShellEnv(SECRET_ENV, {});

  for (const leaked of [
    "ANTHROPIC_API_KEY",
    "OPENAI_API_KEY",
    "MYSQL_PASSWORD",
    "DATAAGENT_RUNTIME_SECRET",
    "AWS_SECRET_ACCESS_KEY",
  ]) {
    assert.equal(env[leaked], undefined, `${leaked} must not be visible to a model-driven shell`);
  }
  const serialized = JSON.stringify(env);
  assert.ok(!serialized.includes("sk-secret"), "no provider key may appear in the shell env");
  assert.ok(!serialized.includes("dataagent123"), "no database password may appear in the shell env");
});

test("runtime_env cannot smuggle a non-allowlisted variable through", () => {
  // The control plane's runtime_env is additive but still filtered: a bug or a
  // compromised profile upstream must not be able to widen the shell's view.
  const env = buildShellEnv(SECRET_ENV, {
    DATAAGENT_PYTHON_BIN: "/opt/py/bin/python3",
    ANTHROPIC_API_KEY: "sk-injected",
    EVIL_VAR: "x",
  });

  assert.equal(env.DATAAGENT_PYTHON_BIN, "/opt/py/bin/python3", "allowlisted value may be overridden");
  assert.equal(env.ANTHROPIC_API_KEY, undefined);
  assert.equal(env.EVIL_VAR, undefined);
});

test("a command really cannot read a secret from its environment", async () => {
  const cwd = fs.mkdtempSync(path.join(os.tmpdir(), "odw-shell-"));
  const result = await runShell("echo \"key=${ANTHROPIC_API_KEY:-ABSENT} pw=${MYSQL_PASSWORD:-ABSENT}\"", {
    cwd,
    env: buildShellEnv(SECRET_ENV, {}),
    timeoutMs: 10_000,
  });

  assert.equal(result.exitCode, 0);
  assert.match(result.stdout, /key=ABSENT/);
  assert.match(result.stdout, /pw=ABSENT/);
});

test("a shell command exceeding its timeout is killed", async () => {
  const cwd = fs.mkdtempSync(path.join(os.tmpdir(), "odw-shell-"));
  const result = await runShell("sleep 30", {
    cwd,
    env: buildShellEnv(SECRET_ENV, {}),
    timeoutMs: 300,
  });

  assert.equal(result.timedOut, true);
});

test("tools refuse a path outside the workspace before touching the filesystem", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "odw-tools-"));
  const outside = fs.mkdtempSync(path.join(os.tmpdir(), "odw-outside-"));
  fs.writeFileSync(path.join(outside, "secret.txt"), "top secret", "utf-8");

  const policy: BoundaryPolicy = {
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
  };

  const tools = createTools({
    boundary: new WorkspaceBoundaryEnforcer(policy),
    workspaceRoot: root,
    runtimeEnv: {},
  }) as Array<{ name: string; execute: (id: string, params: never) => Promise<{ isError?: boolean }> }>;

  const read = tools.find((t) => t.name === "Read")!;
  const denied = await read.execute("call-1", { file_path: path.join(outside, "secret.txt") } as never);
  assert.equal(denied.isError, true);

  const bash = tools.find((t) => t.name === "Bash")!;
  const deniedBash = await bash.execute("call-2", { command: `cat ${outside}/secret.txt` } as never);
  assert.equal(deniedBash.isError, true);
});
