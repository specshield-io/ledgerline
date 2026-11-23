import { TONE_CLASSES, type Presentation } from "../lib/status";

/** Colour, icon and text together; the text is what assistive technology reads. */
export function StatusBadge({ presentation }: { presentation: Presentation }) {
  const { label, icon: Icon, tone } = presentation;
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ring-1 ring-inset ${TONE_CLASSES[tone]}`}
    >
      <Icon aria-hidden="true" className="size-3.5" />
      {label}
    </span>
  );
}
