import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";
import os from "node:os";

/**
 * Helper to execute command within environment contract:
 * "$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/<name>.py" ...
 */
async function executeSkillScript(pythonBin, skillRoot, scriptName, args = [], envOverrides = {}) {
  const scriptPath = path.join(skillRoot, "scripts", scriptName);

  return new Promise((resolve, reject) => {
    const proc = spawn(pythonBin, [scriptPath, ...args], {
      env: {
        ...process.env,
        DATAAGENT_PYTHON_BIN: pythonBin,
        DATAAGENT_SKILL_ROOT: skillRoot,
        ...envOverrides,
      },
    });

    let stdout = "";
    let stderr = "";

    proc.stdout.on("data", (chunk) => {
      stdout += chunk.toString();
    });
    proc.stderr.on("data", (chunk) => {
      stderr += chunk.toString();
    });

    proc.on("close", (code) => {
      resolve({
        code,
        stdout,
        stderr,
      });
    });

    proc.on("error", (err) => {
      reject(err);
    });
  });
}

test("Skill invocation contract: Node process executes skill python script using DATAAGENT_PYTHON_BIN", async () => {
  const tmpDir = await fs.mkdtemp(path.join(os.tmpdir(), "pi-skill-test-"));
  const scriptsDir = path.join(tmpDir, "scripts");
  await fs.mkdir(scriptsDir, { recursive: true });

  const sampleScriptPath = path.join(scriptsDir, "query_stat.py");
  const scriptContent = `
import sys
import json

args = sys.argv[1:]
if "--error" in args:
    sys.stderr.write("Database connection failed: timed out\\n")
    sys.exit(1)

result = {
    "status": "success",
    "table": "workflow_execution",
    "count": 128,
    "received_args": args
}
print(json.dumps(result))
sys.exit(0)
`;
  await fs.writeFile(sampleScriptPath, scriptContent, "utf8");

  const pythonBin = process.env.DATAAGENT_PYTHON_BIN || "python3";

  // Test 1: Successful execution
  const successRes = await executeSkillScript(pythonBin, tmpDir, "query_stat.py", ["--days", "30", "--limit", "100"]);
  assert.equal(successRes.code, 0, "exit code must be 0");
  const parsed = JSON.parse(successRes.stdout);
  assert.equal(parsed.status, "success");
  assert.equal(parsed.table, "workflow_execution");
  assert.equal(parsed.count, 128);
  assert.deepEqual(parsed.received_args, ["--days", "30", "--limit", "100"]);

  // Test 2: Error execution capturing stderr
  const errorRes = await executeSkillScript(pythonBin, tmpDir, "query_stat.py", ["--error"]);
  assert.equal(errorRes.code, 1, "exit code must be 1 on error");
  assert.ok(errorRes.stderr.includes("Database connection failed"), "stderr must capture error message");

  // Cleanup
  await fs.rm(tmpDir, { recursive: true, force: true });
});
