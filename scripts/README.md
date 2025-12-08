# OpenDataWorks Utility Scripts

This directory contains various utility scripts for building, deploying, testing, and maintaining the OpenDataWorks platform.

## Directory Structure

| Directory | Description |
|-----------|-------------|
| `build/` | Scripts for building the project artifacts (frontend, backend). |
| `deploy/` | **Deployment configurations and scripts.** Contains Docker Compose files, environment configurations (`.env`), and control scripts (`start.sh`, `stop.sh`). |
| `dev/` | Developer tools and utilities for local development setup. |
| `maintenance/` | Maintenance scripts for system cleanup, data migration, or other administrative tasks. |
| `offline/` | Scripts related to creating and managing offline deployment packages. |
| `test/` | Automated test scripts for validating workflows and system functionality. |

## Common Tasks

### Deployment
Go to `deploy/` directory to manage the application lifecycle.
```bash
# Start the application
./scripts/deploy/start.sh

# Stop the application
./scripts/deploy/stop.sh
```

### Testing
Run automated tests from the `test/` directory.
```bash
# Run workflow lifecycle tests
./scripts/test/test-workflow-lifecycle.sh
```

### Build
Build the project using scripts in `build/`.
```bash
# Build backend
./scripts/build/build-backend.sh
```
