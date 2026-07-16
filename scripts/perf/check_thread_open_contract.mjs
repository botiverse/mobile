#!/usr/bin/env node
// perf/check_thread_open_contract — checker v3 for the task #304 frozen
// contract (freeze package v2 SHA f6683935… §5/§6.4/§7 + the three arbitrated
// deltas: offscreen false-visible observations produce reason-coded drops and
// do not consume the token; auxiliary work is judged per (stage, operation);
// large secondary parse has a bytes threshold + UI-critical dispatcher deny
// set with a violation count).
//
// Consumes any logcat capture (CI emulator run or a Hands feedback bundle)
// and emits the Contract A (card stability) + Contract B (render commit +
// aux lifecycle) JSON per §7. Exit 0 = both PASS; 1 = violations; 2 = wrong
// capture (no thread markers at all).
//
// Usage:
//   node scripts/perf/check_thread_open_contract.mjs --log logcat.txt
//     [--tagged <key>[,<key>...]]      keys allowed one presentation update
//     [--max-recompose 1] [--max-tagged-recompose 2]
//     [--max-window 40]                declared window size ceiling
//     [--require-bottom-suffix]        ordinary-#all fixture extra tooth
//     [--parse-bytes-threshold 65536]  "large" parse cutoff (v3 materials pin the final number)
//     [--ui-dispatchers main,ui,immediate]  UI-critical dispatcher deny set
//
// ── CONFIG: frozen marker vocabulary ─────────────────────────────────────────
// Field names mirror freeze-package v2 §5; the three delta fields (operation=,
// false-visible drop reasons, parse verdict inputs) follow the arbitration
// wording. This block is the single place to align if v3 materials adjust a
// field name.

import fs from "node:fs";
import crypto from "node:crypto";

const TOKEN_FIELDS = ["surfaceOwner", "pageInstance", "intent", "routeGeneration", "resolvedRouteKey", "renderRevision", "windowGeneration"];

const COMMIT_DROP_REASONS = new Set([
  "invalid_token", "surface_not_visible", "surface_owner_mismatch", "page_instance_mismatch",
  "intent_mismatch", "route_generation_mismatch", "resolved_route_mismatch", "render_revision_mismatch",
  "window_generation_mismatch", "no_pending_snapshot", "unsupported_route_refinement",
  "already_committed", "disposed", "cleared",
]);
const TERMINAL_RESULTS = new Set(["success", "failure", "cancelled"]);

const M = {
  windowDeclared: /thread_render_window_declared\b/,
  observed: /thread_first_content_draw_observed\b/,
  committed: /thread_first_content_frame_committed\b/,
  commitDrop: /thread_first_content_frame_commit_drop\b/,
  budget: /thread_first_content_budget\b/,
  stage: /thread_auxiliary_hydration\b/,
  workRequest: /thread_auxiliary_request\b/,
  workParse: /thread_auxiliary_parse\b/,
  workEmit: /thread_auxiliary_emit\b/,
  completionDrop: /thread_auxiliary_completion_drop\b/,
  rowRecompose: /thread_row_recompose\b/,
  legacyReady: /thread_first_content_ready\b/,
};

// ── args ─────────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);
const arg = (f, d) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : d; };
const opts = {
  log: arg("--log", ""),
  tagged: new Set((arg("--tagged", "") || "").split(",").map((s) => s.trim()).filter(Boolean)),
  maxRecompose: Number(arg("--max-recompose", "1")),
  maxTaggedRecompose: Number(arg("--max-tagged-recompose", "2")),
  maxWindow: Number(arg("--max-window", "40")),
  requireBottomSuffix: argv.includes("--require-bottom-suffix"),
  parseBytesThreshold: Number(arg("--parse-bytes-threshold", "65536")),
  uiDispatchers: new Set((arg("--ui-dispatchers", "main,ui,immediate") || "").split(",").map((s) => s.trim().toLowerCase()).filter(Boolean)),
};
if (!opts.log) { console.error("--log <logcat file> required"); process.exit(2); }

