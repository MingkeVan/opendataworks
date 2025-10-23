# Scripts Directory

OpenDataWorks scripts are grouped by purpose. Every script is self-contained and can be executed from any working directory.

- `build/` – image build automation (single-arch, multi-arch, quick wrapper)
- `deploy/` – runtime operations: start/stop/restart, image loading, offline package bootstrap, quick local deployment helper
- `dev/` – developer utilities: database initialization, workflow integration tests, end-to-end verification
- `offline/` – tooling to assemble offline deployment bundles
- `maintenance/` – one-off maintenance helpers (e.g. legacy script cleanup)
- `test/` – workflow regression scripts and verifiers used during troubleshooting

See individual script headers for detailed usage instructions.
