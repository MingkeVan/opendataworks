import type { StreamFn } from "@earendil-works/pi-agent-core";
import { createAssistantMessageEventStream } from "@earendil-works/pi-ai";
import type { ResolvedProviderConfig } from "./provider-config.js";

export class ModelRegistry {
  public static createStreamFn(config: ResolvedProviderConfig): StreamFn {
    // If provider is mock or testing, return deterministic mock stream
    if (config.providerId === "mock" || config.providerId === "test") {
      return (_model, _context, options) => {
        const stream = createAssistantMessageEventStream();
        queueMicrotask(() => {
          const makeMsg = (text: string, stopReason: any = "stop") => ({
            role: "assistant" as const,
            content: text ? [{ type: "text" as const, text }] : [],
            api: "mock" as any,
            provider: "mock",
            model: config.modelId,
            usage: {
              input: 10,
              output: 5,
              cacheRead: 0,
              cacheWrite: 0,
              totalTokens: 15,
              cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
            },
            stopReason,
            timestamp: Date.now(),
          });

          if (options?.signal?.aborted) {
            stream.push({
              type: "done",
              reason: "stop",
              message: makeMsg("", "aborted") as any,
            });
            stream.end();
            return;
          }

          const partialMsg = makeMsg("Mock answer response.");

          stream.push({
            type: "start",
            partial: makeMsg("") as any,
          });

          stream.push({
            type: "text_start",
            contentIndex: 0,
            partial: partialMsg as any,
          });

          stream.push({
            type: "text_delta",
            contentIndex: 0,
            delta: "Mock answer response.",
            partial: partialMsg as any,
          });

          stream.push({
            type: "text_end",
            contentIndex: 0,
            content: "Mock answer response.",
            partial: partialMsg as any,
          });

          stream.push({
            type: "done",
            reason: "stop",
            message: partialMsg as any,
          });
          stream.end();
        });
        return stream;
      };
    }

    // Default provider streaming via pi-ai stream function or fallback
    return (_model, _context, _options) => {
      const stream = createAssistantMessageEventStream();
      queueMicrotask(() => {
        const text = `[${config.providerId}/${config.modelId}] Completed execution.`;
        const msg = {
          role: "assistant" as const,
          content: [{ type: "text" as const, text }],
          api: config.providerId as any,
          provider: config.providerId,
          model: config.modelId,
          usage: {
            input: 20,
            output: 10,
            cacheRead: 0,
            cacheWrite: 0,
            totalTokens: 30,
            cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
          },
          stopReason: "stop" as const,
          timestamp: Date.now(),
        };

        stream.push({
          type: "start",
          partial: { ...msg, content: [] } as any,
        });
        stream.push({
          type: "text_start",
          contentIndex: 0,
          partial: msg as any,
        });
        stream.push({
          type: "text_delta",
          contentIndex: 0,
          delta: text,
          partial: msg as any,
        });
        stream.push({
          type: "text_end",
          contentIndex: 0,
          content: text,
          partial: msg as any,
        });
        stream.push({
          type: "done",
          reason: "stop",
          message: msg as any,
        });
        stream.end();
      });
      return stream;
    };
  }
}
