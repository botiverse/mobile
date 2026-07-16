#!/usr/bin/env node
// perf/check_thread_open_contract — checker for the task #304 frozen contract
// v3 (SHA-256 9802f1a596269eda6a0…: see the freeze package for §5 marker
// formats, §6.4 assertions, §7 JSON shapes; this file implements them 1:1).
//
// Reads any logcat capture (CI emulator run or Hands feedback bundle) and
// emits the Contract A (card stability) + Contract B (render commit + aux
// lifecycle) JSON. Exit 0 = PASS; 1 = violations; 2 = wrong capture.
//
// Seeded-run boundary (frozen): sub-teeth that a log cannot prove are
// reported as null with an explicit reason and listed in `notProven` — they
// never count toward PASS proof, and the aggregate gate needs the same-exact
// owner test artifacts for them. Unknowns are never written as 0.
//
// Usage:
//   node scripts/perf/check_thread_open_contract.mjs --log logcat.txt
//     [--tagged <lazyKey>[,...]] [--max-recompose 1] [--max-tagged-recompose 2]
//     [--max-window 40] [--require-bottom-suffix]

import fs from "node:fs";
import crypto from "node:crypto";

// ── frozen vocabulary (v3 §5) ────────────────────────────────────────────────
const TOKEN_FIELDS = ["surfaceOwner", "pageInstance", "intent", "routeGeneration", "resolvedRouteKey", "renderRevision", "windowGeneration"];
const STAGES = ["members", "secondary"];
const OPERATIONS = {
  members: ["channel_members", "member_suggestions", "channel_suggestions"],
  secondary: ["followed_summary", "saved_status_enrichment"],
};
const COMMIT_DROP_REASONS = new Set([
  "invalid_token", "window_not_declared", "surface_not_visible", "surface_owner_mismatch",
  "page_instance_mismatch", "intent_mismatch", "route_generation_mismatch", "resolved_route_mismatch",
  "render_revision_mismatch", "window_generation_mismatch", "no_pending_snapshot",
  "unsupported_route_refinement", "disposed", "cleared",
]);
const STAGE_REASONS = new Set([
  "resolved_snapshot", "no_renderable_content", "surface_not_supported", "reply_thread_followed_not_applicable",
  "tab_not_chat", "composer_not_mounted", "operation_not_demanded", "cache_sufficient",
  "route_refined", "route_switched", "disposed", "cleared", "stale_token", "late_completion",
  "request_failed", "parse_failed", "cancelled",
]);
const OP_REASONS = new Set([
  "resolved_snapshot", "composer_member_demand", "composer_channel_demand", "no_renderable_content",
  "surface_not_supported", "reply_thread_followed_not_applicable", "tab_not_chat", "composer_not_mounted",
  "operation_not_demanded", "cache_sufficient",
]);
const WORK_REASONS = new Set([
  "resolved_snapshot", "request_failed", "parse_failed", "cancelled", "route_switched",
  "disposed", "cleared", "stale_token", "late_completion", "projection_unchanged", "coalesced",
]);
const TERMINAL_RESULTS = new Set(["success", "failure", "cancelled"]);
const ANCHORS = new Set(["bottom", "focused", "cached", "empty", "non_chat"]);
const DECL_REASONS = new Set(["initial", "equivalent_refresh", "changed_refresh", "realtime", "route_refined"]);
const LARGE_PARSE_THRESHOLD = 262144;
const LARGE_PARSE_ALLOWED = ["json_parse", "background_parse", "default"];
const UI_CRITICAL_DISPATCHERS = ["caller", "main", "ui", "kuikly_context"];
const DISPATCHERS = new Set([...LARGE_PARSE_ALLOWED, ...UI_CRITICAL_DISPATCHERS]);

const MARKS = [
  ["windowDeclared", /thread_render_window_declared\b/],
  ["observed", /thread_first_content_draw_observed\b/],
  ["committed", /thread_first_content_frame_committed\b/],
  ["commitDrop", /thread_first_content_frame_commit_drop\b/],
  ["budget", /thread_first_content_budget\b/],
  ["stage", /thread_auxiliary_hydration\b/],
  ["operation", /thread_auxiliary_operation\b/],
  ["requestJoin", /thread_auxiliary_request_join\b/],
  ["workRequest", /thread_auxiliary_request\b/],
  ["workParse", /thread_auxiliary_parse\b/],
  ["workEmit", /thread_auxiliary_emit\b/],
  ["completionDrop", /thread_auxiliary_completion_drop\b/],
  ["rowRecompose", /thread_row_recompose\b/],
  ["legacyReady", /thread_first_content_ready\b/],
];

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

// ── helpers ──────────────────────────────────────────────────────────────────
const field = (line, name) => new RegExp(`\\b${name}=("[^"]*"|\\S+)`).exec(line)?.[1]?.replace(/^"|"$/g, "");
const tokenOf = (line) => Object.fromEntries(TOKEN_FIELDS.map((f) => [f, field(line, f)]));
const tokenKey = (t) => TOKEN_FIELDS.map((f) => t[f] ?? "").join("\x1f");
const tokenComplete = (t) => TOKEN_FIELDS.every((f) => t[f] !== undefined && t[f] !== "");
// canonical base64url-no-padding: decodes AND re-encodes to the same string
const b64urlCanonical = (s) => {
  if (!s || /[=+/]/.test(s)) return null;
  try {
    const buf = Buffer.from(s, "base64url");
    return buf.toString("base64url") === s ? buf.toString("utf8") : null;
  } catch { return null; }
};
const posInt = (v) => v !== undefined && /^\d+$/.test(v) && Number(v) > 0;
const nonNegInt = (v) => v !== undefined && /^\d+$/.test(v);

