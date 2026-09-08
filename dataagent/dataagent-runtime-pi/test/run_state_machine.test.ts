import test from "node:test";
import assert from "node:assert/strict";
import { RunStateMachine } from "../src/kernel/run-state-machine.js";

test("RunStateMachine generates monotonic sequences and forbids post-terminal events", () => {
  const sm = new RunStateMachine("run-1", "task-1", "att-1");
  assert.equal(sm.status, "idle");

  sm.markStarted();
  assert.equal(sm.status, "running");

  const ev1 = sm.createEvent("run.started", {});
  assert.equal(ev1.sequence, 1);
  assert.equal(ev1.run_id, "run-1");

  const ev2 = sm.createEvent("turn.started", { turn_id: "t-1" });
  assert.equal(ev2.sequence, 2);

  const ev3 = sm.createEvent("content.delta", { delta: "ok" });
  assert.equal(ev3.sequence, 3);

  // Terminal event
  const termEv = sm.createEvent("run.completed", { terminal_status: "success" });
  assert.equal(termEv.sequence, 4);
  assert.equal(sm.isSettled(), true);
  assert.equal(sm.status, "settled");

  // Attempting to emit after terminal must throw protocol violation
  assert.throws(
    () => {
      sm.createEvent("content.delta", { delta: "late" });
    },
    /Protocol violation: cannot emit/
  );
});
