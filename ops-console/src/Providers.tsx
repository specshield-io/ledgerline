import { QueryClientProvider, type QueryClient } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { AuthProvider } from "./state/auth";
import { RunsProvider } from "./state/runs";
import { ToastProvider } from "./state/toast";

export function Providers({
  children,
  client,
  session = null,
}: {
  children: ReactNode;
  client: QueryClient;
  session?: { username: string; roles: ("ADMIN" | "VIEWER")[] } | null;
}) {
  return (
    <QueryClientProvider client={client}>
      <AuthProvider initial={session}>
        <ToastProvider>
          <RunsProvider>{children}</RunsProvider>
        </ToastProvider>
      </AuthProvider>
    </QueryClientProvider>
  );
}
