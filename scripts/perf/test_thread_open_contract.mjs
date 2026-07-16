#!/usr/bin/env node
// perf/test_thread_open_contract — executable fixtures + runner for the task
// #304 contract checker. Builds one GREEN fixture (full frozen-v3 lifecycle)
// and one RED fixture per violation class as a mutation of GREEN, runs the
// checker as a child process, and asserts exit code + violation substring
// (and for GREEN, the §7 JSON shape keys). Exit 0 = all assertions hold.
//
// Usage: node scripts/perf/test_thread_open_contract.mjs [--keep <dir>]

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";

const CHECKER = new URL("./check_thread_open_contract.mjs", import.meta.url).pathname;
const keepDir = (() => { const i = process.argv.indexOf("--keep"); return i >= 0 ? process.argv[i + 1] : null; })();
const dir = keepDir ?? fs.mkdtempSync(path.join(os.tmpdir(), "t304-fixtures-"));
fs.mkdirSync(dir, { recursive: true });

// ── GREEN fixture builder ────────────────────────────────────────────────────
const TOK = "surfaceOwner=s1 pageInstance=p1 intent=i1 routeGeneration=1 resolvedRouteKey=channel:abc renderRevision=1 windowGeneration=1";
const b64u = (s) => Buffer.from(s, "utf8").toString("base64url");
const KEYS = Array.from({ length: 5 }, (_, j) => ({ idx: 20 + j, keyB64: b64u(`thread-page:msgid${20 + j}full`) }));
const DIGEST = crypto.createHash("sha256").update(KEYS.map((k) => `index=${k.idx};key=${k.keyB64}`).join("\n"), "utf8").digest("hex");
const P = "07-16 15:30:00.000  1  1 I SlockKuikly: ";

const CURR = "currentSurfaceOwner=s1 currentPageInstance=p1 currentIntent=i1 currentRouteGeneration=1 currentResolvedRouteKey=channel:abc currentRenderRevision=1 currentWindowGeneration=1";
const OLDTOK = TOK.replace("windowGeneration=1", "windowGeneration=0");
function greenLines() {
  const L = [];
  // draw-before-declaration: observed -> window_not_declared drop -> declaration
  L.push(`thread_first_content_draw_observed ${TOK} visible=true drawSeq=1`);
  L.push(`thread_first_content_frame_commit_drop reason=window_not_declared drawSeq=1 ${TOK} ${CURR}`);
  L.push(`thread_render_window_declared ${TOK} totalCount=25 windowStart=20 windowEnd=24 anchor=bottom reason=initial windowKeyCount=5 windowKeysDigest=${DIGEST}`);
  for (const { idx, keyB64 } of KEYS) {
    L.push(`thread_row_recompose scope=card index=${idx} lazyItemKey=${keyB64} n=1 bodyLen=40 ${TOK} presentation=pr1`);
    L.push(`thread_row_recompose scope=body index=${idx} lazyItemKey=${keyB64} n=1 bodyLen=40 ${TOK} presentation=pr1`);
  }
  L.push(`thread_first_content_draw_observed ${TOK} visible=false drawSeq=2`);
  L.push(`thread_first_content_frame_commit_drop reason=surface_not_visible drawSeq=2 ${TOK} ${CURR}`);
  L.push(`thread_first_content_draw_observed ${TOK} visible=true drawSeq=3`);
  L.push(`thread_first_content_frame_committed threadId=t1 ${TOK}`);
  L.push(`thread_first_content_budget phase=render layoutMs=20 drawMs=40 layoutBudgetMs=100 drawBudgetMs=100 pass=true`);
  const stagePlan = {
    members: { applicableOps: ["channel_members", "member_suggestions"], skippedOps: ["channel_suggestions"] },
    secondary: { applicableOps: ["followed_summary"], skippedOps: ["saved_status_enrichment"] },
  };
  for (const [stage, plan] of Object.entries(stagePlan)) {
    const aid = `adm-${stage}`;
    L.push(`thread_auxiliary_hydration stage=${stage} stageAdmissionId=${aid} applicable=true result=admitted terminal=none reason=resolved_snapshot ${TOK}`);
    L.push(`thread_auxiliary_hydration stage=${stage} stageAdmissionId=${aid} applicable=true result=launched terminal=none reason=resolved_snapshot ${TOK}`);
    for (const op of plan.applicableOps) {
      const rid = `r-${op}`;
      const disp = op === "followed_summary" ? "background_parse" : "caller";
      const bytes = op === "followed_summary" ? 390598 : 2048;
      L.push(`thread_auxiliary_operation stage=${stage} operation=${op} stageAdmissionId=${aid} applicable=true result=admitted reason=resolved_snapshot ${TOK}`);
      L.push(`thread_auxiliary_request stage=${stage} operation=${op} stageAdmissionId=${aid} requestId=${rid} phase=started result=none reason=resolved_snapshot ${TOK}`);
      L.push(`thread_auxiliary_parse stage=${stage} operation=${op} stageAdmissionId=${aid} requestId=${rid} phase=started result=none reason=resolved_snapshot dispatcher=${disp} bytes=${bytes} ${TOK}`);
      L.push(`thread_auxiliary_parse stage=${stage} operation=${op} stageAdmissionId=${aid} requestId=${rid} phase=terminal result=success reason=resolved_snapshot dispatcher=${disp} bytes=${bytes} ${TOK}`);
      L.push(`thread_auxiliary_request stage=${stage} operation=${op} stageAdmissionId=${aid} requestId=${rid} phase=terminal result=success reason=resolved_snapshot ${TOK}`);
      L.push(`thread_auxiliary_emit stage=${stage} operation=${op} stageAdmissionId=${aid} requestId=${rid} result=accepted reason=resolved_snapshot emitted=true ${TOK}`);
    }
    for (const op of plan.skippedOps) {
      L.push(`thread_auxiliary_operation stage=${stage} operation=${op} stageAdmissionId=${aid} applicable=false result=skipped reason=operation_not_demanded ${TOK}`);
    }
    L.push(`thread_auxiliary_hydration stage=${stage} stageAdmissionId=${aid} applicable=true result=terminal terminal=success reason=resolved_snapshot ${TOK}`);
  }
  // a legally fenced old-token late completion must NOT pollute current counts
  L.push(`thread_auxiliary_request stage=members operation=channel_members stageAdmissionId=adm-old requestId=r-old phase=terminal result=success reason=stale_token ${OLDTOK}`);
  L.push(`thread_auxiliary_completion_drop stage=members operation=channel_members stageAdmissionId=adm-old requestId=r-old phase=request reason=stale_token ${OLDTOK}`);
  return L;
}

