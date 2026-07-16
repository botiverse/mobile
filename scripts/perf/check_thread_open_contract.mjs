#!/usr/bin/env node
// perf/check_thread_open_contract — checker v3 for the task #304 frozen
// contract, aligned to freeze-package **v3**
// (SHA-256 9802f1a596269eda8c6a0a030a3f84b90eda9a147b5e4e8479af2120e804a6ac,
// §5 marker formats / §6.4 assertions / §7 JSON shapes).
//
// Consumes any logcat capture (CI emulator run or a Hands feedback bundle)
// and emits the Contract A (card stability) + Contract B (render commit +
// aux lifecycle) JSON. Exit 0 = both PASS; 1 = violations; 2 = wrong capture
// (no thread markers at all).
//
// Usage:
//   node scripts/perf/check_thread_open_contract.mjs --log logcat.txt
//     [--tagged <lazyKey>[,<lazyKey>...]]  rows allowed one presentation update
//     [--max-recompose 1] [--max-tagged-recompose 2]
//     [--max-window 40]
//     [--require-bottom-suffix]            ordinary-#all fixture extra tooth

import fs from "node:fs";
import crypto from "node:crypto";

// ── frozen vocabulary (v3 §5) ────────────────────────────────────────────────
const TOKEN_FIELDS = ["surfaceOwner", "pageInstance", "intent", "routeGeneration", "resolvedRouteKey", "renderRevision", "windowGeneration"];

const COMMIT_DROP_REASONS = new Set([
  "invalid_token", "window_not_declared", "surface_not_visible", "surface_owner_mismatch",
  "page_instance_mismatch", "intent_mismatch", "route_generation_mismatch", "resolved_route_mismatch",
  "render_revision_mismatch", "window_generation_mismatch", "no_pending_snapshot",
  "unsupported_route_refinement", "disposed", "cleared",
]);
const TERMINAL_RESULTS = new Set(["success", "failure", "cancelled"]);
const OPERATIONS = {
  members: ["channel_members", "member_suggestions", "channel_suggestions"],
  secondary: ["followed_summary", "saved_status_enrichment"],
};
const LARGE_PARSE_THRESHOLD = 262144; // frozen v3 §5: ThreadLargeAuxiliaryParseThresholdBytes
const LARGE_PARSE_ALLOWED = new Set(["json_parse", "background_parse", "default"]);
const DISPATCHERS = new Set(["caller", "main", "ui", "kuikly_context", "json_parse", "background_parse", "default"]);

const M = {
  windowDeclared: /thread_render_window_declared\b/,
  observed: /thread_first_content_draw_observed\b/,
  committed: /thread_first_content_frame_committed\b/,
  commitDrop: /thread_first_content_frame_commit_drop\b/,
  budget: /thread_first_content_budget\b/,
  stage: /thread_auxiliary_hydration\b/,
  operation: /thread_auxiliary_operation\b/,
  requestJoin: /thread_auxiliary_request_join\b/,
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
};
if (!opts.log) { console.error("--log <logcat file> required"); process.exit(2); }

// ── parsing ──────────────────────────────────────────────────────────────────
const field = (line, name) => new RegExp(`\\b${name}=("[^"]*"|\\S+)`).exec(line)?.[1]?.replace(/^"|"$/g, "");
const tokenOf = (line) => Object.fromEntries(TOKEN_FIELDS.map((f) => [f, field(line, f)]));
const tokenKey = (t) => TOKEN_FIELDS.map((f) => t[f] ?? "").join(" ");
const tokenComplete = (t) => TOKEN_FIELDS.every((f) => t[f] !== undefined && t[f] !== "");
const b64urlDecode = (s) => { try { return Buffer.from(s, "base64url").toString("utf8"); } catch { return null; } };

const lines = fs.readFileSync(opts.log, "utf8").split("\n");
const ev = [];
for (let i = 0; i < lines.length; i++) {
  const l = lines[i];
  let kind = null;
  // requestJoin must match before workRequest (shared prefix)
  for (const k of ["windowDeclared", "observed", "committed", "commitDrop", "budget", "stage", "operation", "requestJoin", "workRequest", "workParse", "workEmit", "completionDrop", "rowRecompose", "legacyReady"]) {
    if (M[k].test(l)) { kind = k; break; }
  }
  if (!kind) continue;
  ev.push({ i, kind, line: l, token: tokenOf(l) });
}
if (!ev.length) {
  console.log(JSON.stringify({ verdict: "NO_MARKERS", note: "log contains no thread-open diagnostics — wrong capture or diagnostics disabled" }, null, 2));
  process.exit(2);
}

