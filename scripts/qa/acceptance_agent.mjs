#!/usr/bin/env node
// qa/acceptance_agent — headless goal-driven acceptance executor (agent-in-CI PoC).
//
// One acceptance run = one process: it gets a GOAL, drives the app on a local
// adb device/emulator in an observe -> act -> verify loop with an M3 model,
// records evidence (per-step screenshots + steps.jsonl), writes verdict.json,
// and EXITS when the model declares a verdict (or a guardrail trips).
//
//   exit 0 = pass    exit 1 = fail    exit 2 = no conclusion (guardrail/error)
//
// The model speaks a strict-JSON action protocol (one action per turn); after
// every action the runner replies with a fresh screenshot. Coordinates should
// come from dump_ui (uiautomator bounds), not eyeballing pixels.
//
// Credentials never enter the model context or evidence: the goal/preamble
// reference {{QA_EMAIL}}/{{QA_PASSWORD}} placeholders, substituted only at
// adb-execution time and redacted from everything written to disk/stdout.
//
// Needs PI_API_KEY/MINIMAX_API_KEY + @earendil-works/pi-ai (SLOCK_PI_AI_MODULE).
//
// Usage:
//   node scripts/qa/acceptance_agent.mjs --goal "<acceptance goal>" [--out qa-evidence]
//     [--max-turns 30] [--max-minutes 20] [--device <serial>]

import { execFileSync } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { normalizeAssertionAnnotation } from "./assertion_annotations.mjs";
import {
  bindDiagnosisToLedger,
  effectiveExecutorVerdict,
  normalizeExecutionLimits,
  normalizeSelfDiagnosis,
  observationFingerprint,
  repeatedActionKey,
} from "./execution_diagnosis.mjs";
import { captureAndroidScreenshotWithPostProof } from "./android_capture_proof.mjs";
import { buildEvidenceOverlays, screenshotPixelTap } from "./evidence_annotations.mjs";
import { capacityClientFromEnv, runWithModelRetries } from "./model_capacity.mjs";
import { detectForeignAndroidSystemBlocker } from "./system_blocker.mjs";
import { serverGuardSection } from "./server_guard.mjs";
import { adbRunAsShellArgs } from "./mobile_login_bootstrap_lib.mjs";

const argv = process.argv.slice(2);
const arg = (f, d) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : d; };

const opts = {
  goal: arg("--goal", process.env.QA_GOAL || ""),
  out: arg("--out", "qa-evidence"),
  maxTurns: Number(arg("--max-turns", process.env.QA_MAX_TURNS || "30")),
  maxMinutes: Number(arg("--max-minutes", process.env.QA_MAX_MINUTES || "20")),
  noProgressTurns: Number(arg("--no-progress-turns", process.env.QA_NO_PROGRESS_TURNS || "8")),
  device: arg("--device", ""),
  appPackage: arg("--package", "build.raft.app"),
  playbook: arg("--playbook", process.env.QA_PLAYBOOK || "docs/qa-acceptance-operations.md"),
  // "android" drives adb directly (unchanged); "ios" delegates every device
  // primitive to scripts/qa/ios_driver.mjs (agent-device by default; qa-loop
  // keeps the XCUITest file protocol).
  platform: arg("--platform", process.env.QA_PLATFORM || "android"),
  iosIoDir: arg("--ios-io-dir", process.env.QA_LOOP_IO_DIR || ""),
  iosUdid: arg("--ios-udid", process.env.QA_IOS_UDID || "booted"),
};

// Non-null only on --platform ios; every device-touching helper below branches
// on it first, so the Android code path is byte-for-byte what it always was.
let drv = null;
let modelCapacity = null;
let lastCaptureBoundary = null;

const SECRETS = {
  "{{QA_EMAIL}}": process.env.QA_TEST_EMAIL || "",
  "{{QA_PASSWORD}}": process.env.QA_TEST_PASSWORD || "",
};

const piKey = () => process.env.MINIMAX_API_KEY || process.env.PI_API_KEY || "";
function loadModels() {
  return import(process.env.SLOCK_PI_AI_MODULE || "@earendil-works/pi-ai/providers/all").then((p) => p.builtinModels());
}

// Strip real secret values from anything that leaves the process (evidence,
// logs). GitHub Actions masks secrets in job logs, but evidence artifacts and
// verdict.json are downloadable — keep them clean at the source.
function redact(s) {
  let out = String(s);
  for (const v of Object.values(SECRETS)) {
    if (v) out = out.split(v).join("***");
  }
  return out;
}

function adb(args, opts2 = {}) {
  const base = opts.device ? ["-s", opts.device] : [];
  return execFileSync("adb", [...base, ...args], { maxBuffer: 64 * 1024 * 1024, timeout: 60000, ...opts2 });
}

function screenshotPng() {
  if (drv) return drv.screenshotPng();
  const captured = captureAndroidScreenshotWithPostProof(
    () => adb(["exec-out", "screencap", "-p"], { encoding: "buffer" }),
    (imageSha256) => captureAndroidQaBoundary(imageSha256)
  );
  lastCaptureBoundary = captured.boundary;
  return captured.png;
}

