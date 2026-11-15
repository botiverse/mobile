#!/usr/bin/env node
// Egress gate for an acceptance evidence directory. Runs before EVERY exit of
// qa-evidence: the Testbed upload (stamp_report.sh report-evidence/report) and
// the GitHub artifact upload (actions/upload-artifact of the whole dir).
//
// Unknown fails closed. The capture-suppression record (acceptance_agent /
// ios_deterministic_repro) must be present, parse, carry the v1 schema, belong
// to this run/attempt (binding) and be finalised (completed) unless it already
// declares an unresolved window. Anything else -- missing, malformed, {} , a
// record from another run, a producer that crashed before finishing, a mode
// that writes no record -- means "the whole run may be inside a login
// window": every capture, recording, dump, log, opaque bundle and transcript
// is deleted, the gate exits 1 (callers upload nothing) and its status is
// UNKNOWN (stamp_report.sh then sends no product verdict).
//
//   1. Capture suppression (record valid):
//      - a phase that is UNKNOWN (unresolved) or whose recording stop was not
//        confirmed: every recording is deleted (never trimmed), every
//        image/video/screen dump produced at or after the phase start is
//        deleted, as is activity-top.txt (post-run screen dump), every opaque
//        file (non-text, non-media: e.g. .xcresult bundle data, which a byte
//        scan cannot read) and every .xcresult bundle;
//      - log lines inside a login window are withheld. A log line whose clock
//        cannot be compared with every window (host-format line vs a
//        device-clock window, device-format line vs a host-clock window), or a
//        log with no timestamp at all, drops the whole log and marks the gate
//        UNKNOWN.
//   2. Secret values (QA_TEST_EMAIL / QA_TEST_PASSWORD, plus URL-encoded
//      forms) are redacted in text files; a binary file containing one is
//      deleted. (Pixels cannot be scanned: step 1 is what keeps login screens
//      out.)
//   3. A final rescan must find nothing; otherwise exit 1.
// Writes secure-evidence-gate.json: ok, status (clean | resolved | UNKNOWN),
// file NAMES and counts only, the run binding and the sha256 of verdict.json
// as gated, which stamp_report.sh report-verdict requires before it sends a
// product verdict.
//
// Usage: node scripts/qa/secure_evidence.mjs --dir qa-evidence

import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { createHash } from "node:crypto";
import { MIN_SECRET_LENGTH, SUPPRESSION_CONTRACT, runBinding, secretValues } from "./secure_input.mjs";

const TEXT_EXT = new Set([".txt", ".json", ".jsonl", ".log", ".xml", ".md", ".csv", ".ips", ".html", ".plist", ".sh", ""]);
const MEDIA_EXT = new Set([".png", ".jpg", ".jpeg", ".webp", ".gif", ".mp4", ".mov", ".m4v", ".webm"]);
const RECORDING_EXT = new Set([".mp4", ".mov", ".m4v", ".webm"]);
const SCREEN_DUMPS = new Set(["activity-top.txt"]);
const REPORT = "secure-evidence-gate.json";
export const GATE_REPORT = REPORT;
export const GATE_CONTRACT = "qa-secure-evidence-gate/v2";
// Kept when the record is unknown: the runner's own outcome files (no screen
// content). Everything else is deleted and nothing is uploaded.
const UNKNOWN_KEEP = new Set(["verdict.json", "agent-rc.txt", "capture-suppression.json", REPORT]);
const LOG_FILE = /(logcat|system-log|syslog|xcodebuild-loop|harness).*\.(txt|log|ndjson|jsonl)$/i;

const THREADTIME = /^(\d\d-\d\d \d\d:\d\d:\d\d)\.\d{3}\s/;
const ISO_TS = /^(\d{4}-\d\d-\d\d[ T]\d\d:\d\d:\d\d(?:\.\d+)?(?:Z|[+-]\d\d:?\d\d)?)/;
function hostTs(line) {
  let m = ISO_TS.exec(line);
  if (!m) {
    const j = /"timestamp"\s*:\s*"([^"]+)"/.exec(line);
    if (!j) return NaN;
    m = [null, j[1]];
  }
  const t = Date.parse(m[1].replace(" ", "T").replace(/([+-]\d\d)(\d\d)$/, "$1:$2"));
  return Number.isFinite(t) ? t : NaN;
}

