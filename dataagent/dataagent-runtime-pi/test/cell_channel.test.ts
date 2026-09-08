import test from "node:test";
import assert from "node:assert/strict";
import { PassThrough } from "node:stream";
import { CellChannel } from "../src/server/cell-channel.js";
import type { CellProtocolFrame } from "../src/contracts/runtime.js";

test("CellChannel parses valid NDJSON frames and routes to handlers", async () => {
  const inStream = new PassThrough();
  const outStream = new PassThrough();
  const channel = new CellChannel(inStream, outStream);

  const receivedFrames: CellProtocolFrame[] = [];
  channel.onFrame((frame) => {
    receivedFrames.push(frame);
  });
  channel.start();

  const testFrame: CellProtocolFrame = {
    protocol_version: 1,
    cell_id: "cell-test",
    run_id: "run-1",
    task_attempt_id: "att-1",
    frame_id: "f-1",
    type: "hello",
    payload: {},
  };

  // Write valid frame with newline
  inStream.write(JSON.stringify(testFrame) + "\n");

  await new Promise((r) => setTimeout(r, 20));

  assert.equal(receivedFrames.length, 1);
  assert.equal(receivedFrames[0].type, "hello");
  assert.equal(receivedFrames[0].cell_id, "cell-test");

  channel.close();
});

test("CellChannel handles non-JSON lines and emits protocol.error", async () => {
  const inStream = new PassThrough();
  const outStream = new PassThrough();
  const channel = new CellChannel(inStream, outStream);

  let output = "";
  outStream.on("data", (chunk) => {
    output += chunk.toString();
  });

  channel.start();

  // Write invalid non-JSON line
  inStream.write("THIS IS NOT JSON\n");

  await new Promise((r) => setTimeout(r, 20));

  assert.ok(output.includes("protocol.error"), "must send protocol.error frame");
  const parsed = JSON.parse(output.trim());
  assert.equal(parsed.type, "protocol.error");

  channel.close();
});
