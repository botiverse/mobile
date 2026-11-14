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

/** `sysctl -n kern.boottime` → seconds, e.g. "{ sec = 1731500000, usec = 12345 } Wed Nov 13 …". */
export function parseKernBootTime(text) {
  const match = String(text).match(/\bsec\s*=\s*([0-9]+)/);
  const seconds = match ? Number(match[1]) : NaN;
  if (!Number.isSafeInteger(seconds) || seconds <= 0) throw new Error("host kern.boottime is unavailable");
  return seconds;
}

/** Same formula as RaftIosQaSessionBootstrapContract.bootGeneration (the app reads kern.boottime too). */
export function iosBootGeneration(udid, bootTimeSeconds) {
  if (!Number.isSafeInteger(bootTimeSeconds) || bootTimeSeconds <= 0) throw new Error("boot time is invalid");
  return sha256Digest(Buffer.from(`ios-simulator-boot/v1|${requireSimulatorUdid(udid)}|${bootTimeSeconds}`, "utf8"));
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
  return String(text).split(/\r?\n/).some((line) => {
    const columns = line.trim().split(/\s+/);
    return columns.length >= 3 && /^[0-9]+$/.test(columns[0]) && columns[2].startsWith(`UIKitApplication:${bundleId}[`);
  });
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
