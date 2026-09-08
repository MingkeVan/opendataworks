import type { CellChannel } from "./cell-channel.js";
import type { AgentKernel, AgentRunRequest, CellProtocolFrame } from "../contracts/runtime.js";

export class RunService {
  private channel: CellChannel;
  private kernel: AgentKernel;
  private cellId: string;
  private activeRunId: string | null = null;

  constructor(channel: CellChannel, kernel: AgentKernel, cellId: string = `cell-${Date.now()}`) {
    this.channel = channel;
    this.kernel = kernel;
    this.cellId = cellId;
  }

  public init(): void {
    this.channel.onFrame(async (frame) => {
      await this.handleFrame(frame);
    });
  }

  private async handleFrame(frame: CellProtocolFrame): Promise<void> {
    switch (frame.type) {
      case "hello": {
        const manifest = this.kernel.manifest();
        this.channel.sendFrame({
          protocol_version: 1,
          cell_id: this.cellId,
          run_id: "none",
          task_attempt_id: "none",
          frame_id: `ack-${Date.now()}`,
          type: "hello.ack",
          payload: { manifest },
        });
        break;
      }
      case "run.start": {
        const request = frame.payload as unknown as AgentRunRequest;
        if (!request || !request.run_id) {
          this.channel.sendProtocolError("Invalid run.start frame: missing request payload");
          return;
        }

        this.activeRunId = request.run_id;

        // Acknowledge acceptance
        this.channel.sendFrame({
          protocol_version: 1,
          cell_id: this.cellId,
          run_id: request.run_id,
          task_attempt_id: request.task_attempt_id,
          frame_id: `acc-${Date.now()}`,
          type: "run.accepted",
          payload: { run_id: request.run_id, status: "accepted" },
        });

        // Run execution in background, sending events as they arrive
        try {
          const result = await this.kernel.run(request, (event) => {
            this.channel.sendFrame({
              protocol_version: 1,
              cell_id: this.cellId,
              run_id: request.run_id,
              task_attempt_id: request.task_attempt_id,
              frame_id: `evf-${event.sequence}`,
              type: "run.event",
              payload: event as any,
            });
          });

          // Settled frame
          this.channel.sendFrame({
            protocol_version: 1,
            cell_id: this.cellId,
            run_id: request.run_id,
            task_attempt_id: request.task_attempt_id,
            frame_id: `set-${Date.now()}`,
            type: "run.settled",
            payload: result as any,
          });
        } catch (err: any) {
          const errorMsg = err instanceof Error ? err.message : String(err);
          this.channel.sendFrame({
            protocol_version: 1,
            cell_id: this.cellId,
            run_id: request.run_id,
            task_attempt_id: request.task_attempt_id,
            frame_id: `err-${Date.now()}`,
            type: "run.settled",
            payload: {
              run_id: request.run_id,
              task_attempt_id: request.task_attempt_id,
              terminal_status: "failed",
              last_sequence: 0,
              error: errorMsg,
            },
          });
        } finally {
          this.activeRunId = null;
        }
        break;
      }
      case "interaction.resolve": {
        const cmd = frame.payload as any;
        if (cmd && cmd.interaction_id) {
          await this.kernel.resolveInteraction(cmd);
        }
        break;
      }
      case "run.cancel": {
        const cmd = frame.payload as any;
        await this.kernel.cancel({
          run_id: frame.run_id,
          reason: cmd?.reason,
        });
        break;
      }
      case "cell.shutdown": {
        process.stderr.write("[RunService] Received shutdown command. Exiting.\n");
        this.channel.close();
        process.exit(0);
      }
    }
  }
}
