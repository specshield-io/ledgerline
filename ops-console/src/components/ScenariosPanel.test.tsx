import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { renderWithProviders } from "../test/render";
import { server } from "../test/server";
import { ScenariosPanel } from "./ScenariosPanel";

describe("LL-F03 scenario buttons", () => {
  it("LL-F03 calls the scenario endpoint with an Idempotency-Key, disables while pending, toasts success", async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    const calls: { path: string; key: string | null }[] = [];
    server.use(
      http.post("*/api/v1/chaos/scenarios/:name", async ({ request }) => {
        calls.push({ path: new URL(request.url).pathname, key: request.headers.get("Idempotency-Key") });
        await gate;
        return HttpResponse.json(
          {
            scenarioRunId: "run-1",
            orderId: "019bc119-0100-7000-8000-00000000b001",
            scenario: "duplicate-storm",
          },
          { status: 202 },
        );
      }),
      http.get("*/api/v1/chaos/runs/:id", () =>
        HttpResponse.json({ scenarioRunId: "run-1", status: "RUNNING", steps: [] }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<ScenariosPanel />);

    const button = screen.getByRole("button", { name: "Run duplicate storm" });
    await user.click(button);

    await waitFor(() => expect(button).toBeDisabled());
    expect(calls).toEqual([
      { path: "/api/v1/chaos/scenarios/duplicate-storm", key: expect.stringMatching(/^console-/) },
    ]);
    release();
    expect(await screen.findByText("Duplicate storm started")).toBeInTheDocument();
    expect(button).toBeDisabled(); // still running until the run finishes
    expect(screen.getByRole("button", { name: "Run happy path" })).toBeEnabled();
  });

  it("LL-F03 shows an error toast when the API answers 429", async () => {
    server.use(
      http.post("*/api/v1/chaos/scenarios/:name", () =>
        HttpResponse.json(
          {
            type: "urn:ledgerline:problem:too-many-scenario-runs",
            title: "Too many scenario runs",
            status: 429,
            detail: "At most 3 scenario runs can be in progress at once.",
            instance: "/api/v1/chaos/scenarios/happy-path",
          },
          { status: 429, headers: { "Retry-After": "5", "Content-Type": "application/problem+json" } },
        ),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<ScenariosPanel />);

    const button = screen.getByRole("button", { name: "Run happy path" });
    await user.click(button);

    expect(await screen.findByText("Too many scenario runs")).toBeInTheDocument();
    expect(screen.getByText(/At most 3 scenario runs.*Try again in 5 s\./)).toBeInTheDocument();
    await waitFor(() => expect(button).toBeEnabled());
  });

  it("toasts when a run finishes, with the order's final status", async () => {
    server.use(
      http.post("*/api/v1/chaos/scenarios/:name", () =>
        HttpResponse.json(
          { scenarioRunId: "run-2", orderId: "019bc119-0100-7000-8000-00000000b002", scenario: "happy-path" },
          { status: 202 },
        ),
      ),
      http.get("*/api/v1/chaos/runs/:id", () =>
        HttpResponse.json({ scenarioRunId: "run-2", status: "COMPLETED", orderStatus: "PAID", steps: [] }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<ScenariosPanel />);

    await user.click(screen.getByRole("button", { name: "Run happy path" }));

    expect(await screen.findByText("happy-path finished")).toBeInTheDocument();
    expect(screen.getByText("The order is Paid.")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Run happy path" })).toBeEnabled());
  });
});