// Withhold every log line inside a login window. Returns null (the caller
// drops the whole file and marks the gate UNKNOWN) when a line cannot be
// placed against EVERY window: a device-clock (threadtime) line needs every
// window to have a device-clock boundary, a host-clock line needs every window
// to have a host-clock boundary, and a log with no timestamp at all cannot be
// placed. Untimestamped lines before the first timestamp are withheld;
// later ones follow the previous line.
export function filterLogWindows(text, phases) {
  const lines = String(text).split("\n");
  const androidWindows = [];
  const hostWindows = [];
  for (const p of phases) {
    const open = p.status !== "resumed";
    if (p.recording === "not_recorded" && Number.isFinite(Number(p.start_ts))) {
      if (!open && !Number.isFinite(Number(p.end_ts))) return null;
      hostWindows.push([Number(p.start_ts), open ? Infinity : Number(p.end_ts)]);
    } else if (/^\d\d-\d\d \d\d:\d\d:\d\d$/.test(String(p.device_start_local || ""))) {
      if (!open && !/^\d\d-\d\d \d\d:\d\d:\d\d$/.test(String(p.device_end_local || ""))) return null;
      androidWindows.push([p.device_start_local, open ? "\uffff" : p.device_end_local]);
    }
  }
  const out = [];
  let withheld = 0;
  let stamped = 0;
  const UNPLACED = ["(before the first timestamp)", "\uffff"];
  let last = UNPLACED;
  for (const line of lines) {
    const tt = THREADTIME.exec(line)?.[1];
    const ht = tt ? NaN : hostTs(line);
    const hasTs = Boolean(tt) || Number.isFinite(ht);
    if (tt && androidWindows.length !== phases.length) return null;
    if (Number.isFinite(ht) && hostWindows.length !== phases.length) return null;
    if (hasTs) stamped++;
    const w = tt ? androidWindows.find(([a, b]) => tt >= a && tt <= b)
      : Number.isFinite(ht) ? hostWindows.find(([a, b]) => ht >= a && ht <= b) : null;
    // A line with no timestamp continues the previous line's fate.
    const inside = hasTs ? (w ?? null) : last;
    if (inside) {
      withheld++;
      if (last !== inside) out.push(`--------- capture suppressed: login window ${inside[0]} .. ${inside[1] === "\uffff" || inside[1] === Infinity ? "unresolved" : inside[1]}`);
    } else out.push(line);
    last = hasTs ? inside : last;
  }
  if (phases.length && !stamped && lines.some((l) => l.trim())) return null;
  return { text: out.join("\n"), withheld };
}

const isPhase = (p) => p && typeof p === "object"
  && Number.isFinite(p.start_ts)
  && ["resumed", "UNKNOWN", "active"].includes(p.status)
  && ["stopped", "not_recorded", "unconfirmed", "unknown"].includes(p.recording)
  && (p.status !== "resumed" || (Number.isFinite(p.end_ts) && p.end_ts >= p.start_ts));

