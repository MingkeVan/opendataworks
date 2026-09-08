export class TokenBudget {
  private maxTokens: number;

  constructor(maxTokens: number = 128000) {
    this.maxTokens = maxTokens;
  }

  public estimateTextTokens(text: string): number {
    if (!text) return 0;
    // Rough conservative approximation: 1 token ~= 3.5 characters for mixed code/cjk/text
    return Math.ceil(text.length / 3.5);
  }

  public estimateMessagesTokens(messages: Array<{ content: any }>): number {
    let total = 0;
    for (const msg of messages) {
      total += 4; // per-message envelope overhead
      if (typeof msg.content === "string") {
        total += this.estimateTextTokens(msg.content);
      } else if (Array.isArray(msg.content)) {
        for (const block of msg.content) {
          if (block.type === "text" && block.text) {
            total += this.estimateTextTokens(block.text);
          } else if (block.type === "thinking" && block.thinking) {
            total += this.estimateTextTokens(block.thinking);
          } else if (block.type === "toolCall") {
            total += 10 + this.estimateTextTokens(JSON.stringify(block.arguments || {}));
          }
        }
      }
    }
    return total;
  }

  public checkBudget(messages: Array<{ content: any }>): { withinBudget: boolean; estimated: number; limit: number } {
    const estimated = this.estimateMessagesTokens(messages);
    return {
      withinBudget: estimated <= this.maxTokens,
      estimated,
      limit: this.maxTokens,
    };
  }
}
