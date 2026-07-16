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
};

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
  return adb(["exec-out", "screencap", "-p"], { encoding: "buffer" });
}

function dumpUi() {
  adb(["shell", "uiautomator", "dump", "/sdcard/qa_window_dump.xml"], { encoding: "utf8" });
  const xml = adb(["exec-out", "cat", "/sdcard/qa_window_dump.xml"], { encoding: "utf8" });
  // uiautomator XML for a busy screen can be huge; the useful part (visible
  // nodes with text/desc/bounds) survives a hard cap fine for M3.
  return xml.length > 60000 ? xml.slice(0, 60000) + "\n<!-- truncated -->" : xml;
}

// `adb shell input text` word-splits on the device shell; %s is its space
// escape. Reject shell metacharacters outright instead of trying to quote
// through two shells — acceptance inputs (emails, passwords, search words)
// don't need them, and a typo here would execute on the device.
function inputText(value) {
  if (/[\\'"`$&|;<>(){}\[\]!*?~#]/.test(value)) throw new Error("input text contains unsupported shell metacharacters");
  adb(["shell", "input", "text", value.replace(/ /g, "%s")]);
}

const KEYCODES = { back: "4", home: "3", enter: "66", tab: "61", del: "67", esc: "111", app_switch: "187" };

// Clear the focused text field: jump to end, then a single adb call with a
// burst of DEL keycodes (input keyevent accepts many codes per invocation —
// one process spawn instead of N, and no per-key model turns).
function clearField() {
  adb(["shell", "input", "keyevent", "123"]); // MOVE_END
  adb(["shell", "input", "keyevent", ...Array(60).fill("67")]);
}

// After a text action, tell the model where the text actually landed: the
// focused node's text/bounds from a fresh uiautomator dump. Passwords render
// masked by the field itself; the runner additionally redacts secrets.
function focusedFieldReport() {
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
    '  {"action":"long_press","x":<px>,"y":<px>}      long-press a point (message actions like',
    "                                                 reply/forward/task live behind long-press)",
    '  {"action":"open_link","url":"<https or app link>"}   open a link/deeplink via Android intent',
    '  {"action":"swipe","direction":"up|down|left|right"}   scroll the screen',
    '  {"action":"text","value":"<text>"}             type into the focused field (tap it first)',
    '  {"action":"key","name":"back|home|enter|tab|del|esc"}  press a key (esc hides the keyboard)',
    '  {"action":"clear"}                             clear the FOCUSED text field completely',
    '  {"action":"dump_ui"}                           get uiautomator XML (text/desc/bounds of every node)',
    '  {"action":"wait","seconds":<1-10>}             wait for loading',
    '  {"action":"launch"}                            (re)launch the app main activity',
    '  {"action":"finish","verdict":"pass|fail","reason":"<what you verified / what failed>",',
    '   "annotations":[{"step":<turn>,"result":"pass|fail","bounds":"[x1,y1][x2,y2]","label":"<short>"}]}',
    "",
    "Rules:",
    "- Before tapping anything non-obvious, use dump_ui and compute the tap point from node bounds",
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

async function callModel(models, model, messages) {
  for (let attempt = 1; attempt <= 3; attempt++) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 120000);
    try {
      const msg = await models.complete(model, { messages },
        { apiKey: piKey(), maxTokens: 1500, signal: controller.signal, timeoutMs: 120000 });
      if (msg.stopReason === "error" || msg.stopReason === "aborted") throw new Error(msg.errorMessage || msg.stopReason);
      return msg.content.filter((b) => b.type === "text").map((b) => b.text).join("\n");
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
  const logStep = (rec) => fs.appendFileSync(stepsPath, redact(JSON.stringify(rec)) + "\n");

  const messages = [{
    role: "user",
    content: [{ type: "text", text: preamble(size) }, { type: "text", text: "Current screen:" }, shoot(0)],
    timestamp: Date.now(),
  }];
  logStep({ turn: 0, event: "start", goal: opts.goal, screen: size });

  let verdict = null, reason = "", turns = 0, malformedStreak = 0;
  const tapAnnotations = [];
  const assertionAnnotations = [];
  while (turns < opts.maxTurns && Date.now() < deadline) {
    turns++;
    pruneImages(messages);
    let action;
    try {
      const reply = await callModel(models, model, messages);
      try {
        action = parseAction(reply);
        malformedStreak = 0;
        messages.push({ role: "assistant", content: [{ type: "text", text: reply }], timestamp: Date.now() });
      } catch {
        // A malformed reply is a reprompt, not a run-killer — but its TEXT
        // must never enter the history: case-03 round-2 (run 29492906221)
        // showed the model imitating its own bad format on alternating turns
        // once one malformed reply was kept, burning 21/46 turns. Keep the
        // assistant/user alternation with a placeholder, discard the bad
        // text, and correct in place; only a streak is an infrastructure
        // error. Still costs a turn so it can't loop free.
        malformedStreak++;
        logStep({ turn: turns, event: "malformed_reply", streak: malformedStreak, preview: redact(reply.slice(0, 200)) });
        if (malformedStreak >= 3) {
          verdict = "error"; reason = "model returned 3 consecutive replies with no JSON action";
          break;
        }
        messages.push({
          role: "assistant",
          content: [{ type: "text", text: "[reply discarded: it contained no JSON action]" }],
          timestamp: Date.now(),
        });
        messages.push({
          role: "user",
          content: [{ type: "text", text: "That reply was discarded. Respond with EXACTLY ONE action as a strict JSON object from the documented list — no prose, no markdown fences, no explanations." }],
          timestamp: Date.now(),
        });
        continue;
      }
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
          adb(["shell", "input", "tap", String(Math.round(action.x)), String(Math.round(action.y))]);
          // The agent decided this tap while looking at the PREVIOUS turn's
          // screenshot — that's the frame the crosshair belongs on.
          tapAnnotations.push({ step: turns - 1, x: Math.round(action.x), y: Math.round(action.y) });
          break;
        case "swipe": {
          const { w, h } = size;
          const c = { up: [w / 2, h * 0.7, w / 2, h * 0.3], down: [w / 2, h * 0.3, w / 2, h * 0.7], left: [w * 0.8, h / 2, w * 0.2, h / 2], right: [w * 0.2, h / 2, w * 0.8, h / 2] }[action.direction] || [w / 2, h * 0.7, w / 2, h * 0.3];
          adb(["shell", "input", "swipe", ...c.map((n) => String(Math.round(n))), "300"]);
          break;
        }
        case "long_press":
          // Same-point swipe with a long duration is the adb long-press idiom.
          adb(["shell", "input", "swipe",
            String(Math.round(action.x)), String(Math.round(action.y)),
            String(Math.round(action.x)), String(Math.round(action.y)), "800"]);
          tapAnnotations.push({ step: turns - 1, x: Math.round(action.x), y: Math.round(action.y) });
          break;
        case "open_link": {
          const url = String(action.url ?? "");
          if (!/^[a-z][a-z0-9+.-]*:\/\/\S+$/i.test(url)) { feedback = "open_link needs a scheme://... url"; break; }
          adb(["shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", url]);
          break;
        }
        case "text": {
          let v = String(action.value ?? "");
          for (const [ph, real] of Object.entries(SECRETS)) v = v.split(ph).join(real);
          inputText(v);
          // The recurring login failure mode is text landing in the wrong
          // field because a tap didn't move focus. Report the focused node's
          // content back mechanically so the model sees the landing spot
          // without spending a turn on dump_ui (batch round finding ③).
          feedback = redact(focusedFieldReport());
          break;
        }
        case "key": adb(["shell", "input", "keyevent", KEYCODES[action.name] || String(action.name)]); break;
        case "clear": clearField(); break;
        case "dump_ui": feedback = dumpUi(); break;
        case "wait": await new Promise((r) => setTimeout(r, Math.min(10, Math.max(1, Number(action.seconds) || 2)) * 1000)); break;
        case "launch": adb(["shell", "am", "start", "-n", `${opts.appPackage}/.MainActivity`]); break;
        default: feedback = `unknown action "${action.action}" — use one of the documented actions`;
      }
    } catch (e) {
      feedback = `action failed: ${String(e?.message || e).slice(0, 200)}`;
    }
    // Give the UI a beat to settle before observing (animations are disabled
    // on the CI AVD, but page transitions/network still take real time).
    await new Promise((r) => setTimeout(r, 1500));

    logStep({ turn: turns, action: { ...action, value: action.action === "text" ? redact(String(action.value ?? "")) : action.value }, feedback: feedback === "ok" ? "ok" : `${feedback.length} chars` });
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
    verdict = "error";
    reason = turns >= opts.maxTurns ? `no verdict within ${opts.maxTurns} turns` : `wall-clock cap ${opts.maxMinutes}min hit`;
  }

  const annotated = await annotateEvidence(size, tapAnnotations, assertionAnnotations);

  const result = {
    goal: redact(opts.goal), verdict, reason: redact(reason), turns,
    durationMs: Date.now() - started, model: "MiniMax-M3", screen: size,
    annotations: {
      assertion: assertionAnnotations.map((a) => ({ ...a, label: redact(String(a.label ?? "")) })),
      taps: tapAnnotations.length,
      annotatedFiles: annotated,
    },
  };
  fs.writeFileSync(path.join(opts.out, "verdict.json"), JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result, null, 2));
  process.exit(verdict === "pass" ? 0 : verdict === "fail" ? 1 : 2);
}

main().catch((e) => { console.error(String(e?.stack || e)); process.exit(2); });
