// Pure helpers for scripts/qa/ios_qa_bootstrap.mjs (the iOS Simulator twin of
// mobile_login_bootstrap.mjs). Contract: docs/qa-acceptance/session-bootstrap.md.
import path from "node:path";
import { canonicalJson, sha256Digest } from "./mobile_login_bootstrap_lib.mjs";

export const IOS_QA_BOOTSTRAP_MARKER = "raft-ios-qa-session-bootstrap/v1";
export const IOS_INBOX_RELATIVE = path.join("Library", "Application Support", "qa-bootstrap", "inbox");
const UDID = /^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$/;
const BUNDLE_ID = /^[A-Za-z0-9][A-Za-z0-9.-]*$/;
const REQUEST_ID = /^[A-Za-z0-9._-]{1,80}$/;
const DIGEST = /^sha256:[a-f0-9]{64}$/;

export function requireSimulatorUdid(udid) {
  if (typeof udid !== "string" || !UDID.test(udid)) throw new Error("Simulator UDID is invalid");
  return udid.toUpperCase();
}

export function requireBundleId(bundleId) {
  if (typeof bundleId !== "string" || !BUNDLE_ID.test(bundleId)) throw new Error("iOS bundle id is invalid");
  return bundleId;
}

const MONTHS = { Jan: 0, Feb: 1, Mar: 2, Apr: 3, May: 4, Jun: 5, Jul: 6, Aug: 7, Sep: 8, Oct: 9, Nov: 10, Dec: 11 };

/**
 * The host PID of the Simulator's launchd_sim for exactly this device, from
 * `ps -axww -o pid=,args=`. launchd_sim is started fresh on every `simctl boot` and its argv carries
 * the device directory (…/CoreSimulator/Devices/<UDID>/data/…), so exactly one line must match.
 */
export function findLaunchdSimPid(psText, udid) {
  const device = `/Devices/${requireSimulatorUdid(udid)}/`;
  const pids = String(psText).split(/\r?\n/).flatMap((line) => {
    const match = line.trim().match(/^([0-9]+)\s+(\S+)(.*)$/);
    if (!match || path.basename(match[2]) !== "launchd_sim" || !line.toUpperCase().includes(device.toUpperCase())) return [];
    return [Number(match[1])];
  });
  if (pids.length !== 1) {
    const seen = String(psText).split(/\r?\n/).filter((line) => line.includes("launchd_sim")).slice(0, 3).map((line) => line.trim().slice(0, 300));
    throw new Error(`expected exactly one launchd_sim for ${udid}, found ${pids.length} (is the Simulator booted?); launchd_sim lines: ${JSON.stringify(seen)}`);
  }
  return pids[0];
}

/**
 * `LC_ALL=C TZ=UTC ps -o lstart= -p <pid>` ("Sat Nov 14 10:00:00 2026") → epoch seconds. ps prints
 * p_starttime.tv_sec, the same field the app reads through sysctl(KERN_PROC_PID).
 */
export function parsePsLstartUtc(text) {
  const match = String(text).trim().match(/^[A-Z][a-z]{2}\s+([A-Z][a-z]{2})\s+([0-9]{1,2})\s+([0-9]{2}):([0-9]{2}):([0-9]{2})\s+([0-9]{4})$/);
  if (!match || !(match[1] in MONTHS)) throw new Error("launchd_sim start time is unavailable");
  const seconds = Date.UTC(Number(match[6]), MONTHS[match[1]], Number(match[2]), Number(match[3]), Number(match[4]), Number(match[5])) / 1000;
  if (!Number.isSafeInteger(seconds) || seconds <= 0) throw new Error("launchd_sim start time is unavailable");
  return seconds;
}

/**
 * Same formula as RaftIosQaSessionBootstrapContract.bootGeneration. The input is the start time
 * (epoch SECONDS) of THIS Simulator's launchd_sim. CoreSimulator starts a fresh launchd_sim on
 * every Simulator boot (so also after an erase, which needs a shutdown), so the value changes
 * between boots UNLESS two boots of the same UDID start launchd_sim within the same UTC second:
 * that collision is bounded by construction (resolution is one second; both sides read
 * p_starttime.tv_sec, and `ps -o lstart` cannot print sub-second time). A shutdown + boot +
 * bootstatus cycle takes far longer than a second, so a collision needs two boots inside one
 * second; it is a documented limit, not a guarantee of "different on every boot".
 * It is not the kernel boot time: Simulator processes run on the host kernel, so kern.boottime
 * read inside the Simulator is the Mac's boot and does not change when the Simulator reboots. It
 * distinguishes Simulator boots of one UDID (to one-second resolution); it says nothing about
 * whether the data was erased in between.
 */
