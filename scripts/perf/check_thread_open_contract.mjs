#!/usr/bin/env node
// perf/check_thread_open_contract — CI gate for the task #304 thread-open
// contract (joint `raft-mobile` first-open regression, 1.5.0 blocker).
//
// Consumes any logcat capture (CI emulator run or a Hands feedback bundle) and
// asserts the two red lines using diagnostics markers:
//
//   A. REBUILD CONTRACT — `thread_markdown_body_recompose key=<k> n=<count>`
//      (SideEffect in SlockMarkdownBody, fires on every REAL re-execution, so
//      lambda-identity churn that pierces row composition is counted even when
//      signature-keyed markers stay silent). Tag-less rows must end at
//      n <= --max-recompose (default 1); only --tagged rows may reach
//      --max-tagged-recompose (default 2, initial + label hydration).
//      `thread_markdown_row` re-emissions are checked with the same budgets as
//      a secondary signal (subtree identity churn).
//
//   B. HYDRATION ORDER CONTRACT — anchored on the REAL frame marker
//      `thread_first_content_frame_committed` (HanXin's #304 fix; the old
//      `thread_first_content_ready` was a false gate — #1055 review finding ①).
//      No `thread_secondary_*_result` / aux-launch / members-fetch marker may
//      appear before the frame commit. A log WITHOUT the frame marker FAILS
//      with MISSING_FRAME_MARKER — the old marker is never a passing anchor,
//      so pre-fix builds read expected-RED instead of false-green.
//
// Exit 0 = PASS; 1 = FAIL / MISSING_FRAME_MARKER; 2 = no thread markers at all.
//
// Usage:
//   node scripts/perf/check_thread_open_contract.mjs --log logcat.txt
//     [--tagged <key>[,<key>...]] [--max-recompose 1] [--max-tagged-recompose 2]

import fs from "node:fs";

const argv = process.argv.slice(2);
const arg = (f, d) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : d; };

const logPath = arg("--log", "");
const tagged = new Set((arg("--tagged", "") || "").split(",").map((s) => s.trim()).filter(Boolean));
const maxRecompose = Number(arg("--max-recompose", "1"));
const maxTaggedRecompose = Number(arg("--max-tagged-recompose", "2"));

if (!logPath) { console.error("--log <logcat file> required"); process.exit(2); }
const lines = fs.readFileSync(logPath, "utf8").split("\n");

// Markers survive arbitrary logcat prefixes (timestamps, pid, KLog tags) —
// match on the payload text only.
const RE = {
  // v2 scoped marker; the v1 body-only name is still parsed (as scope=body)
  // so pre-v2 采证 logs keep reading.
  recompose: /thread_row_recompose scope=(\w+) key=(\S+) n=(\d+) bodyLen=(\d+)/,
  recomposeV1: /thread_markdown_body_recompose key=(\S+) n=(\d+) bodyLen=(\d+)/,
  row: /thread_markdown_row key=(\S*) message=(\S+) bodyLen=(\d+)/,
  frameCommitted: /thread_first_content_frame_committed threadId=(\S*)(?: routeKey=(\S*))?/,
  firstContentReadyLegacy: /thread_first_content_ready threadId=(\S*)/,
  secondary: /thread_(secondary_refresh_result|secondary_followed_result)/,
  auxLaunched: /thread_auxiliary_hydration result=launch/,
  membersFetch: /thread_member_repository .*(fetch|load|start)/i,
};

const events = [];
for (let i = 0; i < lines.length; i++) {
  const l = lines[i];
  let m;
  if ((m = RE.recompose.exec(l))) events.push({ i, kind: "recompose", scope: m[1], key: m[2], n: Number(m[3]) });
  else if ((m = RE.recomposeV1.exec(l))) events.push({ i, kind: "recompose", scope: "body", key: m[1], n: Number(m[2]) });
  else if ((m = RE.row.exec(l))) events.push({ i, kind: "row", id: m[2] });
  else if ((m = RE.frameCommitted.exec(l))) events.push({ i, kind: "frame_committed", threadId: m[1], routeKey: m[2] || "" });
  else if ((m = RE.firstContentReadyLegacy.exec(l))) events.push({ i, kind: "legacy_ready", threadId: m[1] });
  else if (RE.secondary.test(l)) events.push({ i, kind: "aux", what: "secondary_result" });
  else if (RE.auxLaunched.test(l)) events.push({ i, kind: "aux", what: "aux_launched" });
  else if (RE.membersFetch.test(l)) events.push({ i, kind: "aux", what: "members_fetch" });
}

