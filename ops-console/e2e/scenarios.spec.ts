import { expect, test } from "@playwright/test";
import { runScenario, status, timelineLabel } from "./support";

test("LL-E01 happy path: the order reaches PAID within 5 s; the timeline shows Received and Applied", async ({
  page,
}) => {
  const { drawer } = await runScenario(page, "happy path");

  await expect(status(drawer)).toHaveText("Paid", { timeout: 5_000 });
  await expect(timelineLabel(drawer, "Received")).toHaveCount(2);
  await expect(timelineLabel(drawer, "Applied")).toHaveCount(2);
  await expect(page.getByText("happy-path finished", { exact: true })).toBeVisible();
});

test("LL-E02 duplicate storm: paid once, with 4 Deduplicated entries", async ({ page }) => {
  const { drawer } = await runScenario(page, "duplicate storm");

  await expect(status(drawer)).toHaveText("Paid");
  await expect(timelineLabel(drawer, "Deduplicated")).toHaveCount(4);
  await expect(drawer.getByTestId("timeline-item").filter({ hasText: "→ PAID" })).toHaveCount(1);
});

test("LL-E03 tampered webhook: a Signature rejected entry and the status stays Payment pending", async ({
  page,
}) => {
  const { drawer } = await runScenario(page, "tampered webhook");

  await expect(timelineLabel(drawer, "Signature rejected")).toHaveCount(1);
  await expect(page.getByText("tampered-webhook finished", { exact: true })).toBeVisible();
  await expect(status(drawer)).toHaveText("Payment pending");
});

test("LL-E05 out of order: stays PAID with a Stale (out of order) entry", async ({ page }) => {
  const { drawer } = await runScenario(page, "out of order");

  await expect(timelineLabel(drawer, "Stale (out of order)")).toHaveCount(1);
  await expect(status(drawer)).toHaveText("Paid");
});
