import { Type } from "@earendil-works/pi-ai";
import type { WorkspaceExecutor } from "./executors/workspace-executor.js";
import type { ProcessExecutor } from "./executors/process-executor.js";

export interface ToolRegistryOptions {
  workspaceExecutor?: WorkspaceExecutor;
  processExecutor?: ProcessExecutor;
}

export class CanonicalToolRegistry {
  private workspaceExecutor?: WorkspaceExecutor;
  private processExecutor?: ProcessExecutor;

  constructor(options: ToolRegistryOptions = {}) {
    this.workspaceExecutor = options.workspaceExecutor;
    this.processExecutor = options.processExecutor;
  }

  public getPiTools(): any[] {
    const tools: any[] = [];

    // 1. workspace.read (Read)
    if (this.workspaceExecutor) {
      const we = this.workspaceExecutor;
      tools.push({
        name: "Read",
        label: "Read",
        description: "Read the contents of a file within the allowed workspace.",
        parameters: Type.Object({
          path: Type.String({ description: "Relative or absolute path to the file." }),
        }),
        execute: async (toolCallId: string, params: { path: string }) => {
          const res = await we.readFile(params.path);
          return {
            content: [{ type: "text", text: res.content }],
            details: { lines: res.lines, truncated: res.truncated },
          };
        },
      });

      // 2. workspace.list (LS)
      tools.push({
        name: "LS",
        label: "LS",
        description: "List files and subdirectories in a directory within the workspace.",
        parameters: Type.Object({
          path: Type.String({ description: "Directory path to list." }),
        }),
        execute: async (toolCallId: string, params: { path: string }) => {
          const entries = await we.listDirectory(params.path);
          const text = entries
            .map((e) => `${e.isDirectory ? "[DIR] " : "[FILE]"} ${e.name}`)
            .join("\n");
          return {
            content: [{ type: "text", text }],
            details: { entries },
          };
        },
      });
    }

    // 3. process.exec (Bash)
    if (this.processExecutor) {
      const pe = this.processExecutor;
      tools.push({
        name: "Bash",
        label: "Bash",
        description: "Execute a bash shell command in the cell environment.",
        parameters: Type.Object({
          command: Type.String({ description: "The bash command line to execute." }),
        }),
        execute: async (toolCallId: string, params: { command: string }, signal?: AbortSignal) => {
          const res = await pe.bash(params.command, { signal });
          const output = (res.stdout + (res.stderr ? "\n" + res.stderr : "")).trim();
          return {
            content: [{ type: "text", text: output || "(no output)" }],
            details: { exitCode: res.exitCode, durationMs: res.durationMs },
            isError: res.exitCode !== 0,
          };
        },
      });
    }

    return tools;
  }
}
