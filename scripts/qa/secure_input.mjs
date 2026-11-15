// Secure-input boundary for the QA acceptance executor.
//
// Contract (also in the header of acceptance_agent.mjs, "Secret input"):
//   * The model only ever names a secret: `${secret:QA_TEST_PASSWORD}` (the
//     legacy `{{QA_EMAIL}}` / `{{QA_PASSWORD}}` placeholders map onto the same
//     names). The value is read from the runner's environment at the moment it
//     is typed, typed once, and dropped. It is never put in a host or device
//     argv, a temp/command file, a log line, a report or a model prompt.
//   * Android types it through the emulator console (a TCP socket from this
//     process; see emulatorConsoleType). iOS drivers have no such channel and
//     refuse (fail closed); so does Android without a reachable console.
//   * LOGIN WINDOW: from entering the login page (the app's web-login attempt
//     opens, a browser/custom tab takes focus, the model declares it, or a
//     secret is about to be typed) until positive receipts. While it is open:
//     no screenshot is taken, saved or sent to the model; recording is
//     stopped and the segment that may show the entry is deleted; no UI dump,
//     focused-field text, driver error detail or screen-derived feedback goes
//     to the model or the logs. Only a "suppressed" placeholder with the time
//     window is recorded (capture-suppression.json, steps.jsonl).
//   * The window closes only on POSITIVE receipts (evaluateResumeReceipts).
//     Anything short of that keeps suppression on and the run ends UNKNOWN.

import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";

