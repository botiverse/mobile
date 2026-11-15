#!/usr/bin/env node
// Egress gate for an acceptance evidence directory. Runs before EVERY exit of
// qa-evidence: the Testbed upload (stamp_report.sh report-evidence/report) and
// the GitHub artifact upload (actions/upload-artifact of the whole dir).
//
//   1. Capture suppression (capture-suppression.json from acceptance_agent):
//      - a phase that is UNKNOWN (unresolved) or whose recording stop was not
//        confirmed: every recording is deleted (never trimmed), and every
//        image/video/screen dump produced at or after the phase start is
//        deleted, as is activity-top.txt (post-run screen dump).
//   2. Secret values (QA_TEST_EMAIL / QA_TEST_PASSWORD, plus URL-encoded
//      forms) are redacted in text files; a binary file containing one is
//      deleted.
//   3. A final rescan must find nothing; otherwise exit 1 (callers then upload
//      nothing).
// Writes secure-evidence-gate.json (file NAMES and counts only).
//
// Usage: node scripts/qa/secure_evidence.mjs --dir qa-evidence

import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { MIN_SECRET_LENGTH, secretValues } from "./secure_input.mjs";

const TEXT_EXT = new Set([".txt", ".json", ".jsonl", ".log", ".xml", ".md", ".csv", ".ips", ".html", ".plist", ".sh", ""]);
const MEDIA_EXT = new Set([".png", ".jpg", ".jpeg", ".webp", ".gif", ".mp4", ".mov", ".m4v", ".webm"]);
const RECORDING_EXT = new Set([".mp4", ".mov", ".m4v", ".webm"]);
const SCREEN_DUMPS = new Set(["activity-top.txt"]);
const REPORT = "secure-evidence-gate.json";
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

