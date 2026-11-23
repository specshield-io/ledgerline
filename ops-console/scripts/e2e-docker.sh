#!/usr/bin/env bash
# Runs the Playwright suite inside the pinned Playwright Linux image, attached to the docker compose network (the app
# is http://ledgerline-api:8080 there, see docker-compose.e2e.yml), so
# screenshots are rendered exactly as in CI. Start the stack first:
#   docker compose -f docker-compose.yml -f docker-compose.e2e.yml up --build --wait
# Usage: scripts/e2e-docker.sh [playwright test arguments]
set -euo pipefail

IMAGE="mcr.microsoft.com/playwright:v1.65.0-noble@sha256:9beb546099e11f52d62e505537914870e01a67e0e6b00ae41da4775324e73dbe"
NETWORK="${E2E_DOCKER_NETWORK:-ledgerline_default}"
BASE_URL="${E2E_BASE_URL:-http://ledgerline-api:8080}"
cd "$(dirname "$0")/.."

# node_modules lives in a named volume: the image is Linux, the host may not be.
exec docker run --rm --init --ipc=host \
  --network "$NETWORK" \
  -e E2E_BASE_URL="$BASE_URL" -e E2E_ADMIN_USER -e E2E_ADMIN_PASSWORD -e CI \
  -v "$PWD":/work -v ledgerline-e2e-node-modules:/work/node_modules -w /work \
  "$IMAGE" \
  bash -c 'npm ci --no-audit --no-fund --loglevel=error && npx playwright test "$@"' bash "$@"