const lines = fs.readFileSync(opts.log, "utf8").split("\n");
const ev = [];
for (let i = 0; i < lines.length; i++) {
  const l = lines[i];
  let kind = null;
  for (const [k, re] of MARKS) if (re.test(l)) { kind = k; break; }
  if (!kind) continue;
  ev.push({ i, kind, line: l, token: tokenOf(l) });
}
if (!ev.length) {
  console.log(JSON.stringify({ verdict: "NO_MARKERS", note: "log contains no thread-open diagnostics — wrong capture or diagnostics disabled" }, null, 2));
  process.exit(2);
}

const violations = [];
const flag = (contract, detail, extra) => violations.push({ contract, detail, ...(extra || {}) });

// ── required-field validation for every marker (v3: seven fields mandatory
//    on ALL token-bearing markers; kind-specific fields on top) ───────────────
const REQUIRED = {
  windowDeclared: (e) => {
    const errs = [];
    for (const f of ["totalCount", "windowStart", "windowEnd", "windowKeyCount", "windowKeysDigest"]) if (field(e.line, f) === undefined) errs.push(f);
    if (!ANCHORS.has(field(e.line, "anchor") ?? "")) errs.push("anchor");
    if (!DECL_REASONS.has(field(e.line, "reason") ?? "")) errs.push("reason");
    return errs;
  },
  observed: (e) => {
    const errs = [];
    if (!["true", "false"].includes(field(e.line, "visible") ?? "")) errs.push("visible");
    if (!posInt(field(e.line, "drawSeq"))) errs.push("drawSeq");
    return errs;
  },
  committed: (e) => (field(e.line, "threadId") === undefined ? ["threadId"] : []),
  commitDrop: (e) => {
    const errs = [];
    const r = field(e.line, "reason");
    if (!r || !COMMIT_DROP_REASONS.has(r)) errs.push("reason");
    if ((r === "surface_not_visible" || r === "window_not_declared") && !posInt(field(e.line, "drawSeq"))) errs.push("drawSeq");
    if (field(e.line, "currentSurfaceOwner") === undefined) errs.push("currentSurfaceOwner");
    return errs;
  },
  stage: (e) => {
    const errs = [];
    if (!STAGES.includes(field(e.line, "stage") ?? "")) errs.push("stage");
    if (!field(e.line, "stageAdmissionId")) errs.push("stageAdmissionId");
    if (!["true", "false"].includes(field(e.line, "applicable") ?? "")) errs.push("applicable");
    const res = field(e.line, "result");
    if (!["admitted", "launched", "terminal", "skipped"].includes(res ?? "")) errs.push("result");
    if (res === "terminal" && !TERMINAL_RESULTS.has(field(e.line, "terminal") ?? "")) errs.push("terminal");
    if (!STAGE_REASONS.has(field(e.line, "reason") ?? "")) errs.push("reason");
    return errs;
  },
  operation: (e) => {
    const errs = [];
    const st = field(e.line, "stage");
    if (!STAGES.includes(st ?? "")) errs.push("stage");
    else if (!(OPERATIONS[st] ?? []).includes(field(e.line, "operation") ?? "")) errs.push("operation");
    if (!field(e.line, "stageAdmissionId")) errs.push("stageAdmissionId");
    if (!["true", "false"].includes(field(e.line, "applicable") ?? "")) errs.push("applicable");
    if (!["admitted", "skipped"].includes(field(e.line, "result") ?? "")) errs.push("result");
    if (!OP_REASONS.has(field(e.line, "reason") ?? "")) errs.push("reason");
    return errs;
  },
  work: (e) => {
    const errs = [];
    const st = field(e.line, "stage");
    if (!STAGES.includes(st ?? "")) errs.push("stage");
    else if (!(OPERATIONS[st] ?? []).includes(field(e.line, "operation") ?? "")) errs.push("operation");
    if (!field(e.line, "stageAdmissionId")) errs.push("stageAdmissionId");
    if (!field(e.line, "requestId")) errs.push("requestId");
    return errs;
  },
  rowRecompose: (e) => {
    const errs = [];
    if (!["card", "body"].includes(field(e.line, "scope") ?? "")) errs.push("scope");
    if (!nonNegInt(field(e.line, "index"))) errs.push("index");
    if (b64urlCanonical(field(e.line, "lazyItemKey")) === null) errs.push("lazyItemKey(canonical-base64url)");
    if (!posInt(field(e.line, "n"))) errs.push("n");
    if (field(e.line, "presentation") === undefined) errs.push("presentation");
    return errs;
  },
};
for (const e of ev) {
  if (e.kind === "budget" || e.kind === "legacyReady") continue;
  if (!tokenComplete(e.token)) { flag(e.kind === "rowRecompose" || e.kind === "windowDeclared" ? "A" : "B", `${e.kind} marker missing token fields`, { line: e.i }); continue; }
  const checker = REQUIRED[e.kind] ?? (["workRequest", "workParse", "workEmit", "requestJoin", "completionDrop"].includes(e.kind) ? REQUIRED.work : null);
  if (checker) {
    const errs = checker(e);
    if (errs.length) flag(e.kind === "rowRecompose" || e.kind === "windowDeclared" ? "A" : "B", `${e.kind} marker invalid/missing fields: ${errs.join(",")}`, { line: e.i });
  }
  if (e.kind === "workParse" && field(e.line, "phase") === "started") {
    if (!DISPATCHERS.has((field(e.line, "dispatcher") ?? "").toLowerCase())) flag("B", `parse with unknown/missing dispatcher "${field(e.line, "dispatcher") ?? ""}"`, { line: e.i });
    if (!nonNegInt(field(e.line, "bytes"))) flag("B", "parse started without bytes", { line: e.i });
  }
}

