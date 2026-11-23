import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { ORDERS } from "../test/fixtures";
import { renderWithProviders } from "../test/render";
import { server } from "../test/server";
import { OrdersPage } from "./OrdersPage";

describe("LL-F05 API down", () => {
  it("LL-F05 renders a retry state when the API is unreachable, and recovers on Retry", async () => {
    let down = true;
    server.use(
      http.get("*/api/v1/orders", () =>
        down
          ? HttpResponse.error()
          : HttpResponse.json({ items: ORDERS, page: 0, size: 100, totalElements: 3, totalPages: 1 }),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<OrdersPage />);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Could not load orders.");
    expect(alert).toHaveTextContent("not reachable");

    down = false;
    await user.click(within(alert).getByRole("button", { name: "Retry" }));

    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
    expect(within(screen.getByRole("table")).getAllByRole("row")).toHaveLength(4);
  });

  it("LL-F05 treats a 503 from a proxy as the API being down", async () => {
    server.use(http.get("*/api/v1/orders", () => new HttpResponse(null, { status: 503 })));
    renderWithProviders(<OrdersPage />);

    expect(await screen.findByRole("alert")).toHaveTextContent("not reachable");
  });
});
