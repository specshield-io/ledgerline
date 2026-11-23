import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { renderWithProviders } from "../test/render";
import { OrdersPage } from "./OrdersPage";

describe("LL-F01 orders table", () => {
  it("LL-F01 renders a row per order with amount and a status badge that has text, not only colour", async () => {
    renderWithProviders(<OrdersPage />);

    const table = await screen.findByRole("table");
    await waitFor(() => expect(within(table).getAllByRole("row")).toHaveLength(4)); // header + 3
    const paidRow = within(table).getByRole("row", { name: /a001/ });
    expect(within(paidRow).getByText("$45.99")).toBeInTheDocument();
    expect(within(paidRow).getByText("Paid")).toBeVisible();
    expect(within(table).getByText("Payment pending")).toBeVisible();
    expect(within(table).getByText("Cancelled")).toBeVisible();
    expect(within(table).getByRole("button", { name: "Open order 0000a001" })).toBeInTheDocument();
  });

  it("LL-F01 filtering by status narrows the rows", async () => {
    const user = userEvent.setup();
    renderWithProviders(<OrdersPage />);
    const table = await screen.findByRole("table");
    await waitFor(() => expect(within(table).getAllByRole("row")).toHaveLength(4));

    await user.selectOptions(screen.getByRole("combobox", { name: "Status" }), "PAID");

    await waitFor(() => expect(within(screen.getByRole("table")).getAllByRole("row")).toHaveLength(2));
    expect(within(screen.getByRole("table")).getByText("Paid")).toBeVisible();
    expect(within(screen.getByRole("table")).queryByText("Cancelled")).not.toBeInTheDocument();
  });
});
