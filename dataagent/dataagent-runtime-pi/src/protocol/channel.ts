/**
 * NDJSON framing over stdio.
 *
 * stdout carries protocol frames and nothing else — every diagnostic goes to
 * stderr, which the Python supervisor mirrors into the task log. A stray
 * console.log here would be parsed as a frame, so this module is the only thing
 * that should ever write to stdout.
 */

import readline from "node:readline";
import type { Readable, Writable } from "node:stream";
import { PROTOCOL_VERSION, makeFrame, type OutboundFrameType, type ProtocolFrame } from "./frames.js";

export type FrameHandler = (frame: ProtocolFrame) => Promise<void> | void;

export function logDiagnostic(message: string): void {
  process.stderr.write(`[PiCell] ${message}\n`);
}

export class CellChannel {
  private rl: readline.Interface | null = null;
  private handler: FrameHandler | null = null;

  constructor(
    private readonly inStream: Readable = process.stdin,
    private readonly outStream: Writable = process.stdout
  ) {}

  public onFrame(handler: FrameHandler): void {
    this.handler = handler;
  }

  public start(): void {
    if (this.rl) {
      return;
    }
    this.rl = readline.createInterface({ input: this.inStream, terminal: false, crlfDelay: Infinity });

    this.rl.on("line", (line: string) => {
      const trimmed = line.trim();
      // A blank line is not end of input, and a malformed line is not a reason
      // to stop reading: the control plane may well send a valid frame next.
      if (!trimmed) {
        return;
      }
      let parsed: unknown;
      try {
        parsed = JSON.parse(trimmed);
      } catch (err) {
        this.send("protocol.error", { error: `non-JSON line on stdin: ${String(err)}` });
        return;
      }
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
        this.send("protocol.error", { error: "frame must be a JSON object" });
        return;
      }
      const frame = parsed as ProtocolFrame;
      if (frame.protocol_version !== PROTOCOL_VERSION) {
        this.send("protocol.error", {
          error: `unsupported protocol_version ${String(frame.protocol_version)}; this Cell speaks ${PROTOCOL_VERSION}`,
        });
        return;
      }
      void Promise.resolve(this.handler?.(frame)).catch((err) => {
        logDiagnostic(`frame handler error: ${String(err)}`);
      });
    });
  }

  public send(type: OutboundFrameType, payload: Record<string, unknown>): void {
    this.outStream.write(JSON.stringify(makeFrame(type, payload)) + "\n");
  }

  public close(): void {
    this.rl?.close();
    this.rl = null;
  }
}
