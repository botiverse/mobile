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

/** `[user,pid,process,...]` of an events-buffer am_kill / am_proc_died line → {pid, name}, or null. */
export function parseEventPid(message) {
  const m = /^\[(\d+),(\d+),([^,\]]+)/.exec(message);
  return m ? { pid: Number(m[2]), name: m[3] } : null;
}

const round3 = (v) => (v == null ? null : Math.round(v * 1000) / 1000);

/**
 * When the old process exited, as an interval on the device's monotonic clock:
 * - lower: the latest line system_server writes right before it signals the process
 *   (ActivityManager "Killing <pid>:<pkg>", events am_kill). The signal comes after both.
 * - upper: the earliest evidence the process is gone: the device-side poller's
 *   `run=<n> pid_gone` probe line (it polls /proc/<pid> every ~5 ms from just before the
 *   force-stop), events am_proc_died, ActivityManager "Process <pkg> (pid <pid>) has died",
 *   libprocessgroup "Successfully killed process cgroup ... pid <pid>".
 */
/** The reason system_server gave for killing [oldPid] of [pkg] on this line, or null if the line is not such a kill. */
export function killReason(l, pkg, oldPid) {
  if (l.tag === "ActivityManager" && parseKillLine(l.message, pkg) === oldPid) return /\):\s*(.*)$/.exec(l.message)?.[1] ?? "";
  if (l.tag === "am_kill") {
    const ev = parseEventPid(l.message);
    if (ev?.pid === oldPid && ev.name === pkg) return l.message.split(",").slice(4).join(",").replace(/\]$/, "");
  }
  return null;
}

/** A force-stop kill: "stop <pkg> due to ..." (cmd activity force-stop / am force-stop). */
export const isForceStopReason = (reason, pkg) => reason != null && reason.startsWith(`stop ${pkg}`);

/**
 * How the old process ended other than by the force-stop, if it did: a system kill for another reason
 * (dependency_died / "depends on provider ...", low memory, ANR), an app crash (AndroidRuntime FATAL in
 * the old pid) or an ANR line. Such a sample is excluded, never counted as a hit or a miss.
 */
export function foreignExit(parsed, { pkg, oldPid }) {
  for (const l of parsed) {
    const reason = killReason(l, pkg, oldPid);
    if (reason != null && !isForceStopReason(reason, pkg)) return { tsMs: l.tsMs, what: `system kill: ${reason}` };
    if (l.tag === "AndroidRuntime" && l.pid === oldPid && /FATAL EXCEPTION/.test(l.message)) return { tsMs: l.tsMs, what: "app crash" };
    if (l.tag === "ActivityManager" && new RegExp(`^ANR in ${pkg.replace(/\./g, "\\.")}\\b`).test(l.message)) return { tsMs: l.tsMs, what: "ANR" };
  }
  return null;
}

export function exitInterval(parsed, { pkg, oldPid, runIndex }) {
  // Only the force-stop's own kill lines bound the exit from below.
  const lowers = parsed.filter((l) => isForceStopReason(killReason(l, pkg, oldPid), pkg));
  const lower = lowers.length ? Math.max(...lowers.map((l) => l.tsMs)) : null;
  const uppers = parsed.filter((l) =>
    (l.tag === "RaftDraftTimingProbe" && new RegExp(`^run=${runIndex} pid_gone\\b`).test(l.message)) ||
    (l.tag === "am_proc_died" && parseEventPid(l.message)?.pid === oldPid && parseEventPid(l.message)?.name === pkg) ||
    (l.tag === "ActivityManager" && l.message.startsWith(`Process ${pkg} (pid ${oldPid}) has died`)) ||
    // system_server after the app's cgroup is empty: "Successfully killed process cgroup uid <uid> pid <pid> in <n>ms".
    (l.tag === "libprocessgroup" && new RegExp(`^Successfully killed process cgroup uid \\d+ pid ${oldPid} `).test(l.message)));
  const valid = uppers.filter((l) => lower == null || l.tsMs >= lower);
  const upper = valid.length ? Math.min(...valid.map((l) => l.tsMs)) : null;
  const sources = (list, at) => [...new Set(list.filter((l) => l.tsMs === at).map((l) => (l.tag === "RaftDraftTimingProbe" ? "pid_gone poll" : l.tag === "libprocessgroup" ? "cgroup empty" : l.tag === "ActivityManager" ? (l.message.startsWith("Killing") ? "AM Killing" : "AM has died") : l.tag)))];
  return {
    lower_ms: lower,
    upper_ms: upper,
    width_ms: lower != null && upper != null ? round3(upper - lower) : null,
    lower_source: lower != null ? sources(lowers, lower).join("+") : null,
    upper_source: upper != null ? sources(valid, upper).join("+") : null,
  };
}

