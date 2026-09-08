import type { ModelTarget } from "../contracts/runtime.js";

export interface ResolvedProviderConfig {
  providerId: string;
  modelId: string;
  baseUrl?: string;
  apiKey?: string;
  headers: Record<string, string>;
  options: Record<string, unknown>;
}

export class ProviderConfig {
  public static resolve(target: ModelTarget, apiKey?: string): ResolvedProviderConfig {
    const providerId = target.provider_id.toLowerCase();
    const modelId = target.model_id;
    const opts = target.options || {};

    const baseUrl = (opts.base_url as string) || (opts.baseUrl as string) || target.endpoint_ref;
    const headers: Record<string, string> = {
      ...(opts.headers as Record<string, string> || {}),
    };

    if (apiKey) {
      if (providerId === "anthropic") {
        headers["x-api-key"] = apiKey;
        headers["anthropic-version"] = "2023-06-01";
      } else {
        headers["authorization"] = `Bearer ${apiKey}`;
      }
    }

    return {
      providerId,
      modelId,
      baseUrl,
      apiKey,
      headers,
      options: opts,
    };
  }
}
