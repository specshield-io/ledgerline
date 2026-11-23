import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { App } from "./App";
import { entry } from "./test/fixtures";
import { renderWithProviders } from "./test/render";
import { server } from "./test/server";

describe("console shell", () => {
  it("shows the demo banner, the metrics strip and opens an order's timeline from the table", async () => {
    server.use(
      http.get("*/api/v1/orders/:id", ({ params }) =>
        HttpResponse.json({
          id: params.id,
          merchantRef: "demo-1001",
          amountMinor: 4599,
          currency: "USD",
          status: "PAID",
          refundedMinor: 0,
          version: 2,
          createdAt: "2026-01-15T10:00:00Z",
          updatedAt: "2026-01-15T10:00:01Z",
          lastProviderEventAt: null,
        }),
      ),
      http.get("*/api/v1/orders/:id/timeline", () =>
        HttpResponse.json([entry({ kind: "DUPLICATE", detail: { providerEventId: "evt_x" } })]),
      ),
      http.get("*/api/v1/orders/:id/deliveries", () => HttpResponse.json([])),
    );
    const user = userEvent.setup();
    renderWithProviders(<App />);

    expect(
      screen.getByText("SpecShield Labs, internal demo with synthetic data, resets daily 03:00 UTC"),
    ).toBeInTheDocument();
    expect(await screen.findByText("4")).toBeInTheDocument(); // duplicates blocked
    await user.click(await screen.findByRole("button", { name: "Open order 0000a001" }));

    const drawer = await screen.findByRole("dialog");
    expect(within(drawer).getByRole("heading", { name: /Order/ })).toBeInTheDocument();
    expect(await within(drawer).findByText("Deduplicated")).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("signs in through GET /api/v1/admin/me with HTTP Basic, and reports wrong credentials", async () => {
    let authorization: string | null = null;
    server.use(
      http.get("*/api/v1/admin/me", ({ request }) => {
        authorization = request.headers.get("Authorization");
        return authorization === `Basic ${btoa("ops:right")}`
          ? HttpResponse.json({ username: "ops", roles: ["ADMIN"] })
          : HttpResponse.json({ title: "Authentication required", status: 401 }, { status: 401 });
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<App />);

    await user.click(screen.getByRole("button", { name: "Sign in" }));
    const dialog = await screen.findByRole("dialog", { name: "Operator sign-in" });
    await user.type(within(dialog).getByLabelText("Username"), "ops");
    await user.type(within(dialog).getByLabelText("Password"), "wrong");
    await user.click(within(dialog).getByRole("button", { name: "Sign in" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Wrong username or password.");

    await user.clear(within(dialog).getByLabelText("Password"));
    await user.type(within(dialog).getByLabelText("Password"), "right");
    await user.click(within(dialog).getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("button", { name: "Sign out" })).toBeInTheDocument();
    expect(authorization).toBe(`Basic ${btoa("ops:right")}`);
  });

  it("the How it works sheet shows the architecture diagram and the ADR links", async () => {
    const user = userEvent.setup();
    renderWithProviders(<App />);

    await user.click(screen.getByRole("button", { name: "How it works" }));
    const sheet = await screen.findByRole("dialog", { name: "How it works" });
    expect(within(sheet).getByRole("img", { name: "Ledgerline architecture" })).toBeInTheDocument();
    expect(within(sheet).getByRole("link", { name: /ADR 0003/ })).toHaveAttribute(
      "href",
      expect.stringContaining("docs/adr/0003-transactional-outbox.md"),
    );
  });
});
