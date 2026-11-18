// Runner side of the Android app identity receipt (`raft-app-identity-receipt/v1`,
// AppIdentityReceiptProvider). The provider ships DISABLED in every build; the runner:
//
//   1. probes the installed package for the provider BEFORE any account is used. A build without
//      it (every 1.26.x) cannot give a receipt: the run fails with "this build does not support the
//      identity receipt", no fallback;
//   2. `pm enable`s it BEFORE sign-in (changing a component's state kills the app process, so it
//      must not happen after the app is signed in and on screen);
//   3. after the normal sign-in, asks it once with `content call` (Testbed's attempt id + nonce and
//      a fresh request id) and cross-checks what the app says against what the OS says:
//      package + versionCode + debuggable from `dumpsys package`, the signer from the installed APK
//      (`pm path` + apksigner, the certificate for the device's SDK: a rotated lineage lists one
//      per SDK range), the pid before and after;
//   4. `pm disable`s it again in cleanup. A disable that fails or cannot be confirmed is reported
//      loudly (::error:: + non-zero exit), never ignored.
//
// user_ref / server_ref are verification digests salted with the attempt nonce (same vector as
// Testbed): they confirm the expected account and server without the receipt carrying either.
// With Testbed's optional pairing salt the receipt also has user_pair_ref / server_pair_ref (same
// formulas, salted with it) for cross-run comparison. The nonce, the salt and all four refs are
// private to Testbed: the request reaches the device over adb's stdin (never argv), and the
// nonce + refs go only to the Testbed-private output file (0600). stdout, the summary and the
// evidence carry only verification STATUS (user_check / server_check / pair_check).
// What the app reports is not proof on its own; the OS-side cross-check is.
import crypto from "node:crypto";
import fs from "node:fs";
import { spawnSync } from "node:child_process";

export const RECEIPT_CONTRACT = "raft-app-identity-receipt/v1";
export const PROVIDER_CLASS = "build.raft.app.core.data.auth.AppIdentityReceiptProvider";
export const UNSUPPORTED_MESSAGE = "this build does not support the identity receipt";
const USER_REF_DOMAIN = "raft-qa-readiness-user/v1";
const SERVER_REF_DOMAIN = "raft-qa-readiness-server/v1";
const MAX_RECEIPT_BYTES = 1024;
const RECEIPT_KEYS = [
  "contract", "attempt_id", "nonce", "request_id", "package", "version_name", "version_code",
  "signer_sha256", "debuggable", "pid", "process_start_ticks", "signed_in", "session_origin",
  "user_ref", "server_ref",
];
const PAIR_KEYS = ["user_pair_ref", "server_pair_ref"];
const ATTEMPT_ID = /^pla_[0-9a-f]{32}$/;
const NONCE = /^[0-9a-f]{64}$/;
const REQUEST_ID = /^[0-9a-f]{32}$/;
const HEX64 = /^[0-9a-f]{64}$/;
const PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;

const sha256Hex = (text) => crypto.createHash("sha256").update(text, "utf8").digest("hex");
export const userRef = (nonce, userId) => sha256Hex(`${USER_REF_DOMAIN}\u0000${nonce}\u0000${userId}`);
export const serverRef = (nonce, serverId, slug) => sha256Hex(`${SERVER_REF_DOMAIN}\u0000${nonce}\u0000${serverId}\u0000${slug ?? ""}`);

export class UnsupportedBuildError extends Error {}

function requirePackage(pkg) {
  if (!PACKAGE.test(String(pkg ?? ""))) throw new Error(`invalid package ${pkg}`);
  return pkg;
}
export const componentFor = (pkg) => `${requirePackage(pkg)}/${PROVIDER_CLASS}`;
export const authorityFor = (pkg) => `${requirePackage(pkg)}.identity-receipt`;

