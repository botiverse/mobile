// Pure analysis for scripts/qa/android_draft_kill_timing.mjs (task #246): parses `adb logcat -v monotonic`
// lines and decides, on the device's own clock, how long after the composer took the last typed text the
// app process was killed, and whether that text was already in the durable draft.
//
// Clock: logcat `-v monotonic` prints CLOCK_MONOTONIC seconds; the app's `RaftDraftTiming` lines carry
// SystemClock.uptimeMillis(), which is CLOCK_MONOTONIC too. Δ = kill line (ActivityManager "Killing <pid>:<pkg>",
// logged by system_server right before it signals the process) − last change_received uptimeMs.

import nodeFs from "node:fs";

/** One parsed logcat line, or null. Format: `<sec>.<ms>  <pid>  <tid> <L> <tag>: <message>`. */
export function parseLogcatLine(line) {
  const m = /^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.+?)\s*:\s(.*)$/.exec(line);
  if (!m) return null;
  return { tsMs: Math.round(Number(m[1]) * 1000 * 1000) / 1000, pid: Number(m[2]), tid: Number(m[3]), level: m[4], tag: m[5].trim(), message: m[6] };
}

/** `change_received seq=3 len=12 uptimeMs=123456` → {kind, seq, len, uptimeMs}. */
export function parseTimingMessage(message) {
  // `path=` (sync | background | flush) and `sync_write_fallback` come from builds with the synchronous first write.
  const m = /^(change_received|draft_written|sync_write_fallback) seq=(\d+) len=(\d+)(?: path=(\w+))? uptimeMs=(\d+)\s*$/.exec(message);
  if (!m) return null;
  return { kind: m[1], seq: Number(m[2]), len: Number(m[3]), path: m[4] ?? null, uptimeMs: Number(m[5]) };
}

/** How typed-text writes were made: counts of draft_written by path, and of synchronous-write fallbacks. */
export function writePathCounts(lines) {
  return logcatStatsOf(lines).write_paths;
}

/** The pid an ActivityManager line says it is killing for [pkg] (main process only), or null. */
export function parseKillLine(message, pkg) {
  const m = /^Killing (\d+):([^/\s]+)\/\S+/.exec(message);
  if (!m || m[2] !== pkg) return null;
  return Number(m[1]);
}

/** |logcat monotonic stamp − uptimeMs| of the change line above which Δ is not trusted. */
export const MAX_CLOCK_SKEW_MS = 20;

/**
 * Tolerance of each bucket: the whole measured exit Δ interval must lie in [lo_ms, hi_ms] (inclusive).
 * Nominal D (the device-side sleep) is never counted as measured.
 */
export const BUCKETS = {
  0.05: { lo_ms: 20, hi_ms: 80 },
  0.1: { lo_ms: 70, hi_ms: 130 },
  0.3: { lo_ms: 250, hi_ms: 350 },
  0.7: { lo_ms: 600, hi_ms: 800 },
  1: { lo_ms: 800, hi_ms: 1200 },
};

export function bucketFor(delaySec) {
  return BUCKETS[delaySec] ?? { lo_ms: Math.round(delaySec * 800), hi_ms: Math.round(delaySec * 1200) };
}

/** Send-then-kill regression (b): the exit must be within 100 ms of the send tap. */
export const SEND_WINDOW = { lo_ms: 0, hi_ms: 100 };

export const insideWindow = (lowerMs, upperMs, w) => lowerMs != null && upperMs != null && lowerMs >= w.lo_ms && upperMs <= w.hi_ms;

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

/** Kill reasons a run's own action may produce: the force-stop, and for the recents swipe also "remove task". */
export const isIntendedReason = (reason, pkg, extra = []) => isForceStopReason(reason, pkg) || (reason != null && extra.some((p) => reason.startsWith(p)));

/**
 * How the old process ended other than by the force-stop, if it did: a system kill for another reason
 * (dependency_died / "depends on provider ...", low memory, ANR), an app crash (AndroidRuntime FATAL in
 * the old pid) or an ANR line. Such a sample is excluded, never counted as a hit or a miss.
 */
export function foreignExit(parsed, { pkg, oldPid, extraReasons = [] }) {
  for (const l of parsed) {
    const reason = killReason(l, pkg, oldPid);
    if (reason != null && !isIntendedReason(reason, pkg, extraReasons)) return { tsMs: l.tsMs, what: `system kill: ${reason}` };
    if (l.tag === "AndroidRuntime" && l.pid === oldPid && /FATAL EXCEPTION/.test(l.message)) return { tsMs: l.tsMs, what: "app crash" };
    if (l.tag === "ActivityManager" && new RegExp(`^ANR in ${pkg.replace(/\./g, "\\.")}\\b`).test(l.message)) return { tsMs: l.tsMs, what: "ANR" };
  }
  return null;
}

