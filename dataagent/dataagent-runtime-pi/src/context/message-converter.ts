import type { ConversationMessage } from "../contracts/runtime.js";
import type { AgentMessage } from "@earendil-works/pi-agent-core";
import type { Message } from "@earendil-works/pi-ai";

export class MessageConverter {
  public static fromConversationMessages(convMessages: ConversationMessage[]): AgentMessage[] {
    return convMessages.map((cm) => {
      let content: any = cm.content;
      if (typeof content === "string") {
        content = [{ type: "text" as const, text: content }];
      }

      if (cm.role === "assistant") {
        return {
          role: "assistant" as const,
          content,
          api: "generic" as any,
          provider: "generic",
          model: "generic",
          usage: {
            input: 0,
            output: 0,
            cacheRead: 0,
            cacheWrite: 0,
            totalTokens: 0,
            cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
          },
          stopReason: "stop" as const,
          timestamp: cm.created_at ? new Date(cm.created_at).getTime() : 0,
        } as any;
      }

      return {
        role: "user" as const,
        content,
        timestamp: cm.created_at ? new Date(cm.created_at).getTime() : 0,
      };
    });
  }

  public static convertToLlm(messages: AgentMessage[]): Message[] {
    const result: Message[] = [];
    for (const msg of messages) {
      if ((msg as any).role === "notification" || (msg as any).internalOnly) {
        continue; // drop internal messages
      }
      if (msg.role === "user" || msg.role === "assistant" || msg.role === "toolResult") {
        result.push(msg as Message);
      }
    }
    return result;
  }
}