// Reads and validates the capture-suppression record. state: "clean" (no
// window), "resolved" (every window closed on receipts), "unresolved" (the
// record itself declares an open/unknown window) or "unknown" (no usable
// record: treat the whole run as inside a login window).
export function readSuppressionRecord(dir, env = process.env) {
  const file = path.join(dir, "capture-suppression.json");
  const unknown = (why) => ({ state: "unknown", why, phases: [] });
  if (!fs.existsSync(file)) return unknown("capture-suppression.json missing");
  let r;
  try { r = JSON.parse(fs.readFileSync(file, "utf8")); } catch { return unknown("capture-suppression.json unreadable"); }
  if (!r || typeof r !== "object" || r.contract !== SUPPRESSION_CONTRACT) return unknown("capture-suppression.json has no v1 contract");
  if (typeof r.unresolved !== "boolean" || typeof r.active !== "boolean" || !Array.isArray(r.phases)) return unknown("capture-suppression.json schema invalid");
  if (!r.phases.every(isPhase)) return unknown("capture-suppression.json has an invalid phase");
  const want = runBinding(env);
  if (!r.binding || r.binding.github_run_id !== want.github_run_id || r.binding.github_run_attempt !== want.github_run_attempt) {
    return unknown("capture-suppression.json belongs to another run or attempt");
  }
  const open = r.unresolved || r.active || r.phases.some((p) => p.status !== "resumed");
  if (open && r.phases.length) return { state: "unresolved", phases: r.phases };
  if (r.completed !== true) return unknown("capture-suppression.json was never finalised (producer did not finish)");
  if (open) return unknown("capture-suppression.json declares an open window without a phase");
  return { state: r.phases.length ? "resolved" : "clean", phases: r.phases };
}

function walk(dir) {
  const out = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p));
    else if (e.isFile()) out.push(p);
  }
  return out;
}

function needles(env) {
  const values = secretValues(env);
  const set = new Set();
  for (const v of values) {
    set.add(v);
    set.add(encodeURIComponent(v));
    set.add(JSON.stringify(v).slice(1, -1));
  }
  return [...set].filter(Boolean);
}

const isTextFile = (f, buf) => {
  const text = buf.toString("utf8");
  return TEXT_EXT.has(path.extname(f).toLowerCase()) && !text.includes("\u0000") && Buffer.from(text, "utf8").equals(buf);
};
const inXcresult = (rel) => rel.split(path.sep).some((part) => /\.xcresult$/i.test(part));

