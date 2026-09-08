import type { McpServerSpec } from "../contracts/runtime.js";

export interface McpToolCallRequest {
  serverName: string;
  toolName: string;
  arguments: Record<string, unknown>;
}

export class PortalMcpClient {
  private servers: Map<string, McpServerSpec> = new Map();

  constructor(serverSpecs: McpServerSpec[] = []) {
    for (const spec of serverSpecs) {
      this.servers.set(spec.name, spec);
    }
  }

  public isToolAllowed(serverName: string, toolName: string): boolean {
    const server = this.servers.get(serverName);
    if (!server) return false;
    if (!server.tool_allowlist || server.tool_allowlist.length === 0) return true;
    return server.tool_allowlist.includes(toolName);
  }

  public async callTool(request: McpToolCallRequest): Promise<unknown> {
    const server = this.servers.get(request.serverName);
    if (!server) {
      throw new Error(`MCP server '${request.serverName}' is not registered`);
    }
    if (!this.isToolAllowed(request.serverName, request.toolName)) {
      throw new Error(`MCP tool '${request.toolName}' on server '${request.serverName}' is not allowed`);
    }

    // Portal MCP execution placeholder/bridge
    return {
      success: true,
      data: {
        server: request.serverName,
        tool: request.toolName,
        result: `Executed ${request.toolName} with arguments`,
      },
    };
  }
}