export function iosBootGeneration(udid, launchdSimStartSeconds) {
  if (!Number.isSafeInteger(launchdSimStartSeconds) || launchdSimStartSeconds <= 0) throw new Error("launchd_sim start time is invalid");
  return sha256Digest(Buffer.from(`ios-simulator-boot/v2|${requireSimulatorUdid(udid)}|${launchdSimStartSeconds}`, "utf8"));
}

export function iosInboxPaths(dataContainer, requestId) {
  if (typeof dataContainer !== "string" || !path.isAbsolute(dataContainer)) throw new Error("app data container is invalid");
  if (!REQUEST_ID.test(requestId ?? "")) throw new Error("request id is invalid");
  const dir = path.join(dataContainer, IOS_INBOX_RELATIVE);
  return { dir, file: path.join(dir, `${requestId}.session`), tmp: path.join(dir, `.${requestId}.session.tmp`) };
}

/**
 * Files whose bytes, concatenated in this order, make the app's code identity: the main executable,
 * then `<executable>.debug.dylib` when present (Xcode Debug builds keep the app code there).
 */
export function iosCodeImages(executablePath, exists) {
  const dylib = `${executablePath}.debug.dylib`;
  return exists(dylib) ? [executablePath, dylib] : [executablePath];
}

/** argv for `xcrun simctl launch`: a fresh process carrying only the request id and the digest. */
export function iosLaunchArgs(udid, bundleId, requestId, envelopeDigest) {
  if (!REQUEST_ID.test(requestId ?? "")) throw new Error("request id is invalid");
  if (!DIGEST.test(envelopeDigest ?? "")) throw new Error("envelope digest is invalid");
  return [
    "simctl", "launch", "--terminate-running-process", requireSimulatorUdid(udid), requireBundleId(bundleId),
    "-qaBootstrapRequestId", requestId, "-qaBootstrapEnvelopeDigest", envelopeDigest,
  ];
}

/** Predicate for `xcrun simctl spawn <udid> log stream`: the app's QA bootstrap lines only. */
export const IOS_LOG_PREDICATE = 'eventMessage CONTAINS "SlockQaBootstrap"';

export function hasBootstrapLine(text, kind, stage, code) {
  const suffix = code === undefined ? "(?:\\s|$)" : ` code=${code}(?:\\s|$)`;
  return new RegExp(`QA_BOOTSTRAP_${kind} stage=${stage}${suffix}`).test(String(text));
}

/** `launchctl list` inside the Simulator: is a UIKitApplication:<bundle>[…] job still running (numeric PID)? */
export function launchctlAppAlive(text, bundleId) {
  requireBundleId(bundleId);
  return launchctlRows(text).some((row) => /^[0-9]+$/.test(row.pid) && row.label.startsWith(`UIKitApplication:${bundleId}[`));
}

function launchctlRows(text) {
  return String(text).split(/\r?\n/).flatMap((line) => {
    const columns = line.trim().split(/\s+/);
    return columns.length >= 3 && /^([0-9]+|-)$/.test(columns[0]) ? [{ pid: columns[0], label: columns[2] }] : [];
  });
}

const LAUNCHCTL_HEADER = /^PID\s+Status\s+Label$/;
const LAUNCHCTL_ROW = /^([0-9]+|-)\t(-?[0-9]+|-)\t\S.*$/;

/**
 * A `launchctl list` listing is only used when it is structurally whole: it ends with a newline
 * (a read cut mid-line is incomplete) and every non-empty line is the header or a
 * `<pid|->\t<status>\t<label>` row. A cut exactly at a line boundary cannot be detected from the
 * text, which is why absence also needs the independent host process query (classifyHostProcessRead).
 */
