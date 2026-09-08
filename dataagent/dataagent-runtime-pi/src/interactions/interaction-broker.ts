import type { ResolveInteractionCommand } from "../contracts/runtime.js";

export interface PendingInteraction {
  interactionId: string;
  runId: string;
  resolve: (value: { block?: boolean; reason?: string; terminate?: boolean }) => void;
  timer?: NodeJS.Timeout;
}

export class InteractionBroker {
  private pending = new Map<string, PendingInteraction>();

  public requestInteraction(
    interactionId: string,
    runId: string,
    timeoutSeconds: number = 300,
    signal?: AbortSignal
  ): Promise<{ block?: boolean; reason?: string; terminate?: boolean }> {
    if (signal?.aborted) {
      return Promise.resolve({ block: true, reason: "Operation aborted", terminate: true });
    }

    return new Promise((resolve) => {
      const entry: PendingInteraction = {
        interactionId,
        runId,
        resolve,
      };

      // Expiration timer
      if (timeoutSeconds > 0) {
        entry.timer = setTimeout(() => {
          this.pending.delete(interactionId);
          resolve({ block: true, reason: "Interaction timed out", terminate: true });
        }, timeoutSeconds * 1000);
      }

      // Abort signal listener
      signal?.addEventListener(
        "abort",
        () => {
          if (entry.timer) {
            clearTimeout(entry.timer);
          }
          this.pending.delete(interactionId);
          resolve({ block: true, reason: "Cancelled by user abort", terminate: true });
        },
        { once: true }
      );

      this.pending.set(interactionId, entry);
    });
  }

  public resolveInteraction(command: ResolveInteractionCommand): boolean {
    const entry = this.pending.get(command.interaction_id);
    if (!entry) {
      return false;
    }
    if (entry.timer) {
      clearTimeout(entry.timer);
    }
    this.pending.delete(command.interaction_id);

    if (command.decision === "allow" || command.decision === "answered") {
      entry.resolve(undefined as any);
    } else {
      entry.resolve({
        block: true,
        reason: command.answer || `Denied (${command.decision})`,
        terminate: true,
      });
    }
    return true;
  }

  public cancelAll(reason: string = "Run cancelled"): void {
    for (const [id, entry] of this.pending.entries()) {
      if (entry.timer) {
        clearTimeout(entry.timer);
      }
      entry.resolve({ block: true, reason, terminate: true });
    }
    this.pending.clear();
  }
}
