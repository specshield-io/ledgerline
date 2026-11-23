import { AlertDialog } from "radix-ui";
import { Lock, RotateCcw } from "lucide-react";
import { useState } from "react";
import { useSearchParams } from "react-router";
import { ApiError, type OutboxMessage, type OutboxStatus } from "../api/client";
import { useOutbox, useReplay } from "../api/queries";
import { formatRelative, shortId } from "../lib/format";
import { OUTBOX_STATUS, OUTBOX_STATUSES } from "../lib/status";
import { useAuth } from "../state/auth";
import { useToast } from "../state/toast";
import { ApiDownState } from "./ApiDownState";
import { LoginButton } from "./LoginDialog";
import { StatusBadge } from "./StatusBadge";

function ReplayButton({ message }: { message: OutboxMessage }) {
  const replay = useReplay();
  const toast = useToast();
  const [open, setOpen] = useState(false);

  const confirm = () =>
    replay.mutate(message.id, {
      onSuccess: () => {
        setOpen(false);
        toast({
          variant: "success",
          title: `Notification #${message.id} queued again`,
          description: "The relay will deliver it within a second.",
        });
      },
      onError: (error) => {
        setOpen(false);
        toast({
          variant: "error",
          title: "Replay failed",
          description: error instanceof ApiError ? error.message : "The API is not reachable.",
        });
      },
    });

  return (
    <AlertDialog.Root open={open} onOpenChange={setOpen}>
      <AlertDialog.Trigger asChild>
        <button type="button" className="btn" aria-label={`Replay notification #${message.id}`}>
          <RotateCcw aria-hidden="true" className="size-4" />
          Replay
        </button>
      </AlertDialog.Trigger>
      <AlertDialog.Portal>
        <AlertDialog.Overlay className="fixed inset-0 z-40 bg-zinc-950/40" />
        <AlertDialog.Content className="fixed top-1/2 left-1/2 z-50 w-[calc(100%-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 rounded-lg bg-white p-5 shadow-xl dark:bg-zinc-900">
          <AlertDialog.Title className="text-base font-semibold">
            Replay notification #{message.id}?
          </AlertDialog.Title>
          <AlertDialog.Description className="mt-2 text-sm text-zinc-700 dark:text-zinc-300">
            {message.eventType} for order {shortId(message.aggregateId)} goes back to the outbox with its
            attempts reset. The merchant may receive it again; receivers deduplicate by Ledgerline-Event-Id.
            This is recorded in the audit log under your name.
          </AlertDialog.Description>
          <div className="mt-5 flex justify-end gap-2">
            <AlertDialog.Cancel asChild>
              <button type="button" className="btn">
                Cancel
              </button>
            </AlertDialog.Cancel>
            <button type="button" className="btn btn-primary" onClick={confirm} disabled={replay.isPending}>
              {replay.isPending ? "Replaying…" : "Replay"}
            </button>
          </div>
        </AlertDialog.Content>
      </AlertDialog.Portal>
    </AlertDialog.Root>
  );
}

export function OutboxPage() {
  const { session, isAdmin } = useAuth();
  const [status, setStatus] = useState<OutboxStatus | undefined>(undefined);
  const outbox = useOutbox(status, session !== null);
  const [, setParams] = useSearchParams();

  if (!session) {
    return (
      <section aria-labelledby="outbox-heading" className="space-y-3">
        <h2 id="outbox-heading" className="text-lg font-semibold">
          Outbox and dead letters
        </h2>
        <p className="flex items-center gap-2 text-sm text-zinc-700 dark:text-zinc-300">
          <Lock aria-hidden="true" className="size-4" />
          Sign in with a VIEWER or ADMIN account to see merchant notifications. Each order's notifications are
          also shown in its timeline.
        </p>
        <LoginButton />
      </section>
    );
  }

  return (
    <section aria-labelledby="outbox-heading" className="min-w-0">
      <div className="mb-3 flex flex-wrap items-end justify-between gap-2">
        <h2 id="outbox-heading" className="text-lg font-semibold">
          Outbox and dead letters
        </h2>
        <label className="flex items-center gap-2 text-sm">
          <span>Status</span>
          <select
            value={status ?? ""}
            onChange={(e) => setStatus((e.target.value || undefined) as OutboxStatus | undefined)}
            className="field"
          >
            <option value="">All statuses</option>
            {OUTBOX_STATUSES.map((s) => (
              <option key={s} value={s}>
                {OUTBOX_STATUS[s].label}
              </option>
            ))}
          </select>
        </label>
      </div>
      {outbox.isError && !outbox.data ? (
        <ApiDownState error={outbox.error} onRetry={() => void outbox.refetch()} what="the outbox" />
      ) : (
        <div
          className="overflow-x-auto rounded-md border border-zinc-200 dark:border-zinc-800"
          tabIndex={0}
          role="region"
          aria-labelledby="outbox-heading"
        >
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Merchant notifications, newest first</caption>
            <thead className="bg-zinc-50 text-xs text-zinc-700 uppercase dark:bg-zinc-900 dark:text-zinc-300">
              <tr>
                <th scope="col" className="px-3 py-2 font-semibold">
                  #
                </th>
                <th scope="col" className="px-3 py-2 font-semibold">
                  Event
                </th>
                <th scope="col" className="px-3 py-2 font-semibold">
                  Status
                </th>
                <th scope="col" className="px-3 py-2 text-right font-semibold">
                  Attempts
                </th>
                <th scope="col" className="hidden px-3 py-2 font-semibold md:table-cell">
                  Last error
                </th>
                <th scope="col" className="hidden px-3 py-2 font-semibold sm:table-cell">
                  Next attempt
                </th>
                <th scope="col" className="px-3 py-2 font-semibold">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
              {outbox.data?.length === 0 && (
                <tr>
                  <td colSpan={7} className="px-3 py-6 text-center text-zinc-600 dark:text-zinc-400">
                    No notifications{status ? ` with status ${OUTBOX_STATUS[status].label}` : ""}.
                  </td>
                </tr>
              )}
              {outbox.data?.map((m) => (
                <tr key={m.id} data-testid="outbox-row" data-order={m.aggregateId}>
                  <td className="px-3 py-2 font-mono tabular-nums">{m.id}</td>
                  <td className="px-3 py-2">
                    <div>{m.eventType}</div>
                    <button
                      type="button"
                      className="link font-mono text-xs"
                      aria-label={`Open order ${shortId(m.aggregateId)}`}
                      onClick={() =>
                        setParams((p) => {
                          p.set("order", m.aggregateId);
                          return p;
                        })
                      }
                    >
                      {shortId(m.aggregateId)}
                    </button>
                  </td>
                  <td className="px-3 py-2">
                    <StatusBadge presentation={OUTBOX_STATUS[m.status]} />
                  </td>
                  <td className="px-3 py-2 text-right tabular-nums">{m.attempts}</td>
                  <td
                    className="hidden max-w-64 truncate px-3 py-2 md:table-cell"
                    title={m.lastError ?? undefined}
                  >
                    {m.lastError ?? "–"}
                  </td>
                  <td className="hidden px-3 py-2 sm:table-cell">
                    {m.status === "PENDING" ? (
                      <time dateTime={m.nextAttemptAt}>{formatRelative(m.nextAttemptAt)}</time>
                    ) : (
                      "–"
                    )}
                  </td>
                  <td className="px-3 py-2">
                    {isAdmin && m.status === "DEAD" && <ReplayButton message={m} />}
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
