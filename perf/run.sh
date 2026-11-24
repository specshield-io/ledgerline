#!/usr/bin/env bash
# LL-N01 load test. Needs the stack up with the perf override (network alias for the k6 container):
#   docker compose -f docker-compose.yml -f docker-compose.perf.yml up --build --wait
#   perf/run.sh                  (RATE, RAMP, HOLD override the 200/s, 30 s ramp, 2 min hold)
set -euo pipefail
cd "$(dirname "$0")/.."
IMAGE="grafana/k6:2.3.0@sha256:2dd4756ec6e14e62778916e1eb850108e4b499304c02df56760fa0bdb0fba0b8"
ORDERS="${ORDERS:-15000}"
RUN_ID="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p perf/results
chmod 0777 perf/results   # the k6 image runs as a non-root user

docker compose exec -T postgres psql -U ledgerline -d ledgerline -v ON_ERROR_STOP=1 -v orders="$ORDERS" -q < perf/seed.sql

status=0
docker run --rm --network ledgerline_default -v "$PWD/perf:/perf" \
  -e BASE_URL=http://ledgerline-api:8080 -e RUN_ID="$RUN_ID" -e ORDERS="$ORDERS" \
  -e RATE -e RAMP -e HOLD \
  "$IMAGE" run --quiet /perf/webhooks.js || status=$?

sent=$(python3 -c 'import json,sys; print(int(json.load(open("perf/results/summary.json"))["metrics"]["unique_events"]["values"]["count"]))')
perf/verify.sh "$RUN_ID" "$sent" || status=1
exit "$status"