// Withhold every log line inside a login window. Returns null if a window
// has no boundary this file can be compared with (the caller drops the file).
export function filterLogWindows(text, phases) {
  const lines = String(text).split("\n");
  const androidWindows = [];
  const hostWindows = [];
  for (const p of phases) {
    const open = p.status !== "resumed";
    if (p.recording === "not_recorded") {
      if (!Number.isFinite(Number(p.start_ts))) return null;
      hostWindows.push([Number(p.start_ts), open ? Infinity : Number(p.end_ts)]);
    } else {
      if (!/^\d\d-\d\d \d\d:\d\d:\d\d$/.test(String(p.device_start_local || ""))) return null;
      if (!open && !/^\d\d-\d\d \d\d:\d\d:\d\d$/.test(String(p.device_end_local || ""))) return null;
      androidWindows.push([p.device_start_local, open ? "\uffff" : p.device_end_local]);
    }
  }
  const out = [];
  let withheld = 0;
  let last = null;
  for (const line of lines) {
    const tt = THREADTIME.exec(line)?.[1];
    const ht = tt ? NaN : hostTs(line);
    const w = tt ? androidWindows.find(([a, b]) => tt >= a && tt <= b)
      : Number.isFinite(ht) ? hostWindows.find(([a, b]) => ht >= a && ht <= b) : null;
    // A line with no timestamp continues the previous line's fate.
    const inside = w ?? (tt || Number.isFinite(ht) ? null : last);
    if (inside) {
      withheld++;
      if (last !== inside) out.push(`--------- capture suppressed: login window ${inside[0]} .. ${inside[1] === "\uffff" || inside[1] === Infinity ? "unresolved" : inside[1]}`);
    } else out.push(line);
    last = tt || Number.isFinite(ht) ? (inside || null) : last;
  }
  return { text: out.join("\n"), withheld };
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

export function gateEvidence(dir, env = process.env) {
  const report = { contract: "qa-secure-evidence-gate/v1", removed: [], redacted: [], suppression: "none" };
  for (const v of secretValues(env)) {
    if (v.length < MIN_SECRET_LENGTH) throw new Error("a configured secret is too short to scan for; refusing egress");
  }
  if (!fs.existsSync(dir)) return { ok: true, report };
  const rel = (f) => path.relative(dir, f);
  const remove = (f, why) => { fs.rmSync(f, { force: true }); report.removed.push({ file: rel(f), why }); };

  // 1. capture suppression (login windows)
  const suppressionFile = path.join(dir, "capture-suppression.json");
  if (fs.existsSync(suppressionFile)) {
    let summary;
    try { summary = JSON.parse(fs.readFileSync(suppressionFile, "utf8")); } catch { summary = { unresolved: true, phases: [], unreadable: true }; }
    const phases = Array.isArray(summary.phases) ? summary.phases : [];
    const unresolved = summary.unresolved === true || summary.active === true || summary.unreadable === true
      || phases.some((p) => p.status !== "resumed");
    const recordingUnconfirmed = phases.some((p) => p.recording !== "stopped" && p.recording !== "not_recorded");
    report.suppression = unresolved ? "unresolved" : phases.length ? "resolved" : "unused";
    const starts = phases.map((p) => Number(p.start_ts)).filter(Number.isFinite);
    const since = starts.length ? Math.min(...starts) : 0;
    const dropped = new Set(phases.flatMap((p) => (Array.isArray(p.dropped_segments) ? p.dropped_segments : [])));
    for (const f of walk(dir)) {
      const ext = path.extname(f).toLowerCase();
      const base = path.basename(f);
      if (RECORDING_EXT.has(ext) && dropped.has(base)) remove(f, "recording segment that may show a login window entry");
      else if (RECORDING_EXT.has(ext) && (unresolved || recordingUnconfirmed)) remove(f, "recording overlaps a login window that was not provably clean");
      else if (unresolved && SCREEN_DUMPS.has(base)) remove(f, "screen dump after an unresolved login window");
      else if (unresolved && MEDIA_EXT.has(ext) && fs.statSync(f).mtimeMs >= since) remove(f, "capture after an unresolved login window began");
    }
    // Logs: lines inside any login window are withheld (one placeholder line
    // per window). Android logcat uses the device-local threadtime prefix the
    // agent recorded; host-clock logs (iOS simulator) use start_ts/end_ts.
    if (phases.length) {
      for (const f of walk(dir)) {
        const base = path.basename(f);
        if (!LOG_FILE.test(base) || base === "capture-suppression.json") continue;
        const result = filterLogWindows(fs.readFileSync(f, "utf8"), phases);
        if (result === null) remove(f, "log without a usable login-window boundary");
        else if (result.withheld) {
          fs.writeFileSync(f, result.text);
          report.redacted.push(`${rel(f)} (${result.withheld} line(s) in a login window withheld)`);
        }
      }
    }
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
      const ext = path.extname(f).toLowerCase();
      const text = buf.toString("utf8");
      const isText = TEXT_EXT.has(ext) && !text.includes("\u0000") && Buffer.from(text, "utf8").equals(buf);
      if (!isText) { remove(f, "binary file contains a secret value"); continue; }
      let out = text;
      for (const n of list) out = out.split(n).join("***");
      fs.writeFileSync(f, out);
      report.redacted.push(rel(f));
    }
    // 3. final rescan
    for (const f of walk(dir)) {
      const latin = fs.readFileSync(f).toString("latin1");
      if (list.some((n) => latin.includes(Buffer.from(n, "utf8").toString("latin1")))) {
        return { ok: false, report: { ...report, residual: rel(f) } };
      }
    }
  }
  return { ok: true, report };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const i = process.argv.indexOf("--dir");
  const dir = i >= 0 ? process.argv[i + 1] : "qa-evidence";
  try {
    const { ok, report } = gateEvidence(dir);
    if (fs.existsSync(dir)) fs.writeFileSync(path.join(dir, REPORT), JSON.stringify(report, null, 2));
    console.log(`secure-evidence: suppression=${report.suppression} removed=${report.removed.length} redacted=${report.redacted.length}${ok ? "" : " RESIDUAL SECRET"}`);
    process.exit(ok ? 0 : 1);
  } catch (error) {
    console.error(`secure-evidence: ${String(error?.message || error)}`);
    process.exit(1);
  }
}