if (!events.length) {
  console.log(JSON.stringify({ verdict: "NO_MARKERS", note: "log contains no thread-open diagnostics — wrong capture or diagnostics disabled" }, null, 2));
  process.exit(2);
}

// Contract A — final recompose count per scope+key (the marker logs
// monotonically increasing n, so the max seen is the count), plus row
// re-emissions. Budgets apply to card and body scopes alike.
const recomposeMax = new Map(); // "scope\x00key" -> max n
const scopesSeen = new Set();
const rowLogs = new Map();
for (const e of events) {
  if (e.kind === "recompose") {
    scopesSeen.add(e.scope);
    const k = `${e.scope}\x00${e.key}`;
    recomposeMax.set(k, Math.max(recomposeMax.get(k) || 0, e.n));
  }
  if (e.kind === "row") rowLogs.set(e.id, (rowLogs.get(e.id) || 0) + 1);
}
const overBudget = [];
for (const [sk, n] of recomposeMax) {
  const [scope, key] = sk.split("\x00");
  const budget = tagged.has(key) ? maxTaggedRecompose : maxRecompose;
  if (n > budget) overBudget.push({ scope, key, recompositions: n, budget, signal: "recompose" });
}
for (const [id, n] of rowLogs) {
  const budget = tagged.has(id) ? maxTaggedRecompose : maxRecompose;
  if (n > budget) overBudget.push({ scope: "row_marker", key: id, logs: n, budget, signal: "row_reemit" });
}

// Contract B — hard-anchored on the committed-frame marker.
const frameIdx = events.filter((e) => e.kind === "frame_committed").map((e) => e.i);
const legacyOnly = !frameIdx.length && events.some((e) => e.kind === "legacy_ready");
const auxBeforeFrame = frameIdx.length
  ? events.filter((e) => e.kind === "aux" && e.i < frameIdx[0]).map((e) => ({ line: e.i, what: e.what }))
  : [];

const violations = [];
if (overBudget.length) {
  violations.push({ contract: "A_rebuild", detail: `${overBudget.length} row(s) exceeded their recomposition budget`, rows: overBudget.slice(0, 60) });
}
// Outer row/Card recompositions can escape a body-only hook (#1076 review
// finding ②) — a run whose log carries no scope=card markers has not measured
// the layer that failed on device, so it cannot pass.
if (recomposeMax.size && !scopesSeen.has("card")) {
  violations.push({
    contract: "A_rebuild",
    detail: "MISSING_CARD_SCOPE: no scope=card recompose markers — the ThreadMessageCard-level hook is not wired; body-only coverage cannot green the gate",
  });
}
const frameMissingRoute = events.filter((e) => e.kind === "frame_committed" && !e.routeKey).length;
if (frameIdx.length && frameMissingRoute) {
  violations.push({
    contract: "B_hydration_order",
    detail: `frame_committed marker(s) without routeKey (${frameMissingRoute}) — the anchor must carry the originating route identity, not derive it from current payload`,
  });
}
if (!frameIdx.length) {
  violations.push({
    contract: "B_hydration_order", detail: "MISSING_FRAME_MARKER: no thread_first_content_frame_committed in log",
    note: legacyOnly
      ? "only the legacy thread_first_content_ready (false gate) is present — expected RED on pre-#304-fix builds; never a passing anchor"
      : "no frame anchor at all",
  });
} else if (auxBeforeFrame.length) {
  violations.push({ contract: "B_hydration_order", detail: "auxiliary hydration/secondary refresh before frame commit", events: auxBeforeFrame.slice(0, 20) });
}

const report = {
  verdict: violations.length ? (frameIdx.length || overBudget.length ? "FAIL" : "MISSING_FRAME_MARKER") : "PASS",
  stats: {
    rowsWithRecomposeMarker: recomposeMax.size,
    rowsWithRowMarker: rowLogs.size,
    maxRecomposeSeen: Math.max(0, ...recomposeMax.values()),
    frameCommittedCount: frameIdx.length,
    legacyReadySeen: events.some((e) => e.kind === "legacy_ready"),
    auxEventsTotal: events.filter((e) => e.kind === "aux").length,
  },
  violations,
};
console.log(JSON.stringify(report, null, 2));
process.exit(violations.length ? 1 : 0);
