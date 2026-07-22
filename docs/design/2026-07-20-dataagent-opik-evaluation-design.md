# DataAgent Opik Evaluation Design

## Current State

DataAgent has independent builtin and DeepEval online evaluation runners. Private
architecture-governance cases live outside OpenDataWorks and exercise the real
topic/task/message/SDK-event chain.

## Problem

File reports are useful for gates but provide limited interactive experiment,
trace, and dataset comparison. Opik is being evaluated as a third tool, without
making it a dependency or shared runtime for the existing engines.

## Solution

- Add an independent Opik runner and image pinned to `opik==2.1.32`.
- Pin the self-hosted Opik server and all local Compose assets to 2.1.32; never
  use `latest`.
- Import V2 items into a project-scoped, content-addressed Dataset and execute
  them with Opik `evaluate()`.
- Create one trace per case and spans for DataAgent submission, polling, SDK
  evidence collection, and judging.
- Implement Opik-native custom metrics for the seven weighted dimensions and
  hard gates, while also writing the standard offline V2 artifacts.
- Treat Opik availability as infrastructure: failures stop new submissions,
  preserve a partial report, and exit 2.
- Independently execute optional read-only reference SQL and report data
  accuracy separately from final-answer/result consistency.
- Treat a successful zero-row reference result as expected empty truth for that
  run; never turn reference SQL, transport, or authorization failures into an
  empty business result.

## Interfaces

The runner accepts the standard DataAgent evaluation arguments plus:

- `--opik-base-url` / `OPIK_BASE_URL`
- `--opik-project-name` / `OPIK_PROJECT_NAME` (default `dataagent-evals`)
- `--opik-dataset-name`
- `--opik-experiment-name`

Dataset names default to `<dataset-id>-<hash-prefix>` and experiment names to
`<run-label>-<model>-<timestamp>`. Dataset item content includes the complete V2
case contract and source hash. Experiment config includes only non-secret run
metadata.

## Deployment And Security

`deploy/opik/` contains a pinned local trial deployment. It binds to loopback,
uses storage independent from OpenDataWorks services, and is not part of the
default application startup. The open-source deployment has no user management;
production exposure requires an existing gateway and unified authentication and
is outside this change.

Local real-chain acceptance uses the repository's
`opendataworks-business-knowledge` Skill and its six-case smoke dataset. The
private architecture-ontology suite remains external and is not a prerequisite
for local execution.

## Tradeoffs

The three engines intentionally duplicate behavior. Golden conformance fixtures
and comparison reports reveal divergence without turning one implementation into
a shared dependency. Opik adds operational weight and is therefore optional.