// ── RED mutations (each returns {name, lines, expectSubstring}) ──────────────
const G = greenLines;
const cases = [
  { name: "green", lines: G(), expectExit: 0 },
  { name: "dup-commit-same-token", mutate: (L) => { const i = L.findIndex((l) => l.includes("frame_committed")); L.splice(i + 1, 0, L[i]); return L; }, expect: "exactly one allowed" },
  {
    name: "two-commits-different-tokens",
    mutate: (L) => { const i = L.findIndex((l) => l.includes("frame_committed")); L.splice(i + 1, 0, L[i].replace("windowGeneration=1", "windowGeneration=2")); return L; },
    expect: "exactly one allowed",
  },
  { name: "commit-without-visible-observed", mutate: (L) => L.filter((l) => !l.includes("visible=true")), expect: "without a preceding same-token visible=true" },
  { name: "missing-frame-marker", mutate: (L) => L.filter((l) => !l.includes("frame_committed")), expect: "MISSING_FRAME_MARKER" },
  { name: "unpaired-offscreen-observed", mutate: (L) => L.filter((l) => !l.includes("reason=surface_not_visible")), expect: "without paired surface_not_visible drop" },
  {
    name: "orphan-surface-not-visible-drop",
    mutate: (L) => { const i = L.findIndex((l) => l.includes("reason=surface_not_visible")); L[i] = L[i].replace("drawSeq=2", "drawSeq=9"); return L; },
    expect: "surface_not_visible drop without matching observed",
  },
  { name: "observed-missing-drawseq", mutate: (L) => L.map((l) => l.includes("drawSeq=3") && l.includes("draw_observed") ? l.replace(" drawSeq=3", "") : l), expect: "observed marker invalid/missing fields" },
  { name: "row-missing-token", mutate: (L) => L.map((l) => l.includes("scope=card") ? l.replace(" surfaceOwner=s1", "") : l), expect: "rowRecompose marker missing token fields" },
  {
    name: "unknown-operation",
    mutate: (L) => { L.push(`thread_auxiliary_request stage=members operation=mystery_op stageAdmissionId=adm-members requestId=rx phase=started result=none reason=resolved_snapshot ${TOK}`); return L; },
    expect: "unknown stage/operation",
  },
  {
    name: "pre-commit-launch",
    mutate: (L) => {
      const c = L.findIndex((l) => l.includes("frame_committed"));
      L.splice(c, 0, `thread_auxiliary_hydration stage=members stageAdmissionId=adm-members applicable=true result=launched terminal=none reason=resolved_snapshot ${TOK}`);
      return L;
    },
    expect: "launched before frame commit",
  },
  {
    name: "pre-commit-request",
    mutate: (L) => {
      const c = L.findIndex((l) => l.includes("frame_committed"));
      L.splice(c, 0, `thread_auxiliary_request stage=members operation=channel_members stageAdmissionId=adm-members requestId=r-pre phase=started result=none reason=resolved_snapshot ${TOK}`);
      return L;
    },
    expect: "pre-commit work for members/channel_members",
  },
  {
    name: "stage-terminal-before-op-phases",
    mutate: (L) => {
      const t = L.findIndex((l) => l.includes("stage=members") && l.includes("result=terminal"));
      const e = L.findIndex((l) => l.includes("operation=member_suggestions") && l.includes("thread_auxiliary_emit"));
      const [term] = L.splice(t, 1);
      L.splice(e, 0, term);
      return L;
    },
    expect: "stage terminal before operation phases completed",
  },
  {
    name: "duplicate-request-start",
    mutate: (L) => { const i = L.findIndex((l) => l.includes("requestId=r-channel_members") && l.includes("phase=started") && l.includes("thread_auxiliary_request")); L.splice(i + 1, 0, L[i]); return L; },
    expect: "duplicate request start",
  },
  {
    name: "join-without-request",
    mutate: (L) => { L.splice(1, 0, `thread_auxiliary_request_join stage=members operation=channel_members stageAdmissionId=adm-members requestId=r-ghost reason=coalesced ${TOK}`); return L; },
    expect: "request_join with no existing",
  },
  {
    name: "obsolete-terminal-without-drop",
    mutate: (L) => L.filter((l) => !l.includes("completion_drop")),
    expect: "not fenced by completion_drop",
  },
  {
    name: "ui-critical-large-parse",
    mutate: (L) => L.map((l) => l.includes("dispatcher=background_parse") ? l.replace("dispatcher=background_parse", "dispatcher=main") : l),
    expect: "large parse (390598B) on UI-critical dispatcher",
  },
  {
    name: "unknown-dispatcher",
    mutate: (L) => L.map((l) => l.includes("dispatcher=caller") ? l.replace("dispatcher=caller", "dispatcher=mystery") : l),
    expect: "unknown/missing dispatcher",
  },
  { name: "duplicate-declaration", mutate: (L) => { const i = L.findIndex((l) => l.includes("thread_render_window_declared")); L.splice(i + 1, 0, L[i].replace("reason=initial", "reason=equivalent_refresh")); return L; }, expect: "duplicate window declaration" },
  {
    name: "digest-mismatch",
    mutate: (L) => L.map((l) => l.includes("windowKeysDigest") ? l.replace(DIGEST, "0".repeat(64)) : l),
    expect: "record set != declared window",
  },
  {
    name: "outside-composition",
    mutate: (L) => { L.splice(1, 0, `thread_row_recompose scope=card index=3 lazyItemKey=${b64u("thread-page:outside")} n=1 bodyLen=40 ${TOK} presentation=pr1`); return L; },
    expect: "outside the declared window",
  },
  {
    name: "recreation",
    mutate: (L) => { const i = L.findIndex((l) => l.includes("scope=card")); L.splice(i + 1, 0, L[i]); return L; },
    expect: "RECREATED",
  },
  {
    name: "card-presentation-change",
    mutate: (L) => { const i = L.findIndex((l) => l.includes("scope=card")); L.splice(i + 1, 0, L[i].replace("n=1", "n=2").replace("presentation=pr1", "presentation=pr2")); return L; },
    expect: "card presentation identity changed",
  },
  {
    name: "production-marker-missing-presentation",
    mutate: (L) => L.map((l) => l.includes("thread_row_recompose") ? l.replace(" presentation=pr1", "") : l),
    expect: "rowRecompose marker invalid/missing fields: presentation",
  },
  {
    name: "stale-token-row",
    mutate: (L) => { L.splice(1, 0, `thread_row_recompose scope=card index=20 lazyItemKey=${KEYS[0].keyB64} n=1 bodyLen=40 ${TOK.replace("windowGeneration=1", "windowGeneration=0")} presentation=pr1`); return L; },
    expect: "stale window composition",
  },
  {
    name: "wrong-admission-operation",
    mutate: (L) => L.map((l) => l.includes("thread_auxiliary_operation") && l.includes("operation=channel_members") ? l.replace("stageAdmissionId=adm-members", "stageAdmissionId=rogue-admission") : l),
    expect: 'carries stageAdmissionId',
  },
  {
    name: "wrong-admission-work",
    mutate: (L) => L.map((l) => (l.includes("thread_auxiliary_request ") || l.includes("thread_auxiliary_parse") || l.includes("thread_auxiliary_emit")) && l.includes("operation=channel_members") && !l.includes("adm-old") ? l.replace("stageAdmissionId=adm-members", "stageAdmissionId=rogue-admission") : l),
    expect: 'carries stageAdmissionId',
  },
  {
    name: "missing-secondary-stage",
    mutate: (L) => L.filter((l) => !(l.includes("stage=secondary"))),
    expect: "stage secondary: no lifecycle markers",
  },
  {
    name: "work-enum-banana",
    mutate: (L) => L.map((l) => l.includes("thread_auxiliary_request ") && l.includes("operation=channel_members") && l.includes("phase=started") ? l.replace("result=none reason=resolved_snapshot", "result=banana reason=projection_unchanged") : l),
    expect: "marker invalid/missing fields",
  },
  {
    name: "precommit-admitted",
    mutate: (L) => {
      const a = L.findIndex((l) => l.includes("stage=members") && l.includes("result=admitted") && l.includes("thread_auxiliary_hydration"));
      const c = L.findIndex((l) => l.includes("frame_committed"));
      const [adm] = L.splice(a, 1);
      L.splice(c, 0, adm);
      return L;
    },
    expect: "admitted before frame commit",
  },
  {
    name: "wnd-double-drop",
    mutate: (L) => { const i = L.findIndex((l) => l.includes("reason=window_not_declared")); L.splice(i + 1, 0, L[i]); return L; },
    expect: "duplicate window_not_declared drops",
  },
  {
    name: "sv-drop-on-visible-observed",
    mutate: (L) => L.map((l) => l.includes("visible=false") ? l.replace("visible=false", "visible=true") : l),
    expect: "paired with a visible=true observed",
  },
  {
    name: "obsolete-drop-wrong-operation",
    mutate: (L) => L.map((l) => l.includes("completion_drop") && l.includes("adm-old") ? l.replace("operation=channel_members", "operation=member_suggestions") : l),
    expect: "not fenced by completion_drop",
  },
  {
    name: "obsolete-drop-rogue-admission",
    mutate: (L) => L.map((l) => l.includes("completion_drop") && l.includes("adm-old") ? l.replace("stageAdmissionId=adm-old", "stageAdmissionId=rogue-admission") : l),
    expect: "not fenced by completion_drop",
  },
  {
    name: "noncanonical-base64url",
    mutate: (L) => { const i = L.findIndex((l) => l.includes("scope=card")); L[i] = L[i].replace(/lazyItemKey=\S+/, "lazyItemKey=abc=="); return L; },
    expect: "lazyItemKey(canonical-base64url)",
  },
];