function launchctlListingWhole(text) {
  const value = String(text);
  if (!value.endsWith("\n")) return false;
  const lines = value.split("\n").slice(0, -1).filter((line) => line.length > 0);
  return lines.length > 0 && lines.every((line, index) => (index === 0 && LAUNCHCTL_HEADER.test(line.trim())) || LAUNCHCTL_ROW.test(line));
}

/**
 * Services that run in every booted Simulator. A `launchctl list` read only counts as evidence of
 * absence when it also shows one of these running (positive control): an empty or truncated listing
 * from a failed spawn must never read as "the app is gone". The control proves the listing covered
 * at least that service, not that it was complete; see launchctlListingWhole and
 * classifyHostProcessRead for the rest of the evidence.
 */
export const IOS_LAUNCHCTL_POSITIVE_CONTROLS = ["com.apple.SpringBoard", "com.apple.backboardd"];

/**
 * Classifies one `xcrun simctl spawn <udid> launchctl list` result for the app process:
 *   present — a running UIKitApplication:<bundle>[…] job is listed;
 *   absent  — the command succeeded, the listing is structurally whole, a positive-control service
 *             is listed running, and the app is not;
 *   unknown — command failure, timeout, spawn error, a truncated/malformed listing, or a listing
 *             without the positive control.
 */
export function classifyLaunchctlRead(result, bundleId) {
  requireBundleId(bundleId);
  if (result?.error || result?.timedOut) return { state: "unknown", reason: result.timedOut ? "timeout" : "spawn_error" };
  if (result?.status !== 0) return { state: "unknown", reason: `exit_${result?.status ?? "none"}` };
  if (launchctlAppAlive(result.stdout, bundleId)) return { state: "present", reason: "app_job_running" };
  if (!launchctlListingWhole(result.stdout)) return { state: "unknown", reason: "incomplete_listing" };
  const control = launchctlRows(result.stdout).find((row) => /^[0-9]+$/.test(row.pid) && IOS_LAUNCHCTL_POSITIVE_CONTROLS.includes(row.label));
  if (!control) return { state: "unknown", reason: "no_positive_control" };
  return { state: "absent", reason: "app_job_not_listed", positive_control: control.label };
}

const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/**
 * Independent host view of the app process: Simulator apps are host processes whose executable
 * lives under …/CoreSimulator/Devices/<UDID>/data/Containers/Bundle/Application/<id>/<App>.app/.
 * Classifies one `ps -axww -o pid=,args=` result:
 *   present — a process runs <App>.app/<executable> from this Simulator's bundle containers;
 *   absent  — ps succeeded, its listing ends with a newline, this Simulator's launchd_sim is listed
 *             exactly once (positive control: the listing covers this device's processes), and no
 *             app process is listed;
 *   unknown — ps failure/timeout, a cut listing, no (or an ambiguous) launchd_sim, or no executable
 *             name to look for.
 */
export function classifyHostProcessRead(result, udid, executableName) {
  const device = requireSimulatorUdid(udid);
  if (result?.error || result?.timedOut) return { state: "unknown", reason: result.timedOut ? "timeout" : "spawn_error" };
  if (result?.status !== 0) return { state: "unknown", reason: `exit_${result?.status ?? "none"}` };
  if (typeof executableName !== "string" || !/^[^/\0]+$/.test(executableName)) return { state: "unknown", reason: "executable_unknown" };
  const text = String(result.stdout);
  const app = new RegExp(`/Devices/${device}/data/Containers/Bundle/Application/[^/]+/[^/]+\\.app/${escapeRegExp(executableName)}(?:\\s|$)`, "i");
  if (text.split(/\r?\n/).some((line) => /^\s*[0-9]+\s/.test(line) && app.test(line))) return { state: "present", reason: "app_process_running" };
  if (!text.endsWith("\n")) return { state: "unknown", reason: "incomplete_listing" };
  try {
    findLaunchdSimPid(text, device);
  } catch {
    return { state: "unknown", reason: "no_positive_control" };
  }
  return { state: "absent", reason: "app_process_not_listed", positive_control: "launchd_sim" };
}

/** Both independent process reads must agree on absence; any present wins; otherwise unknown. */
export function combineProcessStates(launchctl, host) {
  if (launchctl?.state === "present" || host?.state === "present") return "present";
  if (launchctl?.state === "absent" && host?.state === "absent") return "absent";
  return "unknown";
}

