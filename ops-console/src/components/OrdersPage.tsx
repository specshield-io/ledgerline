import { useState } from "react";
import { useSearchParams } from "react-router";
import type { OrderStatus } from "../api/client";
import { useOrders } from "../api/queries";
import { formatRelative, formatUsd, shortId } from "../lib/format";
import { ORDER_STATUS, ORDER_STATUSES } from "../lib/status";
import { ApiDownState } from "./ApiDownState";
import { StatusBadge } from "./StatusBadge";

export function OrdersPage() {
  const [status, setStatus] = useState<OrderStatus | undefined>(undefined);
  const orders = useOrders(status);
  const [, setParams] = useSearchParams();

  const open = (id: string) =>
    setParams((params) => {
      params.set("order", id);
      return params;
    });

  return (
    <section aria-labelledby="orders-heading" className="min-w-0">
      <div className="mb-3 flex flex-wrap items-end justify-between gap-2">
        <h2 id="orders-heading" className="text-lg font-semibold">
          Orders
        </h2>
        <label className="flex items-center gap-2 text-sm">
          <span>Status</span>
          <select
            value={status ?? ""}
            onChange={(e) => setStatus((e.target.value || undefined) as OrderStatus | undefined)}
            className="field"
          >
            <option value="">All statuses</option>
            {ORDER_STATUSES.map((s) => (
              <option key={s} value={s}>
                {ORDER_STATUS[s].label}
              </option>
            ))}
          </select>
        </label>
      </div>

      {orders.isError && !orders.data ? (
        <ApiDownState error={orders.error} onRetry={() => void orders.refetch()} what="orders" />
      ) : (
        <div
          className="overflow-x-auto rounded-md border border-zinc-200 dark:border-zinc-800"
          tabIndex={0}
          role="region"
          aria-labelledby="orders-heading"
        >
          <table className="w-full text-left text-sm">
            <caption className="sr-only">
              Orders, newest first{status ? `, ${ORDER_STATUS[status].label} only` : ""}. Select an order to
              see its timeline.
            </caption>
            <thead className="bg-zinc-50 text-xs text-zinc-700 uppercase dark:bg-zinc-900 dark:text-zinc-300">
              <tr>
                <th scope="col" className="px-3 py-2 font-semibold">
                  Order
                </th>
                <th scope="col" className="hidden px-3 py-2 font-semibold sm:table-cell">
                  Merchant ref
                </th>
                <th scope="col" className="px-3 py-2 text-right font-semibold">
                  Amount
                </th>
                <th scope="col" className="px-3 py-2 font-semibold">
                  Status
                </th>
                <th scope="col" className="hidden px-3 py-2 font-semibold md:table-cell">
                  Updated
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
              {orders.isPending && (
                <tr>
                  <td colSpan={5} className="px-3 py-6 text-center text-zinc-600 dark:text-zinc-400">
                    Loading orders…
                  </td>
                </tr>
              )}
              {orders.data?.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-3 py-6 text-center text-zinc-600 dark:text-zinc-400">
                    No orders{status ? ` with status ${ORDER_STATUS[status].label}` : ""}.
                  </td>
                </tr>
              )}
              {orders.data?.map((order) => (
                <tr key={order.id} className="hover:bg-zinc-50 dark:hover:bg-zinc-900">
                  <td className="px-3 py-2">
                    <button
                      type="button"
                      onClick={() => open(order.id)}
                      aria-label={`Open order ${shortId(order.id)}`}
                      className="link font-mono"
                      data-testid="order-id"
                    >
                      {shortId(order.id)}
                    </button>
                  </td>
                  <td className="hidden px-3 py-2 sm:table-cell">{order.merchantRef}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{formatUsd(order.amountMinor)}</td>
                  <td className="px-3 py-2">
                    <StatusBadge presentation={ORDER_STATUS[order.status]} />
                  </td>
                  <td
                    className="hidden px-3 py-2 text-zinc-700 md:table-cell dark:text-zinc-300"
                    data-testid="updated"
                  >
                    <time dateTime={order.updatedAt} title={order.updatedAt}>
                      {formatRelative(order.updatedAt)}
                    </time>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