// ── parsing ──────────────────────────────────────────────────────────────────
const field = (line, name) => new RegExp(`\\b${name}=("[^"]*"|\\S+)`).exec(line)?.[1]?.replace(/^"|"$/g, "");
const tokenOf = (line) => Object.fromEntries(TOKEN_FIELDS.map((f) => [f, field(line, f)]));
const tokenKey = (t) => TOKEN_FIELDS.map((f) => t[f] ?? "").join(" ");
const tokenComplete = (t) => TOKEN_FIELDS.every((f) => t[f] !== undefined && t[f] !== "");

const lines = fs.readFileSync(opts.log, "utf8").split("\n");
const ev = [];
for (let i = 0; i < lines.length; i++) {
  const l = lines[i];
  let kind = null;
  for (const [k, re] of Object.entries(M)) if (re.test(l)) { kind = k; break; }
  if (!kind) continue;
  ev.push({ i, kind, line: l, token: tokenOf(l) });
}
if (!ev.length) {
  console.log(JSON.stringify({ verdict: "NO_MARKERS", note: "log contains no thread-open diagnostics — wrong capture or diagnostics disabled" }, null, 2));
  process.exit(2);
}

const violations = [];
const flag = (contract, detail, extra) => violations.push({ contract, detail, ...(extra || {}) });

// ── Contract B: render commit + aux lifecycle ────────────────────────────────
const committed = ev.filter((e) => e.kind === "committed");
const observed = ev.filter((e) => e.kind === "observed");
const drops = ev.filter((e) => e.kind === "commitDrop");
const budgets = ev.filter((e) => e.kind === "budget");

for (const c of committed) if (!tokenComplete(c.token)) flag("B", "committed marker missing token fields", { line: c.i });
for (const o of observed) if (!tokenComplete(o.token)) flag("B", "observed marker missing token fields", { line: o.i });

const committedByToken = new Map();
for (const c of committed) {
  const k = tokenKey(c.token);
  committedByToken.set(k, (committedByToken.get(k) || []).concat(c));
}
for (const [, cs] of committedByToken) if (cs.length > 1) flag("B", `duplicate committed for the same token (${cs.length})`, { lines: cs.map((c) => c.i) });

const firstCommitIdx = committed.length ? Math.min(...committed.map((c) => c.i)) : null;
if (firstCommitIdx === null) {
  flag("B", "MISSING_FRAME_MARKER: no thread_first_content_frame_committed in log", {
    note: ev.some((e) => e.kind === "legacyReady")
      ? "only the legacy thread_first_content_ready is present — expected RED on pre-fix builds"
      : "no frame anchor at all",
  });
} else {
  const commitTokenK = tokenKey(committed[0].token);
  const pre = observed.filter((o) => tokenKey(o.token) === commitTokenK && o.i < firstCommitIdx && field(o.line, "visible") === "true");
  if (!pre.length) flag("B", "committed without a preceding same-token visible=true draw_observed", { line: firstCommitIdx });
}
// budget can never be the only draw evidence
if (budgets.length && !observed.length) flag("B", "budget marker emitted with zero draw_observed — witness swallowed", { lines: budgets.map((b) => b.i).slice(0, 3) });
// every drop must carry a frozen reason
for (const d of drops) {
  const r = field(d.line, "reason");
  if (!r || !COMMIT_DROP_REASONS.has(r)) flag("B", `commit_drop with missing/unknown reason "${r ?? ""}"`, { line: d.i });
}
// delta ①: false-visible observations must be judged, not silently ignored
const falseVisible = observed.filter((o) => field(o.line, "visible") === "false");
if (falseVisible.length && !drops.some((d) => field(d.line, "reason") === "surface_not_visible")) {
  flag("B", `visible=false observation(s) (${falseVisible.length}) without any surface_not_visible drop`);
}

