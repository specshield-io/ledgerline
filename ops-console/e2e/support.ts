import { expect, type APIRequestContext, type Locator, type Page } from "@playwright/test";

/** Synthetic local-profile account (application.yml); override for other deployments. */
export const ADMIN = {
  username: process.env.E2E_ADMIN_USER ?? "local-admin",
  password: process.env.E2E_ADMIN_PASSWORD ?? "local-dev-only-admin-password",
};

/** Clicks a scenario button; the console then opens the new order's drawer. */
export async function runScenario(page: Page, title: string): Promise<{ drawer: Locator; orderId: string }> {
  await page.goto("/");
  await page.getByRole("button", { name: `Run ${title}` }).click();
  const drawer = page.getByRole("dialog");
  await expect(drawer).toBeVisible();
  await expect(page).toHaveURL(/order=/);
  const orderId = new URL(page.url()).searchParams.get("order") ?? "";
  return { drawer, orderId };
}

export function status(drawer: Locator): Locator {
  return drawer.getByTestId("drawer-status");
}

export function timelineLabel(drawer: Locator, label: string): Locator {
  return drawer.getByTestId("timeline-label").filter({ hasText: new RegExp(`^${escape(label)}$`) });
}

function escape(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

export async function signIn(page: Page): Promise<void> {
  await page.getByRole("button", { name: "Sign in" }).first().click();
  const dialog = page.getByRole("dialog", { name: "Operator sign-in" });
  await dialog.getByLabel("Username").fill(ADMIN.username);
  await dialog.getByLabel("Password").fill(ADMIN.password);
  await dialog.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
}

export async function resetDemoData(request: APIRequestContext): Promise<void> {
  const response = await request.post("/api/v1/admin/reset", {
    headers: {
      Authorization: `Basic ${Buffer.from(`${ADMIN.username}:${ADMIN.password}`).toString("base64")}`,
      "Idempotency-Key": `e2e-reset-${Date.now()}`,
    },
  });
  expect(response.status(), await response.text()).toBe(200);
}