function captureAndroidQaBoundary(imageSha256) {
  const nonce = process.env.QA_CAPTURE_WINDOW_NONCE || "";
  if (!nonce) { lastCaptureBoundary = null; return; }
  const scopeGeneration = process.env.QA_CAPTURE_SCOPE_GENERATION || "";
  const readyReceiptDigest = process.env.QA_READY_RECEIPT_DIGEST || "";
  const runCaseId = process.env.QA_RUN_CASE_ID || "";
  const attempt = Number(process.env.QA_ATTEMPT || "0");
  if (!scopeGeneration || !readyReceiptDigest || !runCaseId || !Number.isInteger(attempt) || attempt <= 0) {
    throw new Error("QA capture boundary requires nonce, scope generation, receipt, run case, and attempt");
  }
  const requestId = randomUUID();
  const producerCommandGeneration = randomUUID();
  const request = Buffer.from(JSON.stringify({
    captureWindowNonce: nonce,
    scopeGeneration,
    readyReceiptDigest,
    runCaseId,
    attempt,
    producerCommandGeneration,
    imageSha256,
  }));
  const requestDigest = `sha256:${createHash("sha256").update(request).digest("hex")}`;
  const relative = `files/qa-bootstrap/inbox/${requestId}.capture`;
  const installScript =
    `umask 077; mkdir -p files/qa-bootstrap/inbox; tmp='${relative}.tmp'; ` +
    `cat >\"$tmp\"; chmod 600 \"$tmp\"; mv \"$tmp\" '${relative}'`;
  // adb shell -T + stdin, not exec-in: canary-15 (CI 34684737549, hosted
  // emulator) reached READY through the same helper on "shell" and then this
  // exec-in injection was torn at stdin EOF, so no request file, no App ack
  // (timed out below). Same channel difference as mobile#2476.
  adb(adbRunAsShellArgs("shell", opts.appPackage, installScript), {
    input: request,
    encoding: "utf8",
  });
  adb([
    "shell", "am", "broadcast",
    "-n", `${opts.appPackage}/build.raft.app.core.data.auth.QaCaptureBoundaryReceiver`,
    "-a", "build.raft.app.qa.CAPTURE_BOUNDARY",
    "--es", "qaCaptureRequestId", requestId,
    "--es", "qaCaptureRequestDigest", requestDigest,
  ], { encoding: "utf8" });
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    const logs = adb(["logcat", "-d", "-s", "SlockQaBootstrap:I", "*:S"], { encoding: "utf8" });
    const line = logs.split("\n").reverse().find((entry) => entry.includes(`QA_CAPTURE_BOUNDARY requestId=${requestId} `));
    if (line) {
      const fields = Object.fromEntries([...line.matchAll(/([A-Za-z][A-Za-z0-9]*)=([^\s]+)/g)].map((match) => [match[1], match[2]]));
      if (fields.imageSha256 !== imageSha256) throw new Error("QA capture boundary image digest mismatch");
      return fields;
    }
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 100);
  }
  // Discriminator on the timeout path (HuaTuo): read the request file back on
  // the plain shell channel. Absent → the injection did not land (channel);
  // present with the right sha → the App did not acknowledge (broadcast /
  // receiver side). Names, mode and digest only; no contents.
  let landed = "readback failed";
  try {
    const seen = adb(["shell", `run-as ${opts.appPackage} sh -c 'ls -laZ files/qa-bootstrap/inbox 2>&1; sha256sum ${relative} 2>&1 | cut -d" " -f1'`], { encoding: "utf8" });
    const lines = seen.trim().split("\n");
    const sha = lines[lines.length - 1]?.trim() ?? "";
    const present = lines.some((line) => line.endsWith(` ${requestId}.capture`));
    landed = `present=${present} sha_matches=${`sha256:${sha}` === requestDigest} inbox=${JSON.stringify(lines.slice(0, -1).join(" | ")).slice(0, 600)}`;
  } catch (error) {
    landed = `readback failed: ${String(error?.message ?? error).slice(0, 200)}`;
  }
  throw new Error(`QA capture boundary acknowledgement timed out (request file readback: ${landed})`);
}

// Structured view of the current uiautomator dump — the single source both
// dump_ui (rendered) and tap_text (fresh-bounds resolution) read from.
function uiNodes() {
  adb(["shell", "uiautomator", "dump", "/sdcard/qa_window_dump.xml"], { encoding: "utf8" });
  const xml = adb(["exec-out", "cat", "/sdcard/qa_window_dump.xml"], { encoding: "utf8" });
  const nodes = [];
  for (const m of xml.matchAll(/<node[^>]*>/g)) {
    const tag = m[0];
    const text = /text="([^"]*)"/.exec(tag)?.[1] ?? "";
    const desc = /content-desc="([^"]*)"/.exec(tag)?.[1] ?? "";
    const cls = /class="([^"]*)"/.exec(tag)?.[1] ?? "";
    const pkg = /package="([^"]*)"/.exec(tag)?.[1] ?? "";
    const resourceId = /resource-id="([^"]*)"/.exec(tag)?.[1] ?? "";
    const focused = /focused="true"/.test(tag);
    const clickable = /clickable="true"/.test(tag);
    const editable = /EditText|TextField|AutoComplete/i.test(cls);
    const b = /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(tag);
    nodes.push({ text, desc, cls, pkg, resourceId, editable, clickable, focused, bounds: b ? [+b[1], +b[2], +b[3], +b[4]] : null });
  }
  return nodes;
}

async function dumpUi() {
  if (drv) return drv.dumpUi();
  // Raw uiautomator XML for a busy screen is ~60KB of mostly-useless
  // attributes, and long dumps in context measurably degrade M3's output
  // format (the forward-message batch run drowned in malformed replies right
  // after dump-heavy turns). The agent only ever uses text + bounds (+focus),
  // so compress to exactly that — lossless for its purposes, ~10x smaller.
  const lines = [];
  for (const n of uiNodes()) {
    // v2 keep-set (first compression dropped empty EditTexts — the login
    // fields — and the agent went blind, batch rerun 29505605156): editable
    // nodes ALWAYS survive even when empty, clickable nodes survive so
    // icon-only buttons finally have dump bounds, and content-desc counts
    // as a label.
    if (!n.text && !n.desc && !n.focused && !n.editable && !n.clickable) continue;
    const bounds = n.bounds ? `[${n.bounds[0]},${n.bounds[1]}][${n.bounds[2]},${n.bounds[3]}]` : "";
    const flags = `${n.editable ? "[INPUT] " : ""}${n.focused ? "[FOCUSED] " : ""}${!n.editable && n.clickable && !n.text ? "[BTN] " : ""}`;
    lines.push(`${flags}"${n.text || n.desc}" ${bounds}`);
  }
  const compact = lines.join("\n") || "(no labelled nodes on screen)";
  return compact.length > 20000 ? compact.slice(0, 20000) + "\n[truncated]" : compact;
}

