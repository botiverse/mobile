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
 * (epoch seconds) of THIS Simulator's launchd_sim, which changes on every Simulator boot (so also
 * after an erase, which needs a shutdown). It is not the kernel boot time: Simulator processes run
 * on the host kernel, so kern.boottime read inside the Simulator is the Mac's boot and does not
 * change when the Simulator reboots. It distinguishes Simulator boots of one UDID; it says nothing
 * about whether the data was erased in between.
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

/**
 * Services that run in every booted Simulator. A `launchctl list` read only counts as evidence of
 * absence when it also shows one of these running (positive control): an empty or truncated listing
 * from a failed spawn must never read as "the app is gone".
 */
export const IOS_LAUNCHCTL_POSITIVE_CONTROLS = ["com.apple.SpringBoard", "com.apple.backboardd"];

/**
 * Classifies one `xcrun simctl spawn <udid> launchctl list` result for the app process:
 *   present — a running UIKitApplication:<bundle>[…] job is listed;
 *   absent  — the command succeeded AND a positive-control service is listed running, and the app is not;
 *   unknown — command failure, timeout, spawn error, or a listing without the positive control.
 */
export function classifyLaunchctlRead(result, bundleId) {
  requireBundleId(bundleId);
  if (result?.error || result?.timedOut) return { state: "unknown", reason: result.timedOut ? "timeout" : "spawn_error" };
  if (result?.status !== 0) return { state: "unknown", reason: `exit_${result?.status ?? "none"}` };
  if (launchctlAppAlive(result.stdout, bundleId)) return { state: "present", reason: "app_job_running" };
  const control = launchctlRows(result.stdout).find((row) => /^[0-9]+$/.test(row.pid) && IOS_LAUNCHCTL_POSITIVE_CONTROLS.includes(row.label));
  if (!control) return { state: "unknown", reason: "no_positive_control" };
  return { state: "absent", reason: "app_job_not_listed", positive_control: control.label };
}

const containerErrorSignature = (stderr, bundleId) => String(stderr).split(bundleId).join("<bundle>").replace(/\s+/g, " ").trim();

/**
 * Classifies `xcrun simctl get_app_container <udid> <bundle> data` after uninstall, against a control
 * read of a bundle id that was never installed on this Simulator (same command, same moment):
 *   present — exit 0 with an absolute path;
 *   absent  — the control failed with a non-empty error, and the app read failed with the SAME exit
 *             status and the SAME error text (bundle id masked): the Simulator's own not-installed answer;
 *   unknown — timeout, spawn error, a control that did not fail as not-installed, or any other error.
 */
export function classifyAppContainerRead(result, bundleId, control, controlBundleId) {
  requireBundleId(bundleId);
  requireBundleId(controlBundleId);
  if (result?.error || result?.timedOut) return { state: "unknown", reason: result.timedOut ? "timeout" : "spawn_error" };
  if (result?.status === 0) {
    return path.isAbsolute(String(result.stdout).trim()) ? { state: "present", reason: "container_path" } : { state: "unknown", reason: "exit_0_without_path" };
  }
  if (!control || control.error || control.timedOut || control.status === 0 || typeof control.status !== "number") {
    return { state: "unknown", reason: "control_not_refused" };
  }
  const expected = containerErrorSignature(control.stderr, controlBundleId);
  if (!expected) return { state: "unknown", reason: "control_without_error" };
  if (result.status !== control.status || containerErrorSignature(result.stderr, bundleId) !== expected) {
    return { state: "unknown", reason: `unexpected_error_exit_${result.status}` };
  }
  return { state: "absent", reason: "not_installed_matches_control" };
}

/** clean only when the process was confirmed gone, uninstall succeeded and the container is confirmed absent. */
export function iosCleanupReceipt({ processState, uninstallStatus, containerState }) {
  if (processState === "present" || containerState === "present") return "failed";
  if (processState === "absent" && uninstallStatus === 0 && containerState === "absent") return "clean";
  return "unknown";
}

/** Run-owned Simulators are created per workflow run as `raft-qa-run-<run id>-<attempt>`. */
export const RUN_OWNED_SIMULATOR = /^raft-qa-run-[0-9]+-[0-9]+$/;

/**
 * Ownership check from `xcrun simctl list devices -j`: the UDID must exist under exactly the
 * run-owned name this run created. Anything else (a shared or reused device) is refused.
 */
export function requireRunOwnedSimulator(listJson, udid, expectedName) {
  const wanted = requireSimulatorUdid(udid);
  if (!RUN_OWNED_SIMULATOR.test(expectedName ?? "")) throw new Error(`simulator name ${expectedName} is not a run-owned name`);
  const devices = Object.values(JSON.parse(String(listJson)).devices ?? {}).flat();
  const device = devices.find((d) => String(d?.udid ?? "").toUpperCase() === wanted);
  if (!device) throw new Error(`simulator ${wanted} is not listed`);
  if (device.name !== expectedName) throw new Error(`simulator ${wanted} is named ${device.name}, not the run-owned ${expectedName}`);
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