// aux stage lifecycle (stage-level) + work markers per (stage, operation) — delta ②
const stageEv = ev.filter((e) => e.kind === "stage");
const workEv = ev.filter((e) => ["workRequest", "workParse", "workEmit", "completionDrop"].includes(e.kind));
const opOf = (l) => field(l, "operation") ?? "default";
const stages = new Map();
for (const s of stageEv) {
  const st = field(s.line, "stage");
  if (!st) { flag("B", "stage marker without stage=", { line: s.i }); continue; }
  const rec = stages.get(st) || { applicable: null, admitted: 0, launched: 0, terminal: 0, terminalResult: null, skipped: 0, lines: [] };
  const app = field(s.line, "applicable");
  if (app !== undefined) rec.applicable = app === "true";
  const res = field(s.line, "result");
  if (res === "admitted") rec.admitted++;
  else if (res === "launched") rec.launched++;
  else if (res === "terminal") { rec.terminal++; rec.terminalResult = field(s.line, "terminal"); }
  else if (res === "skipped") rec.skipped++;
  rec.lines.push(s.i);
  stages.set(st, rec);
}
if (firstCommitIdx !== null && !stages.size) {
  flag("B", "no auxiliary stage lifecycle markers after commit — queued-forever or instrumentation missing");
}
for (const [st, r] of stages) {
  if (r.applicable === null) { flag("B", `stage ${st}: applicability never declared explicitly`, { lines: r.lines.slice(0, 3) }); continue; }
  if (r.applicable) {
    if (r.skipped) flag("B", `stage ${st}: applicable stage was skipped`);
    if (r.admitted !== 1 || r.launched !== 1 || r.terminal !== 1) {
      flag("B", `stage ${st}: applicable lifecycle not exactly-once (admitted=${r.admitted} launched=${r.launched} terminal=${r.terminal})`);
    }
    if (r.terminal && !TERMINAL_RESULTS.has(r.terminalResult)) flag("B", `stage ${st}: terminal result "${r.terminalResult}" not in enum`);
  } else {
    if (r.skipped !== 1) flag("B", `stage ${st}: non-applicable stage must skip exactly once (skipped=${r.skipped})`);
    if (r.admitted || r.launched || r.terminal) flag("B", `stage ${st}: non-applicable stage did work`);
  }
}
// pre-commit zero work per (stage, operation); duplicate started; obsolete completions
const preWork = {};
const workStarted = new Map();
const commitTokenK = committed.length ? tokenKey(committed[0].token) : null;
for (const w of workEv) {
  const st = field(w.line, "stage") ?? "?";
  const op = opOf(w.line);
  const phase = field(w.line, "phase");
  const isStart = phase === "started";
  const isAcceptedEmit = w.kind === "workEmit" && field(w.line, "result") === "accepted";
  if (w.kind !== "completionDrop" && (isStart || isAcceptedEmit) && (firstCommitIdx === null || w.i < firstCommitIdx)) {
    const k = `${st}/${op}/${w.kind}`;
    preWork[k] = (preWork[k] || 0) + 1;
  }
  if (isStart && w.kind === "workRequest") {
    const rk = `${st} ${op} ${field(w.line, "requestId") ?? ""}`;
    workStarted.set(rk, (workStarted.get(rk) || 0) + 1);
  }
  if (w.kind !== "completionDrop" && commitTokenK && phase === "terminal" && tokenComplete(w.token) && tokenKey(w.token) !== commitTokenK) {
    const hasDrop = workEv.some((d) => d.kind === "completionDrop" && field(d.line, "stage") === st && d.i >= w.i);
    if (!hasDrop) flag("B", `stage ${st}/${op}: obsolete-token ${w.kind} terminal without completion_drop`, { line: w.i });
  }
}
if (Object.keys(preWork).length) flag("B", "auxiliary work before frame commit", { preCommitWork: preWork });
for (const [rk, n] of workStarted) if (n > 1) flag("B", `duplicate request started (${n}) for ${rk.split(" ").slice(0, 2).join("/")}`);
// delta ③: parse budget tooth
let uiCriticalParseViolations = 0;
for (const w of workEv.filter((w) => w.kind === "workParse")) {
  const dispatcher = (field(w.line, "dispatcher") ?? "").toLowerCase();
  const bytes = Number(field(w.line, "bytes") ?? 0);
  if (dispatcher && opts.uiDispatchers.has(dispatcher) && bytes >= opts.parseBytesThreshold) {
    uiCriticalParseViolations++;
    flag("B", `large parse (${bytes}B) on UI-critical dispatcher "${dispatcher}"`, { line: w.i });
  }
}