// tap_text: resolve the target's bounds from a FRESH dump at execution time
// and tap the center. Dialogs shift layout as selections/keyboards change, so
// coordinates computed from an earlier dump go stale — the edit-name 80-turn
// run burned 20+ turns blind-tapping a Create Channel button that had moved
// ~100px (Argus's checkpoint-timeline diagnosis).
//
// Match ladder tuned for Kuikly reality (edit-name round 4: dialog title
// "CREATE CHANNEL" and button "Create Channel" both matched, and the old
// clickable-preferred tiebreak was a no-op because Kuikly nodes don't expose
// clickable — the same gap behind the [BTN] marker caveat):
//   1. case-SENSITIVE exact  — "Create Channel" picks the button, not the
//      shouting title, with zero ambiguity;
//   2. case-insensitive exact;
//   3. case-insensitive substring.
// Ties break by clickable first (real on stock Android widgets), then by
// SMALLEST bounds area — a button is always smaller than the title/row/dialog
// containers that share its label.
function tapTextResolve(wanted) {
  const nodes = uiNodes().filter((n) => n.bounds);
  const norm = (s) => String(s).trim().toLowerCase();
  const w = norm(wanted);
  const exact = String(wanted).trim();
  let matches = nodes.filter((n) => n.text.trim() === exact || n.desc.trim() === exact);
  if (!matches.length) matches = nodes.filter((n) => norm(n.text) === w || norm(n.desc) === w);
  if (!matches.length) matches = nodes.filter((n) => (n.text && norm(n.text).includes(w)) || (n.desc && norm(n.desc).includes(w)));
  if (!matches.length) return { feedback: `tap_text: no on-screen node matches "${wanted}" — use dump_ui to see what is visible` };
  const clickables = matches.filter((n) => n.clickable);
  const pool = clickables.length ? clickables : matches;
  const area = (n) => Math.max(1, n.bounds[2] - n.bounds[0]) * Math.max(1, n.bounds[3] - n.bounds[1]);
  const pick = pool.reduce((a, b) => (area(b) < area(a) ? b : a));
  const [x1, y1, x2, y2] = pick.bounds;
  const x = Math.round((x1 + x2) / 2), y = Math.round((y1 + y2) / 2);
  const label = pick.text || pick.desc;
  const note = matches.length > 1 ? ` (${matches.length} matches; picked the smallest${clickables.length ? " clickable" : ""})` : "";
  return { x, y, feedback: `tapped "${label}" at [${x},${y}]${note}` };
}

// `adb shell input text` word-splits on the device shell; %s is its space
// escape. Reject shell metacharacters outright instead of trying to quote
// through two shells — acceptance inputs (emails, passwords, search words)
// don't need them, and a typo here would execute on the device.
async function inputText(value) {
  if (drv) return drv.text(value);
  if (/[\\'"`$&|;<>(){}\[\]!*?~#]/.test(value)) throw new Error("input text contains unsupported shell metacharacters");
  adb(["shell", "input", "text", value.replace(/ /g, "%s")]);
}

const KEYCODES = { back: "4", home: "3", enter: "66", tab: "61", del: "67", esc: "111", app_switch: "187" };

// Clear the focused text field: jump to end, then a single adb call with a
// burst of DEL keycodes (input keyevent accepts many codes per invocation —
// one process spawn instead of N, and no per-key model turns).
async function clearField() {
  if (drv) return drv.clear();
  adb(["shell", "input", "keyevent", "123"]); // MOVE_END
  adb(["shell", "input", "keyevent", ...Array(60).fill("67")]);
}

// After a text action, tell the model where the text actually landed: the
// focused node's text/bounds from a fresh uiautomator dump. Passwords render
// masked by the field itself; the runner additionally redacts secrets.
async function focusedFieldReport() {
  if (drv) return drv.focusedFieldReport();
  try {
    adb(["shell", "uiautomator", "dump", "/sdcard/qa_window_dump.xml"], { encoding: "utf8" });
    const xml = adb(["exec-out", "cat", "/sdcard/qa_window_dump.xml"], { encoding: "utf8" });
    const node = /<node[^>]*focused="true"[^>]*>/.exec(xml)?.[0];
    if (!node) return "typed; no focused field found in dump — verify via screenshot";
    const text = /text="([^"]*)"/.exec(node)?.[1] ?? "";
    const bounds = /bounds="(\[[^"]*\])"/.exec(node)?.[1] ?? "?";
    return `typed; focused field now contains "${text.slice(0, 80)}" at ${bounds}`;
  } catch {
    return "typed; focus check failed — verify via screenshot";
  }
}

function screenSize() {
  if (drv) return drv.screenSize();
  const out = adb(["shell", "wm", "size"], { encoding: "utf8" });
  const m = /(\d+)x(\d+)/.exec(out.split("\n").filter((l) => l.includes("size")).pop() || out);
  return m ? { w: Number(m[1]), h: Number(m[2]) } : { w: 0, h: 0 };
}

