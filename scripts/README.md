# OpenDataWorks Utility Scripts

This directory contains various utility scripts for building and deploying the OpenDataWorks platform.

## Directory Structure

| Directory | Description |
|-----------|-------------|
| `build/` | Scripts for building the project artifacts (frontend, backend). |
| `deploy/` | **Deployment configurations and scripts.** Contains Docker Compose files, environment configurations (`.env`), and control scripts (`start.sh`, `stop.sh`). |
| `offline/` | Scripts related to creating and managing offline deployment packages. |

## Common Tasks

### Deployment
Go to `deploy/` directory to manage the application lifecycle.
```bash
# Start the application
./scripts/deploy/start.sh

# Stop the application
./scripts/deploy/stop.sh
```

### Build
Build the project using scripts in `build/`.
```bash
# Build backend
./scripts/build/build-backend.sh
```