export function exitInterval(parsed, { pkg, oldPid, runIndex, extraReasons = [] }) {
  // Only the run's own kill lines (force-stop; recents swipe: remove task) bound the exit from below.
  const lowers = parsed.filter((l) => isIntendedReason(killReason(l, pkg, oldPid), pkg, extraReasons));
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

/** The tightest bucket whose window holds the whole exit Δ interval [lowerMs, upperMs], or null. */
/**
 * The bucket a kill run counts for: the one it aimed at ([aimedSec]) when the WHOLE exit interval lies in
 * that bucket's window, else the tightest bucket among [enabledSecs] whose window holds the whole
 * interval, else null (the sample counts nowhere). Buckets not in this round never get credit.
 */
export function creditedBucket(lowerMs, upperMs, aimedSec, enabledSecs) {
  if (lowerMs == null || upperMs == null) return null;
  const enabled = enabledSecs.filter((d) => BUCKETS[d]);
  if (aimedSec != null && enabled.includes(aimedSec) && insideWindow(lowerMs, upperMs, BUCKETS[aimedSec])) return aimedSec;
  const fits = enabled.filter((d) => insideWindow(lowerMs, upperMs, BUCKETS[d])).sort((a, b) => BUCKETS[a].hi_ms - BUCKETS[b].hi_ms);
  return fits.length ? fits[0] : null;
}

export function measuredBucket(lowerMs, upperMs) {
  if (lowerMs == null || upperMs == null) return null;
  const fits = Object.entries(BUCKETS)
    .filter(([, b]) => insideWindow(lowerMs, upperMs, b))
    .sort((a, b) => a[1].hi_ms - b[1].hi_ms);
  return fits.length ? Number(fits[0][0]) : null;
}

/** Hit only when the whole possible exit interval, measured from [refMs], is inside the bucket. */
function classify(result, delaySec) {
  if (result.reason) return;
  if (delaySec == null) {
    result.timing = "measured";
    return;
  }
  const w = bucketFor(delaySec);
  if (insideWindow(result.delta_lower_ms, result.delta_upper_ms, w)) result.timing = "hit";
  else result.reason = `exit Δ [${result.delta_lower_ms}, ${result.delta_upper_ms}] ms not inside bucket [${w.lo_ms}, ${w.hi_ms}] ms`;
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
export function analyzeKillRun({ lines, pkg, oldPid, samePid = true, runIndex, expectedLen, delaySec, pidGone, refProbe = null, extraReasons = [], window = null }) {
  const parsed = lines.map(parseLogcatLine).filter(Boolean);
  const timing = parsed
    .filter((l) => l.tag === "RaftDraftTiming")
    .map((l) => ({ ...l, ...parseTimingMessage(l.message) }))
    .filter((l) => l.kind);
  const exit = exitInterval(parsed, { pkg, oldPid, runIndex, extraReasons });
  const foreign = foreignExit(parsed, { pkg, oldPid, extraReasons });
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
  result.kill_reason = exit.lower_ms != null ? parsed.map((l) => killReason(l, pkg, oldPid)).find((r) => isIntendedReason(r, pkg, extraReasons)) ?? null : null;
  if (window && !result.reason) {
    // A regression with its own required window (b: within 100 ms of the send tap).
    if (insideWindow(result.delta_lower_ms, result.delta_upper_ms, window)) result.timing = "hit";
    else result.reason = `exit Δ [${result.delta_lower_ms}, ${result.delta_upper_ms}] ms not inside required [${window.lo_ms}, ${window.hi_ms}] ms`;
  } else classify(result, delaySec);
  // Whatever D was asked for, the bucket the measured interval actually proves (bucketed modes only).
  // The exit interval is trustworthy (own kill, bounds measured, clocks agree): only then can it count for a bucket.
  result.interval_trusted = !result.reason || /not inside bucket/.test(result.reason ?? "");
  result.measured_bucket = result.interval_trusted ? (delaySec == null ? null : measuredBucket(result.delta_lower_ms, result.delta_upper_ms)) : null;
  return result;
}

const quantiles = (values, unit) => {
  values.sort((a, b) => a - b);
  const at = (q) => (values.length ? values[Math.min(values.length - 1, Math.floor(q * values.length))] : null);
  return { samples: values.length, [`min_${unit}`]: values[0] ?? null, [`p50_${unit}`]: at(0.5), [`p95_${unit}`]: at(0.95), [`p99_${unit}`]: at(0.99), [`max_${unit}`]: values.at(-1) ?? null };
};

/**
 * Whether the crash journal's cost samples cover the typed edits, paired by (process, seq). Every typed
 * change logs `change_received seq=N` first (the moment it arrived) and then, from the same synchronous
 * call, `draft_journal_written seq=N ...` (the cost sample) or `draft_journal_write_failed seq=N`.
 * - paired: an edit with its cost line; failures: an edit with its failure line (or an unpaired one).
 * - likely_interrupted: the LAST edit of a process with neither line, AND evidence that this process
 *   died at or after that edit's marker (AM "Killing <pid>", am_kill / am_proc_died of the pid, its cgroup
 *   killed, AM "has died"): the kill most likely landed during that journal write. Listed apart.
 * - missing: every other edit with neither line, including a last edit with no death evidence.
 * - unpaired: cost / failure lines with no edit of that (process, seq), and repeats of one edit's cost
 *   line; never counted for an edit and never in the cost percentiles.
 * Zero tolerance: a single missing input makes the run "unconfirmed" (a lost logcat line cannot be told
 * apart from a journal write that never ran). Any failure fails the run.
 */
export const JOURNAL_MISSING_TOLERANCE = 0;
export function journalCoverage({ edits, paired, failures, likely_interrupted = 0, missing, unpaired = 0 }) {
  const allowed = Math.floor(edits * JOURNAL_MISSING_TOLERANCE);
  return { edits, paired, failures, likely_interrupted, missing, unpaired, allowed_missing: allowed, covered: edits > 0 && missing <= allowed };
}

/**
 * Pairs the journal lines with the edits. [editsByPid]: pid -> [{ seq, tsMs }] in log order;
 * [costLines]: [{ key: "pid:seq", costUs }] in log order; [failureKeys]: "pid:seq" keys; [deathsByPid]:
 * pid -> logcat times of death evidence. Returns { coverage, pairedCostsUs }.
 */
export function pairJournalLines(editsByPid, costLines, failureKeys, deathsByPid = new Map()) {
  const editKeys = new Set();
  for (const [pid, list] of editsByPid) for (const e of list) editKeys.add(`${pid}:${e.seq}`);
  const costByKey = new Map();
  const partsByKey = new Map();
  let unpaired = 0;
  for (const { key, costUs, parts } of costLines) {
    if (editKeys.has(key) && !costByKey.has(key)) {
      costByKey.set(key, costUs);
      if (parts) partsByKey.set(key, parts);
    } else unpaired += 1;
  }
  let edits = 0;
  let failures = 0;
  let likelyInterrupted = 0;
  let missing = 0;
  for (const [pid, list] of editsByPid) {
    list.forEach((e, index) => {
      const key = `${pid}:${e.seq}`;
      edits += 1;
      if (costByKey.has(key)) return;
      if (failureKeys.has(key)) failures += 1;
      else if (index === list.length - 1 && (deathsByPid.get(pid) ?? []).some((at) => at >= e.tsMs)) likelyInterrupted += 1;
      else missing += 1;
    });
  }
  const unpairedFailures = [...failureKeys].filter((key) => !editKeys.has(key)).length;
  const coverage = journalCoverage({
    edits,
    paired: costByKey.size,
    failures: failures + unpairedFailures,
    likely_interrupted: likelyInterrupted,
    missing,
    unpaired: unpaired + unpairedFailures,
  });
  // ENCODE / STORE (index + put) halves of the paired samples that carry them.
  const pairedPartsUs = {};
  for (const name of ["encode", "store", "index", "put"]) {
    pairedPartsUs[name] = [...partsByKey.values()].map((p) => p[name]).filter((v) => v != null);
  }
  return { coverage, pairedCostsUs: [...costByKey.values()], pairedPartsUs };
}

/** The pid a death-evidence line names (AM Killing / has died, am_kill / am_proc_died, cgroup killed), or null. */
export function deathEvidencePid(l) {
  if (l.tag === "ActivityManager") {
    const m = /^Killing (\d+):/.exec(l.message) ?? /^Process \S+ \(pid (\d+)\) has died/.exec(l.message);
    return m ? Number(m[1]) : null;
  }
  if (l.tag === "am_kill" || l.tag === "am_proc_died") return parseEventPid(l.message)?.pid ?? null;
  if (l.tag === "libprocessgroup") {
    const m = /^Successfully killed process cgroup uid \d+ pid (\d+) /.exec(l.message);
    return m ? Number(m[1]) : null;
  }
  return null;
}

/**
 * The wrap-up statistics of a whole run, fed one logcat line at a time ([add]) so the raw logcat is never
 * held in memory: what it keeps grows with the app's own RaftDraftTiming lines (one number per keystroke),
 * not with the size of the logcat.
 *
 * - write_latency: the real persistence window on this device, change_received → draft_written of the same
 *   change (same pid and seq), over every change the run logged. draft_written is logged after the durable
 *   write and the active-draft-records refresh, so this is an upper bound on when the text became durable.
 * - write_paths: counts of draft_written by path, and of synchronous-write fallbacks.
 * - journal_write_cost: the crash journal's input-thread write cost (`draft_journal_written ... costUs=N`).
 */
export function createLogcatStats() {
  const changes = new Map();
  const latencies = [];
  const paths = { sync: 0, background: 0, flush: 0, unlabeled: 0, sync_write_fallback: 0 };
  const editsByPid = new Map();
  const costLines = [];
  const failureKeys = new Set();
  const deathsByPid = new Map();
  const appPids = new Set();
  return {
    /** Pids that logged RaftDraftTiming lines: the app's own processes. */
    appPids,
    add(raw) {
      if (!raw.includes("RaftDraftTiming")) {
        if (!/Killing |has died|am_kill|am_proc_died|libprocessgroup/.test(raw)) return;
        const d = parseLogcatLine(raw);
        const pid = d ? deathEvidencePid(d) : null;
        if (pid != null) {
          if (!deathsByPid.has(pid)) deathsByPid.set(pid, []);
          deathsByPid.get(pid).push(d.tsMs);
        }
        return;
      }
      const l = parseLogcatLine(raw);
      if (!l || l.tag !== "RaftDraftTiming") return;
      appPids.add(l.pid);
      const cost = /^draft_journal_written\b.*\bcostUs=(\d+)/.exec(l.message);
      if (cost) {
        const seq = /\bseq=(\d+)/.exec(l.message);
        const part = (name) => {
          const m = new RegExp(`\\b${name}Us=(\\d+)`).exec(l.message);
          return m ? Number(m[1]) : null;
        };
        costLines.push({
          key: seq ? `${l.pid}:${seq[1]}` : `${l.pid}:noseq`,
          costUs: Number(cost[1]),
          parts: { encode: part("encode"), store: part("store"), index: part("index"), put: part("put") },
        });
        return;
      }
      if (/^draft_journal_write_failed\b/.test(l.message)) {
        const seq = /\bseq=(\d+)/.exec(l.message);
        failureKeys.add(seq ? `${l.pid}:${seq[1]}` : `${l.pid}:noseq:${failureKeys.size}`);
        return;
      }
      const t = parseTimingMessage(l.message);
      if (!t) return;
      const key = `${l.pid}:${t.seq}`;
      if (t.kind === "change_received") {
        if (!editsByPid.has(l.pid)) editsByPid.set(l.pid, []);
        editsByPid.get(l.pid).push({ seq: t.seq, tsMs: l.tsMs });
        changes.set(key, t.uptimeMs);
      }
      else if (changes.has(key)) latencies.push(t.uptimeMs - changes.get(key));
      if (t.kind === "sync_write_fallback") paths.sync_write_fallback += 1;
      else if (t.kind === "draft_written") paths[t.path && t.path in paths ? t.path : "unlabeled"] += 1;
    },
    result() {
      const journal = pairJournalLines(editsByPid, costLines, failureKeys, deathsByPid);
      return {
        write_latency: quantiles([...latencies], "ms"),
        write_paths: { ...paths },
        // Percentiles of PAIRED samples only (a cost line matched to its change_received by process + seq).
        journal_write_cost: { ...quantiles([...journal.pairedCostsUs], "us"), basis: "paired samples only", unpaired_excluded: journal.coverage.unpaired },
        // The same paired samples split: ENCODE (entry to JSON) and STORE (index + put), each with its parts.
        journal_write_cost_parts: Object.fromEntries(Object.entries(journal.pairedPartsUs).map(([name, values]) => [name, quantiles([...values], "us")])),
        journal_coverage: journal.coverage,
      };
    },
  };
}

export function logcatStatsOf(lines) {
  const stats = createLogcatStats();
  for (const line of lines) stats.add(line);
  return stats.result();
}

export const writeLatencyStats = (lines) => logcatStatsOf(lines).write_latency;
export const journalCostStats = (lines) => logcatStatsOf(lines).journal_write_cost;

/**
 * Calls [onLine] for every line of [file] from byte [from] on, reading at most [chunkBytes] at a time; a line
 * is decoded only once it is complete, so a multi-byte character split across two reads stays whole. A line
 * longer than [maxLineBytes] is cut there (the rest of it is dropped). Memory is bounded by chunkBytes +
 * maxLineBytes whatever the file size. Returns { bytes, lines, truncated }.
 */
export function forEachLogLine(file, from, onLine, { chunkBytes = 1 << 20, maxLineBytes = 64 << 10, fsImpl } = {}) {
  const io = fsImpl ?? nodeFs;
  const fd = io.openSync(file, "r");
  const chunk = Buffer.alloc(chunkBytes);
  let carry = [];
  let carryBytes = 0;
  let position = from;
  let lines = 0;
  let truncated = 0;
  const take = (part) => {
    const room = maxLineBytes - carryBytes;
    if (part.length > room) {
      if (room > 0) carry.push(Buffer.from(part.subarray(0, room)));
      carryBytes = maxLineBytes;
      return false;
    }
    carry.push(Buffer.from(part));
    carryBytes += part.length;
    return true;
  };
  let cut = false;
  const emit = () => {
    const text = Buffer.concat(carry, carryBytes).toString("utf8");
    carry = [];
    carryBytes = 0;
    if (cut) truncated += 1;
    cut = false;
    lines += 1;
    onLine(text.endsWith("\r") ? text.slice(0, -1) : text);
  };
  try {
    for (;;) {
      const read = io.readSync(fd, chunk, 0, chunkBytes, position);
      if (read <= 0) break;
      position += read;
      let start = 0;
      for (let nl = chunk.indexOf(10, 0); nl !== -1 && nl < read; nl = chunk.indexOf(10, start)) {
        if (!take(chunk.subarray(start, nl))) cut = true;
        emit();
        start = nl + 1;
      }
      if (start < read && !take(chunk.subarray(start, read))) cut = true;
    }
    if (carryBytes > 0) emit();
  } finally {
    io.closeSync(fd);
  }
  return { bytes: position - from, lines, truncated };
}

/**
 * Lines kept as evidence (timing-lines.txt): the app's timing markers and the probe's, ActivityManager /
 * am_kill / am_proc_died lines naming [pkg], the cgroup line of a killed pid in [appPids] and the crash
 * headers (`FATAL EXCEPTION`, `Process: <pkg>, PID`) logged by a pid in [appPids]. No other process's
 * lines, no stack traces, nothing that could carry a token.
 */
export function isEvidenceLine(line, pkg, appPids = new Set()) {
  if (/\bRaftDraftTiming(Probe)?\s*:/.test(line)) return true;
  if (/\bActivityManager\s*:/.test(line) || /\b(am_kill|am_proc_died)\s*:/.test(line)) return line.includes(pkg);
  const l = /\b(libprocessgroup|AndroidRuntime)\s*:/.test(line) ? parseLogcatLine(line) : null;
  if (!l) return false;
  // Only the app's own processes: the cgroup line of a killed app pid, and crash headers the app logged.
  if (l.tag === "libprocessgroup") {
    const m = /^Successfully killed process cgroup uid \d+ pid (\d+) /.exec(l.message);
    return m != null && appPids.has(Number(m[1]));
  }
  if (l.tag === "AndroidRuntime") {
    if (!appPids.has(l.pid)) return false;
    if (/^FATAL EXCEPTION: /.test(l.message)) return true;
    const p = /^Process: ([^,\s]+), PID: (\d+)/.exec(l.message);
    return p != null && p[1] === pkg;
  }
  return false;
}

/** Every acceptance condition the finished [summary] does not meet (empty = pass). */
export function acceptanceUnmet(summary, config) {
  const { runsPerBucket, fastRuns, env = {} } = config;
  const unmet = [];
  for (const [d, p] of Object.entries(summary.bucket_progress ?? {})) if (p.valid < runsPerBucket) unmet.push(`bucket ${d} s: ${p.valid}/${runsPerBucket} valid in ${p.attempted} attempts`);
  for (const r of summary.runs) if (!r.pass) unmet.push(`run ${r.run} (${r.group}): not restored as expected${r.reason ? ` - ${r.reason}` : ""}`);
  for (const [d, p] of Object.entries(summary.fast_progress ?? {})) if (p.valid < fastRuns) unmet.push(`fast typing bucket ${d} s: ${p.valid}/${fastRuns} valid in ${p.attempted} attempts`);
  const fast = summary.runs.filter((r) => r.mode === "fast");
  if (fast.some((r) => !r.typing_in_3_to_5_s)) unmet.push(`fast typing outside 3-5 s: ${fast.filter((r) => !r.typing_in_3_to_5_s).map((r) => `${r.typing_span_ms ?? "?"} ms`).join(", ")}`);
  if (!summary.runs.some((r) => r.mode === "send" && r.timing === "hit" && r.pass)) unmet.push("send regression: no attempt with the exit inside [0, 100] ms of the tap and the message delivered");
  if (!summary.journal_write_cost?.samples) unmet.push("no draft_journal_written lines: the crash journal's cost was not measured");
  else if (summary.journal_write_cost.p95_us >= 1000) unmet.push(`crash-journal write p95 ${summary.journal_write_cost.p95_us} µs is not below 1000 µs`);
  if (summary.journal_coverage?.failures > 0) unmet.push(`crash-journal write failed ${summary.journal_coverage.failures} time(s) (draft_journal_write_failed)`);
  if (!summary.apk || summary.apk.file_sha256 !== summary.apk.installed_base_apk_sha256) unmet.push("installed base.apk SHA-256 differs from the downloaded APK (or was not read)");
  if (summary.apk?.downloaded_sha256_from_workflow && summary.apk.downloaded_sha256_from_workflow !== summary.apk.file_sha256) unmet.push("APK SHA-256 differs from the one the workflow downloaded");
  if (env.REQUIRE_COMMIT && !["identical", "ahead"].includes(env.REQUIRE_COMMIT_STATUS ?? "")) unmet.push(`APK source ${env.APK_SOURCE_SHA} does not contain ${env.REQUIRE_COMMIT} (compare: ${env.REQUIRE_COMMIT_STATUS || "unavailable"})`);
  if (config.recents !== false && !summary.runs.some((r) => r.mode === "recents" && r.recents_swipe_killed)) unmet.push("recents regression: the swipe did not remove the task / kill the process");
  return unmet;
}

/**
 * Conditions that keep a run from passing without making it a failure: the evidence does not cover what
 * the pass would claim.
 * - Channel concurrency: the check must have read the run's channel completely (see channelActivityReport)
 *   and found nothing this run did not post. Other activity is not verified to be harmless, so it is
 *   unconfirmed, as is a failed or incomplete read.
 * - Crash-journal cost: the samples must cover the typed edits (journalCoverage).
 */
export function acceptanceUnconfirmed(summary) {
  const unconfirmed = [];
  const a = summary.channel_activity;
  if (!a || a.error) unconfirmed.push(`channel concurrency not checked${a?.error ? ` (${a.error})` : ""}`);
  else {
    if (!a.coverage?.complete) unconfirmed.push(`channel concurrency check incomplete: ${a.coverage?.reason ?? "coverage unknown"}`);
    if (a.other_activity == null) unconfirmed.push("other activity in the run's channel unknown");
    else if (a.other_activity > 0) unconfirmed.push(`other activity in the run's channel: ${a.other_activity} item(s) ${JSON.stringify(a.other_kinds ?? {})}, not verified to be harmless`);
  }
  const c = summary.journal_coverage;
  if (!c) unconfirmed.push("crash-journal sample coverage unknown");
  else if (!c.covered && c.failures === 0) {
    unconfirmed.push(`crash-journal cost samples do not cover the edits: ${c.paired} paired of ${c.edits} edits, ${c.missing} without a result (allowed ${c.allowed_missing}), ${c.likely_interrupted} likely interrupted by the process's death`);
  }
  return unconfirmed;
}

/**
 * A channel-messages response as a usable read, or not: only HTTP 200 whose body parsed to a messages
 * array (the array itself, or `{ messages: [...] }`) counts, and only then is an empty array an empty
 * channel. A 200 with an empty or unparsable body ([json] null / [parseError]) or another shape is
 * "unavailable", never an empty channel.
 */
export function parseMessagesResponse(status, json, parseError = false) {
  if (status !== 200) return { status, ok: false, rows: [], problem: `HTTP ${status}` };
  if (parseError || json == null) return { status, ok: false, rows: [], problem: "HTTP 200 with an empty or unparsable body" };
  const rows = Array.isArray(json) ? json : Array.isArray(json?.messages) ? json.messages : null;
  if (rows == null) return { status, ok: false, rows: [], problem: "HTTP 200 without a messages array" };
  return { status, ok: true, rows };
}

/**
 * A short, token-free summary of an error response body: only `error`, `code`, `message`, `statusCode`
 * and `reason` (strings cut at 120 characters), so a 401 from an expired token can be told from another
 * auth failure without logging anything else the body carries.
 */
export function errorBodySummary(json) {
  if (json == null || typeof json !== "object" || Array.isArray(json)) return null;
  const out = {};
  for (const key of ["error", "code", "message", "statusCode", "reason"]) {
    const v = json[key];
    if (v == null) continue;
    out[key] = typeof v === "string" ? v.slice(0, 120) : typeof v === "number" || typeof v === "boolean" ? v : "[object]";
  }
  return Object.keys(out).length ? out : null;
}

/**
 * One server query of the send regression, re-authenticating at most ONCE per run: on HTTP 401 (and only
 * if [state].reauthed is still false) [reauth] signs the same test identity in again and the QUERY alone
 * is retried once. Nothing is ever re-sent. Returns the last answer and the query records (phase, time,
 * status, ok, problem / body summary, matches), the retry marked as such; [state] records the re-auth.
 */
export async function queryWithReauth(phase, runQuery, reauth, state) {
  const record = (p, q) => ({
    phase: p,
    at: q.at,
    http: q.http,
    ok: q.ok,
    ...(q.problem ? { problem: q.problem } : {}),
    ...(q.body_summary ? { body_summary: q.body_summary } : {}),
    found: Boolean(q.message),
    matches: q.matches ?? (q.message ? 1 : 0),
  });
  let q = await runQuery();
  const records = [record(phase, q)];
  if (q.http === 401 && !state.reauthed) {
    state.reauthed = true;
    try {
      await reauth();
      state.reauth = "ok";
    } catch (error) {
      state.reauth = "failed";
      state.reauth_error = String(error?.message ?? error).slice(0, 120);
    }
    if (state.reauth === "ok") {
      q = await runQuery();
      records.push(record(`${phase}: retry after re-auth`, q));
    }
  }
  return { q, records };
}

/**
 * How the sent message reached the server, from the query records: "exactly once" (the run's unique text
 * seen once), "more than once" (a duplicate send), else "unknown": not seen by the deadline, or no query
 * succeeded. A query that answered but did not show it never proves it was lost.
 */
export function sendDeliveryCount(queries) {
  const ok = queries.filter((q) => q.ok);
  if (!ok.length) return { delivered: "unknown", reason: "no query succeeded" };
  const maxMatches = Math.max(...ok.map((q) => q.matches ?? 0));
  if (maxMatches === 1) return { delivered: "exactly once", matches: 1 };
  if (maxMatches > 1) return { delivered: "more than once", matches: maxMatches };
  return { delivered: "unknown", matches: 0, reason: `not found by the deadline (last successful query at ${ok.at(-1).at})` };
}

/**
 * The run's channel as the concurrency check read it ([status], [ok] / [problem] from parseMessagesResponse,
 * [rows] newest first, at most [limit]).
 * The channel is created by this run, so a page shorter than [limit] is its whole history ("complete");
 * a full page may have cut older items off, so it is "incomplete", never "no other activity". Ours =
 * content carrying this run's marker tag and posted by the QA account. Only counts, kinds and the
 * covered createdAt window are kept, never other content.
 */
export function channelActivityReport({ status, ok = status === 200, problem = null, rows = [], limit, runTag, selfId = null }) {
  if (!ok) {
    const why = problem ?? `HTTP ${status}`;
    return { http: status, other_activity: null, coverage: { complete: false, reason: `the channel query is unavailable (${why})` } };
  }
  const created = rows.map((m) => m?.createdAt ?? m?.created_at ?? null).filter(Boolean).sort();
  const complete = rows.length < limit;
  const coverage = {
    complete,
    limit,
    items: rows.length,
    oldest_created_at: created[0] ?? null,
    newest_created_at: created.at(-1) ?? null,
    ...(complete ? {} : { reason: `a full page of ${limit} items was read; anything older is not covered` }),
  };
  const ours = (m) => String(m?.content ?? "").includes(runTag) && (selfId == null || m?.senderId === selfId);
  const others = rows.filter((m) => !ours(m));
  const kinds = {};
  for (const m of others) {
    const kind = `${m?.type ?? m?.messageType ?? "message"}${selfId != null && m?.senderId === selfId ? " (QA account)" : ""}`;
    kinds[kind] = (kinds[kind] ?? 0) + 1;
  }
  return { http: status, messages: rows.length, ours: rows.length - others.length, other_activity: others.length, other_kinds: kinds, coverage };
}

/**
 * The verdict, decided at wrap-up AFTER the whole-run logcat statistics exist: [logcatStats] (or null when
 * the capture could not be read) is merged into [summary] first, so the crash-journal gate sees the measured
 * cost. A run that already failed at a step keeps that failure; otherwise the result is "fail" with any
 * unmet condition, else "unconfirmed" with any unconfirmed one, else "pass".
 */
export function wrapUpVerdict(summary, logcatStats, config) {
  if (logcatStats) Object.assign(summary, logcatStats);
  if (summary.failed_step) {
    summary.result = "fail";
    return summary;
  }
  summary.unmet = acceptanceUnmet(summary, config);
  summary.unconfirmed = acceptanceUnconfirmed(summary);
  summary.result = summary.unmet.length ? "fail" : summary.unconfirmed.length ? "unconfirmed" : "pass";
  return summary;
}

/**
 * The journal write cost, sample by sample, as timing-lines.txt has it (reproducible from that file alone):
 * 1. A sample is a `draft_journal_written seq=N ... costUs encodeUs storeUs indexUs putUs` line whose
 *    (logcat pid, seq) matches a `change_received seq=N` line of the same pid; the first such line per
 *    (pid, seq) counts, repeats and lines without a matching input are excluded (counted as unpaired).
 * 2. Percentiles: nearest-rank on the sorted values, index floor(q * n) clamped to n - 1 (as quantiles()).
 * 3. Registration vs update: a line with `indexWrite=1` (the write also registered the draft key in the
 *    journal index) is "registration", `indexWrite=0` "update". Lines of builds that do not log the field
 *    are "unlabelled": which of them registered a key cannot be told from the line, so it is not guessed.
 * 4. Slow = total costUs >= [slowUs]. For each slow sample the dominant part is the one of ENCODE / INDEX /
 *    PUT holding >= 50% of the total, else "none"; share = sum of a part / sum of totals over slow samples.
 */
export function costSplitReport(lines, { slowUs = 1000, listSlowest = 50 } = {}) {
  const inputs = new Set();
  const seen = new Set();
  const samples = [];
  let unpaired = 0;
  for (const raw of lines) {
    if (!raw.includes("RaftDraftTiming")) continue;
    const l = parseLogcatLine(raw);
    if (!l || l.tag !== "RaftDraftTiming") continue;
    const input = /^change_received seq=(\d+) /.exec(l.message);
    if (input) {
      inputs.add(`${l.pid}:${input[1]}`);
      continue;
    }
    if (!l.message.startsWith("draft_journal_written ")) continue;
    const field = (name) => {
      const m = new RegExp(`\\b${name}=(\\d+)`).exec(l.message);
      return m ? Number(m[1]) : null;
    };
    const key = `${l.pid}:${field("seq")}`;
    if (field("seq") == null || !inputs.has(key) || seen.has(key)) {
      unpaired += 1;
      continue;
    }
    seen.add(key);
    const indexWrite = field("indexWrite");
    samples.push({
      key,
      ts_ms: l.tsMs,
      total: field("costUs"),
      encode: field("encodeUs"),
      index: field("indexUs"),
      put: field("putUs"),
      len: field("len"),
      group: indexWrite == null ? "unlabelled" : indexWrite === 1 ? "registration" : "update",
    });
  }
  const q = (values) => quantiles(values.filter((v) => v != null), "us");
  const parts = (list) => Object.fromEntries(["total", "encode", "index", "put"].map((k) => [k, q(list.map((s) => s[k]))]));
  const groups = {};
  for (const g of ["registration", "update", "unlabelled"]) {
    const list = samples.filter((s) => s.group === g);
    if (list.length) groups[g] = parts(list);
  }
  const slow = samples.filter((s) => s.total >= slowUs).sort((a, b) => b.total - a.total);
  const dominant = { encode: 0, index: 0, put: 0, none: 0 };
  for (const s of slow) {
    const top = ["encode", "index", "put"].map((k) => [k, s[k] ?? 0]).sort((a, b) => b[1] - a[1])[0];
    dominant[top[1] >= s.total * 0.5 ? top[0] : "none"] += 1;
  }
  const sum = (k) => slow.reduce((a, s) => a + (s[k] ?? 0), 0);
  const totalSlow = sum("total");
  return {
    method: "paired by (pid, seq); first line per input; nearest-rank percentiles; slow = costUs >= slowUs; dominant part >= 50% of the total",
    slow_us: slowUs,
    samples: samples.length,
    unpaired,
    all: parts(samples),
    groups,
    slow: {
      count: slow.length,
      share_pct: totalSlow ? { encode: Math.round((100 * sum("encode")) / totalSlow), index: Math.round((100 * sum("index")) / totalSlow), put: Math.round((100 * sum("put")) / totalSlow) } : null,
      dominant,
      slowest: slow.slice(0, listSlowest),
    },
  };
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

/** Per measured bucket: samples whose whole exit Δ interval lies in that bucket, and how many were restored. */
export function summarizeByMeasuredBucket(runs) {
  const rows = new Map();
  for (const r of runs) {
    if (r.measured_bucket == null) continue;
    const g = rows.get(r.measured_bucket) ?? { bucket: r.measured_bucket, samples: 0, restored: 0, written_before_kill: 0 };
    g.samples += 1;
    if (r.pass) g.restored += 1;
    if (r.written_before_kill) g.written_before_kill += 1;
    rows.set(r.measured_bucket, g);
  }
  return [...rows.values()].sort((a, b) => a.bucket - b.bucket);
}

export function markdownTable(runs, summary) {
  const out = [];
  const measured = summarizeByMeasuredBucket(runs);
  out.push(
    "### By measured bucket (the whole exit Δ interval inside the window, whatever D was asked)", "",
    "| bucket | tolerance (whole exit Δ interval) | samples | restored | written before kill |", "|---|---|---|---|---|",
    ...measured.map((g) => `| ${g.bucket} | [${BUCKETS[g.bucket].lo_ms}, ${BUCKETS[g.bucket].hi_ms}] ms | ${g.samples} | ${g.restored} | ${g.written_before_kill} |`),
    "",
  );
  out.push(
    "### Per bucket", "",
    "| group | runs | timing hits | unconfirmed | excluded (exit not by force-stop) | restored as expected | restored when hit | written before kill | exit Δ upper min / median / max (ms) | exit interval width median / max (ms) |",
    "|---|---|---|---|---|---|---|---|---|---|",
  );
  for (const g of summary) {
    out.push(`| ${g.group} | ${g.runs} | ${g.hits} | ${g.unconfirmed} | ${g.excluded} | ${g.restored} | ${g.restored_when_hit} | ${g.written_before_kill} | ${cell(g.delta_upper_min_ms)} / ${cell(g.delta_upper_median_ms)} / ${cell(g.delta_upper_max_ms)} | ${cell(g.exit_width_median_ms)} / ${cell(g.exit_width_max_ms)} |`);
  }
  out.push(
    "", "### Per run", "",
    "| # | group | D (s) | sleep (s) | measured bucket | exit Δ lower (ms) | exit Δ upper (ms) | width (ms) | bounds | timing | written before kill | expected | composer after relaunch | restored as expected | old pid | new pid | pid gone | note |",
    "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|",
  );
  runs.forEach((r, i) => {
    const bounds = r.exit_lower_source ? `${r.exit_lower_source} .. ${r.exit_upper_source ?? "?"}` : "-";
    out.push(`| ${i + 1} | ${r.group} | ${cell(r.delay_sec)} | ${cell(r.sleep_sec)} | ${cell(r.measured_bucket)} | ${cell(r.delta_lower_ms)} | ${cell(r.delta_upper_ms)} | ${cell(r.exit_width_ms)} | ${bounds} | ${r.timing} | ${r.written_before_kill ? "yes" : "no"} | ${cell(r.expected_desc)} | ${cell(r.observed_desc)} | ${r.pass ? "yes" : "no"} | ${cell(r.old_pid)} | ${cell(r.new_pid)} | ${r.pid_gone ? "yes" : "no"} | ${cell([r.sample, r.reason, r.note, r.error, r.foreign_app_deaths?.length ? `other app deaths: ${r.foreign_app_deaths.join("; ")}` : null].filter(Boolean).join("; ") || null)} |`);
  });
  return `${out.join("\n")}\n`;
}