export const SECRET_ALLOWLIST = Object.freeze(["QA_TEST_EMAIL", "QA_TEST_PASSWORD"]);
const LEGACY_PLACEHOLDERS = Object.freeze({ "{{QA_EMAIL}}": "QA_TEST_EMAIL", "{{QA_PASSWORD}}": "QA_TEST_PASSWORD" });
const SECRET_REF = /^\$\{secret:([A-Z][A-Z0-9_]*)\}$/;
const ANY_SECRET_MARKER = /\$\{secret:|\{\{QA_(?:EMAIL|PASSWORD)\}\}/;
// Shorter values cannot be scanned for without redacting innocent text.
export const MIN_SECRET_LENGTH = 4;

export function secretValues(env = process.env) {
  return SECRET_ALLOWLIST.map((name) => env[name] || "").filter(Boolean);
}

// Classify one model `text` action value. A secret must be the WHOLE value
// (exactly one reference): mixing literal text with a secret would make the
// typed string partly model-controlled and partly secret, which we never need.
export function parseTextValue(raw, env = process.env) {
  const value = String(raw ?? "");
  const trimmed = value.trim();
  const name = LEGACY_PLACEHOLDERS[trimmed] ?? SECRET_REF.exec(trimmed)?.[1];
  if (name) {
    if (!SECRET_ALLOWLIST.includes(name)) throw new SecureInputError(`secret ${name} is not on the allowlist`);
    const secret = env[name] || "";
    if (!secret) throw new SecureInputError(`secret ${name} is not configured for this run`);
    if (secret.length < MIN_SECRET_LENGTH) throw new SecureInputError(`secret ${name} is too short to protect`);
    return { kind: "secret", name, value: secret };
  }
  if (ANY_SECRET_MARKER.test(value)) {
    throw new SecureInputError("a secret reference must be the entire text value (no surrounding text)");
  }
  // The model never knows a real value, so a literal match means the value
  // leaked into its context somehow: refuse rather than type it on argv.
  for (const secret of secretValues(env)) {
    if (value.includes(secret)) throw new SecureInputError("plain text contains a configured secret value; use ${secret:NAME}");
  }
  return { kind: "plain", value };
}

// Error whose message is safe for logs/model (never carries a value).
export class SecureInputError extends Error {}

export function redactSecrets(text, env = process.env) {
  let out = String(text ?? "");
  for (const secret of secretValues(env)) out = out.split(secret).join("***");
  return out;
}

export function containsSecret(text, env = process.env) {
  const s = String(text ?? "");
  return secretValues(env).some((secret) => s.includes(secret));
}

// ------------------------------------------------------- Android typing channel
//
// The only channel that keeps the value out of EVERY process argv (host and
// device) and out of every file: the emulator console's `event text`, spoken
// over a TCP socket from this process. The console turns the text into key
// events inside the emulator, so no adb, shell, `input` or `cmd` process ever
// carries it. A device without a console (a physical phone, an emulator whose
// console cannot be reached or authenticated) has no safe channel: refuse.
//
// Printable ASCII only, no line breaks and no leading/trailing space, so the
// console line is unambiguous and the value is typed exactly.
export function encodeConsoleText(secret) {
  const value = String(secret);
  if (!/^[\x21-\x7e](?:[\x20-\x7e]*[\x21-\x7e])?$/.test(value)) {
    throw new SecureInputError("secret contains characters the emulator console cannot type exactly");
  }
  return value;
}

// The console port is the ADB serial's own (emulator-<port>). An override
// (QA_EMULATOR_CONSOLE_PORT) is accepted only when it names that same port: a
// console that belongs to another emulator must never receive a secret.
export function emulatorConsolePort(serial, env = process.env) {
  const m = /^emulator-(\d+)$/.exec(String(serial || ""));
  const own = m ? Number(m[1]) : null;
  const override = env.QA_EMULATOR_CONSOLE_PORT;
  if (override !== undefined && override !== "") {
    if (!/^\d+$/.test(override) || Number(override) !== own) return null;
  }
  return own;
}

// Types `secret` through the emulator console. Resolves on the console's OK;
// every failure is a SecureInputError with a fixed message (console replies
// are never echoed: they could quote the line that was sent).
//
// Console binding: before anything is typed the console is asked `avd name`
// and the answer must equal `expectAvdName` (read from the ADB target's
// ro.boot.qemu.avd_name). A console that answers for another emulator, or a
// missing expectation, refuses: the secret goes only to the device whose
// ownership was verified over ADB.
export async function emulatorConsoleType({ port, secret, tokenFile, expectAvdName, connect = net.connect, timeoutMs = 15000 }) {
  const text = encodeConsoleText(secret);
  if (!Number.isInteger(port) || port <= 0) throw new SecureInputError("no emulator console: refusing to type a secret (no argv-free channel)");
  if (typeof expectAvdName !== "string" || !/^[A-Za-z0-9._-]+$/.test(expectAvdName)) {
    throw new SecureInputError("emulator console not bound to the ADB target (AVD name unknown): refusing to type a secret");
  }
  let token = "";
  try { token = fs.readFileSync(tokenFile || path.join(os.homedir(), ".emulator_console_auth_token"), "utf8").trim(); } catch { /* checked below */ }
  return await new Promise((resolve, reject) => {
    const socket = connect({ host: "127.0.0.1", port });
    let buffer = "";
    let stage = "banner";
    let settled = false;
    const done = (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      buffer = "";
      try { socket.end("quit\n"); } catch { /* closing */ }
      socket.destroy();
      error ? reject(error) : resolve({ channel: "emulator-console", port });
    };
    const timer = setTimeout(() => done(new SecureInputError("emulator console timed out")), timeoutMs);
    socket.setEncoding("utf8");
    socket.on("error", () => done(new SecureInputError("emulator console unreachable")));
    socket.on("close", () => done(new SecureInputError("emulator console closed early")));
    socket.on("data", (chunk) => {
      buffer += chunk;
      if (/(^|\n)KO\b/.test(buffer)) return done(new SecureInputError(`emulator console refused (${stage})`));
      if (!/(^|\n)OK\r?\n/.test(buffer)) return;
      const banner = buffer;
      buffer = "";
      const askName = () => { stage = "avd"; socket.write("avd name\n"); };
      if (stage === "banner") {
        if (/Authentication required/i.test(banner)) {
          if (!token) return done(new SecureInputError("emulator console needs an auth token that is not readable"));
          stage = "auth";
          socket.write(`auth ${token}\n`);
        } else askName();
      } else if (stage === "auth") {
        askName();
      } else if (stage === "avd") {
        const name = banner.split(/\r?\n/).map((l) => l.trim()).find((l) => l && l !== "OK");
        if (name !== expectAvdName) return done(new SecureInputError("emulator console belongs to a different emulator than the ADB target: refusing to type a secret"));
        stage = "text";
        socket.write(`event text ${text}\n`);
      } else if (stage === "text") {
        done(null);
      }
    });
  });
}

// ---------------------------------------------------------------- capture gate

export const SUPPRESSION_CONTRACT = "qa-capture-suppression/v1";

// The run this record belongs to. The egress gate compares it with its own
// environment: a record from another run/attempt is no record.
export function runBinding(env = process.env) {
  return { github_run_id: String(env.GITHUB_RUN_ID || ""), github_run_attempt: String(env.GITHUB_RUN_ATTEMPT || "") };
}

export class CaptureGate {
  constructor(now = () => Date.now(), env = process.env) {
    this.now = now;
    this.active = null;
    this.phases = [];
    this.unresolved = false;
    // Set only when the producer ran to its end. A record that is not
    // completed (crash, kill, early exit) proves nothing about the time after
    // its last write: the egress gate treats it as unknown.
    this.completed = false;
    this.binding = runBinding(env);
  }

  get suppressed() {
    return this.active !== null || this.unresolved;
  }

  begin(turn, reason) {
    if (this.active) return this.active;
    this.active = { start_ts: this.now(), start_turn: turn, reason, status: "active", recording: "unknown" };
    this.phases.push(this.active);
    return this.active;
  }

  // Close only with positive receipts; returns the closed phase.
  resume(turn, receipts) {
    const phase = this.active;
    if (!phase || this.unresolved) return null;
    Object.assign(phase, { end_ts: this.now(), end_turn: turn, status: "resumed", receipts });
    this.active = null;
    return phase;
  }

  // Suppression stays on for the rest of the run.
  markUnknown(turn, reasons) {
    const phase = this.active ?? this.begin(turn, "unknown");
    Object.assign(phase, { status: "UNKNOWN", unknown_ts: this.now(), unknown_turn: turn, reasons });
    this.unresolved = true;
    return phase;
  }

  assertCaptureAllowed(what) {
    if (this.suppressed) throw new Error(`${what} refused: capture suppressed during a secret-input phase`);
  }

  summary() {
    return {
      contract: SUPPRESSION_CONTRACT,
      unresolved: this.unresolved,
      active: this.active !== null,
      completed: this.completed,
      binding: { ...this.binding },
      phases: this.phases.map((p) => ({ ...p })),
    };
  }
}

// ------------------------------------------------------------- receipt checks

// Parse `dumpsys window` for the focused window's package.
export function parseFocusedPackage(dumpsysWindow) {
  const m = /mCurrentFocus=Window\{[^}]*\s([A-Za-z0-9_.]+)\/[^\s}]+\}/.exec(String(dumpsysWindow ?? ""));
  return m ? m[1] : null;
}

