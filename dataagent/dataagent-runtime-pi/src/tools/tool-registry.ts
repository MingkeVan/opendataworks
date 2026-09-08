/**
 * Canonical tools exposed to the agent.
 *
 * Two properties are enforced here rather than left to the model:
 *
 * 1. Every path argument passes the workspace boundary before the tool runs.
 * 2. A spawned shell gets an *allowlisted* environment, never a copy of this
 *    process's env. The Cell's environment carries provider API keys, the
 *    database password and other runtime secrets; handing all of that to a
 *    model-driven `bash -c` would make every one of them readable with `env`.
 */

import { spawn } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";
import { Type } from "@earendil-works/pi-ai";
import type { WorkspaceBoundaryEnforcer } from "../policy/workspace-boundary-enforcer.js";

const MAX_OUTPUT_CHARS = 100 * 1024;
const MAX_READ_BYTES = 64 * 1024;
const DEFAULT_TOOL_TIMEOUT_MS = 120_000;

/**
 * Environment variables a skill script legitimately needs. Everything else in
 * the Cell's env — provider keys, DB credentials, the runtime secret — stays
 * out of the child.
 */
const SHELL_ENV_ALLOWLIST = [
  "PATH",
  "HOME",
  "LANG",
  "LC_ALL",
  "TZ",
  "PWD",
  "TMPDIR",
  "DATAAGENT_PYTHON_BIN",
  "DATAAGENT_SKILL_ROOT",
  "DATAAGENT_SQL_READ_TIMEOUT_SECONDS",
  "DATAAGENT_SQL_WRITE_TIMEOUT_SECONDS",
];

export interface ToolRegistryOptions {
  boundary: WorkspaceBoundaryEnforcer;
  workspaceRoot: string;
  runtimeEnv: Record<string, string>;
  toolTimeoutMs?: number;
}

export interface ShellResult {
  exitCode: number | null;
  stdout: string;
  stderr: string;
  timedOut: boolean;
}

export function buildShellEnv(
  processEnv: NodeJS.ProcessEnv,
  runtimeEnv: Record<string, string>
): Record<string, string> {
  const env: Record<string, string> = {};
  for (const key of SHELL_ENV_ALLOWLIST) {
    const value = processEnv[key];
    if (typeof value === "string") {
      env[key] = value;
    }
  }
  // Values the control plane explicitly designated for skill scripts. These are
  // additive on purpose; they are the one channel for run-scoped configuration.
  for (const [key, value] of Object.entries(runtimeEnv)) {
    if (SHELL_ENV_ALLOWLIST.includes(key)) {
      env[key] = value;
    }
  }
  return env;
}

export async function runShell(
  command: string,
  options: { cwd: string; env: Record<string, string>; timeoutMs: number; signal?: AbortSignal }
): Promise<ShellResult> {
  return new Promise<ShellResult>((resolve, reject) => {
    const child = spawn("bash", ["-c", command], {
      cwd: options.cwd,
      env: options.env,
    });

    let stdout = "";
    let stderr = "";
    let timedOut = false;

    child.stdout.on("data", (chunk: Buffer) => {
      if (stdout.length < MAX_OUTPUT_CHARS) {
        stdout += chunk.toString("utf8");
      }
    });
    child.stderr.on("data", (chunk: Buffer) => {
      if (stderr.length < MAX_OUTPUT_CHARS) {
        stderr += chunk.toString("utf8");
      }
    });

    const timer = setTimeout(() => {
      timedOut = true;
      child.kill("SIGKILL");
    }, options.timeoutMs);

    const onAbort = () => child.kill("SIGTERM");
    options.signal?.addEventListener("abort", onAbort, { once: true });

    child.on("close", (code) => {
      clearTimeout(timer);
      options.signal?.removeEventListener("abort", onAbort);
      resolve({ exitCode: code, stdout, stderr, timedOut });
    });
    child.on("error", (err) => {
      clearTimeout(timer);
      options.signal?.removeEventListener("abort", onAbort);
      reject(err);
    });
  });
}

export function createTools(options: ToolRegistryOptions): unknown[] {
  const { boundary, workspaceRoot, runtimeEnv } = options;
  const timeoutMs = options.toolTimeoutMs ?? DEFAULT_TOOL_TIMEOUT_MS;

  const denial = (reason: string) => ({
    content: [{ type: "text", text: reason }],
    details: { denied: true },
    isError: true,
  });

  return [
    {
      name: "Read",
      label: "Read",
      description: "Read a UTF-8 text file inside the agent workspace.",
      parameters: Type.Object({
        file_path: Type.String({ description: "Absolute or workspace-relative file path." }),
      }),
      execute: async (_toolCallId: string, params: { file_path: string }) => {
        const reason = boundary.validate("Read", params);
        if (reason) {
          return denial(reason);
        }
        const resolved = path.resolve(workspaceRoot, params.file_path);
        const stat = await fs.stat(resolved);
        if (stat.isDirectory()) {
          return denial(`Cannot read '${params.file_path}': it is a directory`);
        }
        const handle = await fs.open(resolved, "r");
        try {
          const size = Math.min(stat.size, MAX_READ_BYTES);
          const buffer = Buffer.alloc(size);
          const { bytesRead } = await handle.read(buffer, 0, size, 0);
          const text = buffer.subarray(0, bytesRead).toString("utf8");
          return {
            content: [{ type: "text", text }],
            details: { truncated: stat.size > MAX_READ_BYTES, bytes: bytesRead },
          };
        } finally {
          await handle.close();
        }
      },
    },
    {
      name: "LS",
      label: "LS",
      description: "List a directory inside the agent workspace.",
      parameters: Type.Object({
        path: Type.String({ description: "Absolute or workspace-relative directory path." }),
      }),
      execute: async (_toolCallId: string, params: { path: string }) => {
        const reason = boundary.validate("LS", params);
        if (reason) {
          return denial(reason);
        }
        const resolved = path.resolve(workspaceRoot, params.path);
        const entries = await fs.readdir(resolved, { withFileTypes: true });
        const text = entries
          .map((entry) => `${entry.isDirectory() ? "[DIR] " : "[FILE]"} ${entry.name}`)
          .join("\n");
        return {
          content: [{ type: "text", text: text || "(empty)" }],
          details: { count: entries.length },
        };
      },
    },
    {
      name: "Bash",
      label: "Bash",
      description: "Run a bash command inside the agent workspace.",
      parameters: Type.Object({
        command: Type.String({ description: "The bash command line to execute." }),
      }),
      execute: async (_toolCallId: string, params: { command: string }, signal?: AbortSignal) => {
        const reason = boundary.validate("Bash", params);
        if (reason) {
          return denial(reason);
        }
        const result = await runShell(params.command, {
          cwd: workspaceRoot,
          env: buildShellEnv(process.env, runtimeEnv),
          timeoutMs,
          signal,
        });
        if (result.timedOut) {
          return denial(`Bash command exceeded ${Math.round(timeoutMs / 1000)}s and was killed`);
        }
        const output = (result.stdout + (result.stderr ? `\n${result.stderr}` : "")).trim();
        return {
          content: [{ type: "text", text: output || "(no output)" }],
          details: { exitCode: result.exitCode },
          isError: result.exitCode !== 0,
        };
      },
    },
  ];
}
