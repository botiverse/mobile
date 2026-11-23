// Runner side of the Android app identity receipt (`raft-app-identity-receipt/v1`,
// AppIdentityReceiptProvider). New packages ship the provider ENABLED (Android 14+ shell cannot
// change a component's state in a signed release: "Shell cannot change component state"); 1.27.0
// and earlier ship it DISABLED and are debuggable/alpha packages the shell can enable. The runner:
//
//   1. probes the installed package for the provider BEFORE any account is used. A build without
//      it (every 1.26.x) cannot give a receipt: the run fails with "this build does not support the
//      identity receipt", no fallback. The probe also learns the provider's start state
//      (PROVIDER_START_STATES) by asking it a method it refuses (`content call --method probe`:
//      no nonce, no receipt, not counted against the answer limit). `dumpsys package` cannot tell:
//      it lists a provider whatever its manifest says and shows only `pm enable/disable` overrides.
//      A start state the probe could not read is "unknown" and stays the run's outcome: the run
//      cannot pass, whatever happens afterwards;
//   2. only when the package ships it disabled: `pm enable`s it BEFORE sign-in (changing a
//      component's state kills the app process, so it must not happen after the app is signed in
//      and on screen). A package that ships it enabled gets no `pm enable` and no `pm disable`;
//   3. after the normal sign-in, asks it once with `content call` (Testbed's attempt id + nonce and
//      a fresh request id) and cross-checks what the app says against what the OS says:
//      package + versionCode + debuggable from `dumpsys package`, the signer from the installed APK
//      (`pm path` + AppIdentityApkSigner.java on apksig 36.0.0: the certificate a released
//      platform of the device's SDK uses, from the structured v3/v3.1 result), the pid before and
//      after; the complete receipt + OS facts go privately to Testbed, which decides;
//   4. in cleanup `pm disable`s it again, only if this run enabled it. A disable that fails or
//      cannot be confirmed is reported loudly (::error:: + non-zero exit), never ignored. The
//      cleanup status is one of PROVIDER_CLEANUP_STATUSES.
//
// user_ref / server_ref are verification digests salted with the attempt nonce (same vector as
// Testbed): they confirm the expected account and server without the receipt carrying either.
// With Testbed's optional pairing salt the receipt also has user_pair_ref / server_pair_ref (same
// formulas, salted with it) for cross-run comparison. The nonce, the salt and all four refs are
// private to Testbed: the request reaches the device over adb's stdin (never argv), and the
// receipt + OS facts go only to the fresh Testbed-private output file (0600, outside the evidence
// directory). stdout, the summary and the evidence carry only PUBLIC_IDENTITY_FIELDS (check
// statuses + Testbed's decision state).
// What the app reports is not proof on its own; the OS-side cross-check is.
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
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

/**
 * How the installed package had the provider when the run started:
 *   enabled_by_package – it answers the probe with exactly `unknown_method` and no `pm enable` override exists: the package ships it enabled.
 *                        The run changes nothing (no `pm enable`, no `pm disable`).
 *   disabled           – it cannot be acquired ("Could not find provider"), or it answers only
 *                        because of a left-over `pm enable` override (dumpsys `enabledComponents:`):
 *                        the package ships it disabled (1.27.0 and earlier). `pm enable` / `pm disable`.
 *   unknown            – the probe gave neither answer (any other reply, e.g. another refusal code). The run cannot pass any more: its cleanup
 *                        status is "unknown" (or "failed"), never "disabled". `pm enable` is still
 *                        tried, so the receipt and Testbed's decision are still produced for
 *                        diagnosis, and whatever the run enabled is disabled again; if `pm enable`
 *                        fails the run stops before any account and sends no `pm disable`.
 */
export const PROVIDER_START_STATES = ["enabled_by_package", "disabled", "unknown"];

/**
 * The `identity_receipt_provider` cleanup status (summary.json `cleanup`), every value:
 *   disabled           – older package (start state read as disabled): this run enabled the
 *                        provider and disabled it again.                                       PASSES
 *   enabled_by_package – new package: enabled from the start, not changed by this run.         PASSES
 *   unknown            – the start state could not be read. Final whatever followed: a
 *                        `pm enable` + `pm disable` that both succeeded do NOT make it
 *                        "disabled" (the run cannot say it left the device as it found it).   does not pass
 *   failed             – this run enabled it with `pm enable` and could not disable it. Wins
 *                        over "unknown": a provider left enabled by the run is the more
 *                        serious condition.                                                    does not pass
 *   not_enabled        – the run stopped before the provider was probed or enabled (nothing
 *                        changed; such a run has already failed).
 */