// ── Contract B: render commit ────────────────────────────────────────────────
const committed = ev.filter((e) => e.kind === "committed");
const observed = ev.filter((e) => e.kind === "observed");
const drops = ev.filter((e) => e.kind === "commitDrop");
const budgets = ev.filter((e) => e.kind === "budget");
const windowDecls = ev.filter((e) => e.kind === "windowDeclared");

// global: exactly one accepted commit per capture
if (committed.length > 1) flag("B", `capture has ${committed.length} accepted commits — exactly one allowed`, { lines: committed.map((c) => c.i) });
const commit = committed[0] ?? null;
const firstCommitIdx = commit ? commit.i : null;
const commitTokenK = commit ? tokenKey(commit.token) : null;

let preCommitVisibleObservedCount = 0;
if (!commit) {
  flag("B", "MISSING_FRAME_MARKER: no thread_first_content_frame_committed in log", {
    note: ev.some((e) => e.kind === "legacyReady")
      ? "only the legacy thread_first_content_ready is present — expected RED on pre-fix builds"
      : "no frame anchor at all",
  });
} else {
  preCommitVisibleObservedCount = observed.filter((o) => tokenKey(o.token) === commitTokenK && o.i < firstCommitIdx && field(o.line, "visible") === "true").length;
  if (!preCommitVisibleObservedCount) flag("B", "committed without a preceding same-token visible=true draw_observed", { line: firstCommitIdx });
}
if (budgets.length && !observed.length) flag("B", "budget marker emitted with zero draw_observed — witness swallowed");

// drop pairing — surface_not_visible: exactly one per (token, drawSeq), AFTER the observed
const svDrops = drops.filter((d) => field(d.line, "reason") === "surface_not_visible");
const wndDrops = drops.filter((d) => field(d.line, "reason") === "window_not_declared");
const obsBySeq = new Map(observed.map((o) => [`${tokenKey(o.token)}\x1f${field(o.line, "drawSeq")}`, o]));
const svBySeq = new Map();
for (const d of svDrops) {
  const k = `${tokenKey(d.token)}\x1f${field(d.line, "drawSeq")}`;
  svBySeq.set(k, (svBySeq.get(k) || []).concat(d));
  const o = obsBySeq.get(k);
  if (!o) flag("B", `surface_not_visible drop without matching observed (drawSeq=${field(d.line, "drawSeq")})`, { line: d.i });
  else if (d.i < o.i) flag("B", "surface_not_visible drop precedes its observed", { line: d.i });
  else if (field(o.line, "visible") !== "false") flag("B", "surface_not_visible drop paired with a visible=true observed", { line: d.i });
}
for (const [k, ds] of svBySeq) if (ds.length > 1) flag("B", `duplicate surface_not_visible drops for one observation (${ds.length})`, { lines: ds.map((d) => d.i) });
const falseVisible = observed.filter((o) => field(o.line, "visible") === "false" && (firstCommitIdx === null || o.i < firstCommitIdx));
let offscreenDropsPaired = true;
for (const o of falseVisible) {
  if (!svBySeq.has(`${tokenKey(o.token)}\x1f${field(o.line, "drawSeq")}`)) {
    offscreenDropsPaired = false;
    flag("B", `visible=false observation (drawSeq=${field(o.line, "drawSeq")}) without paired surface_not_visible drop`, { line: o.i });
  }
}
// window_not_declared: paired with an observed and recovery proven by a later
// same-token visible observed followed by the accepted commit
let drawBeforeDeclarationRecovered = null;
for (const d of wndDrops) {
  const k = `${tokenKey(d.token)}\x1f${field(d.line, "drawSeq")}`;
  if (!obsBySeq.has(k)) flag("B", `window_not_declared drop without matching observed`, { line: d.i });
}
if (wndDrops.length) {
  drawBeforeDeclarationRecovered =
    commit !== null &&
    wndDrops.every((d) => observed.some((o) => tokenKey(o.token) === tokenKey(d.token) && o.i > d.i && field(o.line, "visible") === "true"));
  if (!drawBeforeDeclarationRecovered) flag("B", "draw-before-declaration never recovered into a same-token visible commit");
}
const unreasonedDrops = drops.filter((d) => { const r = field(d.line, "reason"); return !r || !COMMIT_DROP_REASONS.has(r); }).length;

// ── Contract B: stage/operation state machines ───────────────────────────────
const stageEv = ev.filter((e) => e.kind === "stage");
const opEv = ev.filter((e) => e.kind === "operation");
const workEv = ev.filter((e) => ["workRequest", "requestJoin", "workParse", "workEmit", "completionDrop"].includes(e.kind));

// only the committed token's lifecycle is THE lifecycle; other-token aux
// events must be fenced by completion_drop (late/stale callbacks)
const isCommitToken = (e) => commitTokenK !== null && tokenKey(e.token) === commitTokenK;
for (const e of [...stageEv, ...opEv, ...workEv]) {
  if (commitTokenK === null || isCommitToken(e) || !tokenComplete(e.token)) continue;
  if (e.kind === "completionDrop") continue;
  const fenced = workEv.some((d) => d.kind === "completionDrop" &&
    field(d.line, "stage") === field(e.line, "stage") &&
    field(d.line, "requestId") === field(e.line, "requestId") && d.i >= e.i);
  if (!fenced) flag("B", `${e.kind} event with non-committed token not fenced by completion_drop`, { line: e.i });
}

