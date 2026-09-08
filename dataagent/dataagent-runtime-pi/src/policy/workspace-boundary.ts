import path from "node:path";
import fs from "node:fs/promises";
import fsSync from "node:fs";

export class WorkspaceBoundaryError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "WorkspaceBoundaryError";
  }
}

export class WorkspaceBoundary {
  private allowedRoots: string[] = [];

  constructor(workspaceRoot: string, scratchRoot?: string) {
    this.addRoot(workspaceRoot);
    if (scratchRoot) {
      this.addRoot(scratchRoot);
    }
  }

  private addRoot(rootPath: string): void {
    const resolved = path.resolve(rootPath);
    this.allowedRoots.push(resolved);
    try {
      const real = fsSync.realpathSync(resolved);
      if (real !== resolved) {
        this.allowedRoots.push(real);
      }
    } catch {
      // Root might not exist yet; resolved path is kept
    }
  }

  public validatePath(targetPath: string): string {
    const resolved = path.resolve(targetPath);
    const isAllowed = this.allowedRoots.some((root) => {
      return resolved === root || resolved.startsWith(root + path.sep);
    });

    if (!isAllowed) {
      throw new WorkspaceBoundaryError(
        `Access denied: path '${targetPath}' resolves outside allowed workspace boundaries.`
      );
    }
    return resolved;
  }

  public async validateRealpath(targetPath: string): Promise<string> {
    const validated = this.validatePath(targetPath);
    try {
      const real = await fs.realpath(validated);
      const isAllowed = this.allowedRoots.some((root) => {
        return real === root || real.startsWith(root + path.sep);
      });
      if (!isAllowed) {
        throw new WorkspaceBoundaryError(
          `Symlink escape detected: real path '${real}' escapes allowed workspace boundaries.`
        );
      }
      return real;
    } catch (err: any) {
      if (err.code === "ENOENT") {
        // Target file doesn't exist yet, validate parent directory
        const parent = path.dirname(validated);
        return this.validateRealpath(parent).then(() => validated);
      }
      throw err;
    }
  }
}
