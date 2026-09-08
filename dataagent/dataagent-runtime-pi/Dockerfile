FROM node:22-bookworm-slim

# python3 is here for skill scripts invoked through "$DATAAGENT_PYTHON_BIN".
RUN apt-get update && apt-get install -y --no-install-recommends \
      python3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY package.json package-lock.json tsconfig.json ./
# NODE_ENV must NOT be "production" here: npm's `omit` config defaults to
# ["dev"] when NODE_ENV=production, which drops typescript and makes the build
# below fail. It is set after the build instead.
RUN npm ci --ignore-scripts

COPY src/ ./src/
RUN npm run build && npm prune --omit=dev

ENV NODE_ENV=production
ENV DATAAGENT_PYTHON_BIN=/usr/bin/python3

USER node

# Stdio cell: the control plane spawns this and speaks NDJSON over stdin/stdout.
# There is no port and no healthcheck by design.
ENTRYPOINT ["node", "dist/src/main.js"]