/** Hit only when the whole possible exit interval, measured from [refMs], is inside the bucket. */
function classify(result, delaySec) {
  if (result.reason) return;
  if (delaySec == null) {
    result.timing = "measured";
    return;
  }
  const { lo, hi } = bucketFor(delaySec);
  if (result.delta_lower_ms > lo * 1000 && result.delta_upper_ms <= hi * 1000) result.timing = "hit";
  else result.reason = `exit Δ [${result.delta_lower_ms}, ${result.delta_upper_ms}] ms not inside bucket (${lo}, ${hi}] s`;
}

/**
 * Decides one kill run from the logcat lines captured around it (main, system, crash and events
 * buffers, `-v monotonic`).
 *
 * @param {object} input
 * @param {string[]} input.lines raw logcat lines from before the last keystroke to after the kill
 * @param {string} input.pkg app package
 * @param {number} input.oldPid app pid read right before the kill command
 * @param {boolean} input.samePid the old pid's start time was unchanged in the kill shell (not a recycled pid)
 * @param {number} input.runIndex run number used in the probe lines
 * @param {number} input.expectedLen length of the full marker text (with the last character)
 * @param {number|null} input.delaySec D, the device-side sleep between last keystroke and kill (null: no bucket)
 * @param {boolean} input.pidGone the old pid was gone after the kill
 * @param {string|null} [input.refProbe] measure from this probe line (`run=<n> <refProbe>`) instead of the last change
 */
export function analyzeKillRun({ lines, pkg, oldPid, samePid = true, runIndex, expectedLen, delaySec, pidGone, refProbe = null }) {
  const parsed = lines.map(parseLogcatLine).filter(Boolean);
  const timing = parsed
    .filter((l) => l.tag === "RaftDraftTiming")
    .map((l) => ({ ...l, ...parseTimingMessage(l.message) }))
    .filter((l) => l.kind);
  const exit = exitInterval(parsed, { pkg, oldPid, runIndex });
  const foreign = foreignExit(parsed, { pkg, oldPid });
  const lower = exit.lower_ms;

  const beforeSignal = (l) => lower == null || l.tsMs <= lower;
  const lastChange = [...timing].reverse().find((l) => l.kind === "change_received" && l.len === expectedLen && l.pid === oldPid && beforeSignal(l)) ?? null;
  const written = timing.find((l) => l.kind === "draft_written" && l.len === expectedLen && l.pid === oldPid && beforeSignal(l) && (!lastChange || l.seq >= lastChange.seq)) ?? null;
  const lateChange = timing.find((l) => l.kind === "change_received" && l.len === expectedLen && lower != null && l.tsMs > lower) ?? null;
  const probe = refProbe ? parsed.find((l) => l.tag === "RaftDraftTimingProbe" && l.message.startsWith(`run=${runIndex} ${refProbe}`)) ?? null : null;
  const refMs = refProbe ? probe?.tsMs ?? null : lastChange?.uptimeMs ?? null;

  const result = {
    old_pid: oldPid,
    pid_gone: Boolean(pidGone),
    same_process: Boolean(samePid),
    measured_from: refProbe ?? "last change_received",
    last_change_seq: lastChange?.seq ?? null,
    last_change_uptime_ms: lastChange?.uptimeMs ?? null,
    exit_lower_ms: exit.lower_ms,
    exit_upper_ms: exit.upper_ms,
    exit_width_ms: exit.width_ms,
    exit_lower_source: exit.lower_source,
    exit_upper_source: exit.upper_source,
    delta_lower_ms: refMs != null && exit.lower_ms != null ? round3(exit.lower_ms - refMs) : null,
    delta_upper_ms: refMs != null && exit.upper_ms != null ? round3(exit.upper_ms - refMs) : null,
    // logcat's own stamp of the change line minus the app's uptimeMs: both CLOCK_MONOTONIC, so ~0.
    clock_skew_ms: lastChange ? round3(lastChange.tsMs - lastChange.uptimeMs) : null,
    written_before_kill: Boolean(written),
    write_after_change_ms: written && lastChange ? written.uptimeMs - lastChange.uptimeMs : null,
    timing: "unconfirmed",
    reason: null,
  };
  if (foreign && (exit.lower_ms == null || foreign.tsMs <= exit.lower_ms)) {
    // Not a sample of the force-stop at all.
    result.timing = "excluded";
    result.reason = `exit not by force-stop (${foreign.what})`;
    return result;
  }
  if (foreign) result.note = `after the force-stop: ${foreign.what}`;
  if (exit.lower_ms == null) result.reason = "no pre-signal force-stop kill line (AM Killing / am_kill \"stop <pkg>\") for the old pid";
  else if (exit.upper_ms == null) result.reason = "exit not bounded above (no pid_gone poll, am_proc_died or has-died line)";
  else if (!samePid) result.reason = "old pid's start time changed: not the same process";
  else if (!pidGone) result.reason = "old pid still alive after the kill";
  else if (refProbe && !probe) result.reason = `no ${refProbe} probe line`;
  else if (!refProbe && !lastChange) result.reason = lateChange ? "last keystroke reached the composer only after the kill signal could have been sent" : "no change_received for the full text before the kill";
  else if (!refProbe && (result.clock_skew_ms == null || Math.abs(result.clock_skew_ms) > MAX_CLOCK_SKEW_MS)) result.reason = `logcat and uptime clocks disagree (skew ${result.clock_skew_ms} ms)`;
  classify(result, delaySec);
  return result;
}

