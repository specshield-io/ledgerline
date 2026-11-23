import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  api,
  newIdempotencyKey,
  unwrap,
  type Meta,
  type Order,
  type OrderDelivery,
  type OrderStatus,
  type OutboxMessage,
  type OutboxStatus,
  type ScenarioRun,
  type ScenarioRunStarted,
  type Stats,
  type TimelineEntry,
} from "./client";

/** Polling: fast while a scenario runs (the timeline changes every few hundred ms), slow otherwise. */
export const FAST_POLL_MS = 1_000;
export const SLOW_POLL_MS = 5_000;

export function useOrders(status: OrderStatus | undefined) {
  return useQuery({
    queryKey: ["orders", status ?? "ALL"],
    queryFn: async () => {
      const page = await unwrap(
        api.GET("/api/v1/orders", { params: { query: { size: 100, ...(status ? { status } : {}) } } }),
      );
      return (page.items ?? []) as Order[];
    },
    refetchInterval: SLOW_POLL_MS,
  });
}

export function useOrder(id: string | null, fast: boolean) {
  return useQuery({
    queryKey: ["order", id],
    enabled: id !== null,
    queryFn: async () =>
      (await unwrap(api.GET("/api/v1/orders/{id}", { params: { path: { id: id ?? "" } } }))) as Order,
    refetchInterval: fast ? FAST_POLL_MS : SLOW_POLL_MS,
  });
}

export function useTimeline(id: string | null, fast: boolean) {
  return useQuery({
    queryKey: ["timeline", id],
    enabled: id !== null,
    queryFn: async () =>
      (await unwrap(
        api.GET("/api/v1/orders/{id}/timeline", { params: { path: { id: id ?? "" } } }),
      )) as TimelineEntry[],
    refetchInterval: fast ? FAST_POLL_MS : SLOW_POLL_MS,
  });
}

export function useDeliveries(id: string | null, fast: boolean) {
  return useQuery({
    queryKey: ["deliveries", id],
    enabled: id !== null,
    queryFn: async () =>
      (await unwrap(
        api.GET("/api/v1/orders/{id}/deliveries", { params: { path: { id: id ?? "" } } }),
      )) as OrderDelivery[],
    // Notifications change more slowly than the timeline; half the rate keeps a visitor well inside the limit.
    refetchInterval: fast ? 2 * FAST_POLL_MS : SLOW_POLL_MS,
  });
}

export function useStats() {
  return useQuery({
    queryKey: ["stats"],
    queryFn: async () => (await unwrap(api.GET("/api/v1/stats"))) as Stats,
    refetchInterval: SLOW_POLL_MS,
  });
}

export function useMeta() {
  return useQuery({
    queryKey: ["meta"],
    queryFn: async () => (await unwrap(api.GET("/api/v1/meta"))) as Meta,
    staleTime: Infinity,
  });
}

export function useScenarioRun(runId: string) {
  return useQuery({
    queryKey: ["run", runId],
    queryFn: async () =>
      (await unwrap(api.GET("/api/v1/chaos/runs/{id}", { params: { path: { id: runId } } }))) as ScenarioRun,
    refetchInterval: (query) => (query.state.data?.status === "RUNNING" ? 2 * FAST_POLL_MS : false),
  });
}

export function useStartScenario() {
  return useMutation({
    mutationFn: async (name: string) =>
      (await unwrap(
        api.POST("/api/v1/chaos/scenarios/{name}", {
          params: { path: { name }, header: { "Idempotency-Key": newIdempotencyKey() } },
        }),
      )) as ScenarioRunStarted,
  });
}

export function useOutbox(status: OutboxStatus | undefined, enabled: boolean) {
  return useQuery({
    queryKey: ["outbox", status ?? "ALL"],
    enabled,
    queryFn: async () => {
      const page = await unwrap(
        api.GET("/api/v1/admin/outbox", {
          params: { query: { size: 100, ...(status ? { status } : {}) } },
        }),
      );
      return (page.items ?? []) as OutboxMessage[];
    },
    refetchInterval: SLOW_POLL_MS,
  });
}

export function useReplay() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (id: number) =>
      (await unwrap(
        api.POST("/api/v1/admin/outbox/{id}/replay", {
          params: { path: { id }, header: { "Idempotency-Key": newIdempotencyKey() } },
        }),
      )) as OutboxMessage,
    onSuccess: () => queries.invalidateQueries({ queryKey: ["outbox"] }),
  });
}
