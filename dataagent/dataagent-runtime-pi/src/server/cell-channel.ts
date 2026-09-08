import readline from "node:readline";
import type { Readable, Writable } from "node:stream";
import type { CellProtocolFrame } from "../contracts/runtime.js";

export type FrameHandler = (frame: CellProtocolFrame) => Promise<void> | void;

export class CellChannel {
  private rl: readline.Interface | null = null;
  private handlers: FrameHandler[] = [];
  private outStream: Writable;
  private inStream: Readable;

  constructor(inStream: Readable = process.stdin, outStream: Writable = process.stdout) {
    this.inStream = inStream;
    this.outStream = outStream;
  }

  public start(): void {
    if (this.rl) {
      return;
    }
    this.rl = readline.createInterface({
      input: this.inStream,
      terminal: false,
      crlfDelay: Infinity,
    });

    this.rl.on("line", (line: string) => {
      const trimmed = line.trim();
      if (!trimmed) {
        return;
      }
      try {
        const parsed = JSON.parse(trimmed) as CellProtocolFrame;
        if (!parsed || typeof parsed !== "object" || parsed.protocol_version !== 1) {
          this.sendProtocolError("Invalid frame: missing or invalid protocol_version", trimmed);
          return;
        }
        for (const handler of this.handlers) {
          try {
            const res = handler(parsed);
            if (res instanceof Promise) {
              res.catch((err) => {
                process.stderr.write(`[CellChannel] handler error: ${err}\n`);
              });
            }
          } catch (err) {
            process.stderr.write(`[CellChannel] sync handler error: ${err}\n`);
          }
        }
      } catch (err) {
        this.sendProtocolError(`Non-JSON line on stdin: ${err}`, trimmed);
      }
    });

    this.rl.on("close", () => {
      process.stderr.write("[CellChannel] stdin stream closed.\n");
    });
  }

  public onFrame(handler: FrameHandler): () => void {
    this.handlers.push(handler);
    return () => {
      this.handlers = this.handlers.filter((h) => h !== handler);
    };
  }

  public sendFrame(frame: CellProtocolFrame): void {
    const line = JSON.stringify(frame) + "\n";
    this.outStream.write(line);
  }

  public sendProtocolError(message: string, raw?: string): void {
    const errorFrame: CellProtocolFrame = {
      protocol_version: 1,
      cell_id: "unknown",
      run_id: "none",
      task_attempt_id: "none",
      frame_id: `err-${Date.now()}`,
      type: "protocol.error",
      payload: { error: message, raw_snippet: raw?.slice(0, 100) },
    };
    this.sendFrame(errorFrame);
  }

  public close(): void {
    if (this.rl) {
      this.rl.close();
      this.rl = null;
    }
  }
}
