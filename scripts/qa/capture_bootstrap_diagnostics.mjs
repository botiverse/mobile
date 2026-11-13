#!/usr/bin/env node
// QA login bootstrap diagnostics bundle (qa-acceptance run 43294979101: 20/25 tasks hung after
// shell_launch_requested with nothing saved). Captured for every bootstrap, pass or fail, BEFORE
// the workflow clears logcat or the device (`pm clear`), so pass and fail runs can be compared:
//   <label>-logcat.txt        full `adb logcat -d -v threadtime`, redacted
//   <label>-logcat-app.txt    only the bootstrap/auth/home/shell/crash tags, redacted
//   <label>-activity-top.txt  `adb shell dumpsys activity top`, redacted
//   <label>-screen.png        `adb exec-out screencap -p`
// Text is redacted line by line (redactDiagnosticsText) before it touches disk: tokens,
// passwords, Authorization/Cookie headers, Bearer/Basic credentials, JWTs, QA bootstrap
// codes/tickets and session ids/generations never reach the artifact.
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { pathToFileURL } from "node:url";

export const BOOTSTRAP_DIAGNOSTIC_TAGS = [
  "SlockQaBootstrap",
  "SlockSharedAuth",
  "SlockSharedHome",
  "SlockKuikly",
  "SlockCmpHost",
  "AndroidRuntime",
];

const REDACTED = "[REDACTED]";
// Key names whose values are credentials or session/bootstrap identifiers. Plain `code`
// (failure codes such as code=recovery_required) is deliberately not in this list.
const SECRET_KEY =
  "[A-Za-z0-9_.-]*(?:token|password|passwd|pwd|secret|authorization|cookie|session|bearer|api[_-]?key|ticket|otp|credential|private[_-]?key|(?:bootstrap|auth|login|verification|qa|magic)[_-]?code)[A-Za-z0-9_.-]*";
const HARMLESS_VALUE = /^(?:true|false|null|none|missing|empty|present|absent|_|\d{1,3})$/i;

export function redactDiagnosticsLine(line) {
  let out = line;
  // Whole header values: everything after the header name.
  out = out.replace(/\b((?:proxy-)?authorization|set-cookie|cookie)(\s*:\s*)\S.*$/gi, `$1$2${REDACTED}`);
  // Scheme credentials anywhere.
  out = out.replace(/\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+/gi, `$1 ${REDACTED}`);
  // JWT-shaped strings.
  out = out.replace(/\beyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]*/g, REDACTED);
  // key=value, key: value, "key":"value", ?key=value&...
  out = out.replace(
    new RegExp(`(${SECRET_KEY})("?\\s*[:=]\\s*"?)([^\\s"'&,;}\\]]+)`, "gi"),
    (match, key, separator, value) => (HARMLESS_VALUE.test(value) || value === REDACTED ? match : `${key}${separator}${REDACTED}`),
  );
  return out;
}

export function redactDiagnosticsText(text) {
  return String(text).split("\n").map(redactDiagnosticsLine).join("\n");
}

export function filterDiagnosticTags(text, tags = BOOTSTRAP_DIAGNOSTIC_TAGS) {
  // threadtime: "MM-DD hh:mm:ss.mmm  PID  TID L Tag: message"
  const tagPattern = new RegExp(`^\\S+\\s+\\S+\\s+\\d+\\s+\\d+\\s+[VDIWEF]\\s+(?:${tags.join("|")})\\s*:`);
  return String(text).split("\n").filter((line) => tagPattern.test(line)).join("\n");
}

function argument(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  const value = index >= 0 ? process.argv[index + 1] : undefined;
  return value && !value.startsWith("--") ? value : fallback;
}

function adb(args, { binary = false } = {}) {
  const result = spawnSync("adb", args, {
    encoding: binary ? undefined : "utf8",
    maxBuffer: 64 * 1024 * 1024,
    timeout: 30_000,
    killSignal: "SIGKILL",
  });
  if (result.error || result.status !== 0) {
    const reason = result.error ? String(result.error.code ?? result.error.message) : `status=${result.status}`;
    return { ok: false, reason };
  }
  return { ok: true, stdout: result.stdout };
}

function main() {
  const out = argument("out");
  const label = argument("label", "bootstrap");
  if (!out) {
    console.error("capture-bootstrap-diagnostics: --out is required");
    process.exit(2);
  }
  if (!/^[a-z0-9-]+$/.test(label)) {
    console.error("capture-bootstrap-diagnostics: --label must match [a-z0-9-]+");
    process.exit(2);
  }
  fs.mkdirSync(out, { recursive: true });
  const write = (name, text) => fs.writeFileSync(path.join(out, `${label}-${name}`), `${redactDiagnosticsText(text)}\n`);
  const notes = [];

  const logcat = adb(["logcat", "-d", "-v", "threadtime"]);
  if (logcat.ok) {
    write("logcat.txt", logcat.stdout);
    write("logcat-app.txt", filterDiagnosticTags(logcat.stdout));
  } else {
    notes.push(`logcat unavailable: ${logcat.reason}`);
  }
  const top = adb(["shell", "dumpsys", "activity", "top"]);
  if (top.ok) write("activity-top.txt", top.stdout);
  else notes.push(`dumpsys activity top unavailable: ${top.reason}`);
  const screen = adb(["exec-out", "screencap", "-p"], { binary: true });
  if (screen.ok && screen.stdout?.length) fs.writeFileSync(path.join(out, `${label}-screen.png`), screen.stdout);
  else notes.push(`screencap unavailable: ${screen.reason ?? "empty"}`);

  if (notes.length) write("capture-notes.txt", notes.join("\n"));
  console.log(`capture-bootstrap-diagnostics: saved ${label} bundle to ${out}${notes.length ? ` (${notes.join("; ")})` : ""}`);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) main();
