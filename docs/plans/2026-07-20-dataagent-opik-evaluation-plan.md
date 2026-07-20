# DataAgent Opik Evaluation Implementation Plan

## Implementation

- [x] Create an independent Opik runner, pinned requirements, Dockerfile, README, and
  shell wrapper.
- [x] Implement V2 loading, DataAgent OAuth preflight, real HTTP task execution, SDK
  event extraction, deterministic checks, fixed judging, and local reports
  without importing builtin or DeepEval code.
- [x] Create content-addressed Opik datasets, case traces/spans, experiments, custom
  metric feedback, and version/run metadata with secret redaction.
- [x] Vendor the required upstream 2.1.32 local Compose assets with source provenance,
  version locks, loopback overlay, and non-destructive lifecycle scripts.
- [x] Extend offline packaging with the Opik runner and an explicit inventory of
  pinned local-platform images.

## Verification

- [x] Unit and contract tests for configuration, dataset idempotency, trace/experiment
  mapping, custom metrics, report parity, and failure handling.
- [ ] Start the pinned local Compose stack externally; verify health, loopback
  binding, persistence across restart, and server/SDK version compatibility.
- [ ] Run the repository-local five-case `opendataworks-business-knowledge`
  smoke suite when provider, administrator token, and runtime services are
  available. Do not depend on private architecture-ontology data for this test.
- [x] Generate engine comparison output only for compatibility-key-equivalent runs.
