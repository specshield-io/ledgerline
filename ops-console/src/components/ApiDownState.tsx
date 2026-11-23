import { CloudOff, RefreshCw } from "lucide-react";
import { ApiError, isApiDown } from "../api/client";

/** Friendly state for a failed query, with a retry button. Polling also keeps trying in the background. */
export function ApiDownState({
  error,
  onRetry,
  what,
}: {
  error: unknown;
  onRetry: () => void;
  what: string;
}) {
  const down = isApiDown(error);
  const message = down
    ? "The Ledgerline API is not reachable right now. It may be restarting or resetting its demo data."
    : error instanceof ApiError
      ? `The API answered ${error.status}: ${error.message}`
      : "Something went wrong while loading.";
  return (
    <div
      role="alert"
      className="flex flex-col items-start gap-3 rounded-md border border-dashed border-zinc-300 p-4 text-sm dark:border-zinc-700"
    >
      <p className="flex items-center gap-2 font-medium">
        <CloudOff aria-hidden="true" className="size-4" />
        Could not load {what}.
      </p>
      <p className="text-zinc-700 dark:text-zinc-300">{message}</p>
      <button type="button" onClick={onRetry} className="btn">
        <RefreshCw aria-hidden="true" className="size-4" />
        Retry
      </button>
    </div>
  );
}
