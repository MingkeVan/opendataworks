import { Agent, type AgentOptions } from "@earendil-works/pi-agent-core";
import type { AgentRunRequest } from "../contracts/runtime.js";
import type { StreamFn } from "@earendil-works/pi-agent-core";

export interface CreatePiAgentConfig {
  request: AgentRunRequest;
  streamFn: StreamFn;
  beforeToolCall?: AgentOptions["beforeToolCall"];
  afterToolCall?: AgentOptions["afterToolCall"];
  transformContext?: AgentOptions["transformContext"];
  convertToLlm?: AgentOptions["convertToLlm"];
  tools?: any[];
}

export function createPiAgent(config: CreatePiAgentConfig): Agent {
  const { request, streamFn } = config;

  // Build model descriptor expected by pi-ai/pi-agent-core
  const modelDescriptor = {
    id: request.model.model_id,
    name: request.model.model_id,
    provider: request.model.provider_id,
    api: request.model.provider_id,
    capabilities: {},
  };

  const agentOptions: AgentOptions = {
    initialState: {
      systemPrompt: request.context.system_instructions,
      model: modelDescriptor as any,
      thinkingLevel: "off",
      tools: (config.tools as any) ?? [],
      messages: [],
    },
    streamFn,
    toolExecution: "sequential", // Strict sequential execution for DataAgent
    maxRetryDelayMs: 10000,
    beforeToolCall: config.beforeToolCall,
    afterToolCall: config.afterToolCall,
    transformContext: config.transformContext,
    convertToLlm: config.convertToLlm,
  };

  return new Agent(agentOptions);
}