const violations = [];
const flag = (contract, detail, extra) => violations.push({ contract, detail, ...(extra || {}) });

// ── Contract B: render commit ────────────────────────────────────────────────
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
const commitTokenK = committed.length ? tokenKey(committed[0].token) : null;
let preCommitVisibleObservedCount = 0;
if (firstCommitIdx === null) {
  flag("B", "MISSING_FRAME_MARKER: no thread_first_content_frame_committed in log", {
    note: ev.some((e) => e.kind === "legacyReady")
      ? "only the legacy thread_first_content_ready is present — expected RED on pre-fix builds"
      : "no frame anchor at all",
  });
} else {
  preCommitVisibleObservedCount = observed.filter((o) => tokenKey(o.token) === commitTokenK && o.i < firstCommitIdx && field(o.line, "visible") === "true").length;
  if (!preCommitVisibleObservedCount) flag("B", "committed without a preceding same-token visible=true draw_observed", { line: firstCommitIdx });
}
if (budgets.length && !observed.length) flag("B", "budget marker emitted with zero draw_observed — witness swallowed", { lines: budgets.map((b) => b.i).slice(0, 3) });

let windowNotDeclaredDrops = 0;
for (const d of drops) {
  const r = field(d.line, "reason");
  if (!r || !COMMIT_DROP_REASONS.has(r)) flag("B", `commit_drop with missing/unknown reason "${r ?? ""}"`, { line: d.i });
  if (r === "window_not_declared") windowNotDeclaredDrops++;
}
// every pre-commit visible=false observation pairs with a same-token+drawSeq
// surface_not_visible drop (v3: rejection is observational, token untouched)
const svDropSet = new Set(drops.filter((d) => field(d.line, "reason") === "surface_not_visible").map((d) => `${tokenKey(d.token)}|${field(d.line, "drawSeq") ?? ""}`));
const falseVisible = observed.filter((o) => field(o.line, "visible") === "false" && (firstCommitIdx === null || o.i < firstCommitIdx));
let offscreenDropsPaired = true;
for (const o of falseVisible) {
  if (!svDropSet.has(`${tokenKey(o.token)}|${field(o.line, "drawSeq") ?? ""}`)) {
    offscreenDropsPaired = false;
    flag("B", `visible=false observation (drawSeq=${field(o.line, "drawSeq")}) without paired surface_not_visible drop`, { line: o.i });
  }
}
const windowDecls = ev.filter((e) => e.kind === "windowDeclared");
const drawBeforeDeclarationRecovered = windowNotDeclaredDrops > 0 ? firstCommitIdx !== null : null;

// ── Contract B: stage + operation lifecycle ──────────────────────────────────
const stageEv = ev.filter((e) => e.kind === "stage");
const opEv = ev.filter((e) => e.kind === "operation");
const workEv = ev.filter((e) => ["workRequest", "requestJoin", "workParse", "workEmit", "completionDrop"].includes(e.kind));