// stage machine keyed (stage) for the committed token, tracking admission ids
const stages = new Map();
for (const s of stageEv.filter((e) => commitTokenK === null || isCommitToken(e))) {
  const st = field(s.line, "stage");
  if (!STAGES.includes(st ?? "")) continue; // already flagged by field validation
  const aid = field(s.line, "stageAdmissionId") ?? "";
  const rec = stages.get(st) || { admissionIds: new Set(), applicable: null, admitted: 0, launched: 0, terminal: 0, terminalResult: null, skipped: 0, skipReason: null, admittedIdx: null, launchedIdx: null, terminalIdx: null, launchedPreCommit: 0 };
  rec.admissionIds.add(aid);
  const app = field(s.line, "applicable");
  if (app !== undefined) rec.applicable = app === "true";
  const res = field(s.line, "result");
  if (res === "admitted") { rec.admitted++; rec.admittedIdx = s.i; }
  else if (res === "launched") {
    rec.launched++; rec.launchedIdx = s.i;
    if (firstCommitIdx === null || s.i < firstCommitIdx) rec.launchedPreCommit++;
  } else if (res === "terminal") { rec.terminal++; rec.terminalResult = field(s.line, "terminal"); rec.terminalIdx = s.i; }
  else if (res === "skipped") { rec.skipped++; rec.skipReason = field(s.line, "reason"); }
  stages.set(st, rec);
}
if (commit && !stages.size) flag("B", "no auxiliary stage lifecycle markers for the committed token — queued-forever or instrumentation missing");
for (const [st, r] of stages) {
  if (r.admissionIds.size > 1) flag("B", `stage ${st}: multiple stageAdmissionIds for one committed token (${r.admissionIds.size})`);
  if (r.launchedPreCommit) flag("B", `stage ${st}: launched before frame commit (${r.launchedPreCommit})`);
  if (r.applicable === null) { flag("B", `stage ${st}: applicability never declared explicitly`); continue; }
  if (r.applicable) {
    if (r.skipped) flag("B", `stage ${st}: applicable stage was skipped`);
    if (r.admitted !== 1 || r.launched !== 1 || r.terminal !== 1) flag("B", `stage ${st}: applicable lifecycle not exactly-once (admitted=${r.admitted} launched=${r.launched} terminal=${r.terminal})`);
    if (r.admittedIdx !== null && r.launchedIdx !== null && r.launchedIdx < r.admittedIdx) flag("B", `stage ${st}: launched before admitted`);
    if (r.launchedIdx !== null && r.terminalIdx !== null && r.terminalIdx < r.launchedIdx) flag("B", `stage ${st}: terminal before launched`);
    if (r.terminal && !TERMINAL_RESULTS.has(r.terminalResult)) flag("B", `stage ${st}: terminal result "${r.terminalResult}" not in enum`);
  } else {
    if (r.skipped !== 1) flag("B", `stage ${st}: non-applicable stage must skip exactly once (skipped=${r.skipped})`);
    if (r.admitted || r.launched || r.terminal) flag("B", `stage ${st}: non-applicable stage did work`);
  }
}
// operation declarations under the committed token
const opsDeclared = new Map();
for (const o of opEv.filter((e) => commitTokenK === null || isCommitToken(e))) {
  const st = field(o.line, "stage"), op = field(o.line, "operation");
  if (!STAGES.includes(st ?? "") || !(OPERATIONS[st] ?? []).includes(op ?? "")) continue;
  const k = `${st}/${op}`;
  const rec = opsDeclared.get(k) || { applicable: null, reason: null, admitted: 0, skipped: 0 };
  const app = field(o.line, "applicable");
  if (app !== undefined) rec.applicable = app === "true";
  rec.reason = field(o.line, "reason") ?? rec.reason;
  const res = field(o.line, "result");
  if (res === "admitted") rec.admitted++;
  else if (res === "skipped") rec.skipped++;
  opsDeclared.set(k, rec);
}
// work machine: (stage, operation) with phase ordering per requestId
const opWork = new Map();
const wrec = (k) => { if (!opWork.has(k)) opWork.set(k, { reqStart: 0, reqTerminal: 0, parseStart: 0, parseTerminal: 0, emitAccepted: 0, joins: 0, duplicateStarts: 0, completionDrops: 0, lateDropViolations: 0, byRid: new Map(), lastPhaseIdx: -1 }); return opWork.get(k); };
const rrec = (m, rid) => { if (!m.has(rid)) m.set(rid, { startIdx: null, reqTermIdx: null, parseStartIdx: null, parseTermIdx: null, emitIdx: null, starts: 0 }); return m.get(rid); };
const preCommit = {};
const bumpPre = (st, op, kind) => {
  preCommit[st] = preCommit[st] || {};
  preCommit[st][op] = preCommit[st][op] || { request: 0, parse: 0, emit: 0 };
  preCommit[st][op][kind]++;
};
let largeParseCount = 0, uiCriticalParseViolations = 0;
const largeParseRecords = [];
for (const w of workEv) {
  const st = field(w.line, "stage") ?? "?";
  const op = field(w.line, "operation") ?? "?";
  if (!STAGES.includes(st) || !(OPERATIONS[st] ?? []).includes(op)) { flag("B", `work marker with unknown stage/operation ${st}/${op}`, { line: w.i }); continue; }
  const k = `${st}/${op}`;
  const r = wrec(k);
  const phase = field(w.line, "phase");
  const rid = field(w.line, "requestId") ?? "";
  const rr = rrec(r.byRid, rid);
  const pre = firstCommitIdx === null || w.i < firstCommitIdx;
  if (w.kind === "workRequest") {
    if (phase === "started") {
      r.reqStart++; rr.starts++;
      if (rr.starts > 1) { r.duplicateStarts++; flag("B", `${k}: duplicate request start for requestId ${rid}`, { line: w.i }); }
      rr.startIdx = rr.startIdx ?? w.i;
      if (pre) bumpPre(st, op, "request");
    } else if (phase === "terminal") {
      r.reqTerminal++; rr.reqTermIdx = w.i;
      if (rr.startIdx === null) flag("B", `${k}: request terminal without started (requestId ${rid})`, { line: w.i });
      if (commitTokenK && tokenComplete(w.token) && tokenKey(w.token) !== commitTokenK) {
        const hasDrop = workEv.some((d) => d.kind === "completionDrop" && field(d.line, "requestId") === rid && d.i >= w.i);
        if (!hasDrop) { r.lateDropViolations++; flag("B", `${k}: obsolete-token request terminal without completion_drop`, { line: w.i }); }
      }
    }
    r.lastPhaseIdx = Math.max(r.lastPhaseIdx, w.i);
  } else if (w.kind === "requestJoin") {
    r.joins++;
    if (rr.startIdx === null || rr.startIdx > w.i) flag("B", `${k}: request_join with no existing same-operation requestId`, { line: w.i });
  } else if (w.kind === "workParse") {
    if (phase === "started") {
      r.parseStart++; rr.parseStartIdx = w.i;
      if (rr.startIdx === null) flag("B", `${k}: parse started before request started (requestId ${rid})`, { line: w.i });
      if (pre) bumpPre(st, op, "parse");
      const dispatcher = (field(w.line, "dispatcher") ?? "").toLowerCase();
      const bytes = Number(field(w.line, "bytes") ?? 0);
      if (DISPATCHERS.has(dispatcher) && bytes >= LARGE_PARSE_THRESHOLD) {
        largeParseCount++;
        const ok = LARGE_PARSE_ALLOWED.includes(dispatcher);
        largeParseRecords.push({ stage: st, operation: op, bytes, dispatcher, verdict: ok ? "PASS" : "FAIL" });
        if (!ok) { uiCriticalParseViolations++; flag("B", `${k}: large parse (${bytes}B) on UI-critical dispatcher "${dispatcher}"`, { line: w.i }); }
      } else if (!DISPATCHERS.has(dispatcher)) {
        uiCriticalParseViolations++;
      }
    } else if (phase === "terminal") {
      r.parseTerminal++; rr.parseTermIdx = w.i;
      if (rr.parseStartIdx === null) flag("B", `${k}: parse terminal without parse started (requestId ${rid})`, { line: w.i });
    }
    r.lastPhaseIdx = Math.max(r.lastPhaseIdx, w.i);
  } else if (w.kind === "workEmit") {
    const res = field(w.line, "result");
    if (res === "accepted") {
      r.emitAccepted++; rr.emitIdx = w.i;
      if (rr.parseStartIdx !== null && rr.parseTermIdx === null) flag("B", `${k}: emit accepted before parse terminal (requestId ${rid})`, { line: w.i });
      if (rr.startIdx === null) flag("B", `${k}: emit accepted without request (requestId ${rid})`, { line: w.i });
      if (pre) bumpPre(st, op, "emit");
    } else if (res === "dropped" && !WORK_REASONS.has(field(w.line, "reason") ?? "")) {
      flag("B", `${k}: dropped emit without frozen reason`, { line: w.i });
    }
    r.lastPhaseIdx = Math.max(r.lastPhaseIdx, w.i);
  } else if (w.kind === "completionDrop") {
    r.completionDrops++;
    if (!["request", "parse", "emit"].includes(field(w.line, "phase") ?? "")) flag("B", `${k}: completion_drop with invalid phase`, { line: w.i });
    if (!WORK_REASONS.has(field(w.line, "reason") ?? "")) flag("B", `${k}: completion_drop with unknown reason`, { line: w.i });
  }
}
// per-operation exactly-once under applicable committed-token stages;
// stage terminal only after every applicable operation's last phase
for (const [st, srec] of stages) {
  if (!srec.applicable) continue;
  for (const op of OPERATIONS[st]) {
    const k = `${st}/${op}`;
    const decl = opsDeclared.get(k);
    const r = opWork.get(k);
    if (!decl || decl.applicable === null) { flag("B", `${k}: operation applicability never declared`); continue; }
    if (decl.applicable) {
      if (decl.admitted !== 1) flag("B", `${k}: applicable operation admitted ${decl.admitted} times (want 1)`);
      if (!r || r.reqStart !== 1 || r.reqTerminal !== 1) flag("B", `${k}: applicable operation request start/terminal not exactly-once (start=${r?.reqStart ?? 0} terminal=${r?.reqTerminal ?? 0})`);
      if (r && r.parseStart !== r.parseTerminal) flag("B", `${k}: parse started/terminal mismatch (${r.parseStart}/${r.parseTerminal})`);
      if (srec.terminalIdx !== null && r && r.lastPhaseIdx > srec.terminalIdx) flag("B", `${k}: stage terminal before operation phases completed`);
    } else {
      if (decl.skipped !== 1) flag("B", `${k}: non-applicable operation must skip exactly once (skipped=${decl.skipped})`);
      if (r && (r.reqStart || r.parseStart || r.emitAccepted)) flag("B", `${k}: non-applicable operation did work`);
    }
  }
}
const preCommitLaunches = [...stages.values()].reduce((a, r) => a + r.launchedPreCommit, 0);
if (commit) {
  for (const [st, ops] of Object.entries(preCommit)) {
    for (const [op, c] of Object.entries(ops)) {
      if (c.request || c.parse || c.emit) flag("B", `pre-commit work for ${st}/${op}`, c);
    }
  }
}
// unexpected operations: work observed while the op (or stage) is declared non-applicable
let nonChatUnexpectedOperations = 0;
for (const [k, decl] of opsDeclared) {
  if (decl.applicable === false) {
    const r = opWork.get(k);
    if (r && (r.reqStart || r.parseStart || r.emitAccepted)) nonChatUnexpectedOperations++;
  }
}

