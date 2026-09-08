/**
 * Cell entrypoint.
 *
 * The kernel is constructed *with* a real model factory. A Cell wired without
 * one throws on the first run and passes every test, because tests inject their
 * own stream function — so the wiring, not the kernel, is what this file exists
 * to get right.
 */

import { CellChannel, logDiagnostic } from "./protocol/channel.js";
import { Cell } from "./kernel/cell.js";
import { resolveRuntimeModel } from "./providers/stream-fn-resolver.js";
import type { CellInitPayload, ProtocolFrame } from "./protocol/frames.js";

function bootstrap(): void {
  const channel = new CellChannel(process.stdin, process.stdout);
  const cell = new Cell(resolveRuntimeModel);
  let running = false;

  channel.onFrame(async (frame: ProtocolFrame) => {
    switch (frame.type) {
      case "cell.init": {
        if (running) {
          channel.send("protocol.error", { error: "cell already has an active run" });
          return;
        }
        running = true;
        const init = frame.payload as unknown as CellInitPayload;
        channel.send("cell.ready", { manifest: { runtime_kind: "pi_agent_core", protocol_version: 1 } });
        try {
          const result = await cell.run(init, (event) => {
            channel.send("run.event", event as unknown as Record<string, unknown>);
          });
          channel.send("run.settled", result as unknown as Record<string, unknown>);
        } finally {
          running = false;
          // One run per Cell process: the control plane spawns a fresh child per
          // turn, so lingering here would only hold the workspace open.
          channel.close();
          process.exit(0);
        }
        return;
      }
      case "run.cancel": {
        logDiagnostic(`cancel requested: ${String((frame.payload as { reason?: string })?.reason ?? "")}`);
        cell.cancel();
        return;
      }
      case "cell.shutdown": {
        channel.close();
        process.exit(0);
        return;
      }
      default: {
        channel.send("protocol.error", { error: `unknown frame type '${frame.type}'` });
      }
    }
  });

  channel.start();
  logDiagnostic("runtime cell listening on stdio");
}

bootstrap();
