import { ExternalLink, FlaskConical } from "lucide-react";
import { useMeta } from "../api/queries";

export const REPOSITORY_URL = "https://github.com/specshield-io/ledgerline";
/** Set at build time (VITE_DEMO_VIDEO_URL); the link is hidden until a video exists. */
const VIDEO_URL: string | undefined = import.meta.env.VITE_DEMO_VIDEO_URL || undefined;

export function Banner() {
  const meta = useMeta();
  const resetsAt = meta.data?.dataResetsAt ?? "03:00 UTC";
  return (
    <div className="bg-amber-100 text-amber-950 dark:bg-amber-950 dark:text-amber-100">
      <p className="mx-auto flex max-w-screen-2xl flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2 text-sm">
        <FlaskConical aria-hidden="true" className="size-4 shrink-0" />
        <strong className="font-semibold">
          SpecShield Labs, internal demo with synthetic data, resets daily {resetsAt}
        </strong>
        <a
          href={REPOSITORY_URL}
          className="link inline-flex items-center gap-1"
          target="_blank"
          rel="noreferrer"
        >
          GitHub
          <ExternalLink aria-hidden="true" className="size-3.5" />
          <span className="sr-only">(opens in a new tab)</span>
        </a>
        {VIDEO_URL && (
          <a
            href={VIDEO_URL}
            className="link inline-flex items-center gap-1"
            target="_blank"
            rel="noreferrer"
          >
            Video
            <ExternalLink aria-hidden="true" className="size-3.5" />
            <span className="sr-only">(opens in a new tab)</span>
          </a>
        )}
      </p>
    </div>
  );
}