/** Testbed's attempt id + nonce (format-checked) and a fresh request id for this one call. */
export function newReceiptRequest({ attemptId, nonce, pairingSalt = null, requestId = crypto.randomBytes(16).toString("hex") }) {
  if (!ATTEMPT_ID.test(String(attemptId ?? ""))) throw new Error("identity receipt: attempt id must match pla_<32 hex>");
  if (!NONCE.test(String(nonce ?? ""))) throw new Error("identity receipt: nonce must be 64 lowercase hex");
  if (!REQUEST_ID.test(requestId)) throw new Error("identity receipt: request id must be 32 lowercase hex");
  // Never quote the salt in an error.
  if (pairingSalt != null && (!NONCE.test(String(pairingSalt)) || pairingSalt === nonce)) throw new Error("identity receipt: pairing salt must be 64 lowercase hex and differ from the nonce");
  return pairingSalt != null ? { attemptId, nonce, requestId, pairingSalt } : { attemptId, nonce, requestId };
}

/** Reads the pairing salt from a private file (never from argv or a logged env value). */
export function readPairingSaltFile(file) {
  if (!file) return null;
  return fs.readFileSync(file, "utf8").trim();
}

export function contentCallCommand(pkg, request) {
  return [
    "content", "call", "--uri", `content://${authorityFor(pkg)}`, "--method", "receipt_v1",
    "--extra", `attempt_id:s:${request.attemptId}`,
    "--extra", `nonce:s:${request.nonce}`,
    "--extra", `request_id:s:${request.requestId}`,
    ...(request.pairingSalt ? ["--extra", `pairing_salt:s:${request.pairingSalt}`] : []),
  ].join(" ");
}

/** The provider is in the installed package (manifest-disabled providers are still listed). */
export function providerDeclared(dumpsysPackage, pkg) {
  const text = String(dumpsysPackage ?? "");
  return text.includes(`[${authorityFor(pkg)}]`) || text.includes(PROVIDER_CLASS) || text.includes(`${pkg}/.core.data.auth.AppIdentityReceiptProvider`);
}

/** `content call` prints `Result: Bundle[{receipt=<json>}]` or `Result: Bundle[{error=<code>}]`. */
export function parseContentCallOutput(output) {
  const text = String(output ?? "");
  const receipt = /Result: Bundle\[\{receipt=(\{.*\})\}\]/s.exec(text);
  if (receipt) return { receipt: receipt[1] };
  const error = /Result: Bundle\[\{error=([a-z_]+)\}\]/.exec(text);
  if (error) return { error: error[1] };
  return { error: "no_answer", detail: text.trim().split("\n")[0]?.slice(0, 200) ?? "" };
}

/** Parses and shape-checks a receipt; throws on anything outside the v1 contract. */
export function parseReceipt(json, { paired = false } = {}) {
  if (Buffer.byteLength(json, "utf8") > MAX_RECEIPT_BYTES) throw new Error("receipt is over 1024 bytes");
  const receipt = JSON.parse(json);
  const keys = Object.keys(receipt);
  const expectedKeys = paired ? [...RECEIPT_KEYS, ...PAIR_KEYS] : RECEIPT_KEYS;
  if (keys.join(",") !== expectedKeys.join(",")) throw new Error(`receipt keys are not the v1 set: ${keys.join(",")}`);
  for (const [key, value] of Object.entries(receipt)) {
    if (value !== null && !["string", "number", "boolean"].includes(typeof value)) throw new Error(`receipt ${key} is not flat`);
  }
  if (receipt.contract !== RECEIPT_CONTRACT) throw new Error(`receipt contract is ${receipt.contract}`);
  return receipt;
}

/**
 * The runner's verdict. "unknown" (with `problems`) when any check fails: the run must fail.
 * "ok" when everything holds including Testbed's frozen refs; "pending_testbed" when everything the
 * runner can check holds but no frozen refs were supplied (Testbed judges the refs itself).
 * `os` is what the runner read from the device, never from the app.
 */
