#!/usr/bin/env node
// Assembles evidence/latest.json from the reports a CI run produced. Every number is parsed from a report; a report
// that is missing or unreadable gives null ("not measured"), never a default value. No dependencies: Node 22+.
//
// node evidence/assemble.mjs --out latest.json --repo owner/name --sha <sha> \
//   [--surefire dir] [--failsafe dir] [--playwright junit.xml] [--jacoco jacoco.xml] [--pit mutations.xml] \
//   [--k6 summary.json] [--k6-environment "..."] [--trivy trivy.json] [--zap report_json.json] \
//   [--codeql pass|fail] [--sbom-url url] [--source-urls json]
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

function read(path) {
  if (!path) return null;
  try {
    return readFileSync(path, "utf8");
  } catch {
    return null;
  }
}

function attributes(tag) {
  const attrs = {};
  for (const m of tag.matchAll(/([\w:-]+)\s*=\s*(["'])(.*?)\2/g)) attrs[m[1]] = m[3];
  return attrs;
}

function int(value) {
  const n = Number.parseInt(value ?? "", 10);
  return Number.isFinite(n) ? n : null;
}

function round(value, digits = 1) {
  if (value === null || !Number.isFinite(value)) return null;
  const f = 10 ** digits;
  return Math.round(value * f) / f;
}

/** Counts from one JUnit XML document: the root <testsuites> totals, or the sum of its <testsuite> elements. */
export function junitCounts(xml) {
  if (!xml) return null;
  const root = xml.match(/<testsuites\b[^>]*>/);
  if (root) {
    const a = attributes(root[0]);
    if (a.tests !== undefined) {
      return { tests: int(a.tests), failed: (int(a.failures) ?? 0) + (int(a.errors) ?? 0), skipped: int(a.skipped) ?? 0 };
    }
  }
  const suites = [...xml.matchAll(/<testsuite\b[^>]*>/g)].map((m) => attributes(m[0]));
  if (suites.length === 0) return null;
  return suites.reduce(
    (sum, a) => ({
      tests: sum.tests + (int(a.tests) ?? 0),
      failed: sum.failed + (int(a.failures) ?? 0) + (int(a.errors) ?? 0),
      skipped: sum.skipped + (int(a.skipped) ?? 0),
    }),
    { tests: 0, failed: 0, skipped: 0 },
  );
}

/** Surefire/failsafe write one TEST-*.xml per class; executed tests exclude skipped ones. */
export function junitDir(dir) {
  if (!dir) return null;
  let files;
  try {
    files = readdirSync(dir).filter((f) => /^TEST-.*\.xml$/.test(f));
  } catch {
    return null;
  }
  if (files.length === 0) return null;
  let tests = 0;
  let failed = 0;
  let skipped = 0;
  for (const f of files) {
    const c = junitCounts(read(join(dir, f)));
    if (!c) return null;
    tests += c.tests;
    failed += c.failed;
    skipped += c.skipped;
  }
  return { tests: tests - skipped, failed };
}

export function playwright(path) {
  const c = junitCounts(read(path));
  return c ? { tests: c.tests - c.skipped, failed: c.failed } : null;
}

function lineCoverage(fragment) {
  // A JaCoCo element's own counters come after its children, so the last LINE counter belongs to the element.
  const counters = [...fragment.matchAll(/<counter\b[^>]*type="LINE"[^>]*\/>/g)];
  if (counters.length === 0) return null;
  const a = attributes(counters.at(-1)[0]);
  const covered = int(a.covered);
  const missed = int(a.missed);
  if (covered === null || missed === null || covered + missed === 0) return null;
  return round((100 * covered) / (covered + missed));
}

/** Overall and domain-package line coverage, in percent, from jacoco.xml. */
export function jacoco(xml, domainPackage = "com/specshield/ledgerline/domain") {
  if (!xml || !xml.includes("<report")) return { line: null, domain: null };
  const start = xml.indexOf(`<package name="${domainPackage}"`);
  const domain = start < 0 ? null : lineCoverage(xml.slice(start, xml.indexOf("</package>", start)));
  const tail = xml.slice(xml.lastIndexOf("</package>") + 1);
  return { line: lineCoverage(tail), domain };
}

/** PIT mutation score in percent: detected (killed, timed out, memory error) / all mutants. */
export function pit(xml) {
  if (!xml) return { score: null };
  const mutations = [...xml.matchAll(/<mutation\b[^>]*>/g)].map((m) => attributes(m[0]));
  if (mutations.length === 0) return { score: null };
  const detected = mutations.filter((a) => a.detected === "true").length;
  return { score: round((100 * detected) / mutations.length) };
}

/** p95 latency, request rate and failed requests from a k6 summary (handleSummary data). */
export function k6(json, environment) {
  const empty = { p95ms: null, rps: null, errors: null, errorRate: null, requests: null, environment: null };
  if (!json) return empty;
  let data;
  try {
    data = JSON.parse(json);
  } catch {
    return empty;
  }
  const values = (name) => data?.metrics?.[name]?.values ?? {};
  const num = (v) => (typeof v === "number" && Number.isFinite(v) ? v : null);
  return {
    p95ms: round(num(values("http_req_duration")["p(95)"])),
    rps: round(num(values("http_reqs").rate)),
    errors: num(values("http_req_failed").passes),
    errorRate: num(values("http_req_failed").rate),
    requests: num(values("http_reqs").count),
    environment: environment || null,
  };
}

/** CRITICAL vulnerabilities in a Trivy JSON report. */
export function trivyCritical(json) {
  if (!json) return null;
  try {
    const results = JSON.parse(json).Results ?? [];
    return results.flatMap((r) => r.Vulnerabilities ?? []).filter((v) => v.Severity === "CRITICAL").length;
  } catch {
    return null;
  }
}

/** High-risk alerts (riskcode 3) in a ZAP JSON report. */
export function zapHigh(json) {
  if (!json) return null;
  try {
    const sites = JSON.parse(json).site ?? [];
    return sites.flatMap((s) => s.alerts ?? []).filter((a) => String(a.riskcode) === "3").length;
  } catch {
    return null;
  }
}

export function assemble(o) {
  const unit = junitDir(o.surefire);
  const integration = junitDir(o.failsafe);
  const e2e = playwright(o.playwright);
  const parts = [unit, integration, e2e];
  const complete = parts.every((p) => p !== null);
  return {
    repo: o.repo ?? null,
    gitSha: o.sha ?? null,
    builtAt: o.builtAt ?? new Date().toISOString(),
    tests: {
      unit: unit?.tests ?? null,
      integration: integration?.tests ?? null,
      e2e: e2e?.tests ?? null,
      total: complete ? parts.reduce((s, p) => s + p.tests, 0) : null,
      failed: complete ? parts.reduce((s, p) => s + p.failed, 0) : null,
    },
    coverage: jacoco(read(o.jacoco)),
    mutation: pit(read(o.pit)),
    load: k6(read(o.k6), o.k6Environment),
    scans: {
      codeql: o.codeql === "pass" || o.codeql === "fail" ? o.codeql : null,
      trivyCritical: trivyCritical(read(o.trivy)),
      zapHigh: zapHigh(read(o.zap)),
    },
    sbomUrl: o.sbomUrl || null,
    sources: o.sourceUrls ?? {},
  };
}

function parseArgs(argv) {
  const o = {};
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i].replace(/^--/, "").replace(/-([a-z])/g, (_, c) => c.toUpperCase());
    o[key] = argv[i + 1];
  }
  if (o.sourceUrls) o.sourceUrls = JSON.parse(o.sourceUrls);
  return o;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const options = parseArgs(process.argv.slice(2));
  const evidence = assemble(options);
  const text = `${JSON.stringify(evidence, null, 2)}\n`;
  if (options.out) writeFileSync(options.out, text);
  process.stdout.write(text);
}
