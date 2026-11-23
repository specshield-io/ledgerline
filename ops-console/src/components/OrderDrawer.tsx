import { Dialog } from "radix-ui";
import { X } from "lucide-react";
import { useLayoutEffect, useMemo, useRef } from "react";
import { useSearchParams } from "react-router";
import { useDeliveries, useOrder, useTimeline } from "../api/queries";
import { formatUsd, shortId } from "../lib/format";
import { ORDER_STATUS } from "../lib/status";
import { buildTimeline } from "../lib/timeline";
import { useRuns } from "../state/runs";
import { ApiDownState } from "./ApiDownState";
import { StatusBadge } from "./StatusBadge";
import { Timeline } from "./Timeline";

/** The order in ?order=<id>, with its merged timeline. Polls every second while a scenario runs. */
export function OrderDrawer() {
  const [params, setParams] = useSearchParams();
  const id = params.get("order");
  const { anyRunning } = useRuns();
  const order = useOrder(id, anyRunning);
  const timeline = useTimeline(id, anyRunning);
  const deliveries = useDeliveries(id, anyRunning);
  const items = useMemo(
    () => buildTimeline(timeline.data ?? [], deliveries.data ?? []),
    [timeline.data, deliveries.data],
  );

  // The drawer opens from a URL parameter, not a Radix trigger, so remember what had focus and return it on close.
  const returnFocus = useRef<HTMLElement | null>(null);
  useLayoutEffect(() => {
    if (id !== null && document.activeElement instanceof HTMLElement && !returnFocus.current) {
      returnFocus.current = document.activeElement;
    }
  }, [id]);

  const close = () =>
    setParams((p) => {
      p.delete("order");
      return p;
    });
  const failed = order.error ?? timeline.error ?? deliveries.error;

  return (
    <Dialog.Root open={id !== null} onOpenChange={(open) => !open && close()}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-zinc-950/40" />
        <Dialog.Content
          className="fixed inset-y-0 right-0 z-50 flex w-full max-w-lg flex-col bg-white shadow-xl focus:outline-none dark:bg-zinc-950"
          onCloseAutoFocus={(event) => {
            const target = returnFocus.current;
            returnFocus.current = null;
            if (target?.isConnected) {
              event.preventDefault();
              target.focus();
            }
          }}
        >
          <header className="flex items-start justify-between gap-3 border-b border-zinc-200 p-4 dark:border-zinc-800">
            <div className="min-w-0">
              <Dialog.Title className="text-lg font-semibold">
                Order <span className="font-mono">{id ? shortId(id) : ""}</span>
              </Dialog.Title>
              <Dialog.Description className="text-sm text-zinc-700 dark:text-zinc-300">
                {order.data
                  ? `${order.data.merchantRef} · ${formatUsd(order.data.amountMinor)}`
                  : "Audit log, provider events and merchant notifications"}
              </Dialog.Description>
              {order.data && (
                <div className="mt-2" data-testid="drawer-status">
                  <StatusBadge presentation={ORDER_STATUS[order.data.status]} />
                </div>
              )}
            </div>
            <Dialog.Close aria-label="Close order details" className="btn-icon">
              <X aria-hidden="true" className="size-5" />
            </Dialog.Close>
          </header>
          {/* Focusable so keyboard users can scroll a long timeline. */}
          <div
            className="flex-1 overflow-y-auto p-4 pl-6"
            tabIndex={0}
            role="region"
            aria-label="Order timeline"
          >
            {failed && !timeline.data ? (
              <ApiDownState
                error={failed}
                what="this order"
                onRetry={() => {
                  void order.refetch();
                  void timeline.refetch();
                  void deliveries.refetch();
                }}
              />
            ) : timeline.isPending ? (
              <p className="text-sm">Loading timeline…</p>
            ) : (
              <Timeline items={items} />
            )}
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