export function crossCheckReceipt({ receipt, request, os, expected = {} }) {
  const problems = [];
  const pending = [];
  const check = (ok, problem) => { if (!ok) problems.push(problem); };
  check(receipt.attempt_id === request.attemptId && receipt.nonce === request.nonce && receipt.request_id === request.requestId, "receipt does not echo this request");
  check(receipt.package === os.packageName, "package differs from the installed package");
  check(String(receipt.version_code) === String(os.versionCode), "version_code differs from dumpsys package");
  check(typeof receipt.signer_sha256 === "string" && HEX64.test(receipt.signer_sha256), "receipt has no single signer");
  check(receipt.signer_sha256 === os.signerSha256, "signer_sha256 differs from apksigner (device SDK certificate)");
  check(receipt.debuggable === false && os.debuggable === false, "build is debuggable");
  check(os.pidBefore != null && String(receipt.pid) === String(os.pidBefore) && String(os.pidBefore) === String(os.pidAfter), "pid changed around the call");
  check(os.startTicksBefore != null && receipt.process_start_ticks === os.startTicksBefore && os.startTicksBefore === os.startTicksAfter, "process start time changed around the call");
  check(receipt.signed_in === true, "app is not signed in");
  check(receipt.session_origin === "app_login", `session origin is ${receipt.session_origin}`);
  check(HEX64.test(receipt.user_ref ?? "") && HEX64.test(receipt.server_ref ?? ""), "receipt has no user_ref/server_ref (no selected server, or it has no slug)");
  if (request.pairingSalt) {
    check(HEX64.test(receipt.user_pair_ref ?? "") && HEX64.test(receipt.server_pair_ref ?? ""), "receipt has no user_pair_ref/server_pair_ref");
  }
  if (expected.userRef || expected.serverRef) {
    check(receipt.user_ref === expected.userRef, "user_ref differs from the frozen value");
    check(receipt.server_ref === expected.serverRef, "server_ref differs from the frozen value");
  } else {
    pending.push("frozen user_ref/server_ref not supplied: Testbed compares them");
  }
  const verdict = problems.length ? "unknown" : pending.length ? "pending_testbed" : "ok";
  return { verdict, problems, pending };
}

/** Receipt fields that are Testbed's comparison baseline: private output only. */
export const PRIVATE_RECEIPT_KEYS = ["nonce", "user_ref", "server_ref", "user_pair_ref", "server_pair_ref"];

/**
 * Splits a receipt into what may go to the summary/evidence (no nonce, no ref) and the
 * Testbed-private part (nonce + every ref present, bound to this attempt and request).
 */
export function splitPrivateReceipt(receipt) {
  const shareable = {};
  const secret = { attempt_id: receipt.attempt_id, request_id: receipt.request_id };
  for (const [key, value] of Object.entries(receipt)) {
    if (PRIVATE_RECEIPT_KEYS.includes(key)) secret[key] = value;
    else shareable[key] = value;
  }
  return { shareable, private: secret };
}

/**
 * The public verdict per digest, without the digest: pass/fail against Testbed's frozen value when
 * the runner has it, "pending" (Testbed compares) when it does not, "fail" when the app gave none.
 */
export function verificationStatus({ receipt, request, expected = {} }) {
  const judge = (value, frozen) => (!HEX64.test(value ?? "") ? "fail" : !frozen ? "pending" : value === frozen ? "pass" : "fail");
  const checks = { user_check: judge(receipt.user_ref, expected.userRef), server_check: judge(receipt.server_ref, expected.serverRef) };
  if (request.pairingSalt) {
    checks.pair_check = HEX64.test(receipt.user_pair_ref ?? "") && HEX64.test(receipt.server_pair_ref ?? "") ? "pending" : "fail";
  }
  return checks;
}

/**
 * Capture gate for --identity-receipt runs: no screenshot, UI tree, recording or scenario until
 * Testbed has answered "matched" for this attempt. Runs without the receipt start open.
 */
export function createCaptureGate({ identityMode }) {
  let open = !identityMode;
  return {
    allows: () => open,
    open: () => { open = true; },
  };
}

/** Wraps a capture function so it does nothing (and returns false) while the gate is closed. */
export function guardedCapture(gate, capture) {
  return (...args) => {
    if (!gate.allows()) return false;
    capture(...args);
    return true;
  };
}

export const TESTBED_DECISION_STATES = ["matched", "awaiting_confirmation", "identity_mismatch", "unknown"];

/**
 * Testbed's private decision for this job: `{ state, attempt_id, request_id }`. It counts only when
 * both ids are this attempt's app request; anything else (other attempt/request, unknown state, no
 * reply, a transport failure) is "unknown". Only "matched" lets the run continue. The public
 * summary may carry only the state.
 */
