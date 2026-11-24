import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests against a running stack:
 *   docker compose -f docker-compose.yml -f docker-compose.e2e.yml up --build --wait
 *   npx playwright test                       (E2E_BASE_URL defaults to http://localhost:8080)
 * Serial: the scenarios share one backend (3 concurrent chaos runs, one demo merchant, rate limits).
 */
export default defineConfig({
  testDir: "e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: {
    timeout: 10_000,
    toHaveScreenshot: { maxDiffPixelRatio: 0.01, animations: "disabled", caret: "hide" },
  },
  // JUnit XML feeds the evidence file (evidence/assemble.mjs); the HTML report is published with it.
  reporter: [["list"], ["html", { open: "never" }], ["junit", { outputFile: "results/junit.xml" }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:8080",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"] }, testIgnore: /mobile\.spec\.ts/ },
    {
      name: "mobile",
      use: { ...devices["Desktop Chrome"], viewport: { width: 390, height: 844 }, deviceScaleFactor: 1 },
      testMatch: /mobile\.spec\.ts/,
      // Runs last: it resets the demo data for stable screenshots.
      dependencies: ["desktop"],
    },
  ],
});
