import type { TimelineItem } from "../lib/timeline";
import { formatClock } from "../lib/format";
import { TONE_CLASSES } from "../lib/status";

export function Timeline({ items }: { items: TimelineItem[] }) {
  if (items.length === 0) {
    return (
      <p className="text-sm text-zinc-600 dark:text-zinc-400">Nothing has happened to this order yet.</p>
    );
  }
  return (
    <ol
      aria-label="Order timeline"
      className="relative space-y-3 border-l border-zinc-200 pl-5 dark:border-zinc-800"
    >
      {items.map(({ key, at, label, detail, icon: Icon, tone }) => (
        <li key={key} className="relative" data-testid="timeline-item">
          <span
            className={`absolute top-0.5 -left-[1.95rem] flex size-6 items-center justify-center rounded-full ring-1 ${TONE_CLASSES[tone]}`}
          >
            <Icon aria-hidden="true" className="size-3.5" />
          </span>
          <p className="flex flex-wrap items-baseline gap-x-2 text-sm">
            <span className="font-semibold" data-testid="timeline-label">
              {label}
            </span>
            <time dateTime={at} className="text-xs text-zinc-600 tabular-nums dark:text-zinc-400">
              {formatClock(at)}
            </time>
          </p>
          <p className="text-xs break-words text-zinc-700 dark:text-zinc-300">{detail}</p>
        </li>
      ))}
    </ol>
  );
}
