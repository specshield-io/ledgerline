import { QueryClient } from "@tanstack/react-query";

/** One retry before showing the API-down state (polling keeps trying afterwards); tests pass false. */
export function createQueryClient(retry: boolean | number = 1): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry, refetchOnWindowFocus: true },
      mutations: { retry: false },
    },
  });
}