// true = keyboard positively hidden; false = shown; null = could not tell.
export function parseKeyboardShown(dumpsysInputMethod) {
  const text = String(dumpsysInputMethod ?? "");
  const values = [...text.matchAll(/\bmInputShown=(true|false)\b/g)].map((m) => m[1]);
  if (!values.length) return null;
  return values.includes("true");
}

// `settings get secure default_input_method` -> IME package (null if unreadable).
export function parseImePackage(settingValue) {
  const m = /^([A-Za-z0-9_.]+)\/[A-Za-z0-9_.$]+$/.exec(String(settingValue ?? "").trim());
  return m ? m[1] : null;
}

// Surfaces that never hold login input and stay capturable (system chrome,
// permission prompts, the system resolver, the stock launchers). Everything
// else that is not the app under test is treated as a possible login surface
// (fail closed): a login provider's own app or an unlisted browser cannot be
// recognised by name, so an unknown package opens a login window too.
const SYSTEM_SURFACES = new Set([
  "android", "com.android.systemui", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
  "com.google.android.apps.nexuslauncher", "com.android.launcher3",
]);
export function isExternalLoginSurface(pkg, appPackage) {
  if (!pkg || pkg === appPackage || SYSTEM_SURFACES.has(pkg)) return false;
  return true;
}

// The app's own web-login diagnostics (compose/shared WebLoginAttempt.kt and
// AppLogin.kt, tag SlockAppLogin).
export const LOGIN_PAGE_MARKER = /^web_login_attempt phase=(?:opening|awaiting_callback)$/;
export const LOGIN_COMPLETED_MARKER = /^app_login_complete result=app_shell reason=exchanged$/;
// The attempt ended WITHOUT a session (browser closed / came back without a
// callback): the sign-in page was left; this is the "sign-out" style exit.
export const LOGIN_ABANDONED_MARKER = /^web_login_attempt phase=idle reason=(?:browser_closed|resumed_without_callback)$/;
export const DEFAULT_COMPLETION_MARKER = LOGIN_COMPLETED_MARKER;

// `adb logcat -v epoch` lines: "<secs>.<ms> <pid> <tid> <lvl> <tag>: <msg>".
export function parseLoginLog(logcatEpoch) {
  const out = [];
  for (const line of String(logcatEpoch ?? "").split("\n")) {
    const m = /^\s*(\d+)\.(\d+)\s+(\d+)\s+(\d+)\s+[VDIWEF]\s+SlockAppLogin\s*:\s*(.*?)\s*$/.exec(line);
    if (m) out.push({ epoch: Number(`${m[1]}.${m[2]}`), msg: m[5], key: `${m[3]}/${m[4]}/${m[5]}` });
  }
  return out;
}