function preamble(size) {
  return [
    "You are a QA acceptance agent driving a REAL Android app on an emulator via adb.",
    "Work strictly toward the GOAL below, then declare a verdict. You will be shown a",
    "screenshot after every action. Be decisive; do not wander outside the goal.",
    "",
    `Screen: ${size.w}x${size.h} px. GOAL:`,
    opts.goal,
    // Spliced directly after the goal so the ban is adjacent to whatever text
    // it may need to override — every goal source passes through here, so no
    // caller-supplied goal can route around it (task #171).
    ...serverGuardSection(),
    "",
    "Respond with EXACTLY ONE action as strict JSON (no prose, no markdown):",
    '  {"action":"tap","x":<px>,"y":<px>}            tap a point',
    '  {"action":"tap_text","text":"<visible label>"}  tap the element with that text/description —',
    "                                                 bounds resolve from a FRESH dump at tap time",
    '  {"action":"long_press","x":<px>,"y":<px>}      long-press a point (message actions like',
    "                                                 reply/forward/task live behind long-press)",
    '  {"action":"open_link","url":"<https or app link>"}   open a link/deeplink via Android intent',
    '  {"action":"swipe","direction":"up|down|left|right"}   scroll the screen',
    '  {"action":"text","value":"<text>"}             type into the focused field (tap it first)',
    '  {"action":"key","name":"back|home|enter|tab|del|esc"}  press a key (esc hides the keyboard)',
    '  {"action":"clear"}                             clear the FOCUSED text field completely',
    '  {"action":"dump_ui"}                           list on-screen nodes as `"text" [x1,y1][x2,y2]` lines',
    "                                                 (focused field marked [FOCUSED])",
    '  {"action":"wait","seconds":<1-10>}             wait for loading',
    '  {"action":"launch"}                            (re)launch the app main activity',
    '  {"action":"note","milestone":"<3-6 words>","note":"<one-sentence conclusion>"}   record a',
    "                                                 progress checkpoint reviewers watch live",
    '  {"action":"finish","verdict":"pass|fail|error","reason":"<what you verified / what failed>",',
    '   "diagnosis":{"classification":"product|navigation_contract|fixture|budget|infra",',
    '     "confidence":"high|medium|low","target_surface_reached":true|false,',
    '     "surface_evidence_steps":[<turns proving target>],"surface_checkpoint_step":<prior note turn proving target>,"first_blocked_step":"<step or null>",',
    '     "missing_guidance":["<specific locator/navigation/precondition gap>"],',
    '     "first_product_failure":{"action":"<performed action>","expected":"<state>",',
    '       "observed":"<contradictory fresh state>","action_step":<performed turn>,',
    '       "evidence_steps":[<fresh post-action turns>]}|null}|null,',
    '   "annotations":[{"assertion_id":"<stable id from [id] in goal>","step":<turn>,"result":"pass|fail","bounds":"[x1,y1][x2,y2]","label":"[id] <short>"}]}',
    "",
    "Rules:",
    "- Before any action that may support a product failure, emit a note checkpoint while the target surface is visible; product diagnosis must cite that earlier note turn.",
    ...(opts.platform === "ios"
      ? [
          "- PLATFORM: iOS Simulator. key back = the left-edge back-swipe gesture; home/app_switch",
          "  do not exist — navigate with on-screen controls. Typing goes to the current first",
          "  responder; tap a field and confirm the cursor before text.",
        ]
      : []),
    "- PREFER tap_text for anything with a visible label: it re-resolves the element's bounds at",
    "  the moment of the tap, so it survives dialog/keyboard/selection layout shifts that make",
    "  earlier dump coordinates stale. Use coordinate tap only for unlabelled targets.",
    "- Before tapping anything unlabelled, use dump_ui and compute the tap point from node bounds",
    '  ("[x1,y1][x2,y2]" -> center). Screenshots alone are not reliable for coordinates.',
    "- The test account is email {{QA_EMAIL}} password {{QA_PASSWORD}} — type them as these",
    "  EXACT placeholder strings in a text action; the runner substitutes real values.",
    "- Typing goes to the FOCUSED field. When the on-screen keyboard opens, the layout may",
    "  shift — dump_ui AGAIN before tapping the next field, and verify focus moved (tap the",
    "  field, then check the screenshot for a cursor) before typing into it.",
    "- After typing into a field, verify via the screenshot that the text landed in the",
    "  INTENDED field. If text went to the wrong field, tap that field, use clear, and redo.",
    "- finish with verdict pass ONLY if you visually confirmed every requirement of the goal;",
    "  pass MUST set diagnosis=null; a pass paired with a blocker diagnosis is inconsistent.",
    "  use product fail only when the target surface was reached, a real action was performed,",
    "  and fresh evidence contradicts the expected state. Navigation/fixture/budget gaps are",
    "  diagnosis causes, not product failures; the runner preserves them as harness/incomplete.",
    "  use fail only when the APP UNDER TEST contradicts the goal or its own UI is blocked/loading;",
    "  use error when a foreign system surface (Pixel Launcher, System UI, emulator/OS dialog)",
    "  prevents testing. Never turn a launcher/emulator failure into a product fail.",
    "- VERDICT BEFORE CLEANUP: once every requirement is verified, finish IMMEDIATELY.",
    "  Cleanup steps (delete test data etc.) come after the requirements in the goal are",
    "  proven — if cleanup looks risky or hard to find, report it unfinished in the reason",
    "  instead of burning the budget; a lost verdict is worse than leftover test data.",
    "- Menu items like Select/multi-select/edit are ENTRANCES, not leaves — open them one",
    "  level before concluding a feature is missing.",
    "- Use note at each MEANINGFUL milestone (logged in, target page reached, a requirement",
    "  verified or contradicted) with your conclusion so far — reviewers follow the run live",
    "  through these. Milestones, not commentary: never two notes in a row, never for taps.",
    "- In finish, include ONE annotations entry per goal requirement: assertion_id = the stable",
    "  bracketed [id] printed in the goal; label MUST start with the same [id]; step = the turn whose",
    "  screenshot shows the verified (or missing) region, bounds = that region's uiautomator",
    '  bounds "[x1,y1][x2,y2]" from your dump_ui, label = 3-6 words naming the requirement.',
    "  Reviewers see these as boxes drawn on the evidence — accuracy matters more than count.",
    ...playbookSection(),
  ].join("\n");
}

// The operation playbook (docs/qa-acceptance-operations.md) is the versioned
// record of what past runs taught — every fail-round root cause becomes a rule
// there, and injecting it here is what makes the next agent tap better (artin's
// ask). A missing file degrades silently so ad-hoc checkouts still run.
function playbookSection() {
  try {
    const text = fs.readFileSync(opts.playbook, "utf8");
    return ["", "Operation playbook (lessons from previous runs — follow these):", text];
  } catch {
    return [];
  }
}

// Per-run model accounting (artin's ask): every call's token usage plus the
// full (redacted) reply stream land in the evidence as session.jsonl, and the
// aggregate goes into verdict.json. Session records reference screenshots by
// step number instead of embedding image bytes.
const modelUsage = { calls: 0, input: 0, output: 0, cacheRead: 0, cacheWrite: 0 };
const usageDetail = [];
let sessionSeq = 0;
function recordSession(kind, payload) {
  sessionSeq++;
  try {
    fs.appendFileSync(path.join(opts.out, "session.jsonl"), redact(JSON.stringify({ seq: sessionSeq, kind, ...payload })) + "\n");
  } catch {}
}

async function callModel(models, model, messages) {
  const maxAttempts = Math.max(1, Math.min(6, Number(process.env.QA_MODEL_MAX_ATTEMPTS || "3")));
  return runWithModelRetries({
    capacity: modelCapacity,
    maxAttempts,
    retryBudgetMs: Number(process.env.QA_MODEL_RETRY_BUDGET_MS || "300000"),
    invoke: async () => {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 120000);
      let msg;
      try {
        msg = await models.complete(model, { messages },
          { apiKey: piKey(), maxTokens: 1500, signal: controller.signal, timeoutMs: 120000 });
      } finally {
        clearTimeout(timer);
      }
      if (msg.stopReason === "error" || msg.stopReason === "aborted") {
        const error = new Error(msg.errorMessage || msg.stopReason);
        error.modelResponse = msg;
        throw error;
      }
      const text = msg.content.filter((b) => b.type === "text").map((b) => b.text).join("\n");
      const u = msg.usage ?? {};
      modelUsage.calls++;
      modelUsage.input += u.input ?? 0;
      modelUsage.output += u.output ?? 0;
      modelUsage.cacheRead += u.cacheRead ?? 0;
      modelUsage.cacheWrite += u.cacheWrite ?? 0;
      usageDetail.push({ call: modelUsage.calls, input: u.input ?? 0, output: u.output ?? 0, cacheRead: u.cacheRead ?? 0, cacheWrite: u.cacheWrite ?? 0 });
      recordSession("model_reply", { usage: { input: u.input ?? 0, output: u.output ?? 0, cacheRead: u.cacheRead ?? 0 }, reply: text });
      return text;
    },
    onError: async ({ attempt, maxAttempts: attempts, classification, status, requestId, retryAfterMs, retryDelayMs: delay, retryable, retryBudgetMs, retryBudgetRemainingMs, retryBudgetExceeded, message }) => {
      recordSession("model_error", {
        attempt, max_attempts: attempts, classification,
        status, request_id: requestId, retry_after_ms: retryAfterMs,
        retry_delay_ms: delay, retryable, retry_budget_ms: retryBudgetMs,
        retry_budget_remaining_ms: retryBudgetRemainingMs, retry_budget_exceeded: retryBudgetExceeded,
        error: redact(message).slice(0, 300),
      });
      try {
        fs.appendFileSync(path.join(opts.out, "model-capacity.jsonl"), `${JSON.stringify({
          ts: Date.now(), event: "provider_error", attempt, max_attempts: attempts,
          classification, status, request_id: requestId,
          retry_after_ms: retryAfterMs, retry_delay_ms: delay, retryable,
          retry_budget_ms: retryBudgetMs, retry_budget_remaining_ms: retryBudgetRemainingMs,
          retry_budget_exceeded: retryBudgetExceeded,
        })}\n`);
      } catch {}
    },
  });
}

