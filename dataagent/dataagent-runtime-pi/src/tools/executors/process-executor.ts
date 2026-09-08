import { spawn } from "node:child_process";

export interface ProcessExecutionOptions {
  cwd?: string;
  env?: Record<string, string>;
  timeoutMs?: number;
  signal?: AbortSignal;
}

export interface ProcessExecutionResult {
  exitCode: number | null;
  stdout: string;
  stderr: string;
  durationMs: number;
}

const MAX_OUTPUT_BYTES = 100 * 1024; // 100KB

export class ProcessExecutor {
  public async exec(
    command: string,
    args: string[] = [],
    options: ProcessExecutionOptions = {}
  ): Promise<ProcessExecutionResult> {
    const startTime = Date.now();

    return new Promise((resolve, reject) => {
      const proc = spawn(command, args, {
        cwd: options.cwd || process.cwd(),
        env: {
          ...process.env,
          ...(options.env || {}),
        },
      });

      let stdout = "";
      let stderr = "";

      proc.stdout.on("data", (chunk: Buffer) => {
        if (stdout.length < MAX_OUTPUT_BYTES) {
          stdout += chunk.toString("utf8");
        }
      });

      proc.stderr.on("data", (chunk: Buffer) => {
        if (stderr.length < MAX_OUTPUT_BYTES) {
          stderr += chunk.toString("utf8");
        }
      });

      let timer: NodeJS.Timeout | null = null;
      if (options.timeoutMs && options.timeoutMs > 0) {
        timer = setTimeout(() => {
          proc.kill("SIGKILL");
        }, options.timeoutMs);
      }

      options.signal?.addEventListener(
        "abort",
        () => {
          proc.kill("SIGTERM");
        },
        { once: true }
      );

      proc.on("close", (code) => {
        if (timer) {
          clearTimeout(timer);
        }
        resolve({
          exitCode: code,
          stdout,
          stderr,
          durationMs: Date.now() - startTime,
        });
      });

      proc.on("error", (err) => {
        if (timer) {
          clearTimeout(timer);
        }
        reject(err);
      });
    });
  }

  public async bash(
    script: string,
    options: ProcessExecutionOptions = {}
  ): Promise<ProcessExecutionResult> {
    return this.exec("bash", ["-c", script], options);
  }
}
