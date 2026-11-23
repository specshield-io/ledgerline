import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { ORDERS } from "./fixtures";

/** Handlers every rendered App needs; tests add or override the ones they are about. */
export const defaultHandlers = [
  http.get("*/api/v1/meta", () =>
    HttpResponse.json({ demo: true, dataResetsAt: "03:00 UTC", version: "test", gitSha: "test" }),
  ),
  http.get("*/api/v1/stats", () =>
    HttpResponse.json({
      received: 12,
      applied: 8,
      duplicatesBlocked: 4,
      signaturesRejected: 1,
      outboxPending: 2,
      outboxDead: 0,
      countingSince: new Date().toISOString(),
    }),
  ),
  http.get("*/api/v1/orders", ({ request }) => {
    const status = new URL(request.url).searchParams.get("status");
    const items = status ? ORDERS.filter((o) => o.status === status) : ORDERS;
    return HttpResponse.json({ items, page: 0, size: 100, totalElements: items.length, totalPages: 1 });
  }),
];

export const server = setupServer(...defaultHandlers);
