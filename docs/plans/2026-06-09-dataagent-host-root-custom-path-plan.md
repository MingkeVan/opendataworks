# DataAgent Host Root Custom Path Plan

Paired design: `docs/design/2026-06-09-dataagent-host-root-custom-path-design.md`

## Tasks

1. Runner root separation (`dataagent/dataagent-backend/sandbox_runner_main.py`).
   - Import `CONTAINER_RUNTIME_ROOT`; add `_container_runtime_root()` and
     `_topic_container_workspace/home/logs` helpers.
   - In `_build_container_command`, perform `mkdir`/skill-target prep/`chown` on
     the container-path workspace and home; use the host-path workspace and home
     only for the child `--mount source=` strings.
   - Point `_sandbox_task_log_path` at the container logs path so the runner's own
     log writes land on the mounted volume.

2. Launcher resolution (`scripts/start.sh`).
   - Add `resolve_dataagent_host_root()` mirroring `resolve_dataagent_skills_dir()`:
     read `DATAAGENT_HOST_ROOT` from `.env` (default `/dataagent_runtime`),
     normalize absolute values, resolve relative values against `deploy/`.
   - Before the compose `up`, set and `export DATAAGENT_HOST_ROOT` to the
     resolved absolute path and echo it so the chosen host directory is visible.

3. Backend runtime-root separation.
   - Add internal `dataagent_runtime_root` configuration and prefer it in
     `core/topic_workspace.py` for implicit topic path resolution.
   - Set `DATAAGENT_RUNTIME_ROOT=/dataagent_runtime` in the backend image so
     file listing, preview, download, cleanup, and attachment detection use the
     mounted volume instead of the host bind-source string.
   - Keep `DATAAGENT_HOST_ROOT` as the local fallback and runner child-bind
     source; do not proxy file downloads to disposable sandbox children.

4. Documentation.
   - `deploy/.env.example`: document that `DATAAGENT_HOST_ROOT` accepts a custom
     absolute path or a `deploy/`-relative path (expanded by `start.sh`), and
     that raw `docker compose` needs an absolute value.
   - `deploy/README.md`: same note on both DataAgent runtime-root bullets.

5. Tests.
   - `dataagent/dataagent-backend/tests/test_sandbox_runner_main.py`: add an
     autouse fixture redirecting `CONTAINER_RUNTIME_ROOT` to a temp dir; assert
     the runner creates topic workspace/home/logs under the container root while
     child bind sources use the (distinct) host root, and that the container path
     never leaks in as a bind source.
   - `tests/test_deepeval_packaging_hooks.py`: assert `start.sh` defines
     `resolve_dataagent_host_root` and exports the resolved value before compose.
   - `dataagent/dataagent-backend/tests/test_topic_workspace.py`: verify the
     container runtime root wins over a distinct host root and resolves an
     existing `output/report.html`; verify local fallback remains unchanged.
   - `dataagent/dataagent-backend/tests/test_runner_dockerfile.py`: assert the
     backend image declares its fixed container-visible runtime root.

## Touched files

- `dataagent/dataagent-backend/sandbox_runner_main.py`
- `dataagent/dataagent-backend/config.py`
- `dataagent/dataagent-backend/core/topic_workspace.py`
- `dataagent/dataagent-backend/Dockerfile`
- `dataagent/dataagent-backend/tests/test_sandbox_runner_main.py`
- `dataagent/dataagent-backend/tests/test_topic_workspace.py`
- `dataagent/dataagent-backend/tests/test_runner_dockerfile.py`
- `scripts/start.sh`
- `deploy/.env.example`
- `deploy/README.md`
- `tests/test_deepeval_packaging_hooks.py`
- `docs/design/2026-06-09-dataagent-host-root-custom-path-design.md`
- `docs/plans/2026-06-09-dataagent-host-root-custom-path-plan.md`

## Verification

- `pytest dataagent/dataagent-backend/tests/test_sandbox_runner_main.py` (host vs
  container root separation).
- `pytest dataagent/dataagent-backend/tests/test_topic_workspace.py
  dataagent/dataagent-backend/tests/test_topic_files.py
  dataagent/dataagent-backend/tests/test_runner_dockerfile.py` (backend direct
  persistent-volume reads and file path confinement).
- `pytest tests/test_deepeval_packaging_hooks.py` for the packaging/launcher
  assertions.
- `bash -n scripts/start.sh` and a shell unit check that
  `resolve_dataagent_host_root` returns an absolute path for both an absolute and
  a relative input.
- Local full-flow smoke (when Docker/MySQL/Redis available): set a custom
  `DATAAGENT_HOST_ROOT`, run one NL2SQL request, and confirm
  `<custom>/<topic>/{workspace,home,logs}` are populated on the host and the
  child completes with a result. Not run in this environment (no Docker daemon).

## Rollout

- Backend/runner code + launcher + docs change. Both backend and runner images
  must be rebuilt for the complete custom-root fix. Deployments using the
  default `/dataagent_runtime` are behavior-unchanged.

## Backout

- Revert the backend runtime-root changes plus the earlier runner/launcher edits.
  No data migration is involved; files remain in the existing host volume.
