// LL-N01: signed MockPay webhook deliveries at 200 requests/s, 20 % of them duplicates of earlier events.
// Synthetic data only. Run through perf/run.sh, which seeds the orders and verifies the database afterwards.
import crypto from "k6/crypto";
import exec from "k6/execution";
import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
// The local profile's synthetic MockPay secret (application.yml); not a secret.
const SECRET = __ENV.MOCKPAY_SECRET || "local-dev-only-mockpay-signing-secret-0001";
const RUN_ID = __ENV.RUN_ID || String(Date.now());
const ORDERS = Number(__ENV.ORDERS || 15000);
const RATE = Number(__ENV.RATE || 200);
const RAMP = __ENV.RAMP || "30s";
const HOLD = __ENV.HOLD || "2m";
const OUTPUT = __ENV.SUMMARY_FILE || "/perf/results/summary.json";

const uniqueEvents = new Counter("unique_events");
const duplicateDeliveries = new Counter("duplicate_deliveries");

export const options = {
  scenarios: {
    webhooks: {
      executor: "ramping-arrival-rate",
      startRate: 10,
      timeUnit: "1s",
      preAllocatedVUs: 100,
      maxVUs: 400,
      stages: [
        { target: RATE, duration: RAMP },
        { target: RATE, duration: HOLD },
      ],
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.001"],
    http_req_duration: ["p(95)<150"],
  },
  summaryTrendStats: ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)"],
};

/** md5('load-order-<n>') formatted as a UUID, matching perf/seed.sql. */
function orderId(n) {
  const h = crypto.md5(`load-order-${n}`, "hex");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/** Unique event u: order floor(u / 2), INITIATED (sequence 1) then SUCCEEDED (sequence 2). */
function event(u) {
  const sequence = (u % 2) + 1;
  return JSON.stringify({
    id: `evt_load_${RUN_ID}_${u}`,
    type: sequence === 1 ? "PAYMENT_INITIATED" : "PAYMENT_SUCCEEDED",
    created: Math.floor(Date.now() / 1000),
    sequence,
    data: { orderId: orderId(Math.floor(u / 2) % ORDERS), amountMinor: 2599, currency: "USD" },
  });
}

export default function () {
  const i = exec.scenario.iterationInTest;
  // Every fifth delivery repeats a random earlier event; the others are new events, numbered without gaps.
  const uniquesBefore = i - Math.floor(i / 5);
  const duplicate = i % 5 === 4 && uniquesBefore > 0;
  const u = duplicate ? Math.floor(Math.random() * uniquesBefore) : uniquesBefore;
  const body = event(u);
  const t = Math.floor(Date.now() / 1000);
  const signature = `t=${t},v1=${crypto.hmac("sha256", SECRET, `${t}.${body}`, "hex")}`;

  const response = http.post(`${BASE_URL}/webhooks/mockpay`, body, {
    headers: { "Content-Type": "application/json", "MockPay-Signature": signature },
    tags: { delivery: duplicate ? "duplicate" : "unique" },
  });
  check(response, { "200 received": (r) => r.status === 200 });
  if (duplicate) {
    duplicateDeliveries.add(1);
  } else {
    uniqueEvents.add(1);
  }
}

export function handleSummary(data) {
  const m = data.metrics;
  const value = (name, stat) => (m[name] && m[name].values ? m[name].values[stat] : null);
  const text = [
    `LL-N01 run ${RUN_ID}`,
    `requests      ${value("http_reqs", "count")} (${value("http_reqs", "rate")?.toFixed(1)}/s)`,
    `unique events ${value("unique_events", "count")}, duplicate deliveries ${value("duplicate_deliveries", "count")}`,
    `p95           ${value("http_req_duration", "p(95)")?.toFixed(1)} ms (threshold < 150 ms)`,
    `failed        ${(100 * (value("http_req_failed", "rate") ?? 0)).toFixed(3)} % (threshold < 0.1 %)`,
    "",
  ].join("\n");
  return { [OUTPUT]: JSON.stringify({ runId: RUN_ID, ...data }, null, 2), stdout: text };
}