export function findCompletionReceipt(logcatEpoch, sinceEpochSec, marker = LOGIN_COMPLETED_MARKER) {
  const hit = parseLoginLog(logcatEpoch).find((e) => Number.isFinite(sinceEpochSec) && e.epoch >= sinceEpochSec && marker.test(e.msg));
  return hit ? { epoch: hit.epoch, marker: marker.source } : null;
}

// The newest login-flow exit after `sinceEpochSec`: completed or abandoned.
export function findLoginExit(logcatEpoch, sinceEpochSec) {
  if (!Number.isFinite(sinceEpochSec)) return null;
  let exit = null;
  for (const e of parseLoginLog(logcatEpoch)) {
    if (e.epoch < sinceEpochSec) continue;
    if (LOGIN_COMPLETED_MARKER.test(e.msg)) exit = { kind: "login_completed", epoch: e.epoch };
    else if (LOGIN_ABANDONED_MARKER.test(e.msg)) exit = { kind: "login_abandoned", epoch: e.epoch };
    else if (LOGIN_PAGE_MARKER.test(e.msg)) exit = null; // a newer attempt reopened the page
  }
  return exit;
}

export function loginPageOpenedSince(logcatEpoch, sinceEpochSec) {
  return parseLoginLog(logcatEpoch).some((e) => (!Number.isFinite(sinceEpochSec) || e.epoch >= sinceEpochSec) && LOGIN_PAGE_MARKER.test(e.msg));
}

// Parse a uiautomator XML dump into nodes (kept in memory only).
export function parseUiautomatorNodes(xml) {
  const nodes = [];
  for (const m of String(xml ?? "").matchAll(/<node\b[^>]*>/g)) {
    const tag = m[0];
    const attr = (k) => new RegExp(`\\s${k}="([^"]*)"`).exec(tag)?.[1] ?? "";
    const cls = attr("class");
    const b = /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(tag);
    nodes.push({
      text: attr("text"), desc: attr("content-desc"), cls, pkg: attr("package"), resourceId: attr("resource-id"),
      hint: attr("hint"), focused: attr("focused") === "true", clickable: attr("clickable") === "true",
      password: attr("password") === "true",
      editable: /EditText|TextField|AutoComplete/i.test(cls),
      bounds: b ? [+b[1], +b[2], +b[3], +b[4]] : null,
    });
  }
  return nodes;
}

// The only screen-derived thing the model gets inside a login window: where
// the input fields are (bounds + a password flag). No text, label, hint,
// value or package — enough to tap a field and type a secret reference.
export function formOutline(uiXml) {
  const fields = parseUiautomatorNodes(uiXml).filter((n) => n.editable && n.bounds);
  if (!fields.length) return "form outline: no input fields found";
  return "form outline (bounds only, login window):\n" + fields.map((n, i) =>
    `input#${i + 1}${n.password ? " (password)" : ""}${n.focused ? " [FOCUSED]" : ""} [${n.bounds[0]},${n.bounds[1]}][${n.bounds[2]},${n.bounds[3]}]`).join("\n");
}

const unescapeXml = (s) => String(s).replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&");