// ── Contract A: window + card/body ───────────────────────────────────────────
const rows = ev.filter((e) => e.kind === "rowRecompose");
const rowByScopeKey = new Map(); // scope\x1findex\x1fkeyB64 -> {maxN, instances, presentations:Set, staleToken}
const scopesSeen = new Set();
let staleTokenRows = 0;
for (const r of rows) {
  const scope = field(r.line, "scope");
  const index = field(r.line, "index");
  const keyB64 = field(r.line, "lazyItemKey");
  if (!scope || index === undefined || !keyB64) continue; // field validation already flagged
  if (commitTokenK !== null && tokenComplete(r.token) && tokenKey(r.token) !== commitTokenK) {
    staleTokenRows++;
    flag("A", `row event with non-committed token (stale window composition)`, { line: r.i });
    continue;
  }
  scopesSeen.add(scope);
  const k = `${scope}\x1f${index}\x1f${keyB64}`;
  const rec = rowByScopeKey.get(k) || { maxN: 0, instances: 0, presentations: new Set() };
  rec.maxN = Math.max(rec.maxN, Number(field(r.line, "n") ?? 0));
  if (field(r.line, "n") === "1") rec.instances++;
  rec.presentations.add(field(r.line, "presentation") ?? "");
  rowByScopeKey.set(k, rec);
}
const recordsOf = (scope) => {
  const map = new Map();
  for (const k of rowByScopeKey.keys()) {
    const [s, idx, keyB64] = k.split("\x1f");
    if (s !== scope) continue;
    if (map.has(idx) && map.get(idx) !== keyB64) flag("A", `${scope}: two different lazy keys at index ${idx}`);
    map.set(idx, keyB64);
  }
  return [...map.entries()].map(([idx, keyB64]) => ({ idx: Number(idx), keyB64 })).sort((a, b) => a.idx - b.idx);
};
const canonicalDigest = (records) =>
  crypto.createHash("sha256").update(records.map((r) => `index=${r.idx};key=${r.keyB64}`).join("\n"), "utf8").digest("hex");

