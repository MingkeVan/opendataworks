# AGENTS.md

## Node / nvm baseline

- This repository uses `nvm`.
- Before running any frontend command (`vite`, `npm --prefix frontend ...`, build/dev), load and switch Node from `.nvmrc`:
  - `export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && nvm use`
- If the target Node version is missing, install it first:
  - `nvm install`

## Frontend execution rule

- Always run `nvm use` successfully before frontend build/test/dev commands.
- If frontend command fails with Node engine/runtime errors, retry only after `nvm use`.
