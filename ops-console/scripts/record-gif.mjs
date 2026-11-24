// Records docs/media/ledgerline.gif from a running stack: the README's 20-second tour of the console.
// Not a test. Synthetic data only.
//
//   docker compose up --build --wait
//   cd ops-console && node scripts/record-gif.mjs            # needs ffmpeg on PATH
//   BASE_URL=http://localhost:18080 node scripts/record-gif.mjs
//
// Playwright records a WebM of the real console while it runs two chaos scenarios (duplicate storm, then tampered
// webhook); ffmpeg turns it into a palette-optimised GIF.
import { execFileSync } from "node:child_process";
import { mkdtempSync, renameSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";

const baseURL = process.env.BASE_URL ?? "http://localhost:8080";
const out = resolve(fileURLToPath(import.meta.url), "../../../docs/media/ledgerline.gif");
const size = { width: 1280, height: 760 };
const work = mkdtempSync(join(tmpdir(), "ledgerline-gif-"));

const browser = await chromium.launch();
const context = await browser.newContext({
    baseURL,
    viewport: size,
    colorScheme: "light",
    recordVideo: { dir: work, size },
});
const page = await context.newPage();
const pause = (ms) => page.waitForTimeout(ms);

async function run(title, until) {
    await page.getByRole("button", { name: `Run ${title}` }).click();
    const drawer = page.getByRole("dialog");
    await drawer
        .getByTestId("timeline-label")
        .filter({ hasText: until })
        .first()
        .waitFor({ timeout: 15_000 });
    await pause(3_500);
    await drawer.getByRole("button", { name: /close/i }).click();
    await drawer.waitFor({ state: "hidden" });
    await pause(1_000);
}

await page.goto("/");
await page.getByRole("button", { name: "Run duplicate storm" }).waitFor();
await pause(2_500);
await run("duplicate storm", "Deduplicated");
await run("tampered webhook", "Signature rejected");
await pause(1_500);

const video = page.video();
await context.close();
await browser.close();
const webm = join(work, "tour.webm");
renameSync(await video.path(), webm);

execFileSync(
    "ffmpeg",
    [
        "-y",
        "-loglevel",
        "error",
        "-i",
        webm,
        "-vf",
        "fps=8,scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=96[p];[b][p]paletteuse=dither=bayer:bayer_scale=4",
        out,
    ],
    { stdio: "inherit" },
);
rmSync(work, { recursive: true, force: true });
console.log(`Wrote ${out}`);
