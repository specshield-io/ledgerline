import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { outboxMessage } from "../test/fixtures";
import { renderWithProviders } from "../test/render";
import { server } from "../test/server";
import { OutboxPage } from "./OutboxPage";

function outboxWith(...items: ReturnType<typeof outboxMessage>[]) {
  return http.get("*/api/v1/admin/outbox", () =>
    HttpResponse.json({ items, page: 0, size: 100, totalElements: items.length, totalPages: 1 }),
  );
}

describe("LL-F04 replay", () => {
  it("LL-F04 needs confirmation; Cancel closes the dialog without calling the API", async () => {
    let replays = 0;
    server.use(
      outboxWith(outboxMessage({ id: 41, status: "DEAD" })),
      http.post("*/api/v1/admin/outbox/:id/replay", () => {
        replays++;
        return HttpResponse.json(outboxMessage({ id: 41, status: "PENDING", attempts: 0 }));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<OutboxPage />, { session: { username: "ops", roles: ["ADMIN"] } });

    await user.click(await screen.findByRole("button", { name: "Replay notification #41" }));
    const dialog = await screen.findByRole("alertdialog", { name: "Replay notification #41?" });
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(replays).toBe(0);

    await user.click(screen.getByRole("button", { name: "Replay notification #41" }));
    await user.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Replay" }));
    expect(await screen.findByText("Notification #41 queued again")).toBeInTheDocument();
    expect(replays).toBe(1);
  });

  it("LL-F04 the Replay button is not shown to a VIEWER, nor for rows that are not dead", async () => {
    server.use(
      outboxWith(
        outboxMessage({ id: 42, status: "DEAD" }),
        outboxMessage({ id: 43, status: "PENDING", attempts: 2 }),
      ),
    );
    renderWithProviders(<OutboxPage />, { session: { username: "viewer", roles: ["VIEWER"] } });

    expect(await screen.findAllByTestId("outbox-row")).toHaveLength(2);
    expect(screen.queryByRole("button", { name: /Replay/ })).not.toBeInTheDocument();
  });

  it("asks anonymous visitors to sign in instead of calling the admin API", async () => {
    renderWithProviders(<OutboxPage />);

    expect(screen.getByText(/Sign in with a VIEWER or ADMIN account/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
  });
});