// Resume rule. Every receipt below must be POSITIVE:
//   login_exit        the app logged a login-complete or an abandoned/closed
//                     attempt after the window began (a window opened by a
//                     browser alone, with no login attempt and no secret typed,
//                     may close on browser_closed instead);
//   screen_switch     the app under test owns the focused window;
//   inputs_cleared    every external login surface was force-stopped AND its
//                     data cleared (form fields, autofill, saved passwords,
//                     cookies), and its process is confirmed gone;
//   ime_cleared       the keyboard is hidden and, if a secret was typed, the
//                     IME's learned data (suggestion strip) was cleared;
//   autofill_off      if a secret was typed, the autofill service was off.
// The accessibility tree is ADVISORY: it can veto (keep suppression) but it
// is never a receipt, and a missing tree proves nothing either way. The XML is
// inspected in memory and dropped.
export function evaluateResumeReceipts({
  appPackage, entrySurfaces, loginExit, completion, secretTyped = true, loginAttempted = true,
  focusedPackage, keyboardShown, externalSurfacesCleared, externalSurfacesStopped, imeCleared, autofillOff,
  uiXml, env = process.env,
}) {
  const reasons = [];
  const receipts = {};
  const exit = loginExit ?? (completion ? { kind: "login_completed", epoch: completion.epoch } : null);
  const external = [...(entrySurfaces ?? [])].filter((p) => p && p !== appPackage);
  const cleared = externalSurfacesCleared ?? externalSurfacesStopped;

  if (exit) receipts.login_exit = { kind: exit.kind, channel: "logcat SlockAppLogin", epoch: exit.epoch };
  else if (!secretTyped && !loginAttempted && external.length && cleared === true) receipts.login_exit = { kind: "browser_closed" };
  else reasons.push("no positive login-complete or sign-in-exit receipt from the app log");

  if ([...(entrySurfaces ?? [])].some((p) => !p || p === appPackage)) {
    reasons.push("input was entered on a surface whose window identity cannot prove a screen switch");
  } else if (secretTyped && !external.length) reasons.push("login surface unknown");
  else if (focusedPackage !== appPackage) reasons.push("focused window is not the app under test");
  else receipts.screen_switch = { from: external, to: appPackage };

  if (!external.length && !secretTyped) receipts.inputs_cleared = { surfaces: [] };
  else if (external.length && cleared === true) receipts.inputs_cleared = { cleared: external };
  else reasons.push("could not confirm the login surface (form, autofill, saved passwords) was cleared");

  if (keyboardShown !== false) reasons.push(keyboardShown === null || keyboardShown === undefined ? "keyboard state unreadable" : "keyboard still shown");
  else if (secretTyped && imeCleared !== true) reasons.push("could not confirm the keyboard's learned suggestions were cleared");
  else receipts.ime_cleared = { keyboard: "hidden", learned_data: secretTyped ? "cleared" : "untouched" };

  if (secretTyped) {
    if (autofillOff === true) receipts.autofill_off = { channel: "settings secure autofill_service" };
    else reasons.push("could not confirm autofill was off while the secret was typed");
  }

  // Advisory veto only.
  const nodes = parseUiautomatorNodes(uiXml);
  if (nodes.length) {
    const raw = unescapeXml(String(uiXml));
    if (containsSecret(raw, env)) reasons.push("advisory: secret still present on screen");
    if (nodes.some((n) => n.pkg && n.pkg !== appPackage && n.pkg !== "com.android.systemui")) reasons.push("advisory: foreign window content on screen");
    if (nodes.some((n) => n.password)) reasons.push("advisory: a password field is still on screen");
  }
  return { ok: reasons.length === 0, reasons, receipts };
}

// Login-log state after a cursor. The cursor is the newest epoch already seen
// plus how many times each line identity (pid/tid/message) was seen AT that
// epoch: logcat stamps to the millisecond, so a new login-page line can share
// the cursor's timestamp and must still count as new (>=, deduplicated by line
// identity, not >). A plain number is a cursor with nothing seen at it.
// Returns the newest epoch, the first login-page opening after the cursor,
// whether an attempt is still open at the end of the log, and the new cursor.
export function loginLogState(logcatEpoch, cursor = -Infinity) {
  const after = typeof cursor === "number" ? cursor : Number.isFinite(cursor?.epoch) ? cursor.epoch : -Infinity;
  const seenAt = cursor && typeof cursor === "object" && cursor.seen instanceof Map ? cursor.seen : new Map();
  const countAtAfter = new Map(); // running count per line identity at `after`
  const countsAtNewest = new Map(); // count per line identity at the newest epoch
  let newest = after;
  let openedAfter = null;
  let open = false;
  for (const e of parseLoginLog(logcatEpoch)) {
    let fresh = e.epoch > after;
    if (e.epoch === after) {
      const n = (countAtAfter.get(e.key) ?? 0) + 1;
      countAtAfter.set(e.key, n);
      fresh = n > (seenAt.get(e.key) ?? 0);
    }
    if (LOGIN_PAGE_MARKER.test(e.msg)) {
      open = true;
      if (fresh && openedAfter === null) openedAfter = e.epoch;
    } else if (LOGIN_COMPLETED_MARKER.test(e.msg) || LOGIN_ABANDONED_MARKER.test(e.msg) || /^web_login_attempt phase=idle\b/.test(e.msg)) {
      open = false;
    }
    if (e.epoch > newest) { newest = e.epoch; countsAtNewest.clear(); }
    if (e.epoch === newest) countsAtNewest.set(e.key, (countsAtNewest.get(e.key) ?? 0) + 1);
  }
  const seen = new Map(countsAtNewest);
  if (newest === after) for (const [k, n] of seenAt) seen.set(k, Math.max(n, seen.get(k) ?? 0));
  return { newest, openedAfter, open, cursor: { epoch: newest, seen } };
}

