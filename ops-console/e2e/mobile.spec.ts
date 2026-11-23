import { expect, test, type Page } from "@playwright/test";
import { resetDemoData } from "./support";

/**
 * Values that change on every run (ids, times, counters) are masked; layout and labels are compared. Masks are drawn
 * wherever the element is, even under a dialog, so each screen masks only what it shows.
 */
function volatileOrdersScreen(page: Page) {
  return [
    page.getByTestId("order-id"),
    page.locator("main time"),
    page.locator('[data-testid^="metric-"] dd'),
    page.getByTestId("metrics-since"),
  ];
}

function volatileDrawer(page: Page) {
  const dialog = page.getByRole("dialog");
  return [dialog.getByRole("heading"), dialog.locator("time")];
}

test.beforeAll(async ({ request }) => {
  await resetDemoData(request);
});

test("LL-E07 orders screen at 390x844", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByTestId("order-id")).toHaveCount(25);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await expect(page).toHaveScreenshot("orders.png", { fullPage: true, mask: volatileOrdersScreen(page) });
});

test("LL-E07 order drawer at 390x844", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("combobox", { name: "Status" }).selectOption("REFUNDED");
  await expect(page.getByTestId("order-id")).toHaveCount(2);
  await page.getByTestId("order-id").last().click();
  await expect(page.getByTestId("timeline-item")).toHaveCount(1);
  await expect(page).toHaveScreenshot("order-drawer.png", { mask: volatileDrawer(page) });
});

test("LL-E07 how it works sheet at 390x844", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "How it works" }).click();
  await expect(page.getByRole("img", { name: "Ledgerline architecture" })).toBeVisible();
  // Static content: nothing to mask.
  await expect(page).toHaveScreenshot("how-it-works.png");
});