// GREEN shape keys that must exist (v3 §7)
const GREEN_SHAPE = [
  ["contractA", "window", "windowKeysDigest"],
  ["contractA", "window", "cardBodyKeysEqual"],
  ["contractA", "card", "recomposeExceeded"],
  ["contractA", "taggedVisibleRow", "reason"],
  ["contractB", "draw", "preCommitVisibleObservedCount"],
  ["contractB", "draw", "windowNotDeclaredDrops"],
  ["contractB", "controlPlane", "renderSliceEmissions"],
  ["contractB", "preCommit", "members", "launch"],
  ["contractB", "stages", "members", "operations", "channel_members", "parseTerminal"],
  ["contractB", "stages", "members", "operations", "channel_members", "duplicateStarts"],
  ["contractB", "parse", "largeThresholdBytes"],
  ["contractB", "parse", "largeParseRecords"],
  ["contractB", "applicabilityMatrix", "nonChatUnexpectedOperations"],
  ["contractB", "budget", "simulatorInformational"],
  ["notProven"],
];

let failures = 0;
for (const c of cases) {
  const linesOut = (c.lines ?? c.mutate(G())).map((l) => (l.startsWith("thread_") ? P + l : l.startsWith("07-16") ? l : P + l));
  const file = path.join(dir, `${c.name}.log`);
  fs.writeFileSync(file, linesOut.join("\n"));
  let out = "", code = 0;
  try {
    out = execFileSync(process.execPath, [CHECKER, "--log", file, "--require-bottom-suffix"], { encoding: "utf8" });
  } catch (e) {
    out = e.stdout ?? "";
    code = e.status ?? -1;
  }
  const expectExit = c.expectExit ?? 1;
  let ok = code === expectExit;
  let why = ok ? "" : `exit ${code} != ${expectExit}`;
  if (ok && c.expect) {
    ok = out.includes(c.expect);
    if (!ok) why = `violation substring not found: "${c.expect}"`;
  }
  if (ok && c.name === "green") {
    const json = JSON.parse(out);
    for (const pathKeys of GREEN_SHAPE) {
      let node = json;
      for (const k of pathKeys) node = node?.[k];
      if (node === undefined) { ok = false; why = `GREEN JSON missing ${pathKeys.join(".")}`; break; }
    }
  }
  console.log(`${ok ? "PASS" : "FAIL"}  ${c.name}${why ? "  — " + why : ""}`);
  if (!ok) failures++;
}
console.log(failures ? `\n${failures} fixture assertion(s) FAILED (fixtures in ${dir})` : `\nall ${cases.length} fixture assertions passed`);
if (!keepDir && !failures) fs.rmSync(dir, { recursive: true, force: true });
process.exit(failures ? 1 : 0);