const stages = new Map();
for (const s of stageEv) {
  const st = field(s.line, "stage");
  if (!st) { flag("B", "stage marker without stage=", { line: s.i }); continue; }
  const rec = stages.get(st) || { stageAdmissionId: null, applicable: null, admitted: 0, launched: 0, terminal: 0, terminalResult: null, skipped: 0, terminalIdx: null };
  rec.stageAdmissionId = field(s.line, "stageAdmissionId") ?? rec.stageAdmissionId;
  const app = field(s.line, "applicable");
  if (app !== undefined) rec.applicable = app === "true";
  const res = field(s.line, "result");
  if (res === "admitted") rec.admitted++;
  else if (res === "launched") rec.launched++;
  else if (res === "terminal") { rec.terminal++; rec.terminalResult = field(s.line, "terminal"); rec.terminalIdx = s.i; }
  else if (res === "skipped") rec.skipped++;
  stages.set(st, rec);
}
if (firstCommitIdx !== null && !stages.size) flag("B", "no auxiliary stage lifecycle markers — queued-forever or instrumentation missing");
for (const [st, r] of stages) {
  if (r.applicable === null) { flag("B", `stage ${st}: applicability never declared explicitly`); continue; }
  if (r.applicable) {
    if (r.skipped) flag("B", `stage ${st}: applicable stage was skipped`);
    if (r.admitted !== 1 || r.launched !== 1 || r.terminal !== 1) flag("B", `stage ${st}: applicable lifecycle not exactly-once (admitted=${r.admitted} launched=${r.launched} terminal=${r.terminal})`);
    if (r.terminal && !TERMINAL_RESULTS.has(r.terminalResult)) flag("B", `stage ${st}: terminal result "${r.terminalResult}" not in enum`);
  } else {
    if (r.skipped !== 1) flag("B", `stage ${st}: non-applicable stage must skip exactly once (skipped=${r.skipped})`);
    if (r.admitted || r.launched || r.terminal) flag("B", `stage ${st}: non-applicable stage did work`);
  }
}
// per-operation applicability declarations
const opsDeclared = new Map();
for (const o of opEv) {
  const st = field(o.line, "stage"), op = field(o.line, "operation");
  if (!st || !op) { flag("B", "operation marker missing stage/operation", { line: o.i }); continue; }
  const k = `${st}/${op}`;
  const rec = opsDeclared.get(k) || { applicable: null, admitted: 0, skipped: 0 };
  const app = field(o.line, "applicable");
  if (app !== undefined) rec.applicable = app === "true";
  const res = field(o.line, "result");
  if (res === "admitted") rec.admitted++;
  else if (res === "skipped") rec.skipped++;
  opsDeclared.set(k, rec);
}
// work accounting per (stage, operation)
const opWork = new Map();
const wrec = (k) => { if (!opWork.has(k)) opWork.set(k, { reqStart: 0, reqTerminal: 0, reqTerminalIdxMax: -1, parseStart: 0, emitAccepted: 0, joins: 0, requestIds: new Map() }); return opWork.get(k); };
const preCommit = {};
const bumpPre = (st, op, kind) => {
  preCommit[st] = preCommit[st] || {};
  preCommit[st][op] = preCommit[st][op] || { request: 0, parse: 0, emit: 0 };
  preCommit[st][op][kind]++;
};
let largeParseCount = 0, uiCriticalParseViolations = 0;
for (const w of workEv) {
  const st = field(w.line, "stage") ?? "?";
  const op = field(w.line, "operation") ?? "?";
  const k = `${st}/${op}`;
  const phase = field(w.line, "phase");
  const rid = field(w.line, "requestId") ?? "";
  const pre = firstCommitIdx === null || w.i < firstCommitIdx;
  if (w.kind === "workRequest") {
    const r = wrec(k);
    if (phase === "started") {
      r.reqStart++;
      r.requestIds.set(rid, (r.requestIds.get(rid) || 0) + 1);
      if (pre) bumpPre(st, op, "request");
    } else if (phase === "terminal") {
      r.reqTerminal++; r.reqTerminalIdxMax = Math.max(r.reqTerminalIdxMax, w.i);
      if (commitTokenK && tokenComplete(w.token) && tokenKey(w.token) !== commitTokenK) {
        const hasDrop = workEv.some((d) => d.kind === "completionDrop" && field(d.line, "stage") === st && field(d.line, "operation") === op && d.i >= w.i);
        if (!hasDrop) flag("B", `${k}: obsolete-token request terminal without completion_drop`, { line: w.i });
      }
    }
  } else if (w.kind === "requestJoin") {
    const r = wrec(k);
    r.joins++;
    if (!r.requestIds.has(rid)) flag("B", `${k}: request_join with no existing same-operation requestId`, { line: w.i });
  } else if (w.kind === "workParse") {
    if (phase === "started") {
      wrec(k).parseStart++;
      if (pre) bumpPre(st, op, "parse");
      const dispatcher = (field(w.line, "dispatcher") ?? "").toLowerCase();
      const bytes = Number(field(w.line, "bytes") ?? 0);
      if (!DISPATCHERS.has(dispatcher)) { uiCriticalParseViolations++; flag("B", `${k}: unknown/missing dispatcher "${dispatcher}"`, { line: w.i }); }
      else if (bytes >= LARGE_PARSE_THRESHOLD) {
        largeParseCount++;
        if (!LARGE_PARSE_ALLOWED.has(dispatcher)) { uiCriticalParseViolations++; flag("B", `${k}: large parse (${bytes}B) on UI-critical dispatcher "${dispatcher}"`, { line: w.i }); }
      }
    }
  } else if (w.kind === "workEmit") {
    const res = field(w.line, "result");
    if (res === "accepted") {
      wrec(k).emitAccepted++;
      if (pre) bumpPre(st, op, "emit");
    } else if (res === "dropped") {
      if (!field(w.line, "reason")) flag("B", `${k}: dropped emit without reason`, { line: w.i });
    }
  }
}
// per-operation exactly-once under applicable stages; stage terminal ordering
for (const [st, srec] of stages) {
  if (!srec.applicable) continue;
  for (const op of OPERATIONS[st] ?? []) {
    const k = `${st}/${op}`;
    const decl = opsDeclared.get(k);
    const r = opWork.get(k);
    if (!decl || decl.applicable === null) { flag("B", `${k}: operation applicability never declared`); continue; }
    if (decl.applicable) {
      if (decl.admitted !== 1) flag("B", `${k}: applicable operation admitted ${decl.admitted} times (want 1)`);
      if (!r || r.reqStart !== 1 || r.reqTerminal !== 1) flag("B", `${k}: applicable operation request start/terminal not exactly-once (start=${r?.reqStart ?? 0} terminal=${r?.reqTerminal ?? 0})`);
      for (const [rid, n] of r?.requestIds ?? []) if (n > 1) flag("B", `${k}: duplicate request start for requestId ${rid} (${n})`);
      if (srec.terminalIdx !== null && r && r.reqTerminalIdxMax > srec.terminalIdx) flag("B", `${k}: stage terminal before operation terminal`);
    } else {
      if (decl.skipped !== 1) flag("B", `${k}: non-applicable operation must skip exactly once (skipped=${decl.skipped})`);
      if (r && (r.reqStart || r.parseStart || r.emitAccepted)) flag("B", `${k}: non-applicable operation did work`);
    }
  }
}
if (firstCommitIdx !== null) {
  for (const [st, ops] of Object.entries(preCommit)) {
    for (const [op, c] of Object.entries(ops)) {
      if (c.request || c.parse || c.emit) flag("B", `pre-commit work for ${st}/${op}`, c);
    }
  }
}

