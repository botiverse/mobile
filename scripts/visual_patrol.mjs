#!/usr/bin/env node
// visual_patrol — daily visual-drift inspection that files GitHub issues.
//
// Reads the nightly visual-testing analysis (per-case react/android/ios divergences),
// keeps only NEW, real drifts (severity >= threshold; excludes declared/intentional
// cross-platform differences and analysis errors), dedupes against already-open patrol
// issues by stable caseId + comparison identity, and files ONE GitHub issue
// per surviving drift with evidence. Runs after the nightly capture+analysis; the
// judgment work (triage, fix) happens in the morning off the issue queue.
//
// SAFETY: dry-run is the DEFAULT. Without --live it prints exactly what it WOULD file
// and creates nothing — so developing/testing this never spams real issues. A per-run
// issue cap and skip logging keep it from flooding even when --live.
//
// Phase 2 (not in this file): for style/token drifts, attach an M3 draft patch +
// 3x panel verdict to the issue (see m3-blade). Semantic/layout drifts stay evidence-only.
//
// Usage:
//   node scripts/visual_patrol.mjs \
//     [--analysis gh-pages|<path>] [--comparison react__android] \
//     [--threshold medium] [--cap 10] [--repo botiverse/mobile] [--live]
//
// Exit: 0 always on a completed run (dry or live); non-zero only on hard error.

import fs from "node:fs";
import { execFileSync } from "node:child_process";
import { hasOpenVisualIdentity } from "./patrol/identity.mjs";

const SEV_ORDER = { low: 1, medium: 2, high: 3, critical: 4 };
const MARKER_RE = /<!--\s*patrol:caseId=([^;]+);comparison=([^;]+);hash=(\S+)\s*-->/;

const argv = process.argv.slice(2);
const arg = (f, d) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : d; };
const has = (f) => argv.includes(f);

const opts = {
  analysis: arg("--analysis", "gh-pages"),
  comparison: arg("--comparison", "react__android"),
  threshold: arg("--threshold", "medium"),
  cap: Number(arg("--cap", "10")),
  repo: arg("--repo", "botiverse/mobile"),
  live: has("--live"),
};

function loadAnalysis() {
  if (opts.analysis === "gh-pages") {
    const out = execFileSync("git", ["-c", "submodule.recurse=false", "show",
      `origin/gh-pages:visual-testing/latest/analysis/${opts.comparison}.json`],
      { encoding: "utf8", maxBuffer: 128 * 1024 * 1024 });
    return JSON.parse(out);
  }
  return JSON.parse(fs.readFileSync(opts.analysis, "utf8"));
}

// A drift is a real regression candidate when: analysis is ready, no analysis error,
// severity clears the threshold, and it is NOT a declared/intentional cross-platform
// difference. `intentional` is an array of matched declaredDivergence ids (empty = none).
function isReportableDrift(c) {
  if (c.status !== "ready" || c.error) return { ok: false, why: `status=${c.status}${c.error ? " error" : ""}` };
  const sev = SEV_ORDER[String(c.severity || "").toLowerCase()] || 0;
  if (sev < (SEV_ORDER[opts.threshold] || 2)) return { ok: false, why: `severity ${c.severity} < ${opts.threshold}` };
  if (Array.isArray(c.intentional) && c.intentional.length > 0) return { ok: false, why: `intentional divergence (${c.intentional.join(",")})` };
  if (!Array.isArray(c.summary) || c.summary.length === 0) return { ok: false, why: "no summary/divergence" };
  return { ok: true };
}

