# DataAgent Evaluation Reliability Implementation Plan

**Design**: [2026-07-23-dataagent-evaluation-reliability-design.md](../design/2026-07-23-dataagent-evaluation-reliability-design.md)

## Implementation

- [x] Audit archived failures and classify Agent, evaluator, and case-contract
  causes.
- [x] Fix SQL evidence collection, multi-query fragment checks, time-range
  validation, and false-positive attribution in all three runners.
- [x] Make reference comparison projection-aware and comparability-aware.
- [x] Separate reference accuracy from the per-case hard gate, with explicit
  opt-in enforcement.
- [x] Add structured query evidence to the judge while keeping reference-oracle
  diagnostics entirely deterministic and outside the judge input.
- [x] Correct result-consistency, reasoning, data-comparability, and final gate
  summary semantics.
- [x] Update V2 schema/README and report rendering for the clarified contract.
- [x] Record the per-case audit under `docs/reports/`.

## Verification

- [x] Add focused builtin-runner regression tests for each discovered failure
  mode.
- [x] Extend three-engine conformance tests for the shared semantics.
- [x] Run the narrow evaluation test suite with the repository Python
  environment.
- [x] Replay the discovered deterministic failure modes through the
  three-engine contract suite and require at least `90%` evaluator accuracy.
- [x] Run 10 real OpenDataWorks end-to-end cases with the production builtin
  runner and reach at least `90%` effective pass rate.
- [x] Verify that dataset/category/tag relabeling cannot change deterministic
  rule results and remove all domain-specific runner branches.
- [x] State exactly which online paths were exercised and do not claim
  unavailable provider/database coverage.

## Rollout

- Bump `metric_semantics_version` and `judge_prompt_version` so trend tooling
  does not compare the new metrics as if they were identical to the old run.
- Keep old report ingestion compatible: new fields are additive.
- Re-run the production evaluation suite after deployment and compare both
  score and comparability coverage.

## Backout

- Revert the runner, schema, documentation, and tests together.
- Historical run artifacts remain readable because their existing fields are
  unchanged.
