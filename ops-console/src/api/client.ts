import createClient from "openapi-fetch";
import type { components, paths } from "./schema";

/** API types with every field present (springdoc does not mark them required; the API always sends them). */
type Schema<K extends keyof components["schemas"]> = Required<components["schemas"][K]>;
export type Order = Schema<"Order">;
export type OrderStatus = Order["status"];
/** JSON objects the generator types as empty (Record<string, never>): their shape depends on the kind. */
type Json = Record<string, unknown>;
export type TimelineEntry = Omit<Schema<"TimelineEntry">, "detail"> & { detail: Json };
export type OrderDelivery = Schema<"OrderDelivery">;
export type OutboxMessage = Omit<Schema<"OutboxMessage">, "payload"> & { payload: Json };
export type OutboxStatus = OutboxMessage["status"];
export type Stats = Schema<"Stats">;
export type Meta = Schema<"Meta">;
export type ScenarioRun = Schema<"ScenarioRun">;
export type ScenarioRunStarted = Schema<"ScenarioRunStarted">;

export interface Credentials {
  username: string;
  password: string;
}

/** Only kept in memory: a reload signs the operator out. */
let credentials: Credentials | null = null;

export function setCredentials(next: Credentials | null): void {
  credentials = next;
}

export const api = createClient<paths>({
  baseUrl: typeof window === "undefined" ? "http://localhost" : window.location.origin,
  // Looked up per request rather than captured once, so instrumentation (MSW in tests) is always honoured.
  fetch: (request) => globalThis.fetch(request),
});

api.use({
  onRequest({ request }) {
    // Tells the API not to send a Basic challenge, so the browser never opens its own login prompt.
    request.headers.set("X-Requested-With", "XMLHttpRequest");
    if (credentials && new URL(request.url).pathname.startsWith("/api/v1/admin/")) {
      const token = btoa(`${credentials.username}:${credentials.password}`);
      request.headers.set("Authorization", `Basic ${token}`);
    }
    return request;
  },
});

/** An HTTP error answer from the API (problem+json), as opposed to the API being unreachable. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly type: string | undefined,
    readonly title: string,
    readonly detail: string | undefined,
    readonly retryAfterSeconds: number | undefined,
  ) {
    super(detail ?? title);
    this.name = "ApiError";
  }
}

/** The API could not be reached at all (network error, proxy down, 502/503/504). */
export function isApiDown(error: unknown): boolean {
  if (error instanceof ApiError) {
    return error.status === 502 || error.status === 503 || error.status === 504;
  }
  return error instanceof TypeError;
}

interface Problem {
  type?: string;
  title?: string;
  detail?: string;
}

/** Returns the data of a successful answer, or throws {@link ApiError}. */
export async function unwrap<T>(
  call: Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<NonNullable<T>> {
  const { data, error, response } = await call;
  if (response.ok && data !== undefined && data !== null) {
    return data as NonNullable<T>;
  }
  const problem = (error ?? {}) as Problem;
  const retryAfter = response.headers.get("Retry-After");
  throw new ApiError(
    response.status,
    problem.type,
    problem.title ?? response.statusText ?? "Request failed",
    problem.detail,
    retryAfter ? Number(retryAfter) : undefined,
  );
}

/**
 * A fresh Idempotency-Key. Built from crypto.getRandomValues, which works on any origin; crypto.randomUUID exists only in
 * secure contexts (HTTPS or localhost), so it would fail when the console is opened over plain HTTP by host name.
 */
export function newIdempotencyKey(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return `console-${Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("")}`;
}