function fetchOpenPatrolMarkers() {
  try {
    const out = execFileSync("gh", ["issue", "list", "--repo", opts.repo, "--label", "visual-drift",
      "--state", "open", "--limit", "800", "--json", "number,body"], { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
    return JSON.parse(out).map((i) => {
      const m = MARKER_RE.exec(i.body || "");
      return m ? { number: i.number, caseId: m[1], comparison: m[2], hash: m[3] } : null;
    }).filter(Boolean);
  } catch (e) {
    // In dry-run without gh auth this can fail; treat as "no known issues" but say so.
    console.error(`[patrol] could not list existing issues (${String(e.message).slice(0, 120)}); dedup skipped this run`);
    return [];
  }
}

function alreadyFiled(c, markers) {
  return hasOpenVisualIdentity(markers, c.id, opts.comparison);
}

function issueTitle(c) {
  const head = Array.isArray(c.summary) && c.summary[0] ? c.summary[0] : "visual drift";
  return `[visual-drift] ${c.id}: ${head}`.slice(0, 240);
}

function issueBody(c) {
  const diffBase = `https://botiverse.github.io/mobile/visual-testing/latest/diff/${opts.comparison}`;
  const lines = [
    `**Case:** \`${c.id}\`  ·  **Comparison:** \`${opts.comparison}\`  ·  **Severity:** ${c.severity}`,
    `**Metric mismatch:** ${c.metricMismatch ?? "n/a"}  ·  **Suggested owner:** ${c.suggestedOwner || "unassigned"}`,
    "",
    "### What drifted",
    ...(c.summary || []).map((s) => `- ${s}`),
    "",
    "### Likely causes",
    ...((c.likelyCauses || []).map((s) => `- ${s}`) || []),
    "",
    "### Suggested fixes",
    ...((c.suggestedFixes || []).map((s) => `- ${s}`) || []),
    "",
    "### Evidence",
    `![react ↔ android side-by-side](${diffBase}/${c.id}.side-by-side.png)`,
    "",
    `- [Side-by-side image](${diffBase}/${c.id}.side-by-side.png) · [Metrics JSON](${diffBase}/${c.id}.metrics.json)`,
    "",
    "> Auto-filed by the daily visual patrol. Style/token drifts may get an M3 draft patch in a follow-up comment; semantic/layout drifts are evidence-only — a human owns the fix decision. Known intentional divergences are excluded.",
    "",
    `<!-- patrol:caseId=${c.id};comparison=${opts.comparison};hash=${c.imageHash || "none"} -->`,
  ];
  return lines.join("\n");
}

function severityLabel(sev) { return `severity:${String(sev || "unknown").toLowerCase()}`; }

// Idempotently ensure the labels we file with exist (gh issue create fails on an
// unknown label). Safe to call repeatedly; errors (e.g. already exists) are ignored.
function ensureLabels(labels) {
  const colors = { "visual-drift": "d73a4a", "auto-triage": "0e8a16", "severity:critical": "b60205", "severity:high": "e99695", "severity:medium": "fbca04", "severity:low": "c2e0c6" };
  for (const name of [...new Set(labels)]) {
    try {
      execFileSync("gh", ["label", "create", name, "--repo", opts.repo, "--force",
        "--color", colors[name] || "ededed"], { encoding: "utf8", stdio: "ignore" });
    } catch { /* already exists or no perms — issue create will surface a real problem */ }
  }
}

function fileIssue(c) {
  const title = issueTitle(c);
  const body = issueBody(c);
  const labels = ["visual-drift", "auto-triage", severityLabel(c.severity)];
  if (!opts.live) {
    return { dryRun: true, title, labels };
  }
  const out = execFileSync("gh", ["issue", "create", "--repo", opts.repo, "--title", title,
    "--body", body, ...labels.flatMap((l) => ["--label", l])], { encoding: "utf8" });
  return { dryRun: false, url: out.trim(), title };
}

function main() {
  const analysis = loadAnalysis();
  const cases = Array.isArray(analysis.cases) ? analysis.cases : [];
  const markers = fetchOpenPatrolMarkers();

  const reportable = [];
  const skipped = [];
  for (const c of cases) {
    const r = isReportableDrift(c);
    if (!r.ok) { skipped.push({ id: c.id, why: r.why }); continue; }
    if (alreadyFiled(c, markers)) { skipped.push({ id: c.id, why: "already-open issue (deduped)" }); continue; }
    reportable.push(c);
  }
  // Worst-first, then cap.
  reportable.sort((a, b) => (SEV_ORDER[b.severity] || 0) - (SEV_ORDER[a.severity] || 0) || (b.metricMismatch || 0) - (a.metricMismatch || 0));
  const toFile = reportable.slice(0, opts.cap);
  const overflow = reportable.slice(opts.cap);

  if (opts.live && toFile.length) {
    ensureLabels(toFile.flatMap((c) => ["visual-drift", "auto-triage", severityLabel(c.severity)]));
  }
  const filed = toFile.map(fileIssue);

  const report = {
    mode: opts.live ? "LIVE" : "DRY_RUN",
    comparison: opts.comparison,
    generatedAt: analysis.generatedAt,
    totals: { cases: cases.length, reportable: reportable.length, filed: filed.length, skipped: skipped.length, overflow: overflow.length },
    filed,
    overflow_capped: overflow.map((c) => ({ id: c.id, severity: c.severity })),
    skipped_sample: skipped.slice(0, 12),
    note: opts.live ? "Issues created." : "DRY RUN — nothing created. Re-run with --live to file.",
  };
  if (overflow.length) report.overflow_note = `${overflow.length} reportable drift(s) exceeded the per-run cap of ${opts.cap} and were NOT filed this run (logged above).`;
  console.log(JSON.stringify(report, null, 2));
}

main();
