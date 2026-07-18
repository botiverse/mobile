#!/usr/bin/env node
// qa/collect_perf — assemble a self-describing perf.json for one acceptance run.
//
// v1 lightweight performance recording (task #14, MingQi/Argus spec): capture
// only cheap, always-on signals — `dumpsys gfxinfo <pkg>` aggregate summary
// (janky frame count/ratio + frame-time percentiles) and logcat Choreographer
// "Skipped N frames" lines. NO thresholds, advisory only: emulator/cloud
// absolute frame times are not hardware truth; the value is same-device
// run-over-run deltas (baseline comes later).
//
// Header fields are REQUIRED (self-describing, so historical samples can be
// re-based later): recorder identity+version, capture window, and
// package/device/environment identity.
//
// Usage:
//   collect_perf.mjs --gfx qa-evidence/gfxinfo.txt --log qa-evidence/logcat.txt \
//     --pkg build.raft.app --steps qa-evidence/steps.jsonl \
//     --device-json '{"model":"sdk_gphone64_arm64","sdk":"34"}' \
//     --environment OranixSlockTest --started <epoch_ms> --out qa-evidence/perf.json

import fs from "node:fs";

const arg = (f, d) => { const i = process.argv.indexOf(f); return i >= 0 ? process.argv[i + 1] : d; };
const gfxPath = arg("--gfx", "");
const logPath = arg("--log", "");
const stepsPath = arg("--steps", "");
const pkg = arg("--pkg", "unknown");
const environment = arg("--environment", "unknown");
const outPath = arg("--out", "");
const startedMs = Number(arg("--started", "0")) || null;
let device = {};
try { device = JSON.parse(arg("--device-json", "{}")); } catch { device = {}; }

const RECORDER = { name: "gfxinfo-logcat-v1", version: "1.1.0", thresholds: "none (advisory record-only)" };

// --- gfxinfo aggregate summary ---
// "Janky frames: 42 (5.31%)" and percentile lines like
// "50th percentile: 6ms" ... "99th percentile: 28ms"
let gfx = { jankyFrames: null, jankyPercent: null, totalFrames: null, percentilesMs: {}, statsSinceNs: null };
if (gfxPath && fs.existsSync(gfxPath)) {
  const text = fs.readFileSync(gfxPath, "utf8");
  const sm = /Stats since:\s*(\d+)ns/.exec(text);
  if (sm) gfx.statsSinceNs = Number(sm[1]); // monotonic (since-boot) anchor
  const jm = /Janky frames:\s*(\d+)\s*\(([\d.]+)%\)/.exec(text);
  if (jm) { gfx.jankyFrames = Number(jm[1]); gfx.jankyPercent = Number(jm[2]); }
  const tm = /Total frames rendered:\s*(\d+)/.exec(text) || /Number Frames\s+Rendered:\s*(\d+)/.exec(text);
  if (tm) gfx.totalFrames = Number(tm[1]);
  for (const p of ["50", "90", "95", "99"]) {
    const pm = new RegExp(p + "th percentile:\\s*([\\d.]+)ms").exec(text);
    if (pm) gfx.percentilesMs["p" + p] = Number(pm[1]);
  }
}

// --- logcat Choreographer skipped-frames aggregation ---
let skipped = { events: 0, frames: 0, lines: [] };
if (logPath && fs.existsSync(logPath)) {
  const lines = fs.readFileSync(logPath, "utf8").split("\n");
  lines.forEach((line, i) => {
    const m = /Skipped (\d+) frames!/.exec(line);
    if (m) {
      skipped.events += 1;
      skipped.frames += Number(m[1]);
      if (skipped.lines.length < 20) skipped.lines.push(i + 1);
    }
  });
}

// --- per-step timing windows (best-effort; steps.jsonl has ts per step) ---
let stepWindows = [];
if (stepsPath && fs.existsSync(stepsPath)) {
  const rows = fs.readFileSync(stepsPath, "utf8").split("\n").filter(Boolean);
  const ts = [];
  for (const row of rows) {
    try {
      const j = JSON.parse(row);
      if (j.ts) ts.push({ ts: j.ts, action: j.action?.action || j.action?.type || j.type || "?" });
    } catch { /* tolerate partial lines */ }
  }
  for (let i = 1; i < ts.length; i++) {
    stepWindows.push({ from: ts[i - 1].action, durationMs: ts[i].ts - ts[i - 1].ts });
  }
  stepWindows.sort((a, b) => b.durationMs - a.durationMs);
  stepWindows = stepWindows.slice(0, 5);
}

const report = {
  recorder: RECORDER,
  // Clock domains are explicit (MingQi): wall-clock for human correlation,
  // monotonic (gfxinfo "Stats since", since-boot ns) for durations.
  capture_window: {
    clock_domain: "wall_clock_epoch_ms",
    started_ms: startedMs,
    ended_ms: Date.now(),
    monotonic_anchor_ns: gfx.statsSinceNs,
  },
  identity: {
    package: pkg,
    device: { model: device.model || "unknown", sdk: device.sdk || "unknown", abi: device.abi || "unknown" },
    environment,
  },
  gfxinfo: gfx,
  choreographer_skipped_frames: skipped.events ? skipped : { events: 0, frames: 0, lines: [] },
  slowest_step_windows: stepWindows,
  note: "Advisory record-only sample. Emulator/cloud absolute frame times are not hardware truth; compare only same-device run-over-run deltas.",
};

if (outPath) fs.writeFileSync(outPath, JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
