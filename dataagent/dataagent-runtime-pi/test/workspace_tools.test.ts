import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import os from "node:os";
import { WorkspaceBoundary, WorkspaceBoundaryError } from "../src/policy/workspace-boundary.js";
import { WorkspaceExecutor } from "../src/tools/executors/workspace-executor.js";
import { ProcessExecutor } from "../src/tools/executors/process-executor.js";
import { PolicyEnforcer } from "../src/policy/policy-enforcer.js";

test("WorkspaceBoundary confines paths strictly within workspace root", async () => {
  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), "boundary-test-"));
  const subDir = path.join(tmpDir, "sub");
  await fs.mkdir(subDir);
  const testFile = path.join(subDir, "hello.txt");
  await fs.writeFile(testFile, "hello boundary", "utf8");

  const boundary = new WorkspaceBoundary(tmpDir);

  // 1. Valid path inside workspace
  const valid = await boundary.validateRealpath(testFile);
  assert.equal(valid, await fs.realpath(testFile));

  // 2. Traversal attempt out of workspace
  const escapePath = path.join(tmpDir, "..", "escaped.txt");
  await assert.rejects(
    async () => {
      await boundary.validateRealpath(escapePath);
    },
    (err: any) => err instanceof WorkspaceBoundaryError
  );

  await fs.rm(tmpDir, { recursive: true, force: true });
});

test("WorkspaceExecutor reads files and lists directories safely", async () => {
  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), "ws-exec-test-"));
  const sampleFile = path.join(tmpDir, "data.txt");
  await fs.writeFile(sampleFile, "line 1\nline 2\nline 3", "utf8");

  const boundary = new WorkspaceBoundary(tmpDir);
  const executor = new WorkspaceExecutor(boundary);

  // Read
  const readRes = await executor.readFile(sampleFile);
  assert.equal(readRes.content, "line 1\nline 2\nline 3");
  assert.equal(readRes.lines, 3);
  assert.equal(readRes.truncated, false);

  // List
  const entries = await executor.listDirectory(tmpDir);
  assert.equal(entries.length, 1);
  assert.equal(entries[0].name, "data.txt");
  assert.equal(entries[0].isDirectory, false);

  await fs.rm(tmpDir, { recursive: true, force: true });
});

test("ProcessExecutor executes shell commands and captures output", async () => {
  const executor = new ProcessExecutor();
  const res = await executor.bash("echo 'hello from bash'");
  assert.equal(res.exitCode, 0);
  assert.equal(res.stdout.trim(), "hello from bash");
});

test("PolicyEnforcer checks permissions and write confirmation", () => {
  const enforcer = new PolicyEnforcer({
    require_write_confirmation: true,
    allowed_tools: ["workspace.read", "process.exec"],
  });

  // Read tool allowed
  const decRead = enforcer.evaluateToolCall("workspace.read", "read", {});
  assert.equal(decRead.decision, "allow");

  // Write tool requires confirmation
  const decWrite = enforcer.evaluateToolCall("process.exec", "write", {});
  assert.equal(decWrite.decision, "require_interaction");

  // Disallowed tool denied
  const decAdmin = enforcer.evaluateToolCall("system.admin", "admin", {});
  assert.equal(decAdmin.decision, "deny");
});