// ── Contract A: window declaration + card/body stability ─────────────────────
const windowDecls = ev.filter((e) => e.kind === "windowDeclared");
const rows = ev.filter((e) => e.kind === "rowRecompose");
const rowByScopeKey = new Map();
const scopesSeen = new Set();
for (const r of rows) {
  const scope = field(r.line, "scope");
  const key = field(r.line, "key");
  const n = Number(field(r.line, "n") ?? 0);
  if (!scope || !key) { flag("A", "row marker missing scope/key", { line: r.i }); continue; }
  scopesSeen.add(scope);
  const k = `${scope} ${key}`;
  const rec = rowByScopeKey.get(k) || { maxN: 0, instances: 0 };
  rec.maxN = Math.max(rec.maxN, n);
  if (n === 1) rec.instances++;
  rowByScopeKey.set(k, rec);
}
const distinctKeys = (scope) => {
  const set = new Set();
  for (const k of rowByScopeKey.keys()) if (k.startsWith(scope + " ")) set.add(k.slice(scope.length + 1));
  return set;
};
// digest = distinct full keys sorted by message index asc, "\n"-joined, no
// trailing newline, lowercase sha256 (v2 §5.1 + CC-Cata's distinct-set note)
const canonicalDigest = (keys) => {
  const sorted = [...keys].sort((a, b) =>
    Number(/message:(\d+):/.exec(a)?.[1] ?? 0) - Number(/message:(\d+):/.exec(b)?.[1] ?? 0));
  return crypto.createHash("sha256").update(sorted.join("\n"), "utf8").digest("hex");
};