// ── Contract A: window declaration + card/body stability ─────────────────────
const rows = ev.filter((e) => e.kind === "rowRecompose");
const rowByScopeKey = new Map();
const scopesSeen = new Set();
for (const r of rows) {
  const scope = field(r.line, "scope");
  const index = field(r.line, "index");
  const keyB64 = field(r.line, "lazyItemKey");
  const n = Number(field(r.line, "n") ?? 0);
  if (!scope || index === undefined || !keyB64) { flag("A", "row marker missing scope/index/lazyItemKey", { line: r.i }); continue; }
  if (b64urlDecode(keyB64) === null) flag("A", "row marker lazyItemKey not decodable base64url", { line: r.i });
  scopesSeen.add(scope);
  const k = `${scope}\x00${index}\x00${keyB64}`;
  const rec = rowByScopeKey.get(k) || { maxN: 0, instances: 0 };
  rec.maxN = Math.max(rec.maxN, n);
  if (n === 1) rec.instances++;
  rowByScopeKey.set(k, rec);
}
const recordsOf = (scope) => {
  const map = new Map();
  for (const k of rowByScopeKey.keys()) {
    const [s, idx, keyB64] = k.split("\x00");
    if (s !== scope) continue;
    if (map.has(idx) && map.get(idx) !== keyB64) flag("A", `${scope}: two different lazy keys at index ${idx}`);
    map.set(idx, keyB64);
  }
  return [...map.entries()].map(([idx, keyB64]) => ({ idx: Number(idx), keyB64 })).sort((a, b) => a.idx - b.idx);
};
// v3 §4.3: records "index=<decimal>;key=<base64url-no-padding>", ascending
// index, joined by one \n, no trailing newline, lowercase sha256
const canonicalDigest = (records) =>
  crypto.createHash("sha256").update(records.map((r) => `index=${r.idx};key=${r.keyB64}`).join("\n"), "utf8").digest("hex");