let windowReport = null;
let outsideComposition = null;
let cardDeltas = null, bodyDeltas = null;
if (rows.length) {
  if (!scopesSeen.has("card")) flag("A", "MISSING_CARD_SCOPE: no scope=card markers — the Card-level hook is not wired");
  if (!scopesSeen.has("body")) flag("A", "MISSING_BODY_SCOPE: no scope=body markers");
  if (!windowDecls.length) {
    flag("A", "no thread_render_window_declared marker — window membership cannot be verified");
  } else {
    const declsByToken = new Map();
    for (const d of windowDecls) {
      const k = tokenKey(d.token);
      declsByToken.set(k, (declsByToken.get(k) || []).concat(d));
    }
    for (const [, ds] of declsByToken) if (ds.length > 1) flag("A", `duplicate window declaration for the same token (${ds.length}) — equivalent refresh must be no-op`, { lines: ds.map((d) => d.i) });
    const decl = (commitTokenK && declsByToken.get(commitTokenK)?.[0]) || windowDecls[windowDecls.length - 1];
    const declaredDigest = (field(decl.line, "windowKeysDigest") ?? "").toLowerCase();
    const declaredCount = Number(field(decl.line, "windowKeyCount") ?? -1);
    const total = Number(field(decl.line, "totalCount") ?? -1);
    const start = Number(field(decl.line, "windowStart") ?? -1);
    const end = Number(field(decl.line, "windowEnd") ?? -1);
    const cardRecords = recordsOf("card");
    const bodyRecords = recordsOf("body");
    const cardExact = declaredDigest === canonicalDigest(cardRecords) && declaredCount === cardRecords.length;
    const bodyExact = declaredDigest === canonicalDigest(bodyRecords) && declaredCount === bodyRecords.length;
    const cardBodyKeysEqual = JSON.stringify(cardRecords) === JSON.stringify(bodyRecords);
    if (!cardExact) flag("A", `card record set != declared window (declared ${declaredCount}, actual ${cardRecords.length})`);
    if (!bodyExact) flag("A", `body record set != declared window (declared ${declaredCount}, actual ${bodyRecords.length})`);
    if (!cardBodyKeysEqual) flag("A", "card and body ordered index/key sequences differ");
    if (declaredCount > opts.maxWindow) flag("A", `declared window ${declaredCount} exceeds --max-window ${opts.maxWindow}`);
    // membership: index set must equal windowStart..windowEnd on every route
    const idx = cardRecords.map((r) => r.idx);
    const contiguousDeclared = idx.length === (end - start + 1) && idx.every((v, j) => v === start + j);
    if (start >= 0 && end >= start && !contiguousDeclared) flag("A", `composed index set != declared windowStart..windowEnd (${start}..${end})`);
    outsideComposition = [...rowByScopeKey.keys()].filter((k) => {
      const i = Number(k.split("\x1f")[1]);
      return start >= 0 && (i < start || i > end);
    }).length;
    if (outsideComposition > 0) flag("A", `composition outside the declared window (${outsideComposition})`);
    let bottomSuffix = null;
    if (opts.requireBottomSuffix) {
      bottomSuffix = contiguousDeclared && end === total - 1;
      if (!bottomSuffix) flag("A", "ordinary-#all bottom contiguous-suffix rule violated", { windowStart: start, windowEnd: end, totalCount: total });
    }
    const anyRandom = cardRecords.some((r) => (b64urlCanonical(r.keyB64) ?? "").includes("random:"));
    windowReport = {
      totalCount: total, windowStart: start, windowEnd: end,
      anchor: field(decl.line, "anchor") ?? "",
      windowGeneration: field(decl.line, "windowGeneration") ?? "",
      windowKeyCount: declaredCount,
      windowKeysDigest: declaredDigest,
      digestInput: "ordered index=<decimal>;key=<base64url-no-padding(actual Lazy key)> joined by newline",
      optimisticRandomKeyFixturePassed: anyRandom ? true : { value: null, reason: "seeded log contains no optimistic random keys; deterministic fixture authority" },
      duplicateRandomFallbackFixturePassed: { value: null, reason: "deterministic fixture authority" },
      cardKeysExact: cardExact, bodyKeysExact: bodyExact, cardBodyKeysEqual,
      withinMaxWindow: declaredCount <= opts.maxWindow,
      outsideComposition,
      ordinaryAllContiguousBottomSuffix: bottomSuffix,
    };
  }
  // budgets: recompose (scope-aware) and recreation (always 0 extra instances)
  let cardExceeded = 0, bodyExceeded = 0, cardRecreated = 0, bodyRecreated = 0;
  let extraCardEvents = 0, extraBodyEvents = 0;
  for (const [k, rec] of rowByScopeKey) {
    const [scope, idx, keyB64] = k.split("\x1f");
    const decoded = b64urlCanonical(keyB64) ?? keyB64;
    const isTagged = opts.tagged.has(decoded) || opts.tagged.has(keyB64);
    // v3: tagged row may update its BODY once; Card/item identity stays stable
    const budget = scope === "body" && isTagged ? opts.maxTaggedRecompose : opts.maxRecompose;
    if (rec.maxN > budget) {
      flag("A", "row over recomposition budget", { scope, index: idx, key: decoded, recompositions: rec.maxN, budget });
      if (scope === "card") cardExceeded++; else bodyExceeded++;
    }
    if (rec.instances > 1) {
      flag("A", `row RECREATED (fresh n=1 x${rec.instances}) — recreation budget is zero for every row`, { scope, index: idx, key: decoded, instances: rec.instances });
      if (scope === "card") cardRecreated++; else bodyRecreated++;
    }
    if (scope === "card" && rec.presentations.size > 1) flag("A", "card presentation identity changed — Card/item must stay stable", { index: idx, key: decoded });
    if (scope === "body" && rec.presentations.size > (isTagged ? 2 : 1)) flag("A", "body presentation changed beyond contract", { index: idx, key: decoded });
    const extra = (rec.maxN - 1) + (rec.instances - 1);
    if (scope === "card") extraCardEvents += Math.max(0, extra); else extraBodyEvents += Math.max(0, extra);
  }
  cardDeltas = extraCardEvents;
  bodyDeltas = extraBodyEvents;
  var cardStats = { missingScope: scopesSeen.has("card") ? 0 : 1, recomposeExceeded: cardExceeded, recreationExceeded: cardRecreated };
  var bodyStats = { missingScope: scopesSeen.has("body") ? 0 : 1, recomposeExceeded: bodyExceeded, recreationExceeded: bodyRecreated };
} else if (commit) {
  flag("A", "no thread_row_recompose markers at all — row instrumentation missing");
  var cardStats = { missingScope: 1, recomposeExceeded: null, recreationExceeded: null };
  var bodyStats = { missingScope: 1, recomposeExceeded: null, recreationExceeded: null };
} else {
  var cardStats = { missingScope: scopesSeen.has("card") ? 0 : 1, recomposeExceeded: 0, recreationExceeded: 0 };
  var bodyStats = { missingScope: scopesSeen.has("body") ? 0 : 1, recomposeExceeded: 0, recreationExceeded: 0 };
}