function parseAction(text) {
  const m = /\{[\s\S]*\}/.exec(text);
  if (!m) throw new Error("no JSON action in model reply");
  return JSON.parse(m[0]);
}

// Live checkpoints (artin's ask: the agent records key points + conclusions
// DURING the run, visible on the Stamp run page as a progress timeline).
// Reuses the existing evidence channel — kind=checkpoint, seq = turn number
// (Argus's interface, #proj-quality). The model only ever emits a `note`
// action; this helper is the sole place that touches STAMP_TOKEN, so the
// credential never enters model context or evidence. Fire-and-forget: a slow
// or missing Stamp must never stall or fail the acceptance run.
const stampPending = [];
function stampCheckpoint(turn, milestone, note) {
  const url = process.env.STAMP_URL, token = process.env.STAMP_TOKEN, rc = process.env.STAMP_RUN_CASE_ID;
  const body = { milestone: redact(String(milestone)).slice(0, 120), note: redact(String(note ?? "")).slice(0, 500), turn, screenshot: `step-${String(Math.max(0, turn)).padStart(2, "0")}.png` };
  recordSession("checkpoint", body);
  if (!url || !token || !rc) return "noted (local only — Stamp not configured)";
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10000);
  stampPending.push(
    fetch(`${url}/api/agent/run-cases/${rc}/evidence?kind=checkpoint&seq=${turn}&step=${turn}`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify(body),
      signal: controller.signal,
    }).then(
      (r) => console.error(`stamp checkpoint turn=${turn} ${r.ok ? "ok" : `http ${r.status}`}`),
      (e) => console.error(`stamp checkpoint turn=${turn} failed: ${String(e?.message || e).slice(0, 120)}`),
    ).finally(() => clearTimeout(timer)),
  );
  return "noted";
}

// Bound context growth: only the newest screenshots stay as image blocks;
// older turns keep a text placeholder (M3 has 1M ctx, but images dominate).
const KEEP_IMAGES = 3;
function pruneImages(messages) {
  let seen = 0;
  for (let i = messages.length - 1; i >= 0; i--) {
    const content = messages[i].content;
    if (!Array.isArray(content)) continue;
    for (let j = content.length - 1; j >= 0; j--) {
      if (content[j].type === "image") {
        seen++;
        if (seen > KEEP_IMAGES) content[j] = { type: "text", text: "[earlier screenshot omitted]" };
      }
    }
  }
  // Old dump results are the other context hog (and stale bounds are a trap
  // anyway once the screen changed): keep the last 2 long text blocks intact,
  // trim older ones to a stub. The preamble (first message) is never trimmed.
  let longSeen = 0;
  for (let i = messages.length - 1; i >= 1; i--) {
    const content = messages[i].content;
    if (!Array.isArray(content)) continue;
    for (let j = content.length - 1; j >= 0; j--) {
      const block = content[j];
      if (block.type !== "text" || block.text.length <= 3000) continue;
      longSeen++;
      if (longSeen > 2) content[j] = { type: "text", text: block.text.slice(0, 300) + "\n[older dump trimmed — bounds are stale; dump_ui again if needed]" };
    }
  }
}

// Evidence annotation (docs/qa-acceptance/annotation-spec.md, artin's ask):
// draw what the agent verified/touched onto copies of the step screenshots so
// reviewers see it at a glance — green box = requirement verified, red box =
// requirement failed, yellow crosshair = tap point (on the frame the agent was
// looking at when it decided the tap). Output: step-NN.annotated.png next to
// the originals; originals stay untouched (evidence integrity). sharp is an
// optional dependency — when absent (ad-hoc checkout), annotation is skipped
// and noted, never fatal.
async function annotateEvidence(size, taps, assertions) {
  const byStep = buildEvidenceOverlays(taps, assertions, redact);
  if (!byStep.size) return [];
  let sharp;
  try {
    sharp = (await import("sharp")).default;
  } catch {
    console.error("sharp not installed — evidence annotation skipped");
    return [];
  }
  const written = [];
  for (const [step, overlay] of byStep) {
    const src = path.join(opts.out, `step-${String(step).padStart(2, "0")}.png`);
    if (!fs.existsSync(src)) continue;
    const dst = src.replace(/\.png$/, ".annotated.png");
    try {
      const svg = Buffer.from(`<svg width="${size.w}" height="${size.h}" xmlns="http://www.w3.org/2000/svg">${overlay}</svg>`);
      await sharp(src).composite([{ input: svg, top: 0, left: 0 }]).png().toFile(dst);
      // Annotated copy inherits the original's mtime: capture-time ordering
      // on the Stamp run page pairs it with the moment it depicts, not the
      // end-of-run annotation pass.
      const st = fs.statSync(src);
      fs.utimesSync(dst, st.atime, st.mtime);
      written.push(path.basename(dst));
    } catch (e) {
      console.error(`annotation failed for ${src}: ${String(e?.message || e).slice(0, 120)}`);
    }
  }
  return written;
}