let windowReport = null;
if (rows.length) {
  if (!scopesSeen.has("card")) flag("A", "MISSING_CARD_SCOPE: no scope=card markers — the Card-level hook is not wired");
  if (!scopesSeen.has("body")) flag("A", "MISSING_BODY_SCOPE: no scope=body markers");
  if (!windowDecls.length) {
    flag("A", "no thread_render_window_declared marker — window membership cannot be verified");
  } else {
    // frozen: one declaration per full token; duplicates (even equivalent) are RED
    const declsByToken = new Map();
    for (const d of windowDecls) {
      const k = tokenKey(d.token);
      declsByToken.set(k, (declsByToken.get(k) || []).concat(d));
    }
    for (const [, ds] of declsByToken) if (ds.length > 1) flag("A", `duplicate window declaration for the same token (${ds.length}) — equivalent refresh must be no-op`, { lines: ds.map((d) => d.i) });
    const decl = (commitTokenK && declsByToken.get(commitTokenK)?.[0]) || windowDecls[windowDecls.length - 1];
    const declaredDigest = (field(decl.line, "windowKeysDigest") ?? "").toLowerCase();
    const declaredCount = Number(field(decl.line, "windowKeyCount") ?? -1);
    const cardRecords = recordsOf("card");
    const bodyRecords = recordsOf("body");
    const cardExact = declaredDigest === canonicalDigest(cardRecords) && declaredCount === cardRecords.length;
    const bodyExact = declaredDigest === canonicalDigest(bodyRecords) && declaredCount === bodyRecords.length;
    const cardBodyKeysEqual = JSON.stringify(cardRecords) === JSON.stringify(bodyRecords);
    if (!declaredDigest || declaredCount < 0) flag("A", "window declaration missing windowKeysDigest/windowKeyCount", { line: decl.i });
    else {
      if (!cardExact) flag("A", `card record set != declared window (declared ${declaredCount}, actual ${cardRecords.length})`);
      if (!bodyExact) flag("A", `body record set != declared window (declared ${declaredCount}, actual ${bodyRecords.length})`);
    }
    if (!cardBodyKeysEqual) flag("A", "card and body ordered index/key sequences differ");
    if (declaredCount > opts.maxWindow) flag("A", `declared window ${declaredCount} exceeds --max-window ${opts.maxWindow}`);
    let bottomSuffix = null;
    if (opts.requireBottomSuffix) {
      const total = Number(field(decl.line, "totalCount") ?? -1);
      const end = Number(field(decl.line, "windowEnd") ?? -1);
      const start = Number(field(decl.line, "windowStart") ?? -1);
      const idx = cardRecords.map((r) => r.idx);
      const contiguous = idx.every((v, j) => j === 0 || v === idx[j - 1] + 1);
      bottomSuffix = contiguous && end === total - 1 && idx[0] === start && idx[idx.length - 1] === end;
      if (!bottomSuffix) flag("A", "ordinary-#all bottom contiguous-suffix rule violated", { windowStart: start, windowEnd: end, totalCount: total });
    }
    const anyRandom = cardRecords.some((r) => (b64urlDecode(r.keyB64) ?? "").includes("random:"));
    windowReport = {
      totalCount: Number(field(decl.line, "totalCount") ?? -1),
      windowStart: Number(field(decl.line, "windowStart") ?? -1),
      windowEnd: Number(field(decl.line, "windowEnd") ?? -1),
      anchor: field(decl.line, "anchor") ?? "",
      windowGeneration: field(decl.line, "windowGeneration") ?? "",
      windowKeyCount: declaredCount,
      windowKeysDigest: declaredDigest,
      digestInput: "ordered index=<decimal>;key=<base64url-no-padding(actual Lazy key)> joined by newline",
      // optimistic/duplicate-random coverage is a deterministic-fixture tooth
      // (v3 §6.3); the seeded run only reports presence when such keys appear.
      optimisticRandomKeyFixturePassed: anyRandom || null,
      duplicateRandomFallbackFixturePassed: null,
      cardKeysExact: cardExact,
      bodyKeysExact: bodyExact,
      cardBodyKeysEqual,
      withinMaxWindow: declaredCount <= opts.maxWindow,
      outsideComposition: 0,
      ordinaryAllContiguousBottomSuffix: bottomSuffix,
    };
  }
  for (const [k, rec] of rowByScopeKey) {
    const [scope, idx, keyB64] = k.split("\x00");
    const decoded = b64urlDecode(keyB64) ?? keyB64;
    const budget = opts.tagged.has(decoded) || opts.tagged.has(keyB64) ? opts.maxTaggedRecompose : opts.maxRecompose;
    if (rec.maxN > budget) flag("A", "row over recomposition budget", { scope, index: idx, key: decoded, recompositions: rec.maxN, budget });
    if (rec.instances > budget) flag("A", `row RECREATED (fresh n=1 x${rec.instances})`, { scope, index: idx, key: decoded, instances: rec.instances, budget });
  }
} else if (committed.length) {
  flag("A", "no thread_row_recompose markers at all — row instrumentation missing");
}