// ── report (v3 §7 shapes; unknowns are null+reason and listed in notProven) ──
const aViolations = violations.filter((v) => v.contract === "A");
const bViolations = violations.filter((v) => v.contract === "B");
const notProven = [
  { tooth: "controlPlane.renderSliceEmissions", reason: "no log marker in frozen §5; same-exact owner test artifact is the authority" },
  { tooth: "window.optimisticRandomKeyFixture/duplicateRandomFallbackFixture", reason: "deterministic production fixture authority" },
];
const stagesOut = {};
for (const [st, r] of stages) {
  const operations = {};
  for (const op of OPERATIONS[st]) {
    const decl = opsDeclared.get(`${st}/${op}`);
    const w = opWork.get(`${st}/${op}`);
    operations[op] = {
      applicable: decl?.applicable ?? null,
      reason: decl?.reason ?? null,
      admitted: decl?.admitted ?? 0, skipped: decl?.skipped ?? 0,
      requestStarted: w?.reqStart ?? 0, requestTerminal: w?.reqTerminal ?? 0,
      parseStarted: w?.parseStart ?? 0, parseTerminal: w?.parseTerminal ?? 0,
      emitAccepted: w?.emitAccepted ?? 0, requestJoins: w?.joins ?? 0,
      duplicateStarts: w?.duplicateStarts ?? 0,
    };
  }
  stagesOut[st] = {
    stageAdmissionId: [...r.admissionIds][0] ?? null,
    applicable: r.applicable, admitted: r.admitted, launched: r.launched,
    terminal: r.terminal, terminalResult: r.terminalResult,
    skipped: r.skipped, ...(r.applicable === false ? { skipReason: r.skipReason } : {}),
    operations,
    completionDrops: [...opWork.entries()].filter(([k]) => k.startsWith(st + "/")).reduce((a, [, w]) => a + w.completionDrops, 0),
    lateDropViolations: [...opWork.entries()].filter(([k]) => k.startsWith(st + "/")).reduce((a, [, w]) => a + w.lateDropViolations, 0),
  };
}
const preCommitOut = {};
for (const [st, ops] of Object.entries(OPERATIONS)) {
  preCommitOut[st] = {};
  for (const op of ops) preCommitOut[st][op] = preCommit[st]?.[op] ?? { request: 0, parse: 0, emit: 0 };
  preCommitOut[st].launch = stages.get(st)?.launchedPreCommit ?? 0;
}
const budgetLine = budgets[budgets.length - 1]?.line;
const emptyExercised = [...stages.values()].some((r) => r.applicable === false);
const applicabilityMatrix = {
  verdict: nonChatUnexpectedOperations === 0 ? "PASS" : "FAIL",
  emptyChannelMemberSuggestionsExecutable: emptyExercised ? true : { value: null, reason: "seeded content scenario does not exercise the empty-composer path" },
  emptyDmMemberSuggestionsExecutable: { value: null, reason: "seeded content scenario does not exercise the empty-DM path" },
  emptyChannelSuggestionsExecutable: { value: null, reason: "seeded content scenario does not exercise the empty-composer path" },
  nonChatUnexpectedOperations,
};
if (!emptyExercised) notProven.push({ tooth: "applicabilityMatrix.empty*Executable", reason: "unexercised in seeded scenario; deterministic fixture authority" });

