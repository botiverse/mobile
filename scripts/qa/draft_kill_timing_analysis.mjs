// Pure analysis for scripts/qa/android_draft_kill_timing.mjs (task #246): parses `adb logcat -v monotonic`
// lines and decides, on the device's own clock, how long after the composer took the last typed text the
// app process was killed, and whether that text was already in the durable draft.
//
// Clock: logcat `-v monotonic` prints CLOCK_MONOTONIC seconds; the app's `RaftDraftTiming` lines carry
// SystemClock.uptimeMillis(), which is CLOCK_MONOTONIC too. Δ = kill line (ActivityManager "Killing <pid>:<pkg>",
// logged by system_server right before it signals the process) − last change_received uptimeMs.

/** One parsed logcat line, or null. Format: `<sec>.<ms>  <pid>  <tid> <L> <tag>: <message>`. */
export function parseLogcatLine(line) {
  const m = /^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.+?)\s*:\s(.*)$/.exec(line);
  if (!m) return null;
  return { tsMs: Math.round(Number(m[1]) * 1000 * 1000) / 1000, pid: Number(m[2]), tid: Number(m[3]), level: m[4], tag: m[5].trim(), message: m[6] };
}

/** `change_received seq=3 len=12 uptimeMs=123456` → {kind, seq, len, uptimeMs}. */
export function parseTimingMessage(message) {
  const m = /^(change_received|draft_written) seq=(\d+) len=(\d+) uptimeMs=(\d+)\s*$/.exec(message);
  if (!m) return null;
  return { kind: m[1], seq: Number(m[2]), len: Number(m[3]), uptimeMs: Number(m[4]) };
}

/** The pid an ActivityManager line says it is killing for [pkg] (main process only), or null. */
export function parseKillLine(message, pkg) {
  const m = /^Killing (\d+):([^/\s]+)\/\S+/.exec(message);
  if (!m || m[2] !== pkg) return null;
  return Number(m[1]);
}

/** |logcat monotonic stamp − uptimeMs| of the change line above which Δ is not trusted. */
export const MAX_CLOCK_SKEW_MS = 20;

/** Bucket windows (seconds of Δ that count as "this bucket"). */
export const BUCKETS = {
  0.05: { lo: 0, hi: 0.1 },
  0.1: { lo: 0.05, hi: 0.15 },
  0.3: { lo: 0.2, hi: 0.4 },
  0.7: { lo: 0.55, hi: 0.85 },
};

export function bucketFor(delaySec) {
  return BUCKETS[delaySec] ?? { lo: delaySec * 0.5, hi: delaySec * 1.5 };
}

/**
 * Decides one kill run from the logcat lines captured around it.
 *
 * @param {object} input
 * @param {string[]} input.lines raw logcat lines from before the last keystroke to after the kill
 * @param {string} input.pkg app package
 * @param {number} input.oldPid app pid read right before the kill command
 * @param {number} input.expectedLen length of the full marker text (with the last character)
 * @param {number|null} input.delaySec D, the device-side sleep between last keystroke and kill (null: no bucket)
 * @param {boolean} input.pidGone the old pid was gone after the kill
 */
