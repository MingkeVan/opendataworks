# DataAgent DeepEval Parallel Evaluation Plan

## Evaluation V2 Addendum

- [x] Keep DeepEval fully independent from builtin and Opik runtime modules.
- [x] Accept V2 datasets only; validate the same external contract independently.
- [x] Add OAuth administrator preflight and propagate one identity to all DataAgent
  requests.
- [x] Extract task, message, and SDK event evidence independently, including real SQL
  execution, turns, errors, and recovery.
- [x] Replace misleading data precision/recall names with result consistency and
  numerator/denominator based business metrics.
- [x] Write the full V2 artifact set including `run.json` and self-contained HTML.
- [x] Pass the common golden fixtures without importing another engine.

## Implementation Tasks

- Add a standalone DeepEval runner under `tools/dataagent-evals/deepeval/` with requirements, Dockerfile, and README.
- Keep shared JSONL datasets private and external; both runners require `--dataset` or `DATAAGENT_EVAL_DATASET`.
- Add `scripts/run-dataagent-deepeval-evals.sh` as the manual Docker/Podman entrypoint.
- Add `opendataworks-dataagent-evals-deepeval:<tag>` to image build, offline package creation, and offline image loading scripts.
- Update deploy and scripts documentation with DeepEval run commands and judge environment variables.

## Verification

- Unit test JSONL-to-DeepEval case conversion and custom metric normalization.
- Contract test fake DataAgent HTTP flow plus fake DeepEval evaluation.
- Contract test offline packaging scripts reference the DeepEval image and eval tool directory.
- Run shell syntax checks for the touched scripts.
- Run DeepEval runner dry-run against an external private dataset.
