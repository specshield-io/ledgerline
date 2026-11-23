import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { MemoryRouter } from "react-router";
import { Providers } from "../Providers";
import { createQueryClient } from "../queryClient";

export function renderWithProviders(
  ui: ReactElement,
  {
    route = "/",
    session = null,
  }: { route?: string; session?: { username: string; roles: ("ADMIN" | "VIEWER")[] } | null } = {},
) {
  const client = createQueryClient(false);
  return render(
    <MemoryRouter initialEntries={[route]}>
      <Providers client={client} session={session}>
        {ui}
      </Providers>
    </MemoryRouter>,
  );
}
