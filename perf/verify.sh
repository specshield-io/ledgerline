#!/usr/bin/env bash
# LL-N01 assertions after a load run: every delivered event is stored exactly once in the inbox and applied at most
# once, however many times it was delivered. Writes perf/results/verify.json; exits 1 on any violation.
# Usage: perf/verify.sh <run id> <unique events sent>   (both printed by k6 in summary.json)
set -euo pipefail
RUN_ID="$1"
SENT="$2"
cd "$(dirname "$0")/.."
psql() { docker compose exec -T postgres psql -U ledgerline -d ledgerline -At -v ON_ERROR_STOP=1 "$@"; }
PREFIX="evt_load_${RUN_ID}_"

stored=$(psql -c "SELECT count(*) FROM order_events_inbox WHERE provider_event_id LIKE '${PREFIX}%'")
distinct=$(psql -c "SELECT count(DISTINCT provider_event_id) FROM order_events_inbox WHERE provider_event_id LIKE '${PREFIX}%'")
applied=$(psql -c "SELECT count(*) FROM audit_log WHERE kind = 'PAYMENT_EVENT_APPLIED' AND detail->>'providerEventId' LIKE '${PREFIX}%'")
applied_twice=$(psql -c "SELECT count(*) FROM (SELECT 1 FROM audit_log WHERE kind = 'PAYMENT_EVENT_APPLIED' AND detail->>'providerEventId' LIKE '${PREFIX}%' GROUP BY detail->>'providerEventId' HAVING count(*) > 1) t")
duplicates=$(psql -c "SELECT count(*) FROM audit_log WHERE kind = 'DUPLICATE' AND detail->>'providerEventId' LIKE '${PREFIX}%'")
pending=$(psql -c "SELECT count(*) FROM order_events_inbox WHERE provider_event_id LIKE '${PREFIX}%' AND outcome IS NULL")
paid=$(psql -c "SELECT count(*) FROM orders WHERE merchant_ref LIKE 'load-%' AND status = 'PAID'")

ok=true
[ "$stored" = "$distinct" ] || ok=false       # one inbox row per event id
[ "$stored" = "$SENT" ] || ok=false           # every unique event that was sent is stored
[ "$applied_twice" = "0" ] || ok=false        # no event applied more than once
[ "$applied" -le "$stored" ] || ok=false

mkdir -p perf/results
cat > perf/results/verify.json <<JSON
{"runId":"${RUN_ID}","uniqueEventsSent":${SENT},"inboxRows":${stored},"distinctEventIds":${distinct},"applied":${applied},"appliedMoreThanOnce":${applied_twice},"duplicatesAcknowledged":${duplicates},"pendingBehindGap":${pending},"ordersPaid":${paid},"passed":${ok}}
JSON
cat perf/results/verify.json
$ok || { echo "LL-N01 database check FAILED" >&2; exit 1; }
echo "LL-N01 database check passed: ${stored} events stored once, none applied twice, ${duplicates} duplicates acknowledged"
