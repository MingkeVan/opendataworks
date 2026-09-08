import type { ExecutionPolicySnapshot } from "../contracts/runtime.js";

export type PolicyDecisionType = "allow" | "deny" | "require_interaction";

export interface PolicyDecision {
  decision: PolicyDecisionType;
  reason?: string;
  prompt?: string;
}

export class PolicyEnforcer {
  private policy: ExecutionPolicySnapshot;

  constructor(policy: ExecutionPolicySnapshot = {}) {
    this.policy = policy;
  }

  public evaluateToolCall(canonicalId: string, sideEffect: "none" | "read" | "write" | "admin", args: unknown): PolicyDecision {
    // 1. Check allowed tools list
    if (this.policy.allowed_tools && this.policy.allowed_tools.length > 0) {
      if (!this.policy.allowed_tools.includes(canonicalId)) {
        return {
          decision: "deny",
          reason: `Tool '${canonicalId}' is not in the allowed tools whitelist for this session.`,
        };
      }
    }

    // 2. Check write confirmation policy
    if (this.policy.require_write_confirmation) {
      if (sideEffect === "write" || sideEffect === "admin") {
        return {
          decision: "require_interaction",
          prompt: `Action '${canonicalId}' requires user confirmation before execution.`,
        };
      }
    }

    return { decision: "allow" };
  }
}