// ------------------------------------------------------ recorder pause fence
//
// The workflow's on-device screenrecord loop (RECORDING_LOOP_SH, started by
// qa-acceptance.yml) and the agent share three files:
//   PAUSE  written by the agent with a fresh nonce: "do not start a segment";
//   ACK    written ONLY by the loop, with PAUSE's content, at the top of an
//          iteration where it saw PAUSE. screenrecord runs in the loop's
//          foreground, so an ACK means: no segment of the loop is running, and
//          the loop will start none while PAUSE exists (it only re-checks);
//   DONE   written by the loop after its last segment, when it exits for good.
// The loop's check-then-start is not atomic: it may have checked (no PAUSE)
// and be about to start a segment when PAUSE appears. That segment cannot be
// prevented, but it cannot be missed either: no ACK carrying this nonce can
// be written until that segment has ended, and the agent keeps interrupting
// recordings until it holds the ACK. Then every segment from the one that was
// current at the previous clean check onwards is deleted whole (never trimmed).
export const RECORDING_DIR = "/data/local/tmp";
export const RECORDING_PAUSE = `${RECORDING_DIR}/qa-rec-pause`;
export const RECORDING_ACK = `${RECORDING_DIR}/qa-rec-ack`;
export const RECORDING_DONE = `${RECORDING_DIR}/qa-rec-done`;
// Canonical loop (workflow test pins the workflow's copy to this text).
export const RECORDING_LOOP_SH =
  `P=${RECORDING_PAUSE}; A=${RECORDING_ACK}; D=${RECORDING_DONE}; rm -f $P $A $D; i=1; ` +
  "while :; do if [ -e $P ]; then cat $P > $A; sleep 1; continue; fi; [ $i -le 8 ] || break; " +
  "screenrecord --size 540x1200 --bit-rate 1000000 --time-limit 180 /sdcard/qa-rec-$(printf %02d $i).mp4; i=$((i+1)); done; touch $D";

export const segmentIndex = (name) => {
  const m = /qa-rec-(\d+)\.mp4$/.exec(String(name));
  return m ? Number(m[1]) : NaN;
};

// ops (all synchronous device reads/writes; any throw = could not tell):
//   writePause(nonce) -> bool     readAck() -> string|null   loopDone() -> bool|null
//   interrupt()                   recorderRunning() -> bool|null
//   list() -> string[]|null (segment names)   remove(name)
// boundary: the newest segment index seen at the previous clean check (null =
// none seen; every segment is then dropped).
export async function pauseRecorder({ ops, nonce, boundary = null, attempts = 40, sleep = (ms) => new Promise((r) => setTimeout(r, ms)) }) {
  const fail = (reason) => ({ stopped: false, dropped: [], reason });
  if (!/^[A-Za-z0-9]{16,}$/.test(String(nonce))) return fail("pause nonce invalid");
  try { if (ops.writePause(nonce) !== true) return fail("pause flag not written"); } catch { return fail("pause flag not written"); }
  let acked = false;
  for (let i = 0; i < attempts && !acked; i++) {
    try { ops.interrupt(); } catch { /* none running, or raced exit */ }
    let ack = null;
    let done = null;
    try { ack = ops.readAck(); } catch { ack = null; }
    try { done = ops.loopDone(); } catch { done = null; }
    acked = ack === nonce || done === true;
    if (!acked) await sleep(500);
  }
  if (!acked) return fail("recording loop never acknowledged the pause");
  let running = null;
  try { running = ops.recorderRunning(); } catch { running = null; }
  if (running !== false) return fail("a screenrecord process is still running after the acknowledgement");
  const listed = (() => { try { return ops.list(); } catch { return null; } })();
  if (!Array.isArray(listed)) return fail("segments could not be listed");
  const floor = Number.isFinite(boundary) ? boundary : -Infinity;
  const drop = listed.filter((n) => !(segmentIndex(n) < floor)).sort();
  for (const n of drop) { try { ops.remove(n); } catch { /* verified next */ } }
  const after = (() => { try { return ops.list(); } catch { return null; } })();
  if (!Array.isArray(after) || drop.some((n) => after.includes(n))) return fail("a segment that may show the login window could not be deleted");
  return { stopped: true, dropped: drop.map((n) => n.replace(/^.*\//, "")) };
}