export function analyzeKillRun({ lines, pkg, oldPid, expectedLen, delaySec, pidGone }) {
  const parsed = lines.map(parseLogcatLine).filter(Boolean);
  const timing = parsed
    .filter((l) => l.tag === "RaftDraftTiming")
    .map((l) => ({ ...l, ...parseTimingMessage(l.message) }))
    .filter((l) => l.kind);
  const killLine = parsed.find((l) => l.tag === "ActivityManager" && parseKillLine(l.message, pkg) === oldPid) ?? null;
  const killMs = killLine?.tsMs ?? null;

  const beforeKill = (l) => killMs == null || l.tsMs <= killMs;
  const lastChange = [...timing].reverse().find((l) => l.kind === "change_received" && l.len === expectedLen && l.pid === oldPid && beforeKill(l)) ?? null;
  const written = lastChange
    ? timing.find((l) => l.kind === "draft_written" && l.seq >= lastChange.seq && l.len === expectedLen && l.pid === oldPid && beforeKill(l)) ?? null
    : null;
  const lateChange = timing.find((l) => l.kind === "change_received" && l.len === expectedLen && killMs != null && l.tsMs > killMs) ?? null;

  const result = {
    old_pid: oldPid,
    kill_pid: killLine ? parseKillLine(killLine.message, pkg) : null,
    pid_gone: Boolean(pidGone),
    last_change_seq: lastChange?.seq ?? null,
    last_change_uptime_ms: lastChange?.uptimeMs ?? null,
    kill_monotonic_ms: killMs,
    delta_ms: null,
    // logcat's own stamp of the change line minus the app's uptimeMs: both CLOCK_MONOTONIC, so ~0.
    clock_skew_ms: lastChange ? Math.round((lastChange.tsMs - lastChange.uptimeMs) * 1000) / 1000 : null,
    written_before_kill: Boolean(written),
    write_after_change_ms: written && lastChange ? written.uptimeMs - lastChange.uptimeMs : null,
    timing: "unconfirmed",
    reason: null,
  };
  if (!killLine) result.reason = "no ActivityManager kill line for the old pid";
  else if (!lastChange) result.reason = lateChange ? "last keystroke reached the composer only after the kill line" : "no change_received for the full text before the kill";
  else if (!pidGone) result.reason = "old pid still alive after the kill";
  else if (result.clock_skew_ms == null || Math.abs(result.clock_skew_ms) > MAX_CLOCK_SKEW_MS) result.reason = `logcat and uptime clocks disagree (skew ${result.clock_skew_ms} ms)`;
  if (killLine && lastChange) result.delta_ms = Math.round((killMs - lastChange.uptimeMs) * 1000) / 1000;
  if (!result.reason) {
    if (delaySec == null) {
      result.timing = "measured";
    } else {
      const { lo, hi } = bucketFor(delaySec);
      const deltaSec = result.delta_ms / 1000;
      if (deltaSec > lo && deltaSec <= hi) result.timing = "hit";
      else result.reason = `Δ ${result.delta_ms} ms outside bucket (${lo}, ${hi}] s`;
    }
  }
  return result;
}

/** Per-bucket counts for the result table. */
export function summarize(runs) {
  const groups = new Map();
  for (const run of runs) {
    const key = run.group;
    if (!groups.has(key)) groups.set(key, { group: key, runs: 0, hits: 0, unconfirmed: 0, restored: 0, restored_when_hit: 0, written_before_kill: 0, delta_ms: [] });
    const g = groups.get(key);
    g.runs += 1;
    if (run.timing === "hit" || run.timing === "measured") g.hits += 1;
    else g.unconfirmed += 1;
    if (run.pass) g.restored += 1;
    if (run.pass && (run.timing === "hit" || run.timing === "measured")) g.restored_when_hit += 1;
    if (run.written_before_kill) g.written_before_kill += 1;
    if (run.delta_ms != null) g.delta_ms.push(run.delta_ms);
  }
  return [...groups.values()].map((g) => {
    const sorted = [...g.delta_ms].sort((a, b) => a - b);
    const { delta_ms, ...rest } = g;
    return { ...rest, delta_min_ms: sorted[0] ?? null, delta_median_ms: sorted.length ? sorted[Math.floor(sorted.length / 2)] : null, delta_max_ms: sorted.at(-1) ?? null };
  });
}

export function markdownTable(runs, summary) {
  const out = [];
  out.push("### Per bucket", "", "| group | runs | timing hits | unconfirmed | pass (restored / expected) | pass when hit | written before kill | Δ min / median / max (ms) |", "|---|---|---|---|---|---|---|---|");
  for (const g of summary) {
    out.push(`| ${g.group} | ${g.runs} | ${g.hits} | ${g.unconfirmed} | ${g.restored} | ${g.restored_when_hit} | ${g.written_before_kill} | ${g.delta_min_ms ?? "-"} / ${g.delta_median_ms ?? "-"} / ${g.delta_max_ms ?? "-"} |`);
  }
  out.push("", "### Per run", "", "| # | group | D (s) | Δ (ms) | timing | written before kill | expected | composer after relaunch | pass | old pid | kill pid | pid gone | note |", "|---|---|---|---|---|---|---|---|---|---|---|---|---|");
  runs.forEach((r, i) => {
    out.push(`| ${i + 1} | ${r.group} | ${r.delay_sec ?? "-"} | ${r.delta_ms ?? "-"} | ${r.timing} | ${r.written_before_kill ? "yes" : "no"} | ${r.expected_desc} | ${r.observed_desc} | ${r.pass ? "yes" : "no"} | ${r.old_pid ?? "-"} | ${r.kill_pid ?? "-"} | ${r.pid_gone ? "yes" : "no"} | ${(r.reason ?? "").replace(/\|/g, "/")} |`);
  });
  return `${out.join("\n")}\n`;
}
