# Deployment Scripts

This directory contains control scripts and configurations for deploying OpenDataWorks using Docker Compose.

## Files

### Control Scripts
- `start.sh`: Starts the application using `docker-compose.prod.yml`. Checks for `.env` and creates it if missing.
- `stop.sh`: Stops the application services.
- `restart.sh`: Restarts the application services.
- `load-images.sh`: Loads Docker images from `docker-images/` directory (for offline deployment).
- `quick-deploy.sh`: A helper script for quick deployment setups.

### Configuration
- `docker-compose.prod.yml`: Production Docker Compose configuration.
- `docker-compose.dev.yml`: Development Docker Compose configuration.
- `.env.example`: Template for environment variables. Copy to `.env` to customize.

## Usage

### Prerequisites
- Docker and Docker Compose installed.

### Starting the Application
1. (Optional) Configure environment variables:
   ```bash
   cp .env.example .env
   # Edit .env with your settings
   ```
2. Run start script:
   ```bash
   ./start.sh
   ```

### Stopping the Application
```bash
./stop.sh
```

### Restarting
```bash
./restart.sh
```