const containerErrorSignature = (stderr, bundleId) => String(stderr).split(bundleId).join("<bundle>").replace(/\s+/g, " ").trim();

/**
 * Error texts of Simulator/CoreSimulator infrastructure failures. Such an error says nothing about
 * whether the app is installed, even when the control read fails with exactly the same text (a
 * shut-down device, a dead CoreSimulatorService or a timed-out XPC call fails every lookup alike).
 */
const INFRASTRUCTURE_ERROR = /current state|shutdown|shutting down|booting|invalid device|no devices|unable to (?:lookup|locate|connect)|timed? ?out|timeout|interrupted|connection|xpc|coresimulatorservice|service is unavailable|not responding|permission denied|operation not permitted|killed|crash/i;

/**
 * Parses `xcrun simctl listapps <udid>` (an old-style plist dictionary keyed by bundle id) into the
 * set of CFBundleIdentifier values, or null when the text is not ONE structurally complete
 * dictionary (braces/parentheses balanced outside quoted strings, nothing after the closing brace):
 * a cut read must never read as "not installed".
 */
export function parseSimctlListApps(text) {
  const value = String(text).trim();
  if (!value.startsWith("{") || !value.endsWith("}")) return null;
  let depth = 0;
  let quoted = false;
  for (let index = 0; index < value.length; index += 1) {
    const char = value[index];
    if (quoted) {
      if (char === "\\") index += 1;
      else if (char === "\"") quoted = false;
      continue;
    }
    if (char === "\"") quoted = true;
    else if (char === "{" || char === "(") depth += 1;
    else if (char === "}" || char === ")") {
      depth -= 1;
      if (depth < 0) return null;
      if (depth === 0 && index !== value.length - 1) return null;
    }
  }
  if (quoted || depth !== 0) return null;
  return new Set([...value.matchAll(/CFBundleIdentifier\s*=\s*"?([A-Za-z0-9][A-Za-z0-9.-]*)"?\s*;/g)].map((match) => match[1]));
}

/** System apps present on every iOS Simulator runtime: the listapps positive control. */
export const IOS_LISTAPPS_POSITIVE_CONTROLS = ["com.apple.Preferences", "com.apple.mobilesafari"];

/**
 * `xcrun simctl list devices -j` read at the moment of the container check: the device must be
 * listed exactly once and Booted, from a successful, parseable read.
 */
export function classifyDeviceBooted(result, udid) {
  const wanted = requireSimulatorUdid(udid);
  if (result?.error || result?.timedOut) return { booted: false, reason: result.timedOut ? "list_timeout" : "list_spawn_error" };
  if (result?.status !== 0) return { booted: false, reason: `list_exit_${result?.status ?? "none"}` };
  let devices;
  try {
    devices = Object.values(JSON.parse(String(result.stdout)).devices ?? {}).flat();
  } catch {
    return { booted: false, reason: "list_unparseable" };
  }
  const matches = devices.filter((d) => String(d?.udid ?? "").toUpperCase() === wanted);
  if (matches.length !== 1) return { booted: false, reason: `device_listed_${matches.length}` };
  if (matches[0].state !== "Booted") return { booted: false, reason: `device_${String(matches[0].state ?? "unknown").toLowerCase()}` };
  return { booted: true, reason: "booted" };
}

/**
 * Classifies the data container after uninstall. Inputs (all read at the same moment, after uninstall):
 *   result   — `xcrun simctl get_app_container <udid> <bundle> data`;
 *   control  — the same command for a bundle id that was never installed;
 *   listApps — `xcrun simctl listapps <udid>`;
 *   device   — `xcrun simctl list devices -j`.
 *   present — the app read exits 0 with an absolute path, or listapps lists the bundle;
 *   absent  — ONLY when the not-installed condition is validated twice over and independently
 *             confirmed: the app read and the never-installed control fail with the same exit status
 *             and the same non-empty error text (bundle id masked) that is not an infrastructure
 *             error, AND the device is listed Booted, AND a structurally complete listapps read
 *             lists a system positive-control app and not the bundle;
 *   unknown — everything else: timeouts, spawn errors, identical generic/infrastructure errors,
 *             a non-booted device, a failed/cut listapps, a listing without the positive control.
 */