export const PROVIDER_CLEANUP_STATUSES = ["disabled", "enabled_by_package", "unknown", "failed", "not_enabled"];

export const PROBE_METHOD = "probe";
/** A request the provider refuses with `unknown_method`: carries nothing, changes nothing. */
export const probeCallCommand = (pkg) => `content call --uri content://${authorityFor(pkg)} --method ${PROBE_METHOD}`;

/**
 * A `pm enable` / `pm disable` override of the provider in `dumpsys package` (the per-user
 * `enabledComponents:` / `disabledComponents:` lists): "enabled", "disabled" or null (none; the
 * manifest decides, and dumpsys does not print what the manifest says).
 */
export function providerOverride(dumpsysPackage) {
  let list = null;
  for (const line of String(dumpsysPackage ?? "").split("\n")) {
    const header = /^\s*(enabled|disabled)Components:\s*$/.exec(line);
    if (header) { list = header[1]; continue; }
    if (!list) continue;
    const entry = /^\s+([\w.$]+)\s*$/.exec(line)?.[1];
    if (!entry) { list = null; continue; }
    if (entry === PROVIDER_CLASS) return list;
  }
  return null;
}

/**
 * The start state from the probe call's output and the package dump. Only the agreed reply, the
 * refusal `unknown_method` as the whole output, proves the provider answered; any other reply
 * (another refusal code, a receipt, anything malformed) is "unknown".
 */