export function gateEvidence(dir, env = process.env) {
  const report = { contract: GATE_CONTRACT, ok: false, status: "UNKNOWN", suppression: "unknown", removed: [], redacted: [], binding: runBinding(env) };
  for (const v of secretValues(env)) {
    if (v.length < MIN_SECRET_LENGTH) throw new Error("a configured secret is too short to scan for; refusing egress");
  }
  if (!fs.existsSync(dir)) return { ok: false, report: { ...report, why: "evidence directory missing" } };
  const rel = (f) => path.relative(dir, f);
  const remove = (f, why) => { fs.rmSync(f, { force: true }); report.removed.push({ file: rel(f), why }); };
  let unknown = false;

  // 1. capture suppression (login windows)
  const record = readSuppressionRecord(dir, env);
  report.suppression = record.state;
  if (record.state === "unknown") {
    unknown = true;
    report.why = record.why;
    for (const f of walk(dir)) {
      if (UNKNOWN_KEEP.has(rel(f))) continue;
      remove(f, "no valid capture-suppression record: the whole run is treated as inside a login window");
    }
  } else if (record.phases.length) {
    const phases = record.phases;
    const unresolved = record.state === "unresolved";
    const recordingUnconfirmed = phases.some((p) => p.recording !== "stopped" && p.recording !== "not_recorded");
    const since = Math.min(...phases.map((p) => Number(p.start_ts)));
    const dropped = new Set(phases.flatMap((p) => (Array.isArray(p.dropped_segments) ? p.dropped_segments : [])));
    for (const f of walk(dir)) {
      const ext = path.extname(f).toLowerCase();
      const base = path.basename(f);
      if (RECORDING_EXT.has(ext) && dropped.has(base)) remove(f, "recording segment that may show a login window entry");
      else if (RECORDING_EXT.has(ext) && (unresolved || recordingUnconfirmed)) remove(f, "recording overlaps a login window that was not provably clean");
      else if (unresolved && inXcresult(rel(f))) remove(f, "opaque .xcresult bundle data after an unresolved login window");
      else if (unresolved && SCREEN_DUMPS.has(base)) remove(f, "screen dump after an unresolved login window");
      else if (unresolved && MEDIA_EXT.has(ext) && fs.statSync(f).mtimeMs >= since) remove(f, "capture after an unresolved login window began");
      else if (unresolved && !MEDIA_EXT.has(ext) && !isTextFile(f, fs.readFileSync(f))) remove(f, "opaque file after an unresolved login window (cannot be inspected)");
    }
    // Logs: lines inside any login window are withheld (one placeholder line
    // per window). Android logcat uses the device-local threadtime prefix the
    // agent recorded; host-clock logs (iOS simulator) use start_ts/end_ts.
    for (const f of walk(dir)) {
      const base = path.basename(f);
      if (!LOG_FILE.test(base) || base === "capture-suppression.json") continue;
      const result = filterLogWindows(fs.readFileSync(f, "utf8"), phases);
      if (result === null) {
        remove(f, "log whose lines cannot be placed against every login window (mixed/unsupported clock)");
        unknown = true;
        report.why = "a log could not be placed against the login windows";
      } else if (result.withheld) {
        fs.writeFileSync(f, result.text);
        report.redacted.push(`${rel(f)} (${result.withheld} line(s) in a login window withheld)`);
      }
    }
    if (unresolved) unknown = true;
  }

  // 2. secret values
  const list = needles(env);
  if (list.length) {
    for (const f of walk(dir)) {
      if (path.basename(f) === REPORT) continue;
      const buf = fs.readFileSync(f);
      const latin = buf.toString("latin1");
      const hit = list.some((n) => latin.includes(Buffer.from(n, "utf8").toString("latin1")));
      if (!hit) continue;
      if (!isTextFile(f, buf)) { remove(f, "binary file contains a secret value"); continue; }
      let out = buf.toString("utf8");
      for (const n of list) out = out.split(n).join("***");
      fs.writeFileSync(f, out);
      report.redacted.push(rel(f));
    }
    // 3. final rescan
    for (const f of walk(dir)) {
      if (path.basename(f) === REPORT) continue;
      const latin = fs.readFileSync(f).toString("latin1");
      if (list.some((n) => latin.includes(Buffer.from(n, "utf8").toString("latin1")))) {
        return { ok: false, report: { ...report, ok: false, status: "UNKNOWN", residual: rel(f) } };
      }
    }
  }
  const verdictFile = path.join(dir, "verdict.json");
  report.verdict_sha256 = fs.existsSync(verdictFile) ? createHash("sha256").update(fs.readFileSync(verdictFile)).digest("hex") : null;
  report.status = unknown ? "UNKNOWN" : record.state;
  // Egress: refused only when there is no usable record (or a residual
  // secret). An unresolved window still lets the frames from before it leave;
  // its status stays UNKNOWN so no product verdict is sent.
  report.ok = record.state !== "unknown";
  return { ok: report.ok, report };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const i = process.argv.indexOf("--dir");
  const dir = i >= 0 ? process.argv[i + 1] : "qa-evidence";
  try {
    const { ok, report } = gateEvidence(dir);
    if (fs.existsSync(dir)) fs.writeFileSync(path.join(dir, REPORT), JSON.stringify(report, null, 2));
    console.log(`secure-evidence: status=${report.status} suppression=${report.suppression} removed=${report.removed.length} redacted=${report.redacted.length}${report.residual ? " RESIDUAL SECRET" : ""}${ok ? "" : " EGRESS REFUSED"}${report.why ? ` (${report.why})` : ""}`);
    process.exit(ok ? 0 : 1);
  } catch (error) {
    console.error(`secure-evidence: ${String(error?.message || error)}`);
    try { if (fs.existsSync(dir)) fs.writeFileSync(path.join(dir, REPORT), JSON.stringify({ contract: GATE_CONTRACT, ok: false, status: "UNKNOWN", why: "gate error" })); } catch { /* nothing leaves anyway */ }
    process.exit(1);
  }
}