export function classifyAppContainerRead(result, bundleId, control, controlBundleId, { listApps, device, udid } = {}) {
  requireBundleId(bundleId);
  requireBundleId(controlBundleId);
  if (result?.error || result?.timedOut) return { state: "unknown", reason: result.timedOut ? "timeout" : "spawn_error" };
  if (result?.status === 0) {
    return path.isAbsolute(String(result.stdout).trim()) ? { state: "present", reason: "container_path" } : { state: "unknown", reason: "exit_0_without_path" };
  }
  const apps = listApps && !listApps.error && !listApps.timedOut && listApps.status === 0 ? parseSimctlListApps(listApps.stdout) : null;
  if (apps?.has(bundleId)) return { state: "present", reason: "listed_by_listapps" };
  if (!control || control.error || control.timedOut || control.status === 0 || typeof control.status !== "number") {
    return { state: "unknown", reason: "control_not_refused" };
  }
  const expected = containerErrorSignature(control.stderr, controlBundleId);
  if (!expected) return { state: "unknown", reason: "control_without_error" };
  if (result.status !== control.status || containerErrorSignature(result.stderr, bundleId) !== expected) {
    return { state: "unknown", reason: `unexpected_error_exit_${result.status}` };
  }
  if (INFRASTRUCTURE_ERROR.test(expected)) return { state: "unknown", reason: "infrastructure_error" };
  if (!device || !udid) return { state: "unknown", reason: "device_unread" };
  const booted = classifyDeviceBooted(device, udid);
  if (!booted.booted) return { state: "unknown", reason: booted.reason };
  if (!apps) return { state: "unknown", reason: "listapps_unavailable" };
  const positive = IOS_LISTAPPS_POSITIVE_CONTROLS.find((id) => apps.has(id));
  if (!positive) return { state: "unknown", reason: "listapps_no_positive_control" };
  return { state: "absent", reason: "not_installed_confirmed", positive_control: positive };
}

/** clean only when the process was confirmed gone, uninstall succeeded and the container is confirmed absent. */
export function iosCleanupReceipt({ processState, uninstallStatus, containerState }) {
  if (processState === "present" || containerState === "present") return "failed";
  if (processState === "absent" && uninstallStatus === 0 && containerState === "absent") return "clean";
  return "unknown";
}

/** Run-owned Simulators are created per workflow run as `raft-qa-run-<run id>-<attempt>`. */
export const RUN_OWNED_SIMULATOR = /^raft-qa-run-[0-9]+-[0-9]+$/;
export const SIMULATOR_OWNERSHIP_CONTRACT = "raft-qa-simulator-ownership/v1";

/** The name this job's run/attempt must use; refuses a missing or non-numeric run id/attempt. */
export function runOwnedSimulatorName(runId, runAttempt) {
  if (!/^[0-9]+$/.test(runId ?? "") || !/^[0-9]+$/.test(runAttempt ?? "")) {
    throw new Error("GITHUB_RUN_ID/GITHUB_RUN_ATTEMPT are required to establish simulator ownership");
  }
  return `raft-qa-run-${runId}-${runAttempt}`;
}

/** The ownership receipt the job writes right after `simctl create` (see recordSimulatorOwnership). */
export function simulatorOwnershipReceipt({ udid, name, runId, runAttempt, createdAt }) {
  const expectedName = runOwnedSimulatorName(runId, runAttempt);
  if (name !== expectedName) throw new Error(`simulator name ${name} is not this job's ${expectedName}`);
  return {
    contract: SIMULATOR_OWNERSHIP_CONTRACT,
    udid: requireSimulatorUdid(udid),
    name,
    github_run_id: runId,
    github_run_attempt: runAttempt,
    created_at: createdAt,
  };
}

/**
 * Ownership check, run immediately before every destructive Simulator action (terminate, uninstall,
 * shutdown). All of these must hold, otherwise it throws:
 *   - the job's ownership receipt (written when this job created the device) is a protected file —
 *     a regular file (not a symlink), owned by this uid, no group/other permissions — with the
 *     exact contract and key set;
 *   - the receipt's run id/attempt are THIS job's GITHUB_RUN_ID/GITHUB_RUN_ATTEMPT and its name is
 *     raft-qa-run-<run id>-<attempt>;
 *   - the receipt's UDID and name equal the caller's --udid/--simulator-name;
 *   - `simctl list devices -j` lists that UDID exactly once, under that name, and no other device
 *     carries that name (ambiguous ownership is refused).
 * `receipt` is { text, stat: { isFile, isSymbolicLink, mode, uid } } or null when the file is missing.
 */