const report = {
  verdict: violations.length ? "FAIL" : "PASS",
  notProven,
  contractA: {
    contract: "A_card_stability",
    verdict: aViolations.length ? "FAIL" : "PASS",
    token: commit ? commit.token : null,
    window: windowReport,
    card: cardStats,
    body: bodyStats,
    staleTokenRowEvents: staleTokenRows,
    taggedVisibleRow: opts.tagged.size
      ? { applicable: true, keys: [...opts.tagged] }
      : { applicable: false, result: "n/a", reason: "seeded_fixture_has_no_visible_tagged_row" },
    violations: aViolations,
  },
  contractB: {
    contract: "B_render_commit_aux",
    verdict: bViolations.length ? "FAIL" : "PASS",
    token: commit ? commit.token : null,
    draw: {
      observedCount: observed.length,
      observedCountMin: 1,
      preCommitVisibleObservedCount,
      preCommitVisibleObservedCountMin: 1,
      preCommitOffscreenObservedCount: falseVisible.length,
      surfaceNotVisibleDrops: svDrops.length,
      windowNotDeclaredDrops: wndDrops.length,
      drawBeforeDeclarationRecovered,
      offscreenDropsPaired,
      tokenConsumedByOffscreen: false,
      committed: committed.length,
      paired: preCommitVisibleObservedCount >= 1,
      duplicateCommitted: Math.max(0, committed.length - 1),
      unreasonedDrops,
    },
    controlPlane: {
      opaqueIdentityProducer: "SharedThreadStore",
      renderSliceEmissions: { value: null, reason: "no log marker in frozen §5; owner test artifact authority" },
      cardDeltas,
      bodyDeltas,
      equivalentDeclarationMarkers: windowDecls.length,
      equivalentDeclarationNoOp: windowDecls.length <= new Set(windowDecls.map((d) => tokenKey(d.token))).size,
    },
    preCommit: preCommitOut,
    stages: stagesOut,
    parse: {
      largeThresholdBytes: LARGE_PARSE_THRESHOLD,
      allowedLargeDispatchers: LARGE_PARSE_ALLOWED,
      forbiddenUiCriticalDispatchers: UI_CRITICAL_DISPATCHERS,
      largeParseCount,
      largeParseRecords,
      uiCriticalParseViolations,
    },
    applicabilityMatrix,
    budget: {
      payloadReadyMs: budgetLine ? Number(field(budgetLine, "payloadReadyMs") ?? -1) : null,
      layoutMs: budgetLine ? Number(field(budgetLine, "layoutMs") ?? -1) : null,
      drawMs: budgetLine ? Number(field(budgetLine, "drawMs") ?? -1) : null,
      simulatorInformational: true,
      factIndependentOfCommit: true,
    },
    legacyReadySeen: ev.some((e) => e.kind === "legacyReady"),
    violations: bViolations,
  },
};
console.log(JSON.stringify(report, null, 2));
process.exit(violations.length ? 1 : 0);