export function providerStartState(probeOutput, dumpsysPackage) {
  const text = String(probeOutput ?? "");
  if (/^Result: Bundle\[\{error=unknown_method\}\]$/.test(text.trim())) return providerOverride(dumpsysPackage) === "enabled" ? "disabled" : "enabled_by_package";
  if (/Could not find provider/i.test(text)) return "disabled";
  return "unknown";
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
  // os.versionCode is dumpsys's original decimal string (Testbed's observation schema); the receipt's is a number.
  check(typeof os.versionCode === "string" && /^[1-9][0-9]{0,19}$/.test(os.versionCode) && String(receipt.version_code) === os.versionCode, "version_code differs from dumpsys package");
  check(typeof receipt.signer_sha256 === "string" && HEX64.test(receipt.signer_sha256), "receipt has no single signer");
  check(receipt.signer_sha256 === os.signerSha256, "signer_sha256 differs from apksigner (device SDK certificate)");
  // os.debuggable is null when pkgFlags could not be read: unknown, never "not debuggable".
  check(receipt.debuggable === false && os.debuggable === false, os.debuggable == null ? "debuggable flag could not be read" : "build is debuggable");
  // This pilot runs on a released SDK only (ro.build.version.codename must read REL).
  check(os.deviceCodename === "REL" && os.devicePreview === false, "device is not on a released SDK (codename is not REL or unread)");
  check(os.splitApks === 0, "installed package is not a single APK");
  check(HEX64.test(os.installedApkSha256 ?? ""), "installed APK digest is unknown");
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

/** Receipt fields that are Testbed's comparison baseline: private output only, redacted everywhere else. */
export const PRIVATE_RECEIPT_KEYS = ["nonce", "user_ref", "server_ref", "user_pair_ref", "server_pair_ref"];

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

/** The only fields `summary.identity_receipt` may carry: fixed check statuses and Testbed's state. */
export const PUBLIC_IDENTITY_FIELDS = ["receipt_check", "os_check", "user_check", "server_check", "pair_check", "state"];
const CHECK_STATUSES = ["pass", "fail", "pending", "not_run"];
export const TESTBED_DECISION_STATES = ["matched", "awaiting_confirmation", "identity_mismatch", "unknown"];
const PUBLIC_STATES = [...TESTBED_DECISION_STATES, "unsupported_build"];

/**
 * Closed allowlist of sanitized check categories for a runner-side `mismatch` (#227): names ONLY
 * which check category failed, never receipt values, hashes, IDs, paths or screen text.
 */
export const MISMATCH_CATEGORIES = [
  "os_facts_unavailable",
  "request_binding",
  "package",
  "version_code",
  "signer",
  "debuggable",
  "device_sdk",
  "apk_integrity",
  "process",
  "session_state",
  "session_origin",
  "user_or_server_ref",
  "pair_ref",
  "expected_ref",
  "unknown",
];

const MISMATCH_CATEGORY_RULES = [
  [/^os_facts_unavailable$/, "os_facts_unavailable"],
  [/receipt does not echo this request/, "request_binding"],
  [/package differs from the installed package/, "package"],
  [/version_code differs from dumpsys package/, "version_code"],
  [/signer/, "signer"],
  [/debuggable/, "debuggable"],
  [/released SDK/, "device_sdk"],
  [/single APK|APK digest|installed base\.apk differs/, "apk_integrity"],
  [/pid changed|process start time changed/, "process"],
  [/app is not signed in/, "session_state"],
  [/session origin is /, "session_origin"],
  [/no user_ref\/server_ref/, "user_or_server_ref"],
  [/no user_pair_ref\/server_pair_ref/, "pair_ref"],
  [/user_ref differs|server_ref differs/, "expected_ref"],
];

/** Maps runner-side cross-check problems to deduplicated allowlisted check categories (no free text). */
export function mismatchCategories(problems) {
  const seen = new Set();
  for (const problem of Array.isArray(problems) ? problems : []) {
    const text = typeof problem === "string" ? problem : "";
    const match = MISMATCH_CATEGORY_RULES.find(([pattern]) => pattern.test(text));
    seen.add(match ? match[1] : "unknown");
  }
  return MISMATCH_CATEGORIES.filter((category) => seen.has(category));
}

/** Projects to the agreed public field list; any other key or value is dropped. */
export function publicIdentitySummary(result) {
  const out = {};
  for (const key of PUBLIC_IDENTITY_FIELDS) {
    const value = result?.[key];
    if (value === undefined) continue;
    if (key === "state" ? PUBLIC_STATES.includes(value) : CHECK_STATUSES.includes(value)) out[key] = value;
  }
  if (!out.state) out.state = "unknown";
  return out;
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

/** Testbed's reply to the private observation: only `recorded` lets the runner ask for a decision. */
export function observationRecorded(reply) {
  return Boolean(reply && typeof reply === "object" && reply.state === "recorded");
}

/**
 * After sign-in in --identity-receipt mode:
 *   1. ask the provider once and read what the OS says (`readOsFacts`; a failed read is unknown);
 *   2. cross-check them; write the complete receipt + OS facts to the private file only;
 *   3. any runner-side problem stops here: nothing is submitted to Testbed;
 *   4. `testbed.observe({ request_id, receipt, os })` privately (must answer `recorded`), then
 *      `testbed.decide(request)`; only a "matched" decision bound to this attempt and request opens
 *      the capture gate.
 * `publish` gets only PUBLIC_IDENTITY_FIELDS (statuses + state), never ids, values or free text.
 */
export async function runIdentityGate({ runner, request, readOsFacts, expected = {}, extraProblems = () => [], writePrivate, publish, testbed, gate }) {
  const show = (fields) => publish(publicIdentitySummary(fields));
  const answer = runner.call(request);
  if (answer.error) {
    show({ receipt_check: "fail", state: "unknown" });
    return { matched: false, reason: "receipt_unavailable" };
  }
  let facts = null;
  const problems = [];
  try {
    facts = readOsFacts();
  } catch {
    problems.push("os_facts_unavailable");
  }
  const os = { ...(facts ?? {}), ...answer.os };
  const crossCheck = crossCheckReceipt({ receipt: answer.receipt, request, os, expected });
  problems.push(...crossCheck.problems);
  if (facts) problems.push(...extraProblems(facts));
  const checks = verificationStatus({ receipt: answer.receipt, request, expected });
  writePrivate({ attempt_id: request.attemptId, request_id: request.requestId, receipt: answer.receipt, os, problems });
  const osCheck = problems.length ? "fail" : "pass";
  if (problems.length) {
    const categories = mismatchCategories(problems);
    show({ receipt_check: "pass", os_check: osCheck, ...checks, state: "unknown" });
    return { matched: false, reason: "mismatch", problems, categories };
  }
  let state = "unknown";
  try {
    if (observationRecorded(await testbed.observe({ request_id: request.requestId, receipt: answer.receipt, os }))) {
      state = parseTestbedDecision(await testbed.decide(request), request);
    }
  } catch {
    state = "unknown";
  }
  show({ receipt_check: "pass", os_check: osCheck, ...checks, state });
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

/** Canonical path: the nearest existing ancestor through realpath, then the rest. */
function canonicalPath(p) {
  let head = path.resolve(p);
  const rest = [];
  while (!fs.existsSync(head)) {
    rest.unshift(path.basename(head));
    const parent = path.dirname(head);
    if (parent === head) break;
    head = parent;
  }
  return path.join(fs.realpathSync(head), ...rest);
}

/** `child` is `dir` or below it, compared by path components of canonical paths. */
function isWithin(child, dir) {
  const a = canonicalPath(child).split(path.sep).filter(Boolean);
  const b = canonicalPath(dir).split(path.sep).filter(Boolean);
  return b.every((part, i) => a[i] === part);
}

/**
 * The Testbed-private file: a FRESH file (nothing, not even a symlink, may exist there) in an
 * existing runner-owned directory that no group/other may write, whose canonical location is
 * outside the canonical evidence directory (which the workflow uploads recursively), and that
 * does not contain the evidence directory either.
 */
export function privateOutPath(file, evidenceDir) {
  if (!file) throw new Error("identity receipt: QA_IDENTITY_PRIVATE_OUT is required");
  const target = path.resolve(file);
  const parent = path.dirname(target);
  let parentStat;
  try {
    parentStat = fs.statSync(parent);
  } catch {
    throw new Error("identity receipt: QA_IDENTITY_PRIVATE_OUT's directory must exist");
  }
  if (!parentStat.isDirectory()) throw new Error("identity receipt: QA_IDENTITY_PRIVATE_OUT's directory must exist");
  const realParent = fs.realpathSync(parent);
  if (isWithin(realParent, evidenceDir) || isWithin(evidenceDir, realParent)) {
    throw new Error("identity receipt: QA_IDENTITY_PRIVATE_OUT must be outside the evidence directory");
  }
  if ((typeof process.getuid === "function" && parentStat.uid !== process.getuid()) || (parentStat.mode & 0o022) !== 0) {
    throw new Error("identity receipt: QA_IDENTITY_PRIVATE_OUT must be in a runner-owned directory no one else can write");
  }
  let exists = true;
  try { fs.lstatSync(target); } catch { exists = false; }
  if (exists) throw new Error("identity receipt: QA_IDENTITY_PRIVATE_OUT must not exist yet");
  return path.join(realParent, path.basename(target));
}

/** Writes the Testbed-private part once, to a new file readable by the runner user only. */
export function writePrivateReceipt(file, data) {
  fs.writeFileSync(file, `${JSON.stringify(data)}\n`, { mode: 0o600, flag: "wx" });
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
 * order: probe -> enable (a no-op for a package that ships it enabled) -> (sign-in) -> call ->
 * disable (only what this run enabled).
 */
export function createIdentityReceiptRunner({ shell, shellPrivate = shell, pkg }) {
  requirePackage(pkg);
  const state = { probed: false, startState: null, enableAttempted: false, enabled: false, signInStarted: false, disabled: false };
  const out = (result) => `${result?.stdout ?? ""}${result?.stderr ?? ""}`;
  const pidOf = () => {
    const pid = String(shell(`pidof ${pkg}`).stdout ?? "").trim();
    return /^[0-9]+$/.test(pid) ? pid : null;
  };
  const startTicks = (pid) => (pid ? procStartTicks(shell(`cat /proc/${pid}/stat`).stdout) : null);
  return {
    state,
    /**
     * Before any account is used. Throws UnsupportedBuildError when the build has no provider.
     * Sets `state.startState`; a package that ships the provider enabled is ready to call.
     */
    probe() {
      const dump = shell(`dumpsys package ${pkg}`);
      const text = out(dump);
      if (!/versionCode=\d+/.test(text)) throw new UnsupportedBuildError(`${UNSUPPORTED_MESSAGE}: ${pkg} is not installed`);
      if (!providerDeclared(text, pkg)) {
        const versionCode = /versionCode=(\d+)/.exec(text)?.[1] ?? "?";
        throw new UnsupportedBuildError(`${UNSUPPORTED_MESSAGE}: ${pkg} versionCode=${versionCode} has no AppIdentityReceiptProvider`);
      }
      state.startState = providerStartState(out(shell(probeCallCommand(pkg))), text);
      if (state.startState === "enabled_by_package") state.enabled = true;
      state.probed = true;
    },
    /**
     * Makes the provider callable. A package that ships it enabled: nothing to do, at any time.
     * Otherwise `pm enable`, which must run before sign-in: changing a component's state kills
     * the app process.
     */
    enable() {
      if (!state.probed) throw new Error("identity receipt: probe the build before enabling the provider");
      if (state.startState === "enabled_by_package") return;
      if (state.signInStarted) throw new Error("identity receipt: enable the provider before sign-in (enabling kills the signed-in app)");
      state.enableAttempted = true;
      const result = shell(`pm enable ${componentFor(pkg)}`);
      if (result.status !== 0 || !/new state: enabled/.test(out(result))) {
        const detail = out(result).trim().slice(0, 200);
        if (/does not exist|Unknown component/i.test(detail)) throw new UnsupportedBuildError(`${UNSUPPORTED_MESSAGE}: ${detail}`);
        throw new Error(`identity receipt: pm enable failed${state.startState === "unknown" ? " (the provider did not answer the probe either)" : ""}: ${detail}`);
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
    /**
     * Cleanup. Never throws. `status` is one of PROVIDER_CLEANUP_STATUSES. `ok: false` only for
     * "failed" (a provider this run enabled is still enabled): the caller must report it loudly.
     * "unknown" is `ok: true` (nothing to annotate: this run left nothing enabled that it knows
     * of) but is not a passing status. An unknown start state never becomes "disabled": what the
     * run enabled is still disabled again, and the status stays "unknown" ("failed" if that
     * `pm disable` fails).
     */
    disable() {
      // Enabled by the package: this run made no change, so there is nothing to undo.
      if (state.startState === "enabled_by_package") return { ok: true, status: "enabled_by_package" };
      // Start state unread and `pm enable` not confirmed: no `pm disable` on a state we do not know.
      if (state.startState === "unknown" && !state.enabled) return { ok: true, status: "unknown" };
      if (!state.enableAttempted) return { ok: true, status: "not_enabled" };
      const result = shell(`pm disable ${componentFor(pkg)}`);
      const text = out(result).trim();
      if (result.status === 0 && /new state: disabled/.test(text)) {
        state.enabled = false;
        state.disabled = true;
        // Restored, but only a start state read as "disabled" may report "disabled".
        return { ok: true, status: state.startState === "unknown" ? "unknown" : "disabled" };
      }
      return { ok: false, status: "failed", detail: text.slice(0, 200) || `exit ${result.status}` };
    },
  };
}

/** A GitHub Actions error annotation for a provider this run enabled and could not disable. */
export function disableFailureAnnotation(pkg, detail) {
  return `::error title=identity receipt provider left enabled::pm disable ${componentFor(pkg)} failed (${String(detail ?? "").replace(/[\r\n]+/g, " ")}); disable it by hand before reusing this device`;
}

/** Build Tools pinned for the signer selection (its apksig is what AppIdentityApkSigner.java uses). */
export const APKSIG_BUILD_TOOLS = "36.0.0";
const SIGNER_SELECTED = /^\{"result":"SIGNER_SELECTED","android_sdk":([1-9][0-9]*),"signer_sha256":"([0-9a-f]{64})"\}$/;

/**
 * `java` arguments for scripts/qa/AppIdentityApkSigner.java: the certificate a RELEASED platform of
 * `sdk` uses for the APK, from apksig's structured v3/v3.1 verification result (the same selection
 * as Testbed's InspectReleaseSettingsApk), never from apksigner's printed text.
 */
export function apkSignerInspectorArgs({ apksignerJar, script, apk, sdk }) {
  if (!Number.isInteger(sdk) || sdk < 1) throw new Error("device SDK is invalid");
  return ["-cp", apksignerJar, script, apk, String(sdk)];
}

/** The inspector's verdict: the selected certificate for exactly `sdk`, or a fixed error. */
export function parseApkSignerInspector(output, { status, sdk }) {
  const match = SIGNER_SELECTED.exec(String(output ?? "").trim());
  if (status !== 0 || !match || Number(match[1]) !== sdk) throw new Error("APK_SIGNER_UNVERIFIED");
  return match[2];
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
    const runner = createIdentityReceiptRunner({ shell, pkg });
    runner.probe();
    console.log(`identity receipt: ${pkg} has the provider (start state: ${runner.state.startState})`);
  } catch (error) {
    console.error(`::error title=identity receipt unsupported::${error.message}`);
    process.exit(error instanceof UnsupportedBuildError ? 3 : 1);
  }
}