export function requireRunOwnedSimulator(listJson, udid, expectedName, { receipt, runId, runAttempt, uid } = {}) {
  const wanted = requireSimulatorUdid(udid);
  if (!RUN_OWNED_SIMULATOR.test(expectedName ?? "")) throw new Error(`simulator name ${expectedName} is not a run-owned name`);
  const jobName = runOwnedSimulatorName(runId, runAttempt);
  if (expectedName !== jobName) throw new Error(`simulator name ${expectedName} is not this job's run-owned ${jobName}`);
  if (!receipt) throw new Error("simulator ownership receipt is missing; this job did not record creating the simulator");
  const { stat } = receipt;
  if (!stat?.isFile || stat.isSymbolicLink) throw new Error("simulator ownership receipt is not a regular file");
  if ((stat.mode & 0o077) !== 0) throw new Error(`simulator ownership receipt is not protected (mode ${(stat.mode & 0o777).toString(8)})`);
  if (uid !== undefined && stat.uid !== uid) throw new Error("simulator ownership receipt is owned by another user");
  let record;
  try {
    record = JSON.parse(String(receipt.text));
  } catch {
    throw new Error("simulator ownership receipt is unreadable");
  }
  const keys = ["contract", "created_at", "github_run_attempt", "github_run_id", "name", "udid"];
  if (!record || typeof record !== "object" || Object.keys(record).sort().join(",") !== keys.join(",") || record.contract !== SIMULATOR_OWNERSHIP_CONTRACT) {
    throw new Error("simulator ownership receipt has an unexpected shape");
  }
  if (record.github_run_id !== runId || record.github_run_attempt !== runAttempt || record.name !== jobName) {
    throw new Error(`simulator ownership receipt belongs to run ${record.github_run_id}/${record.github_run_attempt}, not this job's ${runId}/${runAttempt}`);
  }
  if (String(record.udid).toUpperCase() !== wanted || record.name !== expectedName) {
    throw new Error(`simulator ownership receipt records ${record.name} ${record.udid}, not ${expectedName} ${wanted}`);
  }
  const devices = Object.values(JSON.parse(String(listJson)).devices ?? {}).flat();
  const byUdid = devices.filter((d) => String(d?.udid ?? "").toUpperCase() === wanted);
  if (byUdid.length === 0) throw new Error(`simulator ${wanted} is not listed`);
  if (byUdid.length > 1) throw new Error(`simulator ${wanted} is listed ${byUdid.length} times; ownership is ambiguous`);
  const device = byUdid[0];
  if (device.name !== expectedName) throw new Error(`simulator ${wanted} is named ${device.name}, not the run-owned ${expectedName}`);
  const byName = devices.filter((d) => d?.name === expectedName);
  if (byName.length !== 1) throw new Error(`${byName.length} simulators are named ${expectedName}; ownership is ambiguous`);
  return device;
}

/**
 * A structurally valid, correctly bound qa-bootstrap-login/v1 envelope whose tokens are fake: the app
 * accepts it through inbox, digest, shape and binding, then the server rejects the token. Smoke only.
 */
export function buildSmokeLogin({ applicationId, signingCertSha256, bootGeneration, runCaseId, ciJobRef, requestId, edit }) {
  const envelope = {
    version: "qa-bootstrap-login/v1",
    run_case_id: runCaseId,
    attempt: 1,
    ci_job_ref: ciJobRef,
    principal_id: "qa-smoke-user",
    server_id: "qa-smoke-server",
    target_route: "compose_slock_app_shell",
    application_id: applicationId,
    signing_cert_sha256: signingCertSha256,
    boot_generation: bootGeneration,
    user: { id: "qa-smoke-user", email: "qa-smoke@example.invalid" },
    access_token: "qa-smoke-invalid-access-token",
    refresh_token: "qa-smoke-invalid-refresh-token",
  };
  if (edit) edit(envelope);
  const bytes = Buffer.from(canonicalJson(envelope), "utf8");
  return { request_id: requestId, envelope_digest: sha256Digest(bytes), envelope, bytes };
}