export function parseTestbedDecision(reply, request) {
  if (!reply || typeof reply !== "object") return "unknown";
  if (reply.attempt_id !== request.attemptId || reply.request_id !== request.requestId) return "unknown";
  return TESTBED_DECISION_STATES.includes(reply.state) ? reply.state : "unknown";
}

/**
 * After sign-in in --identity-receipt mode: ask the provider, cross-check with the OS, write the
 * private part for Testbed, publish only statuses, then ask Testbed for its decision
 * (`requestTestbedDecision(request)` -> the job's private reply). Only a "matched" reply bound to
 * this attempt and request opens the capture gate; every other outcome (unclear/missing receipt,
 * runner-side mismatch, awaiting_confirmation, identity_mismatch, unknown, ids of another request)
 * returns `{ matched: false }` with nothing captured.
 */
export async function runIdentityGate({ runner, request, readOsFacts, expected = {}, extraProblems = () => [], writePrivate, publish, requestTestbedDecision, gate }) {
  const answer = runner.call(request);
  if (answer.error) {
    publish({ verdict: "unknown", problems: [`provider answered ${answer.error}${answer.detail ? `: ${answer.detail}` : ""}`] });
    return { matched: false, reason: "receipt_unavailable" };
  }
  const { shareable, private: secret } = splitPrivateReceipt(answer.receipt);
  writePrivate(secret);
  const facts = readOsFacts();
  const os = { ...facts, ...answer.os };
  const { verdict, problems, pending } = crossCheckReceipt({ receipt: answer.receipt, request, os, expected });
  problems.push(...extraProblems(facts));
  const checks = verificationStatus({ receipt: answer.receipt, request, expected });
  const runnerVerdict = problems.length ? "unknown" : verdict;
  publish({ verdict: runnerVerdict, problems, pending, checks, receipt: shareable, os });
  if (runnerVerdict === "unknown") return { matched: false, reason: "mismatch", problems };
  let state = "unknown";
  try {
    state = parseTestbedDecision(await requestTestbedDecision(request), request);
  } catch {
    state = "unknown";
  }
  publish({ verdict: runnerVerdict, problems, pending, checks, receipt: shareable, os, testbed: { state } });
  if (state !== "matched") return { matched: false, reason: state };
  gate.open();
  return { matched: true };
}

/**
 * The post-sign-in order: the identity gate (when enabled) strictly before any scenario. A
 * scenario runs only when there is no identity check or Testbed answered "matched".
 */
export async function afterSignIn({ identityGate, runScenario }) {
  if (identityGate) {
    const outcome = await identityGate();
    if (!outcome.matched) return { ...outcome, scenarioRan: false };
  }
  if (runScenario) await runScenario();
  return { matched: true, scenarioRan: Boolean(runScenario) };
}

/** Writes the Testbed-private part, readable by the runner user only. */
export function writePrivateReceipt(file, data) {
  fs.writeFileSync(file, `${JSON.stringify(data)}\n`, { mode: 0o600 });
  fs.chmodSync(file, 0o600);
}

/** `/proc/<pid>/stat` field 22. */
export function procStartTicks(stat) {
  const text = String(stat ?? "");
  const close = text.lastIndexOf(")");
  if (close < 0) return null;
  const value = text.slice(close + 2).trim().split(" ")[19];
  return /^[0-9]+$/.test(value ?? "") ? value : null;
}

/**
 * Drives the provider over `shell(command) -> { status, stdout, stderr }` (adb shell). Enforces the
 * order: probe -> enable -> (sign-in) -> call -> disable.
 */
