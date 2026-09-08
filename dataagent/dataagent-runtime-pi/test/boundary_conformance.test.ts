/**
 * TypeScript half of the cross-language workspace-boundary conformance suite.
 *
 * Runs dataagent/contracts/boundary/v1/conformance-cases.json — the same file
 * dataagent-backend/tests/test_boundary_conformance.py runs — against this
 * process's enforcer.
 *
 * The Python side generates the policy; this side enforces it. Two separate
 * implementations of the same rules can only be kept honest by a shared case
 * table, so this test is load-bearing rather than incidental: if it is skipped
 * or its fixture path drifts, the boundary can widen here without anything
 * failing on the Python side.
 */

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { WorkspaceBoundaryEnforcer, type BoundaryPolicy } from "../src/policy/workspace-boundary-enforcer.js";

const here = path.dirname(fileURLToPath(import.meta.url));
// dist/test -> dist -> dataagent-runtime-pi -> dataagent
const fixturePath = path.resolve(here, "..", "..", "..", "contracts", "boundary", "v1", "conformance-cases.json");

interface ConformanceCase {
  id: string;
  tool: string;
  input: Record<string, unknown>;
  expect: "allow" | "deny";
  reason_contains?: string;
}

interface Fixture {
  fixture_version: number;
  cases: ConformanceCase[];
}

function loadFixture(): Fixture {
  assert.ok(
    fs.existsSync(fixturePath),
    `shared boundary fixture not found at ${fixturePath}; the Python and TypeScript enforcers must read the same file`
  );
  return JSON.parse(fs.readFileSync(fixturePath, "utf-8")) as Fixture;
}

/** Materialize the fixture placeholders, mirroring the Python harness layout. */
function buildEnvironment() {
  const base = fs.mkdtempSync(path.join(os.tmpdir(), "odw-boundary-"));
  const wsAncestor = path.join(base, "runtime");
  const workspace = path.join(wsAncestor, "topic_1", "workspace");
  const skillRoot = path.join(base, "skills", "opendataworks-platform-tools");
  const scratch = path.join(base, "scratch");
  const outside = path.join(base, "outside");
  for (const dir of [workspace, skillRoot, scratch, outside]) {
    fs.mkdirSync(dir, { recursive: true });
  }

  const pyBin = process.execPath;
  const substitutions: Record<string, string> = {
    "{WS}": workspace,
    "{WS_ANCESTOR}": wsAncestor,
    "{SKILL}": skillRoot,
    "{SCRATCH}": scratch,
    "{OUTSIDE}": outside,
    "{PYBIN}": pyBin,
  };

  // Shaped exactly like build_boundary_policy(..., profile="pi_agent_core").
  const policy: BoundaryPolicy = {
    policy_version: 1,
    profile: "pi_agent_core",
    workspace_root: workspace,
    allowed_roots: [workspace, skillRoot, scratch],
    allowed_executables: [pyBin],
    discard_sinks: ["/dev/null"],
    tool_path_keys: {
      Read: ["file_path", "path"],
      LS: ["path"],
      Glob: ["path", "pattern"],
      Grep: ["path", "glob"],
      Write: ["file_path"],
      Edit: ["file_path"],
      MultiEdit: ["file_path"],
      NotebookEdit: ["notebook_path"],
    },
    operator_chars: "();<>|&",
    tool_result_root: null,
    readonly_commands: [],
  };

  return { substitutions, policy };
}

function substitute(value: unknown, substitutions: Record<string, string>): unknown {
  if (typeof value === "string") {
    let out = value;
    for (const [token, replacement] of Object.entries(substitutions)) {
      out = out.split(token).join(replacement);
    }
    return out;
  }
  if (value && typeof value === "object" && !Array.isArray(value)) {
    const result: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      result[k] = substitute(v, substitutions);
    }
    return result;
  }
  return value;
}

const fixture = loadFixture();
const { substitutions, policy } = buildEnvironment();
const enforcer = new WorkspaceBoundaryEnforcer(policy);

test("shared fixture is non-empty", () => {
  assert.ok(fixture.cases.length > 0, "no conformance cases loaded");
});

for (const testCase of fixture.cases) {
  test(`boundary conformance: ${testCase.id}`, () => {
    const input = substitute(testCase.input, substitutions) as Record<string, unknown>;
    const denial = enforcer.validate(testCase.tool, input);

    if (testCase.expect === "allow") {
      assert.equal(denial, null, `${testCase.id}: expected allow, got denial ${denial}`);
    } else {
      assert.notEqual(denial, null, `${testCase.id}: expected deny, got allow`);
    }
  });
}
