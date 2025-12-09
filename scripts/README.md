# OpenDataWorks Utility Scripts

This directory contains various utility scripts for building and deploying the OpenDataWorks platform.

## Directory Structure

| Path | Description |
|------|-------------|
| `build/` | Build helpers for Docker images (single arch / multi-arch). |
| `start.sh` | Start the stack with `deploy/docker-compose.prod.yml`, auto-creating `deploy/.env` from the example when missing. |
| `stop.sh` | Stop all services defined in `deploy/docker-compose.prod.yml`. |
| `restart.sh` | Restart services from the compose file. |
| `load-images.sh` | Load tarred images from `deploy/docker-images/` (offline deployment). |
| `load-package-and-start.sh` | Extract an offline package, load images, and optionally start the stack. |
| `create-offline-package.sh` | Produce an offline tarball containing compose files, scripts, and images. |

## Common Tasks

### Deployment
From the repository root:
```bash
# Start the application (deploy/.env is required)
bash scripts/start.sh

# Stop the application
bash scripts/stop.sh
```

### Build
Build the project using scripts in `build/`.
```bash
# Build images (multi-arch)
bash scripts/build/build-multiarch.sh
```
