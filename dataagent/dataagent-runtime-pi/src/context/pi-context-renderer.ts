import crypto from "node:crypto";
import type { ContextBundle } from "../contracts/runtime.js";
import { MessageConverter } from "./message-converter.js";
import type { AgentMessage } from "@earendil-works/pi-agent-core";

export interface RenderedContext {
  renderer_version: "v1";
  systemPrompt: string;
  messages: AgentMessage[];
  rendered_digest: string;
}

export class PiContextRenderer {
  public static readonly VERSION = "v1" as const;

  public static render(bundle: ContextBundle): RenderedContext {
    // 1. Build augmented system prompt
    const parts: string[] = [bundle.system_instructions.trim()];

    if (bundle.locale || bundle.timezone) {
      parts.push(`Environment: Locale=${bundle.locale || "zh-CN"}, Timezone=${bundle.timezone || "Asia/Shanghai"}.`);
    }

    if (bundle.data_scope && Object.keys(bundle.data_scope).length > 0) {
      parts.push(`Data Scope: ${JSON.stringify(bundle.data_scope)}`);
    }

    if (bundle.enabled_skills && bundle.enabled_skills.length > 0) {
      parts.push(`Enabled Skills: ${bundle.enabled_skills.join(", ")}`);
    }

    const systemPrompt = parts.join("\n\n");

    // 2. Convert messages
    const messages = MessageConverter.fromConversationMessages(bundle.messages);

    // 3. Compute deterministic sha256 digest
    const hash = crypto.createHash("sha256");
    hash.update(systemPrompt);
    for (const msg of messages) {
      hash.update(JSON.stringify(msg));
    }
    const rendered_digest = `sha256:${hash.digest("hex")}`;

    return {
      renderer_version: "v1",
      systemPrompt,
      messages,
      rendered_digest,
    };
  }
}
