// Pure rules + device-independent orchestration for the release pre-login
// (scripts/qa/release_prelogin.mjs; contract: docs/qa-acceptance/release-prelogin-contract.md).
// Nothing here touches adb, the network or a credential: the device and the sign-in run are
// injected, so every rule is testable with fake values (release_prelogin.test.mjs).
import crypto from "node:crypto";

export const CONTRACT = "raft-release-prelogin/v1";
export const ALLOWLIST_CONTRACT = "raft-release-prelogin-allowlist/v1";
/** What the app writes (QaReleaseReadinessReceiver.kt). */
export const RECEIPT_CONTRACT = "raft-qa-release-readiness/v1";
export const RECEIVER_CLASS = "build.raft.app.core.data.auth.QaReleaseReadinessReceiver";
export const READINESS_TAG = "RaftQaReadiness";
export const READINESS_MARKER = "RAFT_QA_READINESS";
export const EXTRA_ATTEMPT_ID = "qaAttemptId";
export const EXTRA_NONCE = "qaNonce";

/**
 * Case classes that are NEVER switched to the release pre-login, whatever the allow-list says:
 * their subject is the login itself, the session's end, the install, more than one server or
 * platform, the bootstrap path, or making a server.
 */
export const NEVER_CLASSES = ["login-ui", "logout", "upgrade", "cross-server", "bootstrap-canary", "server-create"];
/** Case tags that put a case in one of those classes (case YAML `tags:`). */
const TAG_CLASS = {
  login: "login-ui",
  "login-ui": "login-ui",
  logout: "logout",
  "sign-out": "logout",
  upgrade: "upgrade",
  update: "upgrade",
  "app-update": "upgrade",
  "multi-server": "cross-server",
  "cross-server": "cross-server",
  "cross-platform": "cross-server",
  bootstrap: "bootstrap-canary",
  canary: "bootstrap-canary",
  provisioning: "server-create",
  "server-create": "server-create",
  "manual-only": "manual-only",
};

const ATTEMPT_ID = /^pla_[0-9a-f]{32}$/;
const NONCE = /^[0-9a-f]{64}$/;
const PROCESS = /^\d+:\d+$/;
const THREADTIME_LINE = /^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+\d+\s+[VDIWEF]\s+([^:]+?)\s*:\s?(.*)$/;
const SENSITIVE_KEY = /token|cookie|password|secret|email|session_key|authorization/i;

// ---------- which case may use it ----------

/**
 * Whether ONE case may be pre-logged-in on a release build. Refuses (never silently switches)
 * unless ALL hold: the build is a release (non-debuggable) build — Alpha/debuggable builds keep
 * their own fixtures; the case is listed in the allow-list with intent "ordinary"; neither the
 * allow-list's never-list, the case's own tags nor its auth fixture put it in a never class.
 */
export function preloginEligibility({ caseId, caseMeta = {}, allowList, buildDebuggable }) {
  const refuse = (reason, cls = null) => ({ eligible: false, reason, ...(cls ? { class: cls } : {}) });
  if (!caseId || typeof caseId !== "string") return refuse("no_case_id");
  if (buildDebuggable !== false) return refuse(buildDebuggable === true ? "debuggable_build_keeps_alpha_fixtures" : "build_kind_unknown");
  if (!allowList || allowList.contract !== ALLOWLIST_CONTRACT || typeof allowList.cases !== "object") return refuse("allow_list_missing_or_malformed");
  const never = allowList.never && Object.hasOwn(allowList.never, caseId) ? allowList.never[caseId] : null;
  if (never) return refuse("never_class", never);
  for (const tag of caseMeta.tags ?? []) {
    const cls = TAG_CLASS[String(tag).toLowerCase()];
    if (cls) return refuse("never_class", cls);
  }
  if (caseMeta.authFixture === "none") return refuse("case_runs_signed_out");
  const entry = Object.hasOwn(allowList.cases, caseId) ? allowList.cases[caseId] : null;
  if (!entry) return refuse("not_allow_listed");
  if (entry.intent !== "ordinary") return refuse("intent_not_ordinary");
  if (NEVER_CLASSES.includes(entry.class)) return refuse("never_class", entry.class);
  return { eligible: true, reason: "allow_listed_ordinary" };
}