/** Per-bucket counts for the result table. */
export function summarize(runs) {
  const groups = new Map();
  for (const run of runs) {
    const key = run.group;
    if (!groups.has(key)) groups.set(key, { group: key, runs: 0, hits: 0, unconfirmed: 0, excluded: 0, restored: 0, restored_when_hit: 0, written_before_kill: 0, delta_ms: [], width_ms: [] });
    const g = groups.get(key);
    g.runs += 1;
    if (run.timing === "hit" || run.timing === "measured") g.hits += 1;
    else if (run.timing === "excluded") g.excluded += 1;
    else g.unconfirmed += 1;
    if (run.pass) g.restored += 1;
    if (run.pass && (run.timing === "hit" || run.timing === "measured")) g.restored_when_hit += 1;
    if (run.written_before_kill) g.written_before_kill += 1;
    if (run.delta_upper_ms != null) g.delta_ms.push(run.delta_upper_ms);
    if (run.exit_width_ms != null) g.width_ms.push(run.exit_width_ms);
  }
  const stats = (values) => {
    const sorted = [...values].sort((a, b) => a - b);
    return { min: sorted[0] ?? null, median: sorted.length ? sorted[Math.floor(sorted.length / 2)] : null, max: sorted.at(-1) ?? null };
  };
  return [...groups.values()].map((g) => {
    const { delta_ms, width_ms, ...rest } = g;
    const d = stats(delta_ms);
    const w = stats(width_ms);
    return { ...rest, delta_upper_min_ms: d.min, delta_upper_median_ms: d.median, delta_upper_max_ms: d.max, exit_width_median_ms: w.median, exit_width_max_ms: w.max };
  });
}

const cell = (v) => (v == null ? "-" : String(v).replace(/\|/g, "/"));

export function markdownTable(runs, summary) {
  const out = [];
  out.push(
    "### Per bucket", "",
    "| group | runs | timing hits | unconfirmed | excluded (exit not by force-stop) | pass (restored / expected) | pass when hit | written before kill | exit Δ upper min / median / max (ms) | exit interval width median / max (ms) |",
    "|---|---|---|---|---|---|---|---|---|---|",
  );
  for (const g of summary) {
    out.push(`| ${g.group} | ${g.runs} | ${g.hits} | ${g.unconfirmed} | ${g.excluded} | ${g.restored} | ${g.restored_when_hit} | ${g.written_before_kill} | ${cell(g.delta_upper_min_ms)} / ${cell(g.delta_upper_median_ms)} / ${cell(g.delta_upper_max_ms)} | ${cell(g.exit_width_median_ms)} / ${cell(g.exit_width_max_ms)} |`);
  }
  out.push(
    "", "### Per run", "",
    "| # | group | D (s) | exit Δ lower (ms) | exit Δ upper (ms) | width (ms) | bounds | timing | written before kill | expected | composer after relaunch | pass | old pid | new pid | pid gone | note |",
    "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|",
  );
  runs.forEach((r, i) => {
    const bounds = r.exit_lower_source ? `${r.exit_lower_source} .. ${r.exit_upper_source ?? "?"}` : "-";
    out.push(`| ${i + 1} | ${r.group} | ${cell(r.delay_sec)} | ${cell(r.delta_lower_ms)} | ${cell(r.delta_upper_ms)} | ${cell(r.exit_width_ms)} | ${bounds} | ${r.timing} | ${r.written_before_kill ? "yes" : "no"} | ${cell(r.expected_desc)} | ${cell(r.observed_desc)} | ${r.pass ? "yes" : "no"} | ${cell(r.old_pid)} | ${cell(r.new_pid)} | ${r.pid_gone ? "yes" : "no"} | ${cell([r.reason, r.note, r.error, r.foreign_app_deaths?.length ? `other app deaths: ${r.foreign_app_deaths.join("; ")}` : null].filter(Boolean).join("; ") || null)} |`);
  });
  return `${out.join("\n")}\n`;
}
