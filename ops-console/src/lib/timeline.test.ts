import { describe, expect, it } from "vitest";
import { entry } from "../test/fixtures";
import { buildTimeline } from "./timeline";

describe("LL-F02 timeline labels", () => {
  it("LL-F02 labels duplicate deliveries Deduplicated and late events Stale (out of order)", () => {
    const items = buildTimeline(
      [
        entry({
          kind: "PROVIDER_EVENT",
          source: "INBOX",
          at: "2026-01-15T10:00:01Z",
          detail: { type: "PAYMENT_SUCCEEDED", sequence: 2 },
          providerEventId: "evt_2",
          outcome: "APPLIED",
        }),
        entry({
          kind: "PAYMENT_EVENT_APPLIED",
          at: "2026-01-15T10:00:01Z",
          detail: { type: "PAYMENT_SUCCEEDED", sequence: 2, from: "PAYMENT_PENDING", to: "PAID" },
        }),
        entry({ kind: "DUPLICATE", at: "2026-01-15T10:00:02Z", detail: { providerEventId: "evt_2" } }),
        entry({ kind: "DUPLICATE", at: "2026-01-15T10:00:02Z", detail: { providerEventId: "evt_2" } }),
        entry({
          kind: "PAYMENT_EVENT_STALE",
          at: "2026-01-15T10:00:03Z",
          detail: { type: "PAYMENT_FAILED", sequence: 1, reason: "sequence 1 <= last applied 2" },
        }),
      ],
      [],
    );

    expect(items.map((i) => i.label)).toEqual([
      "Received",
      "Applied",
      "Deduplicated",
      "Deduplicated",
      "Stale (out of order)",
    ]);
    expect(items[2]?.detail).toContain("evt_2");
    expect(items[4]?.detail).toContain("PAYMENT_FAILED");
  });

  it("LL-F02 shows signature rejections, retries, delivery and dead letters", () => {
    const items = buildTimeline(
      [
        entry({
          kind: "CHAOS_STEP",
          at: "2026-01-15T10:00:01Z",
          detail: {
            step: "PAYMENT_SUCCEEDED altered after signing",
            httpStatus: 400,
            result: "invalid-signature",
          },
        }),
      ],
      [
        {
          id: 7,
          eventType: "order.paid",
          status: "PENDING",
          attempts: 2,
          nextAttemptAt: "2026-01-15T10:00:20Z",
          lastError: "HTTP 500 from merchant endpoint",
          createdAt: "2026-01-15T10:00:02Z",
          deliveredAt: null,
        },
        {
          id: 8,
          eventType: "order.refunded",
          status: "DELIVERED",
          attempts: 1,
          nextAttemptAt: "2026-01-15T10:00:03Z",
          lastError: null,
          createdAt: "2026-01-15T10:00:03Z",
          deliveredAt: "2026-01-15T10:00:04Z",
        },
        {
          id: 9,
          eventType: "order.cancelled",
          status: "DEAD",
          attempts: 8,
          nextAttemptAt: "2026-01-15T10:30:00Z",
          lastError: "HTTP 500 from merchant endpoint",
          createdAt: "2026-01-15T10:00:05Z",
          deliveredAt: null,
        },
      ],
    );

    expect(items.map((i) => i.label)).toEqual([
      "Signature rejected",
      "Notification queued",
      "Notification queued",
      "Outbox delivered",
      "Notification queued",
      "Retry scheduled",
      "Dead-lettered",
    ]);
    expect(items.find((i) => i.label === "Retry scheduled")?.detail).toContain("attempt 3 at 10:00:20 UTC");
  });
});