export function createIdentityReceiptRunner({ shell, shellPrivate = shell, pkg }) {
  requirePackage(pkg);
  const state = { probed: false, enableAttempted: false, enabled: false, signInStarted: false, disabled: false };
  const out = (result) => `${result?.stdout ?? ""}${result?.stderr ?? ""}`;
  const pidOf = () => {
    const pid = String(shell(`pidof ${pkg}`).stdout ?? "").trim();
    return /^[0-9]+$/.test(pid) ? pid : null;
  };
  const startTicks = (pid) => (pid ? procStartTicks(shell(`cat /proc/${pid}/stat`).stdout) : null);
  return {
    state,
    /** Before any account is used. Throws UnsupportedBuildError when the build has no provider. */
    probe() {
      const dump = shell(`dumpsys package ${pkg}`);
      const text = out(dump);
      if (!/versionCode=\d+/.test(text)) throw new UnsupportedBuildError(`${UNSUPPORTED_MESSAGE}: ${pkg} is not installed`);
      if (!providerDeclared(text, pkg)) {
        const versionCode = /versionCode=(\d+)/.exec(text)?.[1] ?? "?";
        throw new UnsupportedBuildError(`${UNSUPPORTED_MESSAGE}: ${pkg} versionCode=${versionCode} has no AppIdentityReceiptProvider`);
      }
      state.probed = true;
    },
    /** Must run before sign-in: enabling a component kills the app process. */
    enable() {
      if (!state.probed) throw new Error("identity receipt: probe the build before enabling the provider");
      if (state.signInStarted) throw new Error("identity receipt: enable the provider before sign-in (enabling kills the signed-in app)");
      state.enableAttempted = true;
      const result = shell(`pm enable ${componentFor(pkg)}`);
      if (result.status !== 0 || !/new state: enabled/.test(out(result))) {
        const detail = out(result).trim().slice(0, 200);
        if (/does not exist|Unknown component/i.test(detail)) throw new UnsupportedBuildError(`${UNSUPPORTED_MESSAGE}: ${detail}`);
        throw new Error(`identity receipt: pm enable failed: ${detail}`);
      }
      state.enabled = true;
    },
    markSignInStarted() {
      state.signInStarted = true;
    },
    /** One call after sign-in. Returns { request, receipt | error, os-side pid/start ticks }. */
    call(request) {
      if (!state.enabled) throw new Error("identity receipt: the provider is not enabled");
      const pidBefore = pidOf();
      const startTicksBefore = startTicks(pidBefore);
      // The nonce (and any pairing salt) are private: the command goes over stdin, never adb's argv.
      const answer = parseContentCallOutput(out(shellPrivate(contentCallCommand(pkg, request))));
      const pidAfter = pidOf();
      const startTicksAfter = startTicks(pidAfter);
      const os = { pidBefore, pidAfter, startTicksBefore, startTicksAfter };
      if (answer.error) {
        let detail = String(answer.detail ?? "").replaceAll(request.nonce, "[nonce]");
        if (request.pairingSalt) detail = detail.replaceAll(request.pairingSalt, "[salt]");
        return { error: answer.error, detail, os };
      }
      return { receipt: parseReceipt(answer.receipt, { paired: Boolean(request.pairingSalt) }), os };
    },
    /** Cleanup. Never throws; { ok:false } must be reported loudly by the caller. */
    disable() {
      if (!state.enableAttempted) return { ok: true, status: "not_enabled" };
      const result = shell(`pm disable ${componentFor(pkg)}`);
      const text = out(result).trim();
      if (result.status === 0 && /new state: disabled/.test(text)) {
        state.enabled = false;
        state.disabled = true;
        return { ok: true, status: "disabled" };
      }
      return { ok: false, status: "failed", detail: text.slice(0, 200) || `exit ${result.status}` };
    },
  };
}

/** A GitHub Actions error annotation for a provider left enabled. */
export function disableFailureAnnotation(pkg, detail) {
  return `::error title=identity receipt provider left enabled::pm disable ${componentFor(pkg)} failed (${String(detail ?? "").replace(/[\r\n]+/g, " ")}); disable it by hand before reusing this device`;
}

const V3_SIGNER_LINE = /^(V3(?:\.0|\.1)?\s+)?Signer:?\s*\(([^)]*)\)\s+certificate SHA-256 digest:\s*([0-9A-Fa-f]{64})$/;
const SUMMARY_SIGNER_LINE = /^Signer #[1-9][0-9]* certificate SHA-256 digest:\s*([0-9A-Fa-f]{64})$/;

