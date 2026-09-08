import { CellChannel } from "./server/cell-channel.js";
import { DataAgentPiKernel } from "./kernel/dataagent-pi-kernel.js";
import { RunService } from "./server/run-service.js";

function bootstrap() {
  process.stderr.write("[PiCell] Bootstrapping DataAgent Pi Runtime Cell...\n");

  const channel = new CellChannel(process.stdin, process.stdout);
  const kernel = new DataAgentPiKernel();
  const runService = new RunService(channel, kernel);

  runService.init();
  channel.start();

  process.stderr.write("[PiCell] Runtime Cell ready and listening on stdio.\n");
}

bootstrap();