/** `tags:` and `auth_fixture:` of a case YAML (flow-style tag list), without a YAML parser. */
export function caseMetaFromYaml(text) {
  const src = String(text ?? "");
  const tags = /^tags:\s*\[([^\]]*)\]/m.exec(src)?.[1].split(",").map((t) => t.trim().replace(/^["']|["']$/g, "")).filter(Boolean) ?? [];
  const authFixture = /^auth_fixture:\s*(\S+)/m.exec(src)?.[1] ?? null;
  const id = /^id:\s*(\S+)/m.exec(src)?.[1] ?? null;
  return { id, tags, authFixture };
}

// ---------- the receipt writer's component state ----------

/**
 * A component's state from `dumpsys package <pkg>`: "enabled" / "disabled" when listed under the
 * user's enabledComponents: / disabledComponents: (an explicit `pm enable` / `pm disable`), else
 * "default" (the manifest's value; android:enabled="false" for the readiness receiver).
 */
export function componentStateFromDumpsys(dump, className) {
  let current = null;
  let indent = -1;
  let state = "default";
  for (const line of String(dump ?? "").split("\n")) {
    const lead = line.length - line.trimStart().length;
    const text = line.trim();
    if (text === "enabledComponents:" || text === "disabledComponents:") {
      current = text.startsWith("enabled") ? "enabled" : "disabled";
      indent = lead;
      continue;
    }
    if (current && text && lead > indent) {
      if (text === className) state = current;
      continue;
    }
    current = null;
  }
  return state;
}

// ---------- the attempt ----------

/** The account + server Testbed froze for the attempt. All non-secret ids. */
export function validateTarget(target) {
  const t = target && typeof target === "object" ? target : {};
  const problems = [];
  if (!["staging", "production"].includes(t.env)) problems.push("env must be staging or production");
  for (const key of ["account_user_id", "server_id", "server_slug", "server_name"]) {
    if (typeof t[key] !== "string" || !t[key].trim()) problems.push(`${key} is required`);
  }
  for (const [key, value] of Object.entries(t)) if (SENSITIVE_KEY.test(key)) problems.push(`${key} must not be in the target`);
  if (problems.length) throw new Error(`frozen target: ${problems.join("; ")}`);
  return { env: t.env, account_user_id: t.account_user_id, server_id: t.server_id, server_slug: t.server_slug, server_name: t.server_name };
}

/**
 * One Testbed case ATTEMPT. Distinct from the GitHub workflow run: a rerun of the same workflow
 * (new run_attempt) or a retry of the same case creates a NEW attempt id and nonce, so a receipt an
 * earlier attempt left on the device never matches.
 */
export function newAttempt({ caseId, runCaseId = "", target, workflow = {}, random = crypto.randomBytes, now = () => new Date() }) {
  return {
    contract: CONTRACT,
    attempt_id: `pla_${random(16).toString("hex")}`,
    nonce: random(32).toString("hex"),
    case_id: caseId,
    run_case_id: runCaseId || null,
    workflow: { run_id: workflow.runId ?? null, run_attempt: workflow.runAttempt ?? null },
    target: validateTarget(target),
    created_at: now().toISOString(),
  };
}

export function validateAttempt(attempt) {
  if (!attempt || attempt.contract !== CONTRACT) throw new Error("attempt record malformed");
  if (!ATTEMPT_ID.test(attempt.attempt_id ?? "") || !NONCE.test(attempt.nonce ?? "")) throw new Error("attempt id / nonce malformed");
  validateTarget(attempt.target);
  return attempt;
}

// ---------- receipts ----------

/**
 * The app's readiness lines, from `logcat -d -v threadtime -s RaftQaReadiness:I`:
 * [{ stamp, pid, receipt }] (a line that is not valid JSON is skipped).
 */
export function parseReadinessLog(raw) {
  const out = [];
  for (const line of String(raw ?? "").split("\n")) {
    const m = THREADTIME_LINE.exec(line.trimEnd());
    if (!m || m[3].trim() !== READINESS_TAG) continue;
    const msg = m[4];
    if (!msg.startsWith(`${READINESS_MARKER} {`)) continue;
    try {
      const receipt = JSON.parse(msg.slice(READINESS_MARKER.length + 1));
      if (receipt && typeof receipt === "object" && !Array.isArray(receipt)) out.push({ stamp: m[1], pid: Number(m[2]), receipt });
    } catch {}
  }
  return out;
}

const sensitiveKeys = (obj) => Object.keys(obj ?? {}).filter((k) => SENSITIVE_KEY.test(k));

/**
 * The app + server readiness receipt. "ok" only when the app's OWN line, written by the current
 * app process (same pid AND /proc start time before and after the request, and as the line's
 * writer) for THIS attempt id + nonce, reports a signed-in session of the frozen account on the
 * frozen server (id AND slug) in the installed build. Anything else is "unknown", with the reason.
 * It is not anti-forgery proof (the installed package identity is checked separately).
 */
export function assessReadiness({ lines = [], attempt, processBefore, processAfter, installed = {} }) {
  const unknown = (reason, extra = {}) => ({ status: "unknown", reason, ...extra });
  if (!PROCESS.test(processBefore ?? "") || !PROCESS.test(processAfter ?? "")) return unknown("app_process_unknown");
  if (processBefore !== processAfter) return unknown("app_process_changed");
  const [pid, startTicks] = processBefore.split(":");
  const mine = lines.filter((l) => l.receipt.attempt_id === attempt.attempt_id && l.receipt.nonce === attempt.nonce);
  if (!mine.length) return unknown(lines.length ? "stale_receipt_other_attempt_or_nonce" : "receipt_missing");
  const fromThisProcess = mine.filter((l) => String(l.pid) === pid && String(l.receipt.pid) === pid && String(l.receipt.process_start_ticks) === startTicks);
  if (!fromThisProcess.length) return unknown("receipt_from_other_process");
  const { stamp, receipt } = fromThisProcess.at(-1);
  const leaked = sensitiveKeys(receipt);
  if (leaked.length) return unknown("receipt_carries_sensitive_field", { fields: leaked });
  if (receipt.contract !== RECEIPT_CONTRACT) return unknown("receipt_contract_mismatch");
  const t = attempt.target;
  const facts = {
    user_id: receipt.user_id ?? null,
    selected_server_id: receipt.selected_server_id ?? null,
    selected_server_slug: receipt.selected_server_slug ?? null,
    version_name: receipt.version_name ?? null,
    version_code: receipt.version_code ?? null,
    package: receipt.package ?? null,
    process: `${receipt.pid}:${receipt.process_start_ticks}`,
    receipt_stamp: stamp,
  };
  if (receipt.signed_in !== true || !receipt.user_id) return unknown("app_not_signed_in", { facts });
  if (receipt.user_id !== t.account_user_id) return unknown("account_mismatch", { facts });
  if (!receipt.selected_server_id) return unknown("no_server_selected", { facts });
  if (receipt.selected_server_id !== t.server_id || receipt.selected_server_slug !== t.server_slug) return unknown("server_mismatch", { facts });
  if (installed.package && receipt.package !== installed.package) return unknown("package_mismatch", { facts });
  if (installed.version_name && receipt.version_name !== installed.version_name) return unknown("build_mismatch", { facts });
  if (installed.version_code != null && Number(receipt.version_code) !== Number(installed.version_code)) return unknown("build_mismatch", { facts });
  return { status: "ok", reason: "app_reports_frozen_account_and_server", facts };
}

/**
 * The callback / redirect receipt, from release_auto_login.mjs's summary.json (sign-in phase,
 * --no-capture --defer-app-session --strict-cleanup): the app exchanged the callback itself
 * (its own app_login_complete exchanged line) for the frozen account.
 */
export function assessCallback(summary, target) {
  const unknown = (reason) => ({ status: "unknown", reason });
  if (!summary || typeof summary !== "object") return unknown("sign_in_summary_missing");
  if (summary.phase !== "sign-in" || summary.no_capture !== true || summary.defer_app_session !== true || summary.strict_cleanup !== true) return unknown("sign_in_run_not_in_prelogin_mode");
  const verify = (summary.steps ?? []).find((s) => s.name === "verify-signed-in");
  const delivered = (summary.steps ?? []).find((s) => s.name === "deliver-callback-to-app");
  if (summary.result !== "signed_in") return unknown(`sign_in_${summary.result ?? "missing"}`);
  if (delivered?.result !== "pass") return unknown("callback_not_delivered");
  if (verify?.result !== "pass" || verify.detail?.exchange !== "exchanged") return unknown("app_exchange_not_verified");
  if (summary.account_user_id !== target.account_user_id) return unknown("account_mismatch");
  return { status: "ok", reason: "app_exchanged_callback", screen: verify.detail?.screen ?? null };
}

/**
 * Data capture during the login window: the screen recorder was confirmed stopped before and
 * still absent after, and the sign-in run wrote no screenshot or UI dump. Else "unknown" (and the
 * workflow uploads nothing from the window).
 */
export function assessCaptureWindow({ recorderStoppedBefore, recorderAbsentAfter, signInSummary, harnessCaptures = 0 }) {
  const unknown = (reason) => ({ status: "unknown", reason });
  if (recorderStoppedBefore !== true) return unknown("recorder_not_stopped_before_login");
  if (harnessCaptures !== 0) return unknown("harness_captured_during_login");
  if (signInSummary && (signInSummary.no_capture !== true || Number(signInSummary.screenshots_written ?? 1) !== 0)) return unknown("sign_in_run_captured");
  if (recorderAbsentAfter !== true) return unknown("recorder_ran_during_login");
  return { status: "off", reason: "no_capture_in_login_window" };
}

/**
 * The cleanup receipt, one status per item:
 *   device          the capture browser, captured URL and browser role (sign-in run's cleanup);
 *   web_session     the web session the sign-in opened: "ok" only when its refresh token was
 *                   refused after logout (signed_out), else "unknown";
 *   server_session  the app's own session: "app_reported_2xx" when the sign-out run bound the app's
 *                   own POST /auth/logout 2xx to its process (app_reported_server_logout_2xx). It is
 *                   NEVER "ok": the server has no per-session read-back, so this is the app's report,
 *                   not proof of revocation. Anything else "unknown";
 *   receipt_writer  the readiness receiver disabled again ("disabled") or "unknown".
 * status "complete" only when every item has its best available value; else "unknown".
 */
export function assessCleanup({ signInSummary, signOutSummary, receiverDisabled }) {
  const signIn = signInSummary?.cleanup ?? {};
  const signOut = signOutSummary?.cleanup ?? {};
  const deviceOk =
    ["deleted", "not_applicable"].includes(signIn.capture_file) &&
    ["restored", "not_changed"].includes(signIn.browser) &&
    ["uninstalled", "not_installed_by_run"].includes(signIn.capture_browser);
  const items = {
    device: deviceOk ? "ok" : "unknown",
    web_session: signIn.web_session === "signed_out" ? "ok" : signIn.web_session === "not_opened" ? "not_opened" : "unknown",
    server_session: signOut.app_session === "app_reported_server_logout_2xx" ? "app_reported_2xx" : signOut.app_session === "not_signed_in" ? "not_signed_in" : "unknown",
    receipt_writer: receiverDisabled === true ? "disabled" : "unknown",
  };
  const best = { device: ["ok"], web_session: ["ok", "not_opened"], server_session: ["app_reported_2xx", "not_signed_in"], receipt_writer: ["disabled"] };
  const open = Object.keys(items).filter((k) => !best[k].includes(items[k]));
  return { status: open.length ? "unknown" : "complete", items, unproven: open, app_logout_evidence: signOut.app_logout_evidence ?? null };
}

/**
 * The attempt's outcome. A product PASS needs every receipt good AND the agent's pass; anything
 * unknown is never a pass. A product FAIL the agent saw on a correctly prepared app stays a FAIL
 * (cleanup problems are reported next to it). Refused cases are NOT_RUN (no product verdict).
 *   rc: 0 pass, 1 fail, 3 unknown / not run (never a product FAIL).
 */
export function attemptOutcome({ eligibility, capture, callback, readiness, cleanup, agentRc }) {
  const result = (outcome, rc, reason) => ({ outcome, rc, reason });
  if (!eligibility?.eligible) return result("not_run", 3, `refused: ${eligibility?.reason ?? "no eligibility"}`);
  if (capture?.status !== "off") return result("unknown", 3, `capture: ${capture?.reason ?? "missing"}`);
  if (callback?.status !== "ok") return result("unknown", 3, `callback: ${callback?.reason ?? "missing"}`);
  if (readiness?.status !== "ok") return result("unknown", 3, `readiness: ${readiness?.reason ?? "missing"}`);
  const rc = Number(agentRc);
  if (rc === 1) return result("fail", 1, cleanup?.status === "complete" ? "agent: product fail" : `agent: product fail; cleanup unknown (${(cleanup?.unproven ?? ["missing"]).join(",")})`);
  if (rc !== 0) return result("unknown", 3, `agent rc ${Number.isFinite(rc) ? rc : "missing"}`);
  if (cleanup?.status !== "complete") return result("unknown", 3, `cleanup: ${(cleanup?.unproven ?? ["missing"]).join(",")}`);
  return result("pass", 0, "all receipts ok and agent pass");
}

// ---------- orchestration (device injected) ----------

/** Flags the sign-in run must get; the login refuses to run it any other way. */
export const SIGN_IN_FLAGS = ["--no-capture", "--defer-app-session", "--strict-cleanup"];

/**
 * The login window for one attempt. `device` (adb in release_prelogin.mjs, fakes in tests):
 *   stopRecorder() -> bool (confirmed gone)   recorderRunning() -> bool
 *   captureCount() -> number of screenshots / recordings / UI dumps the harness wrote
 *   enableReceiver() -> bool                  runSignIn(flags) -> summary.json of release_auto_login
 *   selectServer(name) -> void                appProcess() -> "pid:start" | null
 *   requestReadiness({ attemptId, nonce })    readReadinessLog() -> logcat threadtime text
 *   installed() -> { package, version_name, version_code }       sleep(ms)
 * Returns { capture, callback, readiness, signInSummary, ok, loginEnd }.
 */
export async function runPreloginLogin({ attempt, device, readinessTries = 6, readinessWaitMs = 2000 }) {
  validateAttempt(attempt);
  const out = { capture: null, callback: { status: "unknown", reason: "not_reached" }, readiness: { status: "unknown", reason: "not_reached" }, signInSummary: null, ok: false, loginEnd: null };
  const capturesBefore = device.captureCount();
  const recorderStoppedBefore = await device.stopRecorder();
  if (!recorderStoppedBefore) {
    out.capture = assessCaptureWindow({ recorderStoppedBefore });
    return out;
  }
  try {
    if (!(await device.enableReceiver())) {
      out.readiness = { status: "unknown", reason: "receipt_writer_not_enabled" };
      return out;
    }
    out.signInSummary = await device.runSignIn([...SIGN_IN_FLAGS]);
    out.callback = assessCallback(out.signInSummary, attempt.target);
    if (out.callback.status !== "ok") return out;
    await device.selectServer(attempt.target.server_name);
    const installed = await device.installed();
    for (let i = 0; i < readinessTries; i += 1) {
      const processBefore = await device.appProcess();
      await device.requestReadiness({ attemptId: attempt.attempt_id, nonce: attempt.nonce });
      await device.sleep(readinessWaitMs);
      const lines = parseReadinessLog(await device.readReadinessLog());
      const processAfter = await device.appProcess();
      out.readiness = assessReadiness({ lines, attempt, processBefore, processAfter, installed });
      // The app may still be applying the server choice: ask again (same attempt, same nonce).
      if (!["no_server_selected", "receipt_missing"].includes(out.readiness.reason)) break;
    }
    out.loginEnd = out.readiness.facts?.receipt_stamp ?? null;
    return out;
  } finally {
    out.capture ??= assessCaptureWindow({
      recorderStoppedBefore,
      recorderAbsentAfter: !(await device.recorderRunning()),
      signInSummary: out.signInSummary,
      harnessCaptures: device.captureCount() - capturesBefore,
    });
    out.ok = out.capture.status === "off" && out.callback.status === "ok" && out.readiness.status === "ok" && Boolean(out.loginEnd);
  }
}

/**
 * The attempt's cleanup: the app's own Sign out (release_auto_login --phase sign-out), then the
 * readiness receiver disabled again. Always runs, whatever the login did.
 *   device: runSignOut() -> summary.json, disableReceiver() -> bool (read back disabled)
 */
export async function runPreloginCleanup({ signInSummary, device }) {
  let signOutSummary = null;
  let receiverDisabled = false;
  try {
    signOutSummary = await device.runSignOut();
  } catch {}
  try {
    receiverDisabled = (await device.disableReceiver()) === true;
  } catch {}
  return assessCleanup({ signInSummary, signOutSummary, receiverDisabled });
}

/** Secret values (from env) must not appear in any receipt; fails closed. */
export function receiptsLeak(texts, secrets) {
  const list = secrets.filter((s) => typeof s === "string" && s.length >= 4);
  return texts.some((t) => list.some((s) => String(t).includes(s)));
}
