import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";

export interface ActiveRun {
  scenario: string;
  runId: string;
  orderId: string;
}

interface RunsState {
  runs: ActiveRun[];
  isRunning: (scenario: string) => boolean;
  /** True while any scenario runs: the timeline then polls every second. */
  anyRunning: boolean;
  add: (run: ActiveRun) => void;
  finish: (runId: string) => void;
}

const RunsContext = createContext<RunsState | null>(null);

export function RunsProvider({ children }: { children: ReactNode }) {
  const [runs, setRuns] = useState<ActiveRun[]>([]);
  const add = useCallback((run: ActiveRun) => setRuns((current) => [...current, run]), []);
  const finish = useCallback(
    (runId: string) => setRuns((current) => current.filter((r) => r.runId !== runId)),
    [],
  );
  const value = useMemo(
    () => ({
      runs,
      anyRunning: runs.length > 0,
      isRunning: (scenario: string) => runs.some((r) => r.scenario === scenario),
      add,
      finish,
    }),
    [runs, add, finish],
  );
  return <RunsContext.Provider value={value}>{children}</RunsContext.Provider>;
}

export function useRuns(): RunsState {
  const value = useContext(RunsContext);
  if (!value) throw new Error("useRuns outside RunsProvider");
  return value;
}
