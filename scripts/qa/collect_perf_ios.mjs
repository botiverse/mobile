#!/usr/bin/env node
// qa/collect_perf_ios — assemble a self-describing perf.json for an iOS acceptance run.
//
// iOS v1 of task #14 (artin: "ios也要"). HONEST SCOPE: there is no gfxinfo
// equivalent on the iOS simulator, and the app's RaftPerformanceDiagnostics
// markers are Android-only today (compose/androidApp). So v1 captures the two
// signals that exist NOW, harness-side:
//   1. per-step timing windows from steps.jsonl (slowest actions of the run)
//   2. system-log jank/lifecycle signal counts (watchdog/jetsam/terminate/crash)
// App-level frame/jank metrics need the shared perf-marker port to iOS —
// a PRODUCT change routed to the KMP lane, NOT this harness v1.
//
// Same self-describing header discipline as the Android collector
// (collect_perf.mjs): recorder identity+version, capture window with explicit
// clock domain, bundle/device/environment identity. Record-only, advisory —
// no thresholds.
//
// Usage:
//   collect_perf_ios.mjs --slog qa-evidence/system-log-raft.txt \
//     --steps qa-evidence/steps.jsonl --bundle build.raft.app \
//     --device-json '{"model":"iPhone 14","os":"17.x"}' \
//     --environment OranixSlockTest --started <epoch_ms> --out qa-evidence/perf.json

import fs from "node:fs";

const arg = (f, d) => { const i = process.argv.indexOf(f); return i >= 0 ? process.argv[i + 1] : d; };
const slogPath = arg("--slog", "");
const stepsPath = arg("--steps", "");
const bundle = arg("--bundle", "unknown");
const environment = arg("--environment", "unknown");
const outPath = arg("--out", "");
const startedMs = Number(arg("--started", "0")) || null;
let device = {};
try { device = JSON.parse(arg("--device-json", "{}")); } catch { device = {}; }

const RECORDER = { name: "slog-steps-ios-v1", version: "1.0.0", thresholds: "none (advisory record-only)" };

// --- system-log signal counts (jank/lifecycle-adjacent) ---
const SIGNAL_PATTERNS = {
  watchdog: /watchdog/i,
  jetsam: /jetsam/i,
  terminate: /terminat/i,
  crash_or_signal: /crash|signal|exception/i,
};
const signals = {};
if (slogPath && fs.existsSync(slogPath)) {
  const lines = fs.readFileSync(slogPath, "utf8").split("\n");
  for (const [name, re] of Object.entries(SIGNAL_PATTERNS)) {
    const hits = [];
    lines.forEach((line, i) => { if (re.test(line) && hits.length < 20) hits.push(i + 1); });
    const count = lines.filter((l) => re.test(l)).length;
    signals[name] = { count, firstLines: hits };
  }
}

// --- per-step timing windows ---
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
  capture_window: { clock_domain: "wall_clock_epoch_ms", started_ms: startedMs, ended_ms: Date.now() },
  identity: {
    bundle,
    device: { model: device.model || "unknown", os: device.os || "unknown" },
    environment,
  },
  system_log_signals: signals,
  slowest_step_windows: stepWindows,
  known_gap: "No app-level frame/jank metrics: RaftPerformanceDiagnostics is Android-only; the iOS perf-marker port is a product change (KMP lane). This v1 covers action timing + system-log lifecycle signals only.",
  note: "Advisory record-only sample. Simulator absolute timings are not hardware truth; compare only same-device run-over-run deltas.",
};

if (outPath) fs.writeFileSync(outPath, JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
