import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { signIn } from "./support";

async function seriousViolations(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"])
    .analyze();
  return results.violations
    .filter((v) => v.impact === "serious" || v.impact === "critical")
    .map((v) => ({
      id: v.id,
      impact: v.impact,
      help: v.help,
      targets: v.nodes.map((n) => n.target.join(" ")),
    }));
}

for (const colorScheme of ["light", "dark"] as const) {
  test.describe(`${colorScheme} theme`, () => {
    test.use({ colorScheme });

    test(`LL-E06 no serious or critical axe violations on any screen (${colorScheme})`, async ({ page }) => {
      await page.goto("/");
      await expect(page.getByRole("table")).toBeVisible();
      await expect(page.getByTestId("order-id").first()).toBeVisible();
      expect(await seriousViolations(page), "orders").toEqual([]);

      await page.getByTestId("order-id").first().click();
      await expect(page.getByTestId("timeline-item").first()).toBeVisible();
      expect(await seriousViolations(page), "order drawer").toEqual([]);
      await page.keyboard.press("Escape");

      await page.getByRole("button", { name: "How it works" }).click();
      await expect(page.getByRole("img", { name: "Ledgerline architecture" })).toBeVisible();
      expect(await seriousViolations(page), "how it works").toEqual([]);
      await page.keyboard.press("Escape");

      await page.getByRole("link", { name: "Outbox" }).click();
      await expect(page.getByText(/Sign in with a VIEWER or ADMIN account/)).toBeVisible();
      expect(await seriousViolations(page), "outbox, signed out").toEqual([]);

      await page.getByRole("button", { name: "Sign in" }).first().click();
      await expect(page.getByRole("dialog", { name: "Operator sign-in" })).toBeVisible();
      expect(await seriousViolations(page), "sign-in dialog").toEqual([]);
      await page.keyboard.press("Escape");

      await signIn(page);
      await expect(page.getByRole("table")).toBeVisible();
      expect(await seriousViolations(page), "outbox, signed in").toEqual([]);
    });
  });
}

test("keyboard: skip link, tabs and the order drawer work without a mouse", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByTestId("order-id").first()).toBeVisible();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "Skip to content" })).toBeFocused();
  await page.keyboard.press("Enter");
  await page.getByTestId("order-id").first().focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toBeHidden();
  await expect(page.getByTestId("order-id").first()).toBeFocused();
});
