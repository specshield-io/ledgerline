import { expect, test } from "@playwright/test";
import { runScenario, signIn, status, timelineLabel } from "./support";

// docker-compose.e2e.yml: 3 attempts with a short backoff and a 15 s outage, so this finishes in under a minute.
test("LL-E04 merchant down: the notification is dead-lettered, an ADMIN replays it, it ends Delivered", async ({
  page,
  request,
}) => {
  const { drawer, orderId } = await runScenario(page, "merchant down");
  await expect(status(drawer)).toHaveText("Paid");
  await expect(timelineLabel(drawer, "Dead-lettered")).toHaveCount(1, { timeout: 30_000 });

  // Wait for the demo merchant to come back before replaying.
  await expect
    .poll(async () => (await (await request.get("/demo/merchant-sink")).json()).mode, { timeout: 30_000 })
    .toBe("OK");

  await page.keyboard.press("Escape");
  await signIn(page);
  await page.getByRole("link", { name: "Outbox" }).click();
  await page.getByRole("combobox", { name: "Status" }).selectOption("DEAD");
  const row = page.locator(`[data-testid="outbox-row"][data-order="${orderId}"]`);
  await expect(row).toHaveCount(1);

  await row.getByRole("button", { name: /Replay notification/ }).click();
  const confirm = page.getByRole("alertdialog");
  await expect(confirm).toContainText("Replay notification");
  await confirm.getByRole("button", { name: "Replay", exact: true }).click();
  await expect(page.getByText(/^Notification #\d+ queued again$/)).toBeVisible();

  await page.goto(`/?order=${orderId}`);
  await expect(timelineLabel(page.getByRole("dialog"), "Outbox delivered")).toHaveCount(1, {
    timeout: 15_000,
  });
  await expect(timelineLabel(page.getByRole("dialog"), "Replayed by operator")).toHaveCount(1);
});