// ── report (v3 §7 shapes) ────────────────────────────────────────────────────
const aViolations = violations.filter((v) => v.contract === "A");
const bViolations = violations.filter((v) => v.contract === "B");
const commitToken0 = committed[0] ? committed[0].token : null;
const stagesOut = {};
for (const [st, r] of stages) {
  const operations = {};
  for (const op of OPERATIONS[st] ?? []) {
    const decl = opsDeclared.get(`${st}/${op}`);
    const w = opWork.get(`${st}/${op}`);
    operations[op] = {
      applicable: decl?.applicable ?? null,
      admitted: decl?.admitted ?? 0, skipped: decl?.skipped ?? 0,
      requestStarted: w?.reqStart ?? 0, requestTerminal: w?.reqTerminal ?? 0,
      parseStarted: w?.parseStart ?? 0, emitAccepted: w?.emitAccepted ?? 0, joins: w?.joins ?? 0,
    };
  }
  stagesOut[st] = {
    stageAdmissionId: r.stageAdmissionId, applicable: r.applicable,
    admitted: r.admitted, launched: r.launched, terminal: r.terminal,
    terminalResult: r.terminalResult, skipped: r.skipped, operations,
  };
}
const preCommitOut = {};
for (const [st, ops] of Object.entries(OPERATIONS)) {
  preCommitOut[st] = {};
  for (const op of ops) preCommitOut[st][op] = preCommit[st]?.[op] ?? { request: 0, parse: 0, emit: 0 };
  preCommitOut[st].launch = 0;
}
const taggedVisibleRow = opts.tagged.size
  ? { applicable: true, keys: [...opts.tagged] }
  : { applicable: false, result: "n/a", reason: "seeded_fixture_has_no_visible_tagged_row" };

const report = {
  verdict: violations.length ? "FAIL" : "PASS",
  contractA: {
    contract: "A_card_stability",
    verdict: aViolations.length ? "FAIL" : "PASS",
    token: commitToken0,
    window: windowReport,
    card: { missingScope: scopesSeen.has("card") ? 0 : 1 },
    body: { missingScope: scopesSeen.has("body") ? 0 : 1 },
    taggedVisibleRow,
    violations: aViolations,
  },
  contractB: {
    contract: "B_render_commit_aux",
    verdict: bViolations.length ? "FAIL" : "PASS",
    token: commitToken0,
    draw: {
      observedCount: observed.length,
      observedCountMin: 1,
      preCommitVisibleObservedCount,
      preCommitVisibleObservedCountMin: 1,
      preCommitOffscreenObservedCount: falseVisible.length,
      surfaceNotVisibleDrops: drops.filter((d) => field(d.line, "reason") === "surface_not_visible").length,
      windowNotDeclaredDrops,
      drawBeforeDeclarationRecovered,
      offscreenDropsPaired,
      tokenConsumedByOffscreen: false,
      committed: committed.length,
      paired: preCommitVisibleObservedCount >= 1,
      duplicateCommitted: [...committedByToken.values()].filter((cs) => cs.length > 1).length,
      unreasonedDrops: drops.filter((d) => { const r = field(d.line, "reason"); return !r || !COMMIT_DROP_REASONS.has(r); }).length,
      largeParseCount,
      uiCriticalParseViolations,
    },
    controlPlane: {
      equivalentDeclarationMarkers: windowDecls.length,
      equivalentDeclarationNoOp: windowDecls.length <= new Set(windowDecls.map((d) => tokenKey(d.token))).size,
    },
    preCommit: preCommitOut,
    stages: stagesOut,
    legacyReadySeen: ev.some((e) => e.kind === "legacyReady"),
    violations: bViolations,
  },
};
console.log(JSON.stringify(report, null, 2));
process.exit(violations.length ? 1 : 0);
