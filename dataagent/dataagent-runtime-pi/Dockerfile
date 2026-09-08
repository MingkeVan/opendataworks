FROM node:22-bookworm-slim

ENV NODE_ENV=production
ENV DATAAGENT_PYTHON_BIN=/usr/bin/python3

# Install python3 for skill script execution and curl for healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy dependency specifications
COPY package.json package-lock.json tsconfig.json ./

# Install exact dependencies and build typescript
RUN npm ci --ignore-scripts
COPY src/ ./src/
RUN npm run build && npm prune --production

# Create non-root runtime directories
RUN mkdir -p /tmp/workspaces /tmp/spool && chown -R node:node /app /tmp/workspaces /tmp/spool

USER node

# Stdio cell entrypoint
ENTRYPOINT ["node", "dist/src/main.js"]
