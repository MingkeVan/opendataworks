import type { SecretEnvelope } from "../contracts/runtime.js";

export class CredentialResolver {
  private activeCredentials: Map<string, SecretEnvelope> = new Map();

  public register(runId: string, envelope?: SecretEnvelope): void {
    if (envelope) {
      this.activeCredentials.set(runId, { ...envelope });
    }
  }

  public resolveApiKey(runId: string): string | undefined {
    return this.activeCredentials.get(runId)?.api_key;
  }

  public resolveMcpToken(runId: string, serverName: string): string | undefined {
    return this.activeCredentials.get(runId)?.mcp_tokens?.[serverName];
  }

  public resolveCustomHeaders(runId: string): Record<string, string> {
    return this.activeCredentials.get(runId)?.custom_headers || {};
  }

  public cleanup(runId: string): void {
    const cred = this.activeCredentials.get(runId);
    if (cred) {
      // Overwrite memory before removing
      cred.api_key = undefined;
      cred.mcp_tokens = {};
      cred.custom_headers = {};
      this.activeCredentials.delete(runId);
    }
  }
}