/**
 * The certificate the device actually uses (what SigningInfo.apkContentsSigners reports), from
 * `apksigner verify -v --print-certs` output. Taken ONLY from the verified v3/v3.1 signer blocks
 * whose SDK range holds the device SDK, never from apksigner's summary "Signer #N" certificates
 * (with a three-generation rotation the summary is the 2nd certificate while API 35/36 devices
 * use the 3rd). A v3.1 block wins over a v3.0 block on the same SDK.
 *
 * A block signed for a dev release (rotation-min-sdk-version + the dev-release flag) shifts only
 * its LOWER bound: a preview device whose SDK is minSdk (the codename build of minSdk + 1) takes
 * it, a release device only from minSdk + 1. So rotation-min-sdk-version=35 + flag selects the old
 * certificate on release API 35 and the new one on release API 36.
 *
 * The summary line is used only when the output has no v3 block at all (a v1/v2-only APK) and
 * names exactly one certificate.
 */
export function apkSignerForDevice(apksignerOutput, { sdk, preview = false }) {
  if (!Number.isInteger(sdk) || sdk < 1) throw new Error("device SDK is invalid");
  const blocks = [];
  const summary = [];
  for (const raw of String(apksignerOutput ?? "").split(/\r?\n/)) {
    const line = raw.trim();
    const block = V3_SIGNER_LINE.exec(line);
    if (block) {
      const attrs = block[2];
      const min = Number(/minSdkVersion=([0-9]+)/.exec(attrs)?.[1]);
      const max = Number(/maxSdkVersion=([0-9]+)/.exec(attrs)?.[1]);
      if (!Number.isInteger(min) || !Number.isInteger(max) || max < min) throw new Error(`apksigner signer range is invalid: ${line.slice(0, 120)}`);
      const devRelease = /targets\s*dev\s*release\s*=?\s*true/i.test(attrs.replace(/[_-]/g, ""));
      blocks.push({ v31: /^V3\.1/.test(block[1] ?? ""), min, max, devRelease, digest: block[3].toLowerCase() });
      continue;
    }
    const sum = SUMMARY_SIGNER_LINE.exec(line);
    if (sum) summary.push(sum[1].toLowerCase());
  }
  if (!blocks.length) {
    if (summary.length !== 1) throw new Error(`no v3 signer block and ${summary.length} summary certificates`);
    return summary[0];
  }
  const applies = (b) => {
    const lower = b.devRelease && !preview ? b.min + 1 : b.min;
    return sdk >= lower && sdk <= b.max;
  };
  const candidates = blocks.filter(applies);
  const preferred = candidates.some((b) => b.v31) ? candidates.filter((b) => b.v31) : candidates;
  const digests = [...new Set(preferred.map((b) => b.digest))];
  if (digests.length !== 1) throw new Error(`apksigner lists ${digests.length} certificates for device SDK ${sdk}${preview ? " (preview)" : ""}`);
  return digests[0];
}

/** `apksigner verify` arguments: verbose, certificates, verified for exactly the device SDK. */
export function apksignerDeviceArgs(apk, sdk) {
  if (!Number.isInteger(sdk) || sdk < 1) throw new Error("device SDK is invalid");
  return ["verify", "-v", "--min-sdk-version", String(sdk), "--max-sdk-version", String(sdk), "--print-certs", apk];
}

/** sha256 of a local file (the APK pulled from `pm path`). */
export function fileSha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

// CLI: `node scripts/qa/app_identity_receipt_lib.mjs probe --package build.raft.app` (adb on PATH).
// Exit 3 + ::error:: when the installed build has no provider. For workflows: run it before
// taking an account.
if (import.meta.url === `file://${process.argv[1]}`) {
  const args = process.argv.slice(2);
  const pkgIndex = args.indexOf("--package");
  const pkg = pkgIndex >= 0 ? args[pkgIndex + 1] : "build.raft.app";
  if (args[0] !== "probe") {
    console.error("usage: app_identity_receipt_lib.mjs probe [--package <id>]");
    process.exit(2);
  }
  const shell = (command) => {
    const r = spawnSync("adb", ["shell", command], { encoding: "utf8", timeout: 60_000 });
    return { status: r.status ?? 1, stdout: r.stdout ?? "", stderr: r.stderr ?? String(r.error ?? "") };
  };
  try {
    createIdentityReceiptRunner({ shell, pkg }).probe();
    console.log(`identity receipt: ${pkg} has the provider`);
  } catch (error) {
    console.error(`::error title=identity receipt unsupported::${error.message}`);
    process.exit(error instanceof UnsupportedBuildError ? 3 : 1);
  }
}
