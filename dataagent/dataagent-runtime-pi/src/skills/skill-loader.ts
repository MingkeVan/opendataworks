import fs from "node:fs/promises";
import path from "node:path";
import type { SkillSpec } from "../contracts/runtime.js";

export class SkillLoader {
  private skills: Map<string, SkillSpec> = new Map();

  constructor(specs: SkillSpec[] = []) {
    for (const spec of specs) {
      this.skills.set(spec.name, spec);
    }
  }

  public getSkill(name: string): SkillSpec | undefined {
    return this.skills.get(name);
  }

  public async loadSkillInstructions(name: string): Promise<string> {
    const spec = this.skills.get(name);
    if (!spec) {
      throw new Error(`Skill '${name}' is not enabled or allowlisted in this run`);
    }

    const skillMdPath = path.join(spec.root_path, "SKILL.md");
    try {
      return await fs.readFile(skillMdPath, "utf-8");
    } catch (e: any) {
      throw new Error(`Failed to load SKILL.md from ${skillMdPath}: ${e.message}`);
    }
  }

  public getSkillEnv(name: string, pythonBin?: string): Record<string, string> {
    const spec = this.skills.get(name);
    const env: Record<string, string> = {
      DATAAGENT_PYTHON_BIN: pythonBin || process.env.DATAAGENT_PYTHON_BIN || "python3",
    };
    if (spec) {
      env.DATAAGENT_SKILL_ROOT = spec.root_path;
    }
    return env;
  }
}
