#!/usr/bin/env bash
# LL-N06: the README quick start works on a clean machine. Starts the stack exactly as the README says, waits for
# health within a time budget, runs one chaos scenario with curl, checks the outcome and tears everything down.
#
#   scripts/fresh-clone-check.sh                     # budget 180 s, port 8080
#   LEDGERLINE_HTTP_PORT=18080 LEDGERLINE_DB_PORT=15432 scripts/fresh-clone-check.sh
#
# The CI job (ci.yml, fresh-clone) runs it on a new GitHub-hosted runner, so the clock includes the image build
# with empty Maven, npm and Docker caches. Locally, a warm Docker cache makes it much faster than a real fresh clone.
# Needs docker (with compose), curl and jq. Synthetic data only.
set -euo pipefail

budget=${HEALTH_BUDGET_SECONDS:-180}
port=${LEDGERLINE_HTTP_PORT:-8080}
base="http://localhost:${port}"
project=${COMPOSE_PROJECT_NAME:-ledgerline-fresh-clone}
export COMPOSE_PROJECT_NAME=$project

cd "$(dirname "$0")/.."

teardown() {
  status=$?
  if [ "$status" -ne 0 ]; then
    echo "--- app logs (last 100 lines) ---"
    docker compose logs app --tail 100 || true
  fi
  docker compose down -v --remove-orphans >/dev/null 2>&1 || true
  exit "$status"
}
trap teardown EXIT

start=$(date +%s)
echo "LL-N06: docker compose up -d (budget ${budget} s to healthy, project ${project})"
docker compose up -d

until curl -fsS "${base}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; do
  elapsed=$(($(date +%s) - start))
  if [ "$elapsed" -ge "$budget" ]; then
    echo "::error::LL-N06 FAILED: not healthy within ${budget} s"
    exit 1
  fi
  sleep 2
done
healthy=$(($(date +%s) - start))
echo "Healthy after ${healthy} s (budget ${budget} s)"

echo "Scenario: duplicate-storm (the success event delivered 5 times at once)"
run=$(curl -fsS -X POST "${base}/api/v1/chaos/scenarios/duplicate-storm" -H "Idempotency-Key: fresh-clone-$(date +%s)")
run_id=$(jq -r .scenarioRunId <<<"$run")
order_id=$(jq -r .orderId <<<"$run")

for _ in $(seq 1 30); do
  state=$(curl -fsS "${base}/api/v1/chaos/runs/${run_id}")
  [ "$(jq -r .status <<<"$state")" != "RUNNING" ] && break
  sleep 1
done

run_status=$(jq -r .status <<<"$state")
order_status=$(curl -fsS "${base}/api/v1/orders/${order_id}" | jq -r .status)
timeline=$(curl -fsS "${base}/api/v1/orders/${order_id}/timeline")
applied=$(jq '[.[] | select(.source == "INBOX" and .outcome == "APPLIED")] | length' <<<"$timeline")
duplicates=$(jq '[.[] | select(.kind == "DUPLICATE")] | length' <<<"$timeline")

echo "run=${run_status} order=${order_status} applied=${applied} duplicates=${duplicates}"
if [ "$run_status" != "COMPLETED" ] || [ "$order_status" != "PAID" ] || [ "$applied" != "2" ] || [ "$duplicates" != "4" ]; then
  echo "::error::LL-N06 FAILED: expected COMPLETED, PAID, 2 events applied (INITIATED, SUCCEEDED) and 4 duplicates"
  exit 1
fi
echo "LL-N06 passed: healthy in ${healthy} s; duplicate-storm applied once with 4 duplicates blocked"
