// LL-N08: the evidence assembler parses real report formats and writes null for anything it cannot measure.
// Run: node --test evidence/
import assert from "node:assert/strict";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { assemble, jacoco, junitCounts, k6, pit, trivyCritical, zapHigh } from "./assemble.mjs";

const fixtures = join(fileURLToPath(new URL(".", import.meta.url)), "fixtures");
const f = (name) => join(fixtures, name);

test("LL-N08 counts tests from surefire, failsafe and Playwright JUnit XML, and totals them", () => {
  const evidence = assemble({
    repo: "specshield-io/ledgerline",
    sha: "abc123",
    builtAt: "2026-01-15T10:00:00Z",
    surefire: f("surefire"),
    failsafe: f("failsafe"),
    playwright: f("playwright-results.xml"),
  });
  // surefire: (10 - 1 skipped) + 5 = 14 executed, 1 error + 2 failures; failsafe-summary.xml is not a TEST- file.
  assert.deepEqual(evidence.tests, { unit: 14, integration: 7, e2e: 9, total: 30, failed: 4 });
  assert.equal(evidence.repo, "specshield-io/ledgerline");
  assert.equal(evidence.gitSha, "abc123");
  assert.equal(evidence.builtAt, "2026-01-15T10:00:00Z");
});

test("LL-N08 coverage, mutation score, load, scans and SBOM come from their reports", () => {
  const evidence = assemble({
    jacoco: f("jacoco.xml"),
    pit: f("mutations.xml"),
    k6: f("k6-summary.json"),
    k6Environment: "GitHub-hosted runner (ubuntu-24.04), 4 vCPU, docker compose",
    trivy: f("trivy.json"),
    zap: f("zap.json"),
    codeql: "pass",
    sbomUrl: "https://example.test/bom.json",
  });
  assert.deepEqual(evidence.coverage, { line: 96.3, domain: 99.5 });
  assert.deepEqual(evidence.mutation, { score: 50 });
  assert.deepEqual(evidence.load, {
    p95ms: 41.3,
    rps: 180,
    errors: 5,
    errorRate: 0.0002,
    requests: 27000,
    environment: "GitHub-hosted runner (ubuntu-24.04), 4 vCPU, docker compose",
  });
  assert.deepEqual(evidence.scans, { codeql: "pass", trivyCritical: 1, zapHigh: 1 });
  assert.equal(evidence.sbomUrl, "https://example.test/bom.json");
});

test("LL-N08 anything missing or unreadable is null, never a made-up number", () => {
  const evidence = assemble({
    surefire: f("surefire"),
    failsafe: f("no-such-dir"),
    playwright: f("no-such-file.xml"),
    jacoco: f("zap.json"),
    pit: f("no-such-file.xml"),
    k6: f("jacoco.xml"),
    trivy: f("mutations.xml"),
    zap: f("no-such-file.json"),
    codeql: "maybe",
  });
  assert.deepEqual(evidence.tests, { unit: 14, integration: null, e2e: null, total: null, failed: null });
  assert.deepEqual(evidence.coverage, { line: null, domain: null });
  assert.deepEqual(evidence.mutation, { score: null });
  assert.equal(evidence.load.p95ms, null);
  assert.equal(evidence.load.environment, null);
  assert.deepEqual(evidence.scans, { codeql: null, trivyCritical: null, zapHigh: null });
  assert.equal(evidence.sbomUrl, null);
  assert.equal(typeof evidence.builtAt, "string");
});

test("parsers handle edge cases: no suites, no mutations, empty reports", () => {
  assert.equal(junitCounts("<root/>"), null);
  assert.deepEqual(junitCounts('<testsuite tests="2" failures="1"/>'), { tests: 2, failed: 1, skipped: 0 });
  assert.deepEqual(pit("<mutations></mutations>"), { score: null });
  assert.deepEqual(jacoco('<report name="x"></report>'), { line: null, domain: null });
  assert.deepEqual(jacoco('<report><counter type="LINE" missed="0" covered="0"/></report>'), { line: null, domain: null });
  assert.equal(k6('{"metrics":{}}').p95ms, null);
  assert.equal(trivyCritical('{"Results":[]}'), 0);
  assert.equal(trivyCritical("not json"), null);
  assert.equal(zapHigh('{"site":[]}'), 0);
});
