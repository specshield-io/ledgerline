import { LoaderCircle, Play } from "lucide-react";
import { useEffect } from "react";
import { useSearchParams } from "react-router";
import { ApiError } from "../api/client";
import { useScenarioRun, useStartScenario } from "../api/queries";
import { SCENARIOS, type ScenarioInfo } from "../lib/scenarios";
import { ORDER_STATUS } from "../lib/status";
import { useRuns, type ActiveRun } from "../state/runs";
import { useToast } from "../state/toast";

/** Follows one run until it finishes, then toasts the result. */
function RunWatcher({ run }: { run: ActiveRun }) {
  const query = useScenarioRun(run.runId);
  const { finish } = useRuns();
  const toast = useToast();
  const status = query.data?.status;
  const orderStatus = query.data?.orderStatus;

  useEffect(() => {
    if (status === "COMPLETED" || status === "FAILED") {
      const label = orderStatus
        ? (ORDER_STATUS[orderStatus as keyof typeof ORDER_STATUS]?.label ?? orderStatus)
        : "";
      toast(
        status === "COMPLETED"
          ? { variant: "success", title: `${run.scenario} finished`, description: `The order is ${label}.` }
          : {
              variant: "error",
              title: `${run.scenario} failed`,
              description: query.data?.error ?? undefined,
            },
      );
      finish(run.runId);
    }
  }, [status, orderStatus, run, finish, toast, query.data?.error]);

  useEffect(() => {
    // A run kept for an hour may have expired, or the API reset: stop following it.
    if (query.error instanceof ApiError && query.error.status === 404) finish(run.runId);
  }, [query.error, run.runId, finish]);
  return null;
}

function ScenarioButton({ scenario }: { scenario: ScenarioInfo }) {
  const start = useStartScenario();
  const runs = useRuns();
  const toast = useToast();
  const [, setParams] = useSearchParams();
  const busy = start.isPending || runs.isRunning(scenario.name);
  const descriptionId = `scenario-${scenario.name}-description`;

  const run = () =>
    start.mutate(scenario.name, {
      onSuccess: (started) => {
        runs.add({ scenario: scenario.name, runId: started.scenarioRunId, orderId: started.orderId });
        toast({
          variant: "info",
          title: `${scenario.title} started`,
          description: "Watch the order's timeline as it happens.",
        });
        setParams((params) => {
          params.set("order", started.orderId);
          return params;
        });
      },
      onError: (error) => {
        const tooMany = error instanceof ApiError && error.status === 429;
        toast({
          variant: "error",
          title: tooMany ? "Too many scenario runs" : `${scenario.title} could not start`,
          description:
            error instanceof ApiError
              ? `${error.message}${error.retryAfterSeconds ? ` Try again in ${error.retryAfterSeconds} s.` : ""}`
              : "The API is not reachable.",
        });
      },
    });

  return (
    <li className="rounded-md border border-zinc-200 p-3 dark:border-zinc-800">
      <button
        type="button"
        onClick={run}
        disabled={busy}
        aria-describedby={descriptionId}
        className="btn w-full justify-between"
      >
        <span>Run {scenario.title.toLowerCase()}</span>
        {busy ? (
          <LoaderCircle aria-hidden="true" className="size-4 animate-spin" />
        ) : (
          <Play aria-hidden="true" className="size-4" />
        )}
      </button>
      <div id={descriptionId} className="mt-2 space-y-1 text-xs">
        <p className="text-zinc-800 dark:text-zinc-200">{scenario.explanation}</p>
        <p className="text-zinc-600 dark:text-zinc-400">
          <span className="font-medium">Watch for:</span> {scenario.watchFor}
        </p>
        {busy && <p className="font-medium text-sky-800 dark:text-sky-300">Running…</p>}
      </div>
    </li>
  );
}

export function ScenariosPanel() {
  const { runs } = useRuns();
  return (
    <section aria-labelledby="scenarios-heading">
      <h2
        id="scenarios-heading"
        className="mb-2 text-sm font-semibold tracking-wide text-zinc-700 uppercase dark:text-zinc-300"
      >
        Chaos scenarios
      </h2>
      <p className="mb-3 text-xs text-zinc-600 dark:text-zinc-400">
        Each run creates its own synthetic order and sends real signed webhooks through MockPay.
      </p>
      <ul className="space-y-2">
        {SCENARIOS.map((scenario) => (
          <ScenarioButton key={scenario.name} scenario={scenario} />
        ))}
      </ul>
      {runs.map((run) => (
        <RunWatcher key={run.runId} run={run} />
      ))}
    </section>
  );
}