async function main() {
  if (!opts.goal) { console.error("no --goal / QA_GOAL"); process.exit(2); }
  if (!piKey()) { console.error("no PI_API_KEY/MINIMAX_API_KEY"); process.exit(2); }
  const limits = normalizeExecutionLimits(opts);
  if (!limits) { console.error("invalid execution budget: turns 20-160, minutes 10-90, no-progress 3-30 required"); process.exit(2); }
  Object.assign(opts, limits);
  fs.mkdirSync(opts.out, { recursive: true });
  modelCapacity = capacityClientFromEnv({
    outDir: opts.out,
    onEvent: (event) => recordSession("model_capacity", event),
  });
  if (opts.platform === "ios") {
    if (!opts.iosIoDir) { console.error("--platform ios requires --ios-io-dir / QA_LOOP_IO_DIR"); process.exit(2); }
    const { createIosDriver } = await import("./ios_driver.mjs");
    drv = createIosDriver({ ioDir: opts.iosIoDir, udid: opts.iosUdid, bundle: opts.appPackage });
    console.error(`waiting for the iOS driver (${drv.driver}) to open the app...`);
    await drv.waitReady();
  }
  const stepsPath = path.join(opts.out, "steps.jsonl");
  const pidPath = path.join(opts.out, "app-pids.txt");
  const started = Date.now();
  const deadline = started + opts.maxMinutes * 60000;

  const models = await loadModels();
  const model = models.getModel(String(process.env.PI_PROVIDER || "minimax"), String(process.env.PI_MODEL || "MiniMax-M3"));
  const size = screenSize();
  const actionLedger = new Map();
  const observationLedger = new Map();
  const checkpointLedger = new Map();

  const shoot = (turn) => {
    const png = screenshotPng();
    const imageSha256 = createHash("sha256").update(png).digest("hex");
    observationLedger.set(turn, imageSha256);
    const fileName = `step-${String(turn).padStart(2, "0")}.png`;
    fs.writeFileSync(path.join(opts.out, fileName), png);
    if (lastCaptureBoundary) {
      if (lastCaptureBoundary.imageSha256 !== imageSha256) {
        throw new Error("QA capture boundary does not bind the saved screenshot bytes");
      }
      fs.appendFileSync(
        path.join(opts.out, "device-capture-boundaries.jsonl"),
        JSON.stringify({ ...lastCaptureBoundary, fileName }) + "\n"
      );
    }
    return { type: "image", data: png.toString("base64"), mimeType: "image/png" };
  };
  // ts per step lets the perf collectors (collect_perf*.mjs) derive per-step
  // timing windows for the perf.json slowest-steps section.
  const captureAppPid = () => {
    if (opts.platform !== "android") return;
    try {
      const pids = adb(["shell", "pidof", opts.appPackage], { encoding: "utf8" }).trim();
      if (pids) fs.appendFileSync(pidPath, `${pids}\n`);
    } catch { /* absence is recorded by the post-run log-scope gate */ }
  };
  const logStep = (rec) => {
    captureAppPid();
    fs.appendFileSync(stepsPath, redact(JSON.stringify({ ts: Date.now(), ...rec })) + "\n");
  };

  const messages = [{
    role: "user",
    content: [{ type: "text", text: preamble(size) }, { type: "text", text: "Current screen:" }, shoot(0)],
    timestamp: Date.now(),
  }];
  logStep({ turn: 0, event: "start", goal: opts.goal, screen: size });
  // Runner-side automatic milestones bracket the model's self-reported ones
  // (auto:* prefix distinguishes source on the Stamp timeline).
  stampCheckpoint(0, "auto:start", opts.goal);

  let verdict = null, reason = "", turns = 0, executionDiagnosis = null;
  let noProgressTriggered = false, lastObservationFingerprint = "", sameObservationTurns = 0;
  // Why this exists: "undecided" has several causes with OPPOSITE next actions —
  // budget exhausted (raise the budget and rerun) vs the no-progress fuse (raise
  // that threshold) vs the agent finishing without proving the goal (investigate
  // the fixture/case). Those look identical in the verdict, and a reader who
  // cannot tell them apart sends someone to fix a fixture that is not broken.
  // The cause is already computed below for the forced-verdict prompt; it was
  // only ever logged to steps.jsonl, so the verdict could not self-report it.
  let terminatedBy = "goal_completed";
  const tapAnnotations = [];
  const assertionAnnotations = [];
  while (turns < opts.maxTurns && Date.now() < deadline) {
    turns++;
    pruneImages(messages);
    let action;
    try {
      // Malformed replies must leave ZERO trace in history. The #1081
      // placeholder approach fixed self-mimicry of the bad format but created
      // a new mimicry surface: the forward-message batch run (29504132395)
      // showed the model parroting the placeholder text itself for 9 straight
      // reprompts. v3: retry with a TRANSIENT corrective message that exists
      // only for the retry call — on success the history reads user(screen) ->
      // assistant(good action), pristine. Retries live inside the turn (the
      // wall clock still bounds them); 3 strikes is an infrastructure error.
      const corrective = {
        role: "user",
        content: [{ type: "text", text: "Your previous reply contained no JSON action and was discarded. Respond with EXACTLY ONE action as a strict JSON object from the documented list — no prose, no markdown fences, no explanations." }],
        timestamp: Date.now(),
      };
      let reply;
      for (let attempt = 1; ; attempt++) {
        reply = await callModel(models, model, attempt === 1 ? messages : [...messages, corrective]);
        try {
          action = parseAction(reply);
          break;
        } catch {
          logStep({ turn: turns, event: "malformed_reply", attempt, preview: redact(reply.slice(0, 200)) });
          if (attempt >= 3) throw new Error("model returned 3 consecutive replies with no JSON action");
        }
      }
      messages.push({ role: "assistant", content: [{ type: "text", text: reply }], timestamp: Date.now() });
    } catch (e) {
      logStep({ turn: turns, event: "model_error", error: String(e?.message || e).slice(0, 300) });
      verdict = "error"; reason = `model call failed: ${String(e?.message || e).slice(0, 200)}`;
      executionDiagnosis = {
        classification: "infra", confidence: "high", target_surface_reached: false, surface_evidence_steps: [],
        first_blocked_step: "model-call", missing_guidance: [], first_product_failure: null,
      };
      break;
    }

    if (action.action === "finish") {
      executionDiagnosis = bindDiagnosisToLedger(normalizeSelfDiagnosis(action.diagnosis, redact), {
        actions: actionLedger, observations: observationLedger, checkpoints: checkpointLedger,
      });
      verdict = action.verdict === "error" ? "error" : effectiveExecutorVerdict(action.verdict, executionDiagnosis);
      reason = String(action.reason || "");
      if (Array.isArray(action.annotations)) assertionAnnotations.push(...action.annotations);
      logStep({ turn: turns, action });
      break;
    }

    let feedback = "ok";
    let mechanicallyPerformed = false;
    let checkpointMilestone = null;
    try {
      switch (action.action) {
        case "tap":
          if (drv) await drv.tap(Math.round(action.x), Math.round(action.y));
          else adb(["shell", "input", "tap", String(Math.round(action.x)), String(Math.round(action.y))]);
          mechanicallyPerformed = true;
          // The agent decided this tap while looking at the PREVIOUS turn's
          // screenshot — that's the frame the crosshair belongs on.
          tapAnnotations.push(screenshotPixelTap(turns, action.x, action.y));
          break;
        case "tap_text": {
          const wanted = String(action.text ?? "").trim();
          if (!wanted) { feedback = "tap_text requires text"; break; }
          // Same fresh-bounds/match-ladder policy on both platforms; the iOS
          // driver resolves via the harness describe protocol host-side.
          const r = drv ? await drv.tapText(wanted) : tapTextResolve(wanted);
          feedback = r.feedback;
          if (r.x !== undefined) {
            if (drv) await drv.tap(r.x, r.y);
            else adb(["shell", "input", "tap", String(r.x), String(r.y)]);
            mechanicallyPerformed = true;
            // A fresh dump refines the coordinate but does not capture a new
            // screenshot. The action was still chosen from the pre-action
            // frame, just like a coordinate tap.
            tapAnnotations.push(screenshotPixelTap(turns, r.x, r.y));
          }
          break;
        }
        case "swipe": {
          if (drv) { await drv.swipe(["up", "down", "left", "right"].includes(action.direction) ? action.direction : "up"); break; }
          const { w, h } = size;
          const c = { up: [w / 2, h * 0.7, w / 2, h * 0.3], down: [w / 2, h * 0.3, w / 2, h * 0.7], left: [w * 0.8, h / 2, w * 0.2, h / 2], right: [w * 0.2, h / 2, w * 0.8, h / 2] }[action.direction] || [w / 2, h * 0.7, w / 2, h * 0.3];
          adb(["shell", "input", "swipe", ...c.map((n) => String(Math.round(n))), "300"]);
          mechanicallyPerformed = true;
          break;
        }
        case "long_press":
          if (drv) await drv.longPress(Math.round(action.x), Math.round(action.y));
          // Same-point swipe with a long duration is the adb long-press idiom.
          else adb(["shell", "input", "swipe",
            String(Math.round(action.x)), String(Math.round(action.y)),
            String(Math.round(action.x)), String(Math.round(action.y)), "800"]);
          mechanicallyPerformed = true;
          tapAnnotations.push(screenshotPixelTap(turns, action.x, action.y));
          break;
        case "open_link": {
          const url = String(action.url ?? "");
          if (!/^[a-z][a-z0-9+.-]*:\/\/\S+$/i.test(url)) { feedback = "open_link needs a scheme://... url"; break; }
          if (drv) drv.openLink(url);
          else adb(["shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", url]);
          mechanicallyPerformed = true;
          break;
        }
        case "text": {
          let v = String(action.value ?? "");
          for (const [ph, real] of Object.entries(SECRETS)) v = v.split(ph).join(real);
          await inputText(v);
          mechanicallyPerformed = true;
          // The recurring login failure mode is text landing in the wrong
          // field because a tap didn't move focus. Report the focused node's
          // content back mechanically so the model sees the landing spot
          // without spending a turn on dump_ui (batch round finding ③).
          feedback = redact(await focusedFieldReport());
          break;
        }
        case "key":
          if (drv) await drv.key(String(action.name));
          else adb(["shell", "input", "keyevent", KEYCODES[action.name] || String(action.name)]);
          mechanicallyPerformed = true;
          break;
        case "clear": await clearField(); mechanicallyPerformed = true; break;
        case "dump_ui": feedback = await dumpUi(); break;
        case "wait": await new Promise((r) => setTimeout(r, Math.min(10, Math.max(1, Number(action.seconds) || 2)) * 1000)); mechanicallyPerformed = true; break;
        case "launch":
          if (drv) await drv.launch();
          else adb(["shell", "am", "start", "-n", `${opts.appPackage}/.MainActivity`]);
          mechanicallyPerformed = true;
          break;
        case "note": {
          const milestone = String(action.milestone ?? "").trim();
          if (!milestone) { feedback = "note needs a non-empty milestone"; break; }
          // The screenshot the model was looking at when it drew this
          // conclusion is the previous turn's — same frame logic as taps.
          feedback = stampCheckpoint(turns - 1, milestone, action.note);
          checkpointMilestone = redact(milestone).slice(0, 200);
          break;
        }
        default: feedback = `unknown action "${action.action}" — use one of the documented actions`;
      }
    } catch (e) {
      feedback = `action failed: ${String(e?.message || e).slice(0, 200)}`;
    }
    // Give the UI a beat to settle before observing (animations are disabled
    // on the CI AVD, but page transitions/network still take real time).
    await new Promise((r) => setTimeout(r, 1500));

    logStep({ turn: turns, action: { ...action, value: action.action === "text" ? redact(String(action.value ?? "")) : action.value }, feedback: feedback === "ok" ? "ok" : `${feedback.length} chars` });
    const actionKey = repeatedActionKey(action, redact);
    const actionFact = {
      performed: mechanicallyPerformed && !String(feedback).startsWith("action failed:"),
      identity_sha256: createHash("sha256").update(actionKey).digest("hex"),
      action_type: String(action.action),
      safe_summary: `${String(action.action)}:${createHash("sha256").update(actionKey).digest("hex").slice(0, 12)}`,
    };
    actionLedger.set(turns, actionFact);
    recordSession("user_turn", { turn: turns, feedback: feedback === "ok" ? "ok" : `${feedback.length} chars`, screenshot: `step-${String(turns).padStart(2, "0")}.png` });
    const observation = shoot(turns);
    logStep({
      turn: turns, event: "action_fact", action_step: turns, action_type: actionFact.action_type,
      action_identity_sha256: actionFact.identity_sha256, action_summary: actionFact.safe_summary,
      performed: actionFact.performed, post_action_screenshot_sha256: observationLedger.get(turns),
    });
    if (checkpointMilestone) {
      const checkpointFact = { milestone: checkpointMilestone, screenshot_sha256: observationLedger.get(turns) };
      checkpointLedger.set(turns, checkpointFact);
      logStep({ turn: turns, event: "surface_checkpoint", checkpoint_step: turns, ...checkpointFact });
    }
    messages.push({
      role: "user",
      content: [
        { type: "text", text: `Result: ${feedback}\nTurn ${turns}/${opts.maxTurns}. Current screen:` },
        observation,
      ],
      timestamp: Date.now(),
    });
    const fingerprint = observationFingerprint({
      actionKey,
      screenshotSha256: observationLedger.get(turns),
      feedback: feedback === "ok" ? "ok" : feedback,
    });
    sameObservationTurns = fingerprint === lastObservationFingerprint ? sameObservationTurns + 1 : 1;
    lastObservationFingerprint = fingerprint;
    if (sameObservationTurns >= opts.noProgressTurns) {
      noProgressTriggered = true;
      logStep({ turn: turns, event: "no_progress_fuse", repeated_action: action.action, unchanged_observation_turns: sameObservationTurns });
      break;
    }
  }

  if (!verdict) {
    // Budget exhausted with no verdict: one forced-verdict call turns a
    // zero-information exit 2 into an honest conclusion (lifecycle round 2:
    // two 8-minute runs uploaded evidence but died verdict-less). The prompt
    // forbids upgrading unverified requirements to pass; exit 2 stays
    // reserved for real infrastructure (emulator dead, model unreachable).
    // ONE decision, two renderings. @JingKe asked whether the steps.jsonl cause and
    // the verdict's terminated_by are the same computation or a second one — a fair
    // question, because a reader using the log as a stand-in for the field needs them
    // to agree. They were two ternaries over the same conditions, which can drift.
    // Now the human-readable cause is derived FROM terminatedBy, so the log and the
    // verdict cannot disagree.
    terminatedBy = noProgressTriggered
      ? "no_progress"
      : turns >= opts.maxTurns ? "max_turns" : "wall_clock";
    // @LiBai's blocker: a lookup replaces the ternary's else-branch fallback, so an
    // unmapped terminatedBy yields undefined -- which then interpolates into the
    // MODEL PROMPT below as the literal string "undefined". Silent, never red, and a
    // corrupted instruction to boot. Unreachable today (the assignment above emits
    // exactly these three), but it is the same shape this PR exists to remove: an
    // unenumerated value quietly becoming a plausible-looking one. Keep the fallback,
    // and make it self-report rather than blend in.
    const budgetCause = {
      no_progress: `no-progress fuse (${opts.noProgressTurns} repeated actions)`,
      max_turns: `turn budget (${opts.maxTurns})`,
      wall_clock: `wall clock (${opts.maxMinutes}min)`,
    }[terminatedBy] ?? `UNMAPPED termination reason "${terminatedBy}" — add it to this table and to the gate's terminated_by mapping`;
    logStep({ turn: turns, event: "forced_verdict", cause: budgetCause });
    try {
      pruneImages(messages);
      messages.push({
        role: "user",
        content: [{ type: "text", text: `EXECUTION BUDGET STOP (${budgetCause}). Produce one finish action including the full diagnosis object. An unverified requirement is NOT automatically a product failure. Use classification=product only if you reached the target surface, performed the relevant action, and fresh evidence contradicts the expected state; otherwise classify navigation_contract, fixture, budget, or infra. Name each unverified requirement and include annotations for whatever you DID verify.` }],
        timestamp: Date.now(),
      });
      const reply = await callModel(models, model, messages);
      const action = parseAction(reply);
      if (action.action === "finish") {
        executionDiagnosis = bindDiagnosisToLedger(normalizeSelfDiagnosis(action.diagnosis, redact), {
          actions: actionLedger, observations: observationLedger, checkpoints: checkpointLedger,
        });
        verdict = action.verdict === "error" ? "error" : effectiveExecutorVerdict(action.verdict, executionDiagnosis);
        reason = `[forced at ${budgetCause}] ${String(action.reason || "")}`;
        if (Array.isArray(action.annotations)) assertionAnnotations.push(...action.annotations);
        logStep({ turn: turns, action });
      }
    } catch (e) {
      logStep({ turn: turns, event: "forced_verdict_failed", error: String(e?.message || e).slice(0, 200) });
    }
  }
  if (!verdict) {
    verdict = "error";
    reason = turns >= opts.maxTurns ? `no verdict within ${opts.maxTurns} turns` : `wall-clock cap ${opts.maxMinutes}min hit`;
    executionDiagnosis = {
      classification: "infra",
      confidence: "high",
      target_surface_reached: false,
      surface_evidence_steps: [],
      first_blocked_step: null,
      missing_guidance: [],
      first_product_failure: null,
    };
  }

  // Model judgment is not authoritative for infrastructure ownership. If the
  // final Android surface mechanically names a known foreign system process,
  // preserve the screenshots/annotations but force an honest harness error.
  // A Raft/app-under-test ANR does not match this narrow allowlist and remains
  // a product fail.
  if (opts.platform === "android" && verdict === "fail") {
    try {
      const blocker = detectForeignAndroidSystemBlocker(uiNodes());
      if (blocker) {
        logStep({ turn: turns, event: "foreign_system_blocker", blocker });
        verdict = "error";
        reason = `harness/system blocker (${blocker.code}): ${blocker.label}; product requirements were not evaluated`;
        executionDiagnosis = {
          classification: "infra",
          confidence: "high",
          target_surface_reached: false,
          surface_evidence_steps: [],
          first_blocked_step: "foreign-system-blocker",
          missing_guidance: [],
          first_product_failure: null,
        };
      }
    } catch (e) {
      logStep({ turn: turns, event: "system_blocker_check_failed", error: String(e?.message || e).slice(0, 200) });
    }
  }

  const annotated = await annotateEvidence(size, tapAnnotations, assertionAnnotations);

  const result = {
    goal: redact(opts.goal), verdict, reason: redact(reason), turns,
    durationMs: Date.now() - started, model: "MiniMax-M3", screen: size,
    execution_diagnosis: executionDiagnosis,
    // goal_completed here means the loop ended on its own terms, NOT that the goal
    // passed — read `verdict` for that. It only rules out budget/fuse truncation.
    terminated_by: terminatedBy,
    execution_budget: { max_turns: opts.maxTurns, max_minutes: opts.maxMinutes, no_progress_turns: opts.noProgressTurns },
    usage: { ...modelUsage, totalTokens: modelUsage.input + modelUsage.output },
    annotations: {
      assertion: assertionAnnotations.map((a) => normalizeAssertionAnnotation({
        ...a,
        label: redact(String(a.label ?? "")),
      })),
      taps: tapAnnotations.length,
      annotatedFiles: annotated,
    },
  };
  fs.writeFileSync(path.join(opts.out, "usage.json"), JSON.stringify({ ...modelUsage, totalTokens: modelUsage.input + modelUsage.output, model: "MiniMax-M3", perCall: usageDetail }, null, 2));
  fs.writeFileSync(path.join(opts.out, "verdict.json"), JSON.stringify(result, null, 2));
  stampCheckpoint(turns, "auto:verdict", `${verdict}: ${reason}`);
  await Promise.allSettled(stampPending);
  if (drv) await drv.finish();
  console.log(JSON.stringify(result, null, 2));
  process.exit(verdict === "pass" ? 0 : verdict === "fail" ? 1 : 2);
}

main().catch((e) => { console.error(String(e?.stack || e)); process.exit(2); });
