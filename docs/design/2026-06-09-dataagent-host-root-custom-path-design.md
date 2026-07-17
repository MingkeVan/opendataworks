# DataAgent Host Root Custom Path Design

## Current State

`DATAAGENT_HOST_ROOT` is the single host-side persistent runtime root introduced
by the config consolidation. Compose mounts it to the fixed container path
`/dataagent_runtime` for `dataagent-home-init`, `dataagent-backend`, and
`dataagent-sandbox-runner`:

```yaml
volumes:
  - ${DATAAGENT_HOST_ROOT:-/dataagent_runtime}:/dataagent_runtime
```

The sandbox runner additionally receives the value as an env var and uses it to
tell the host Docker/Podman daemon where to bind-mount each topic's
`workspace/` and `home/` into per-task child containers
(`sandbox_runner_main._host_sandbox_root()` →
`Path(raw).expanduser().resolve()`).

The runner and launcher now separate the host and container roots, but a
follow-up defect remains in the backend file APIs. The complete failure had
three independent parts:

1. Runner conflates two roots. `sandbox_runner_main` used `DATAAGENT_HOST_ROOT`
   for both (a) the filesystem prep it performs *inside its own container*
   (`mkdir` the topic workspace/home, create child skill mount-target dirs,
   `chown`, write per-task logs) and (b) the bind-mount `source=` for child
   containers (resolved by the host Docker daemon). The runner sees the
   persistent volume at the fixed container path `/dataagent_runtime`, so the
   filesystem prep must target that path. These coincide only when
   `DATAAGENT_HOST_ROOT == /dataagent_runtime` — the default. With any custom
   root, the runner's `mkdir`/`chown`/log writes hit a non-existent path in its
   own overlay filesystem instead of the mounted volume: nothing is written
   under the custom host dir, the child's `.claude/skills/<folder>` mount targets
   never get created, and the child exits with "warm sandbox container exited
   without a result".

2. Relative values are re-resolved in the wrong place. Compose resolves a
   relative bind source relative to the project directory (`deploy/`), so
   `dataagent-backend` persists under `deploy/<rel>`. But the runner forwards the
   raw relative string and resolves it inside its own container (CWD `/app`), so
   `./runtime` becomes the container-local `/app/runtime` — a wrong child bind
   source. `scripts/start.sh` already normalizes `DATAAGENT_SKILLS_DIR` via
   `resolve_dataagent_skills_dir()` but had no equivalent for the runtime root.

3. Backend file APIs still conflate the roots. The backend receives `.env`
   through `env_file`, so a custom host value such as
   `/data/odw/dataagent_runtime` is also visible in the backend container.
   `core.topic_workspace` used that host path for `list_files()` and downloads,
   even though the volume is mounted inside the backend at the fixed path
   `/dataagent_runtime`. Sandbox tasks successfully write the host file, but the
   backend checks a different, container-local path and returns `file not found`.

## Problem

With a custom host directory, sandbox tasks can produce files successfully but
the backend cannot list, preview, or download them because it reads the host
path string from inside its container. Downloading must not depend on a sandbox
child: children are disposable, while topic files live on the shared persistent
volume and remain available through the backend.

## Scope

- Separate the two roots in the sandbox runner: a fixed container runtime root
  (`/dataagent_runtime`) for the runner's own filesystem operations, and the
  host root (`DATAAGENT_HOST_ROOT`) only for child bind-mount sources.
- Normalize a relative `DATAAGENT_HOST_ROOT` to an absolute host path in
  `scripts/start.sh` before invoking compose, mirroring the skills-dir handling.
- Separate the backend's container-visible runtime root from the host bind
  source so file APIs always read the shared volume directly.
- Keep the container-visible runtime root fixed at `/dataagent_runtime` and the
  compose contract unchanged.

Affected stacks: DataAgent backend and runner (`dataagent/dataagent-backend`)
and deployment (`scripts/start.sh`, `deploy/.env.example`, `deploy/README.md`).

## Solution

Primary fix — runner root separation (`sandbox_runner_main.py`):

- Add `_container_runtime_root()` returning the fixed `CONTAINER_RUNTIME_ROOT`
  (`/dataagent_runtime`) and container-path helpers
  `_topic_container_workspace/home/logs`. Use these for every filesystem
  operation the runner performs itself: workspace/home `mkdir`, child skill
  mount-target prep, `chown`, and per-task log writes.
- Keep `_host_sandbox_root()` (`DATAAGENT_HOST_ROOT`) and `_topic_host_workspace/home`
  strictly for the child bind-mount `source=` strings handed to the host daemon.
- Because the runner mounts the same volume the backend does, files it writes to
  `/dataagent_runtime/<topic>/...` land on the host at
  `DATAAGENT_HOST_ROOT/<topic>/...`, which is exactly the child bind source.

Supporting fix — launcher normalization (`scripts/start.sh`):

- `resolve_dataagent_host_root()` normalizes absolute values and resolves
  relative values against `deploy/`; the resolved value is `export`ed so Compose
  interpolation (shell env beats `--env-file`) gives every
  `${DATAAGENT_HOST_ROOT:-/dataagent_runtime}` mount and the runner's forwarded
  env the same absolute host path.

Together: the operator sets one value in `.env`; all services and per-task child
binds agree on one host directory, and the runner's own prep always lands on the
mounted volume regardless of where that volume is on the host.

Direct `docker compose` invocations that bypass `start.sh` still require an
absolute value; this is documented in `.env.example` and `deploy/README.md`.

Follow-up fix — backend runtime-root separation:

- Add the internal `DATAAGENT_RUNTIME_ROOT` setting. `core.topic_workspace`
  resolves implicit topic paths from this container-visible root first, then
  falls back to `DATAAGENT_HOST_ROOT` for local, non-container execution.
- Set `DATAAGENT_RUNTIME_ROOT=/dataagent_runtime` in the backend image. The
  backend therefore lists and serves files from its mounted volume regardless
  of the host directory used as the compose bind source.
- Keep download handling in the backend. It authorizes the topic, confines the
  relative path, and serves the persistent file without requiring a live or
  reusable sandbox child.

## Interfaces

- No new operator-facing `.env` variables. `DATAAGENT_HOST_ROOT` semantics widen from
  "absolute host path" to "absolute or `deploy/`-relative host path when launched
  via `start.sh`".
- `DATAAGENT_RUNTIME_ROOT` is an internal image contract for the backend's
  container-visible mount path, not a deployment tuning knob.
- Container runtime root stays fixed at `/dataagent_runtime` (an internal module
  constant, not an `.env` knob).

## Tradeoffs

- The relative-path convenience only applies through `start.sh`. Resolving a
  relative host path inside the runner container is impossible (the container
  cannot know the host's `deploy/` location), so the launcher is the correct
  single place to normalize it; raw `docker compose` keeps the explicit
  absolute-path requirement instead of a second, unreliable resolution layer.
- The backend image supplies the container runtime root as a fixed internal
  environment value. The setting exists so local tests and non-container
  execution can select the process-visible root explicitly, but it is not an
  operator-facing deployment knob.
- Local source execution still falls back to `DATAAGENT_HOST_ROOT`, preserving
  existing `.dataagent_runtime` development setups without container-only path
  detection or a sandbox download proxy.
