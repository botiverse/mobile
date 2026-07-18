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
import fs from "node:fs";
import path from "node:path";

const argv = process.argv.slice(2);
const arg = (f, d) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : d; };

const opts = {
  goal: arg("--goal", process.env.QA_GOAL || ""),
  out: arg("--out", "qa-evidence"),
  maxTurns: Number(arg("--max-turns", process.env.QA_MAX_TURNS || "30")),
  maxMinutes: Number(arg("--max-minutes", "20")),
  device: arg("--device", ""),
  appPackage: arg("--package", "build.raft.app"),
  playbook: arg("--playbook", process.env.QA_PLAYBOOK || "docs/qa-acceptance-operations.md"),
  // "android" drives adb directly (unchanged); "ios" delegates every device
  // primitive to scripts/qa/ios_driver.mjs (simctl + XCUITest loop harness).
  platform: arg("--platform", process.env.QA_PLATFORM || "android"),
  iosIoDir: arg("--ios-io-dir", process.env.QA_LOOP_IO_DIR || ""),
  iosUdid: arg("--ios-udid", process.env.QA_IOS_UDID || "booted"),
};

// Non-null only on --platform ios; every device-touching helper below branches
// on it first, so the Android code path is byte-for-byte what it always was.
let drv = null;

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
  return adb(["exec-out", "screencap", "-p"], { encoding: "buffer" });
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
    const focused = /focused="true"/.test(tag);
    const clickable = /clickable="true"/.test(tag);
    const editable = /EditText|TextField|AutoComplete/i.test(cls);
    const b = /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(tag);
    nodes.push({ text, desc, editable, clickable, focused, bounds: b ? [+b[1], +b[2], +b[3], +b[4]] : null });
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
    '  {"action":"finish","verdict":"pass|fail","reason":"<what you verified / what failed>",',
    '   "annotations":[{"step":<turn>,"result":"pass|fail","bounds":"[x1,y1][x2,y2]","label":"<short>"}]}',
    "",
    "Rules:",
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
    "  if blocked, loading forever, or the UI contradicts the goal, finish with verdict fail",
    "  and a concrete reason. You have a limited turn budget — no exploration detours.",
    "- VERDICT BEFORE CLEANUP: once every requirement is verified, finish IMMEDIATELY.",
    "  Cleanup steps (delete test data etc.) come after the requirements in the goal are",
    "  proven — if cleanup looks risky or hard to find, report it unfinished in the reason",
    "  instead of burning the budget; a lost verdict is worse than leftover test data.",
    "- Menu items like Select/multi-select/edit are ENTRANCES, not leaves — open them one",
    "  level before concluding a feature is missing.",
    "- Use note at each MEANINGFUL milestone (logged in, target page reached, a requirement",
    "  verified or contradicted) with your conclusion so far — reviewers follow the run live",
    "  through these. Milestones, not commentary: never two notes in a row, never for taps.",
    "- In finish, include ONE annotations entry per goal requirement: step = the turn whose",
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
  for (let attempt = 1; attempt <= 3; attempt++) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 120000);
    try {
      const msg = await models.complete(model, { messages },
        { apiKey: piKey(), maxTokens: 1500, signal: controller.signal, timeoutMs: 120000 });
      if (msg.stopReason === "error" || msg.stopReason === "aborted") throw new Error(msg.errorMessage || msg.stopReason);
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
    } catch (e) {
      if (attempt === 3) throw e;
      await new Promise((r) => setTimeout(r, 3000 * attempt));
    } finally { clearTimeout(timer); }
  }
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
const BOUNDS_RE = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/;
function escapeXml(s) {
  return String(s).replace(/[<>&"']/g, (c) => ({ "<": "&lt;", ">": "&gt;", "&": "&amp;", '"': "&quot;", "'": "&apos;" }[c]));
}

async function annotateEvidence(size, taps, assertions) {
  const byStep = new Map();
  const add = (step, svg) => {
    const n = Number(step);
    if (!Number.isInteger(n) || n < 0) return;
    byStep.set(n, (byStep.get(n) || "") + svg);
  };
  for (const t of taps) {
    const s = 22;
    add(t.step,
      `<line x1="${t.x - s}" y1="${t.y - s}" x2="${t.x + s}" y2="${t.y + s}" stroke="#ffd400" stroke-width="5"/>` +
      `<line x1="${t.x - s}" y1="${t.y + s}" x2="${t.x + s}" y2="${t.y - s}" stroke="#ffd400" stroke-width="5"/>` +
      `<circle cx="${t.x}" cy="${t.y}" r="${s + 8}" fill="none" stroke="#ffd400" stroke-width="3"/>`);
  }
  for (const a of assertions) {
    const m = BOUNDS_RE.exec(String(a.bounds ?? ""));
    if (!m) continue;
    const [x1, y1, x2, y2] = m.slice(1).map(Number);
    if (x2 <= x1 || y2 <= y1) continue;
    const color = a.result === "fail" ? "#ff2d2d" : "#00c853";
    const label = escapeXml(redact(String(a.label ?? "")).slice(0, 60));
    const labelY = y1 >= 34 ? y1 - 10 : y2 + 26;
    add(a.step,
      `<rect x="${x1}" y="${y1}" width="${x2 - x1}" height="${y2 - y1}" fill="none" stroke="${color}" stroke-width="5" rx="4"/>` +
      (label
        ? `<text x="${x1}" y="${labelY}" font-family="sans-serif" font-size="26" font-weight="bold" fill="${color}" stroke="#000000" stroke-width="0.6">${label}</text>`
        : ""));
  }
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
  fs.mkdirSync(opts.out, { recursive: true });
  if (opts.platform === "ios") {
    if (!opts.iosIoDir) { console.error("--platform ios requires --ios-io-dir / QA_LOOP_IO_DIR"); process.exit(2); }
    const { createIosDriver } = await import("./ios_driver.mjs");
    drv = createIosDriver({ ioDir: opts.iosIoDir, udid: opts.iosUdid, bundle: opts.appPackage });
    console.error("waiting for the iOS loop harness (xcodebuild test) to launch the app...");
    await drv.waitReady();
  }
  const stepsPath = path.join(opts.out, "steps.jsonl");
  const started = Date.now();
  const deadline = started + opts.maxMinutes * 60000;

  const models = await loadModels();
  const model = models.getModel(String(process.env.PI_PROVIDER || "minimax"), String(process.env.PI_MODEL || "MiniMax-M3"));
  const size = screenSize();

  const shoot = (turn) => {
    const png = screenshotPng();
    fs.writeFileSync(path.join(opts.out, `step-${String(turn).padStart(2, "0")}.png`), png);
    return { type: "image", data: png.toString("base64"), mimeType: "image/png" };
  };
  // ts per step lets the perf collectors (collect_perf*.mjs) derive per-step
  // timing windows for the perf.json slowest-steps section.
  const logStep = (rec) => fs.appendFileSync(stepsPath, redact(JSON.stringify({ ts: Date.now(), ...rec })) + "\n");

  const messages = [{
    role: "user",
    content: [{ type: "text", text: preamble(size) }, { type: "text", text: "Current screen:" }, shoot(0)],
    timestamp: Date.now(),
  }];
  logStep({ turn: 0, event: "start", goal: opts.goal, screen: size });
  // Runner-side automatic milestones bracket the model's self-reported ones
  // (auto:* prefix distinguishes source on the Stamp timeline).
  stampCheckpoint(0, "auto:start", opts.goal);

  let verdict = null, reason = "", turns = 0;
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
      break;
    }

    if (action.action === "finish") {
      verdict = action.verdict === "pass" ? "pass" : "fail";
      reason = String(action.reason || "");
      if (Array.isArray(action.annotations)) assertionAnnotations.push(...action.annotations);
      logStep({ turn: turns, action });
      break;
    }

    let feedback = "ok";
    try {
      switch (action.action) {
        case "tap":
          if (drv) await drv.tap(Math.round(action.x), Math.round(action.y));
          else adb(["shell", "input", "tap", String(Math.round(action.x)), String(Math.round(action.y))]);
          // The agent decided this tap while looking at the PREVIOUS turn's
          // screenshot — that's the frame the crosshair belongs on.
          tapAnnotations.push({ step: turns - 1, x: Math.round(action.x), y: Math.round(action.y) });
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
            // Crosshair on the CURRENT frame: bounds came from a fresh dump,
            // not the previous screenshot.
            tapAnnotations.push({ step: turns, x: r.x, y: r.y });
          }
          break;
        }
        case "swipe": {
          if (drv) { await drv.swipe(["up", "down", "left", "right"].includes(action.direction) ? action.direction : "up"); break; }
          const { w, h } = size;
          const c = { up: [w / 2, h * 0.7, w / 2, h * 0.3], down: [w / 2, h * 0.3, w / 2, h * 0.7], left: [w * 0.8, h / 2, w * 0.2, h / 2], right: [w * 0.2, h / 2, w * 0.8, h / 2] }[action.direction] || [w / 2, h * 0.7, w / 2, h * 0.3];
          adb(["shell", "input", "swipe", ...c.map((n) => String(Math.round(n))), "300"]);
          break;
        }
        case "long_press":
          if (drv) await drv.longPress(Math.round(action.x), Math.round(action.y));
          // Same-point swipe with a long duration is the adb long-press idiom.
          else adb(["shell", "input", "swipe",
            String(Math.round(action.x)), String(Math.round(action.y)),
            String(Math.round(action.x)), String(Math.round(action.y)), "800"]);
          tapAnnotations.push({ step: turns - 1, x: Math.round(action.x), y: Math.round(action.y) });
          break;
        case "open_link": {
          const url = String(action.url ?? "");
          if (!/^[a-z][a-z0-9+.-]*:\/\/\S+$/i.test(url)) { feedback = "open_link needs a scheme://... url"; break; }
          if (drv) drv.openLink(url);
          else adb(["shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", url]);
          break;
        }
        case "text": {
          let v = String(action.value ?? "");
          for (const [ph, real] of Object.entries(SECRETS)) v = v.split(ph).join(real);
          await inputText(v);
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
          break;
        case "clear": await clearField(); break;
        case "dump_ui": feedback = await dumpUi(); break;
        case "wait": await new Promise((r) => setTimeout(r, Math.min(10, Math.max(1, Number(action.seconds) || 2)) * 1000)); break;
        case "launch":
          if (drv) await drv.launch();
          else adb(["shell", "am", "start", "-n", `${opts.appPackage}/.MainActivity`]);
          break;
        case "note": {
          const milestone = String(action.milestone ?? "").trim();
          if (!milestone) { feedback = "note needs a non-empty milestone"; break; }
          // The screenshot the model was looking at when it drew this
          // conclusion is the previous turn's — same frame logic as taps.
          feedback = stampCheckpoint(turns - 1, milestone, action.note);
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
    recordSession("user_turn", { turn: turns, feedback: feedback === "ok" ? "ok" : `${feedback.length} chars`, screenshot: `step-${String(turns).padStart(2, "0")}.png` });
    messages.push({
      role: "user",
      content: [
        { type: "text", text: `Result: ${feedback}\nTurn ${turns}/${opts.maxTurns}. Current screen:` },
        shoot(turns),
      ],
      timestamp: Date.now(),
    });
  }

  if (!verdict) {
    // Budget exhausted with no verdict: one forced-verdict call turns a
    // zero-information exit 2 into an honest conclusion (lifecycle round 2:
    // two 8-minute runs uploaded evidence but died verdict-less). The prompt
    // forbids upgrading unverified requirements to pass; exit 2 stays
    // reserved for real infrastructure (emulator dead, model unreachable).
    const budgetCause = turns >= opts.maxTurns ? `turn budget (${opts.maxTurns})` : `wall clock (${opts.maxMinutes}min)`;
    logStep({ turn: turns, event: "forced_verdict", cause: budgetCause });
    try {
      pruneImages(messages);
      messages.push({
        role: "user",
        content: [{ type: "text", text: `BUDGET EXHAUSTED (${budgetCause}). Produce your final verdict NOW as a single finish action. Judge ONLY from what you actually verified: any requirement you did not visually confirm makes the verdict fail, and the reason must name each unverified requirement. Include annotations for whatever you DID verify.` }],
        timestamp: Date.now(),
      });
      const reply = await callModel(models, model, messages);
      const action = parseAction(reply);
      if (action.action === "finish") {
        verdict = action.verdict === "pass" ? "pass" : "fail";
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
  }

  const annotated = await annotateEvidence(size, tapAnnotations, assertionAnnotations);

  const result = {
    goal: redact(opts.goal), verdict, reason: redact(reason), turns,
    durationMs: Date.now() - started, model: "MiniMax-M3", screen: size,
    usage: { ...modelUsage, totalTokens: modelUsage.input + modelUsage.output },
    annotations: {
      assertion: assertionAnnotations.map((a) => ({ ...a, label: redact(String(a.label ?? "")) })),
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
