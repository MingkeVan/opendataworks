import fs from "node:fs/promises";
import path from "node:path";
import type { WorkspaceBoundary } from "../../policy/workspace-boundary.js";

const MAX_READ_BYTES = 64 * 1024; // 64KB per read

export class WorkspaceExecutor {
  private boundary: WorkspaceBoundary;

  constructor(boundary: WorkspaceBoundary) {
    this.boundary = boundary;
  }

  public async readFile(filePath: string): Promise<{ content: string; lines: number; truncated: boolean }> {
    const validated = await this.boundary.validateRealpath(filePath);
    const stat = await fs.stat(validated);

    if (stat.isDirectory()) {
      throw new Error(`Cannot read path '${filePath}': path is a directory`);
    }

    const fileHandle = await fs.open(validated, "r");
    try {
      const buffer = Buffer.alloc(Math.min(stat.size, MAX_READ_BYTES));
      const { bytesRead } = await fileHandle.read(buffer, 0, buffer.length, 0);
      const text = buffer.subarray(0, bytesRead).toString("utf8");
      const lines = text.split("\n").length;
      return {
        content: text,
        lines,
        truncated: stat.size > MAX_READ_BYTES,
      };
    } finally {
      await fileHandle.close();
    }
  }

  public async listDirectory(dirPath: string): Promise<Array<{ name: string; isDirectory: boolean; size?: number }>> {
    const validated = await this.boundary.validateRealpath(dirPath);
    const entries = await fs.readdir(validated, { withFileTypes: true });

    return entries.map((entry) => ({
      name: entry.name,
      isDirectory: entry.isDirectory(),
    }));
  }

  public async findFiles(dirPath: string, extension?: string): Promise<string[]> {
    const validated = await this.boundary.validateRealpath(dirPath);
    const results: string[] = [];

    async function walk(current: string) {
      const entries = await fs.readdir(current, { withFileTypes: true });
      for (const entry of entries) {
        const full = path.join(current, entry.name);
        if (entry.isDirectory()) {
          if (!entry.name.startsWith(".") && entry.name !== "node_modules") {
            await walk(full);
          }
        } else if (entry.isFile()) {
          if (!extension || entry.name.endsWith(extension)) {
            results.push(full);
          }
        }
      }
    }

    await walk(validated);
    return results;
  }
}
