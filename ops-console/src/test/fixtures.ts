import type { Order, OutboxMessage, TimelineEntry } from "../api/client";

const NOW = new Date().toISOString();

export function order(overrides: Partial<Order> & Pick<Order, "id" | "status">): Order {
  return {
    merchantRef: `demo-${overrides.id.slice(-4)}`,
    amountMinor: 2599,
    currency: "USD",
    refundedMinor: 0,
    version: 1,
    createdAt: NOW,
    updatedAt: NOW,
    lastProviderEventAt: null,
    ...overrides,
  };
}

export const ORDERS: Order[] = [
  order({
    id: "019bc119-0100-7000-8000-00000000a001",
    status: "PAID",
    merchantRef: "demo-1001",
    amountMinor: 4599,
  }),
  order({ id: "019bc119-0100-7000-8000-00000000a002", status: "PAYMENT_PENDING", merchantRef: "demo-1002" }),
  order({
    id: "019bc119-0100-7000-8000-00000000a003",
    status: "CANCELLED",
    merchantRef: "demo-1003",
    amountMinor: 999,
  }),
];

export function entry(overrides: Partial<TimelineEntry> & Pick<TimelineEntry, "kind">): TimelineEntry {
  return {
    at: "2026-01-15T10:00:00Z",
    source: "AUDIT",
    detail: {},
    actor: "provider:mockpay",
    correlationId: null,
    providerEventId: null,
    outcome: null,
    ...overrides,
  };
}

export function outboxMessage(
  overrides: Partial<OutboxMessage> & Pick<OutboxMessage, "id" | "status">,
): OutboxMessage {
  return {
    aggregateId: "019bc119-0100-7000-8000-00000000a001",
    eventType: "order.paid",
    payload: {},
    attempts: 8,
    nextAttemptAt: NOW,
    lastError: "HTTP 500 from merchant endpoint",
    createdAt: NOW,
    deliveredAt: null,
    ...overrides,
  };
}
