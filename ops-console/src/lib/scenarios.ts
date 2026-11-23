/** The chaos scenarios (A5), with what each shows and what to watch for in the timeline. */
export interface ScenarioInfo {
  name: string;
  title: string;
  explanation: string;
  watchFor: string;
}

export const SCENARIOS: ScenarioInfo[] = [
  {
    name: "happy-path",
    title: "Happy path",
    explanation: "Create an order and pay it through MockPay.",
    watchFor:
      "Two events Received and Applied; the order ends Paid and an order.paid notification is delivered.",
  },
  {
    name: "duplicate-storm",
    title: "Duplicate storm",
    explanation: "MockPay delivers the same success event 5 times at once.",
    watchFor: "One Applied, four Deduplicated; the order is paid exactly once.",
  },
  {
    name: "tampered-webhook",
    title: "Tampered webhook",
    explanation: "The success event's body is changed after it was signed.",
    watchFor: "Signature rejected; the order stays Payment pending.",
  },
  {
    name: "replay-attack",
    title: "Replay attack",
    explanation: "A correctly signed event is replayed 10 minutes later.",
    watchFor: "Signature rejected (timestamp too old); the order stays Payment pending.",
  },
  {
    name: "out-of-order",
    title: "Out of order",
    explanation: "SUCCEEDED (sequence 2) arrives, then a late FAILED (sequence 1).",
    watchFor: "Stale (out of order) for the late event; the order stays Paid.",
  },
  {
    name: "merchant-down",
    title: "Merchant down",
    explanation: "The merchant endpoint answers 500 for 2 minutes.",
    watchFor: "Retry scheduled with growing delays, then Outbox delivered after recovery (or Dead-lettered).",
  },
  {
    name: "merchant-slow",
    title: "Merchant slow",
    explanation: "The merchant takes 10 s to answer for 2 minutes.",
    watchFor: "Attempts time out after 3 s and are retried; delivery is at-least-once.",
  },
  {
    name: "refund-flow",
    title: "Refund flow",
    explanation: "Pay, refund part of the amount, then refund the rest.",
    watchFor: "Paid, then Partially refunded, then Refunded, each with its notification.",
  },
];
