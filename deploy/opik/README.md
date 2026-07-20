# Opik 2.1.32 local evaluation platform

This directory vendors the required Docker Compose assets from upstream tag
`2.1.32` / commit `69eb4a5812d067d1fbe07437bdec50ecf316adb4`.
`SOURCE.json` records original checksums and local hardening patches. The
service, SDK, and DataAgent Opik runner are all pinned to `2.1.32`; no active
Compose image uses `latest`.

Start and verify:

```bash
deploy/opik/start.sh
deploy/opik/health.sh
```

The scripts prefer Docker and automatically fall back to the Podman CLI and
`podman compose`. Podman Desktop by itself is insufficient if its `podman`
command is not installed or not on `PATH`.

The UI and API are exposed only at `127.0.0.1:5173`; Opik's MySQL, Redis,
ClickHouse, ZooKeeper, MinIO, and backend ports are not published. Persistent
volumes use the separate `odw-opik-2132` Compose project and do not share
OpenDataWorks MySQL or Redis storage.

Stop without deleting data:

```bash
deploy/opik/stop.sh
```

Data deletion is intentionally separate and explicit:

```bash
deploy/opik/destroy-data.sh --confirm-delete-opik-data
```

Before proposing an upgrade, run `deploy/opik/check-upgrade.sh <version>` and
then execute the SDK, Dataset, Experiment, trace persistence, stop/restart, and
three-engine contract tests. Never change the version to `latest`.

This Compose deployment is only for local evaluation-platform trials and is
not part of the default OpenDataWorks startup path. Open-source self-hosted
Opik does not provide the required enterprise authentication boundary. A
production deployment must sit behind the organization's Nginx/API gateway and
unified authentication; this directory does not provide production Helm, SSO,
LDAP, or JWT integration.
