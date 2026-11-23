import { useStats } from "../api/queries";
import { formatRelative } from "../lib/format";

const METRICS = [
  { key: "received", label: "Webhooks received" },
  { key: "applied", label: "Events applied" },
  { key: "duplicatesBlocked", label: "Duplicates blocked" },
  { key: "signaturesRejected", label: "Signatures rejected" },
  { key: "outboxPending", label: "Outbox pending" },
] as const;

export function MetricsStrip() {
  const stats = useStats();
  return (
    <section aria-labelledby="metrics-heading" className="border-b border-zinc-200 dark:border-zinc-800">
      <div className="mx-auto max-w-screen-2xl px-4 py-3">
        <h2 id="metrics-heading" className="sr-only">
          Reliability metrics
        </h2>
        <dl className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-5">
          {METRICS.map(({ key, label }) => (
            <div
              key={key}
              className="rounded-md bg-zinc-50 px-3 py-2 dark:bg-zinc-900"
              data-testid={`metric-${key}`}
            >
              <dt className="text-xs text-zinc-600 dark:text-zinc-400">{label}</dt>
              <dd className="text-xl font-semibold tabular-nums">
                {stats.data ? stats.data[key].toLocaleString("en-US") : "–"}
              </dd>
            </div>
          ))}
        </dl>
        <p className="mt-1 text-xs text-zinc-600 dark:text-zinc-400" data-testid="metrics-since">
          {stats.isError
            ? "Metrics unavailable; retrying."
            : stats.data
              ? `Webhook counters since this instance started (${formatRelative(stats.data.countingSince)}).`
              : "Loading metrics…"}
        </p>
      </div>
    </section>
  );
}