let windowReport = null;
if (rows.length) {
  if (!scopesSeen.has("card")) flag("A", "MISSING_CARD_SCOPE: no scope=card markers — the Card-level hook is not wired");
  if (!scopesSeen.has("body")) flag("A", "MISSING_BODY_SCOPE: no scope=body markers");
  if (!windowDecls.length) {
    flag("A", "no thread_render_window_declared marker — window membership cannot be verified");
  } else {
    // membership is judged against the last declaration (refresh declarations
    // are legal; the final window is what the settled screen shows)
    const decl = windowDecls[windowDecls.length - 1];
    const declaredDigest = (field(decl.line, "windowKeysDigest") ?? "").toLowerCase();
    const declaredCount = Number(field(decl.line, "windowKeyCount") ?? -1);
    const cardKeys = distinctKeys("card");
    const bodyKeys = distinctKeys("body");
    const cardExact = declaredDigest === canonicalDigest(cardKeys) && declaredCount === cardKeys.size;
    const bodyExact = declaredDigest === canonicalDigest(bodyKeys) && declaredCount === bodyKeys.size;
    if (!declaredDigest || declaredCount < 0) {
      flag("A", "window declaration missing windowKeysDigest/windowKeyCount", { line: decl.i });
    } else {
      if (!cardExact) flag("A", `card key set != declared window (declared ${declaredCount}, actual ${cardKeys.size})`);
      if (!bodyExact) flag("A", `body key set != declared window (declared ${declaredCount}, actual ${bodyKeys.size})`);
    }
    if (declaredCount > opts.maxWindow) flag("A", `declared window ${declaredCount} exceeds --max-window ${opts.maxWindow}`);
    let bottomSuffix = null;
    if (opts.requireBottomSuffix) {
      const total = Number(field(decl.line, "totalCount") ?? -1);
      const end = Number(field(decl.line, "windowEnd") ?? -1);
      const start = Number(field(decl.line, "windowStart") ?? -1);
      const idx = [...cardKeys].map((k) => Number(/message:(\d+):/.exec(k)?.[1] ?? -1)).sort((a, b) => a - b);
      const contiguous = idx.every((v, j) => j === 0 || v === idx[j - 1] + 1);
      bottomSuffix = contiguous && end === total - 1 && idx[0] === start && idx[idx.length - 1] === end;
      if (!bottomSuffix) flag("A", "ordinary-#all bottom contiguous-suffix rule violated", { windowStart: start, windowEnd: end, totalCount: total });
    }
    windowReport = {
      totalCount: Number(field(decl.line, "totalCount") ?? -1),
      windowStart: Number(field(decl.line, "windowStart") ?? -1),
      windowEnd: Number(field(decl.line, "windowEnd") ?? -1),
      anchor: field(decl.line, "anchor") ?? "",
      windowGeneration: field(decl.line, "windowGeneration") ?? "",
      declaredKeyCount: declaredCount,
      cardKeysExact: cardExact, bodyKeysExact: bodyExact,
      withinMaxWindow: declaredCount <= opts.maxWindow,
      ordinaryAllContiguousBottomSuffix: bottomSuffix,
    };
  }
  for (const [k, rec] of rowByScopeKey) {
    const sp = k.indexOf(" ");
    const scope = k.slice(0, sp), key = k.slice(sp + 1);
    const id8 = /message:\d+:(\S+)/.exec(key)?.[1] ?? key;
    const budget = opts.tagged.has(key) || opts.tagged.has(id8) ? opts.maxTaggedRecompose : opts.maxRecompose;
    if (rec.maxN > budget) flag("A", "row over recomposition budget", { scope, key, recompositions: rec.maxN, budget });
    if (rec.instances > budget) flag("A", `row RECREATED (fresh n=1 x${rec.instances})`, { scope, key, instances: rec.instances, budget });
  }
} else if (committed.length) {
  flag("A", "no thread_row_recompose markers at all — row instrumentation missing");
}

// ── report (§7 shapes) ───────────────────────────────────────────────────────
const aViolations = violations.filter((v) => v.contract === "A");
const bViolations = violations.filter((v) => v.contract === "B");
const stagesOut = {};
for (const [st, r] of stages) {
  stagesOut[st] = { applicable: r.applicable, admitted: r.admitted, launched: r.launched, terminal: r.terminal, terminalResult: r.terminalResult, skipped: r.skipped };
}
const taggedVisibleRow = opts.tagged.size
  ? { applicable: true, keys: [...opts.tagged] }
  : { applicable: false, result: "n/a", reason: "seeded_fixture_has_no_visible_tagged_row" };

const report = {
  verdict: violations.length ? "FAIL" : "PASS",
  contractA: {
    contract: "A_card_stability",
    verdict: aViolations.length ? "FAIL" : "PASS",
    window: windowReport,
    card: { missingScope: scopesSeen.has("card") ? 0 : 1 },
    body: { missingScope: scopesSeen.has("body") ? 0 : 1 },
    taggedVisibleRow,
    violations: aViolations,
  },
  contractB: {
    contract: "B_render_commit_aux",
    verdict: bViolations.length ? "FAIL" : "PASS",
    token: committed[0] ? committed[0].token : null,
    draw: {
      observedCount: observed.length,
      falseVisibleObserved: falseVisible.length,
      committed: committed.length,
      dropCount: drops.length,
      uiCriticalParseViolations,
    },
    stages: stagesOut,
    legacyReadySeen: ev.some((e) => e.kind === "legacyReady"),
    violations: bViolations,
  },
};
console.log(JSON.stringify(report, null, 2));
process.exit(violations.length ? 1 : 0);
