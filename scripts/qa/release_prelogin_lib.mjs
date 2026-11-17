// Pure rules + device-independent orchestration for the release pre-login
// (scripts/qa/release_prelogin.mjs; contract: docs/qa-acceptance/release-prelogin-contract.md).
// Nothing here touches adb, the network or a credential: the device and the sign-in run are
// injected, so every rule is testable with fake values (release_prelogin.test.mjs).
//
// Fail-closed rule everywhere: a reading that is missing, failed or ambiguous is "unknown", never
// a safe value.
import crypto from "node:crypto";

export const SPEC_CONTRACT = "testbed-release-prelogin-spec/v1";
export const ATTEMPT_CONTRACT = "raft-release-prelogin-attempt/v2";
export const ALLOWLIST_CONTRACT = "raft-release-prelogin-allowlist/v2";
/** What the app writes (QaReleaseReadinessReceiver.kt, qaRelease candidate only). */
export const RECEIPT_CONTRACT = "raft-qa-release-readiness/v2";
export const RECEIVER_CLASS = "build.raft.app.core.data.auth.QaReleaseReadinessReceiver";
export const READINESS_TAG = "RaftQaReadiness";
export const READINESS_MARKER = "RAFT_QA_READINESS";
export const EXTRA_ATTEMPT_ID = "qaAttemptId";
export const EXTRA_NONCE = "qaNonce";
export const EXTRA_REQUEST_ID = "qaRequestId";
export const USER_REF_DOMAIN = "raft-qa-readiness-user/v1";

/**
 * Case classes that are NEVER switched to the release pre-login, whatever any list says: their
 * subject is the login itself, the session's end, the install, more than one server or platform,
 * the bootstrap path, or making a server.
 */
export const NEVER_CLASSES = ["login-ui", "logout", "upgrade", "cross-server", "bootstrap-canary", "server-create"];
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
const HEX64 = /^[0-9a-f]{64}$/;
const REQUEST_ID = /^[0-9a-f]{32}$/;
const PROCESS = /^\d+:\d+$/;
const THREADTIME_LINE = /^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+\d+\s+[VDIWEF]\s+([^:]+?)\s*:\s?(.*)$/;
const SENSITIVE_KEY = /token|cookie|password|secret|email|session_key|authorization|user_id$/i;

export const sha256Hex = (data) => crypto.createHash("sha256").update(data).digest("hex");

/**
 * Nonce-bound minimisation of a user id: sha256(UTF-8 "raft-qa-readiness-user/v1" NUL nonce NUL
 * userId), lowercase hex. Same bytes as qaReadinessUserRef (Kotlin). Not anonymisation (the id
 * may be guessable) and not a signature (the nonce is not secret).
 */
export function userRef(nonce, userId) {
  return sha256Hex(`${USER_REF_DOMAIN}\u0000${nonce}\u0000${userId}`);
}

/** JSON with object keys sorted at every level: the bytes Testbed hashes for spec_sha256. */
export function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((k) => `${JSON.stringify(k)}:${canonicalJson(value[k])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

// ---------- case metadata (strict, fail closed) ----------

const unquote = (v) => {
  const t = v.trim();
  if (/^"[^"\\]*"$/.test(t) || /^'[^']*'$/.test(t)) return t.slice(1, -1);
  return t;
};

/**
 * `id`, `tags` and `auth_fixture` of a case YAML. Supports exactly: plain or quoted scalars, a flow
 * list `[a, "b"]` or a block list (`- a` lines). Anything else for these keys (anchors, aliases,
 * YAML tags, multi-line scalars, duplicates) makes `problem` non-null; callers refuse then.
 */
export function caseMetaFromYaml(text) {
  const lines = String(text ?? "").split(/\r?\n/);
  const seen = {};
  let problem = null;
  const out = { id: null, tags: [], authFixture: null, problem: null };
  for (let i = 0; i < lines.length; i += 1) {
    const m = /^(id|tags|auth_fixture)\s*:(.*)$/.exec(lines[i]);
    if (!m) continue;
    const [, key, rest] = m;
    if (seen[key]) problem ??= `duplicate ${key}`;
    seen[key] = true;
    const value = rest.replace(/\s+#.*$/, "").trim();
    if (/[&*!|>]/.test(value.slice(0, 1))) { problem ??= `${key} uses an unsupported YAML form`; continue; }
    if (key === "tags") {
      if (value.startsWith("[")) {
        if (!value.endsWith("]")) { problem ??= "tags flow list not closed on its line"; continue; }
        out.tags = value.slice(1, -1).split(",").map(unquote).filter(Boolean);
      } else if (value === "") {
        const items = [];
        while (i + 1 < lines.length && /^\s+-\s+/.test(lines[i + 1])) {
          i += 1;
          const item = lines[i].replace(/^\s+-\s+/, "").replace(/\s+#.*$/, "");
          if (/^[&*!|>]/.test(item.trim())) problem ??= "tags item uses an unsupported YAML form";
          items.push(unquote(item));
        }
        if (i + 1 < lines.length && /^\s+\S/.test(lines[i + 1]) && !/^\s+-/.test(lines[i + 1])) problem ??= "tags block not a plain list";
        out.tags = items;
      } else {
        problem ??= "tags is not a list";
      }
    } else {
      if (value === "") { problem ??= `${key} is empty or multi-line`; continue; }
      out[key === "id" ? "id" : "authFixture"] = unquote(value);
    }
  }
  out.problem = problem;
  return out;
}

// ---------- the Testbed-frozen spec ----------

/**
 * The frozen spec Testbed hands to the workflow (contract `testbed-release-prelogin-spec/v1`). It
 * is only accepted when the Stamp claim of the same run case returns
 * `release_prelogin.spec_sha256 == sha256(canonicalJson(spec))` (see preloginEligibility): the
 * dispatch input alone is caller-asserted, not frozen.
 */
export function validateSpec(spec) {
  const s = spec && typeof spec === "object" && !Array.isArray(spec) ? spec : {};
  const p = [];
  const str = (v) => typeof v === "string" && v.trim().length > 0;
  if (s.contract !== SPEC_CONTRACT) p.push("contract");
  if (!str(s.run_case_id)) p.push("run_case_id");
  if (!ATTEMPT_ID.test(s.attempt?.attempt_id ?? "")) p.push("attempt.attempt_id");
  if (!NONCE.test(s.attempt?.nonce ?? "")) p.push("attempt.nonce");
  const c = s.case ?? {};
  if (!str(c.id) || !/^[a-z0-9][a-z0-9-]{0,80}$/.test(c.id)) p.push("case.id");
  if (!Number.isInteger(c.revision) || c.revision < 1) p.push("case.revision");
  if (c.platform !== "android") p.push("case.platform");
  if (!HEX64.test(c.case_sha256 ?? "")) p.push("case.case_sha256");
  if (!HEX64.test(c.goal_sha256 ?? "")) p.push("case.goal_sha256");
  if (c.auth_mode !== "release-prelogin") p.push("case.auth_mode");
  const t = s.target ?? {};
  if (!["staging", "production"].includes(t.env)) p.push("target.env");
  for (const k of ["account_user_id", "server_id", "server_slug", "server_name"]) if (!str(t[k])) p.push(`target.${k}`);
  const a = s.artifact ?? {};
  if (!HEX64.test(a.sha256 ?? "")) p.push("artifact.sha256");
  if (!str(a.package_id) || !str(a.version_name) || !Number.isInteger(a.version_code)) p.push("artifact.identity");
  const walk = (o, at) => {
    for (const [k, v] of Object.entries(o ?? {})) {
      if (SENSITIVE_KEY.test(k) && k !== "account_user_id") p.push(`${at}${k} must not be in the spec`);
      if (v && typeof v === "object") walk(v, `${at}${k}.`);
    }
  };
  walk(s, "");
  if (p.length) throw new Error(`frozen spec invalid: ${p.join(", ")}`);
  return s;
}

/**
 * Whether THIS attempt may use the release pre-login. Testbed decides (its claim binds the frozen
 * spec); every check here can only refuse. All must hold:
 *  - the spec is well-formed and the claim of the same run case returns its exact sha256;
 *  - the run case, goal (sha256 of the goal the agent gets) and artifact (sha256 + badging of the
 *    installed APK, and the frozen artifact_sha256 input) are the ones in the spec;
 *  - the APK is known NOT debuggable (Alpha/debuggable builds keep their fixtures) and the case is
 *    not on the bootstrap path;
 *  - the case file with that id exists, its bytes hash to the spec's case_sha256, its metadata
 *    parses, and it is in no never class;
 *  - the Mobile allow-list (narrowing only) lists that exact case revision + sha256 as compatible.
 */
export function preloginEligibility({ spec, claimRelease, runCaseId, goal, artifactSha256Input, build, bootstrapRequired, caseBytes, allowList }) {
  const refuse = (reason, cls = null) => ({ eligible: false, reason, ...(cls ? { class: cls } : {}) });
  let s;
  try {
    s = validateSpec(spec);
  } catch (error) {
    return refuse(`spec_invalid: ${error.message.replace(/^frozen spec invalid: /, "")}`);
  }
  if (!claimRelease || typeof claimRelease !== "object") return refuse("spec_not_bound_by_testbed_claim");
  if (claimRelease.spec_sha256 !== sha256Hex(canonicalJson(s)) || claimRelease.attempt_id !== s.attempt.attempt_id) return refuse("claim_binding_mismatch");
  if (!runCaseId || runCaseId !== s.run_case_id) return refuse("run_case_mismatch");
  if (typeof goal !== "string" || !goal || sha256Hex(goal) !== s.case.goal_sha256) return refuse("goal_mismatch");
  if (bootstrapRequired !== false) return refuse(bootstrapRequired === true ? "bootstrap_case" : "bootstrap_mode_unknown");
  if (!build || build.ok !== true) return refuse("build_identity_unknown");
  if (build.debuggable !== false) return refuse(build.debuggable === true ? "debuggable_build_keeps_alpha_fixtures" : "build_kind_unknown");
  if (build.sha256 !== s.artifact.sha256 || artifactSha256Input !== s.artifact.sha256) return refuse("artifact_sha256_mismatch");
  if (build.package_id !== s.artifact.package_id || build.version_name !== s.artifact.version_name || Number(build.version_code) !== s.artifact.version_code) return refuse("artifact_identity_mismatch");
  if (caseBytes == null) return refuse("case_file_missing");
  if (sha256Hex(caseBytes) !== s.case.case_sha256) return refuse("case_bytes_mismatch");
  const meta = caseMetaFromYaml(Buffer.isBuffer(caseBytes) ? caseBytes.toString("utf8") : caseBytes);
  if (meta.problem) return refuse(`case_meta_unparseable: ${meta.problem}`);
  if (meta.id !== s.case.id) return refuse("case_id_mismatch");
  if (!allowList || allowList.contract !== ALLOWLIST_CONTRACT || typeof allowList.cases !== "object") return refuse("allow_list_missing_or_malformed");
  const own = (o, k) => (o && Object.hasOwn(o, k) ? o[k] : null);
  const never = own(allowList.never, s.case.id);
  if (never) return refuse("never_class", never);
  for (const tag of meta.tags) {
    const cls = TAG_CLASS[String(tag).toLowerCase()];
    if (cls) return refuse("never_class", cls);
  }
  if (meta.authFixture === "none" || meta.authFixture === null) return refuse("case_runs_signed_out_or_unknown");
  const entry = own(allowList.cases, s.case.id);
  if (!entry) return refuse("not_allow_listed");
  if (entry.intent !== "ordinary" || NEVER_CLASSES.includes(entry.class)) return refuse("intent_not_ordinary");
  if (entry.revision !== s.case.revision || entry.case_sha256 !== s.case.case_sha256 || entry.platform !== "android") return refuse("allow_list_pins_other_revision");
  if (entry.status !== "compatible") return refuse(`allow_list_status_${entry.status ?? "missing"}`);
  return { eligible: true, reason: "testbed_frozen_and_mobile_compatible" };
}

/** The attempt record: Testbed's ids, bound to this workflow run/rerun. No randomness here. */
export function attemptFromSpec(spec, { workflow = {} } = {}) {
  const s = validateSpec(spec);
  return {
    contract: ATTEMPT_CONTRACT,
    attempt_id: s.attempt.attempt_id,
    nonce: s.attempt.nonce,
    run_case_id: s.run_case_id,
    spec_sha256: sha256Hex(canonicalJson(s)),
    case: { id: s.case.id, revision: s.case.revision, platform: s.case.platform, case_sha256: s.case.case_sha256, goal_sha256: s.case.goal_sha256 },
    target: { env: s.target.env, account_user_ref: userRef(s.attempt.nonce, s.target.account_user_id), server_id: s.target.server_id, server_slug: s.target.server_slug, server_name: s.target.server_name },
    artifact: { ...s.artifact },
    workflow: { run_id: workflow.runId ?? null, run_attempt: workflow.runAttempt ?? null },
  };
}

export function validateAttempt(attempt) {
  if (!attempt || attempt.contract !== ATTEMPT_CONTRACT) throw new Error("attempt record malformed");
  if (!ATTEMPT_ID.test(attempt.attempt_id ?? "") || !NONCE.test(attempt.nonce ?? "")) throw new Error("attempt id / nonce malformed");
  if (!HEX64.test(attempt.target?.account_user_ref ?? "") || !attempt.target?.server_id || !attempt.target?.server_slug) throw new Error("attempt target malformed");
  return attempt;
}

// ---------- device readings ----------

/**
 * A component's state from `dumpsys package <pkg>`: "enabled" / "disabled" when listed under the
 * user's enabledComponents: / disabledComponents:, "default" when the package section is there but
 * does not list it, "unknown" when the dump has no section for the package (failed / empty read).
 */
export function componentStateFromDumpsys(dump, className, pkg = "build.raft.app") {
  const text = String(dump ?? "");
  if (!text.includes(`Package [${pkg}]`)) return "unknown";
  let current = null;
  let indent = -1;
  let state = "default";
  for (const line of text.split("\n")) {
    const lead = line.length - line.trimStart().length;
    const t = line.trim();
    if (t === "enabledComponents:" || t === "disabledComponents:") {
      current = t.startsWith("enabled") ? "enabled" : "disabled";
      indent = lead;
      continue;
    }
    if (current && t && lead > indent) {
      if (t === className) state = current;
      continue;
    }
    current = null;
  }
  return state;
}

/** Installed identity from `dumpsys package <pkg>`; ok only with name AND code read. */
export function installedFromDumpsys(dump, pkg) {
  const text = String(dump ?? "");
  const name = /versionName=(\S+)/.exec(text)?.[1] ?? null;
  const code = /versionCode=(\d+)/.exec(text)?.[1];
  const ok = text.includes(`Package [${pkg}]`) && Boolean(name) && code !== undefined;
  return { ok, package: pkg, version_name: name, version_code: code === undefined ? null : Number(code) };
}

// ---------- receipts ----------

/** The app's readiness lines from `logcat -d -v threadtime -s RaftQaReadiness:I`: [{ stamp, pid, receipt }]. */
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
 * The app + server readiness receipt. "ok" only when the app's OWN line answers THIS request
 * (attempt id + nonce + this request's id), was written by the current app process (same pid AND
 * /proc start time before and after the request, as the line's writer and in the receipt), reports
 * a signed-in session whose user_ref is the frozen account's, on the frozen server (id AND slug),
 * in the installed build, which must be fully read and equal to the frozen artifact. Anything else
 * is "unknown", with the reason. It is a bounded signal, not anti-forgery attestation.
 */
export function assessReadiness({ lines = [], attempt, requestId, processBefore, processAfter, installed }) {
  const unknown = (reason, extra = {}) => ({ status: "unknown", reason, ...extra });
  if (!REQUEST_ID.test(requestId ?? "")) return unknown("request_id_missing");
  if (!installed || installed.ok !== true) return unknown("installed_build_unknown");
  if (installed.package !== attempt.artifact?.package_id || installed.version_name !== attempt.artifact?.version_name || installed.version_code !== attempt.artifact?.version_code) return unknown("installed_build_not_frozen_artifact");
  if (!PROCESS.test(processBefore ?? "") || !PROCESS.test(processAfter ?? "")) return unknown("app_process_unknown");
  if (processBefore !== processAfter) return unknown("app_process_changed");
  const [pid, startTicks] = processBefore.split(":");
  const forAttempt = lines.filter((l) => l.receipt.attempt_id === attempt.attempt_id && l.receipt.nonce === attempt.nonce);
  if (!forAttempt.length) return unknown(lines.length ? "stale_receipt_other_attempt_or_nonce" : "receipt_missing");
  const answers = forAttempt.filter((l) => l.receipt.request_id === requestId);
  if (!answers.length) return unknown("no_answer_to_this_request");
  if (answers.length > 1) return unknown("ambiguous_answers");
  const { stamp, pid: writer, receipt } = answers[0];
  if (String(writer) !== pid || String(receipt.pid) !== pid || String(receipt.process_start_ticks) !== startTicks) return unknown("receipt_from_other_process");
  const leaked = sensitiveKeys(receipt);
  if (leaked.length) return unknown("receipt_carries_sensitive_field", { fields: leaked });
  if (receipt.contract !== RECEIPT_CONTRACT) return unknown("receipt_contract_mismatch");
  const facts = {
    user_ref: receipt.user_ref ?? null,
    selected_server_id: receipt.selected_server_id ?? null,
    selected_server_slug: receipt.selected_server_slug ?? null,
    version_name: receipt.version_name ?? null,
    version_code: receipt.version_code ?? null,
    package: receipt.package ?? null,
    process: `${receipt.pid}:${receipt.process_start_ticks}`,
    receipt_stamp: stamp,
  };
  if (receipt.signed_in !== true || !receipt.user_ref) return unknown("app_not_signed_in", { facts });
  if (receipt.user_ref !== attempt.target.account_user_ref) return unknown("account_mismatch", { facts });
  if (!receipt.selected_server_id) return unknown("no_server_selected", { facts });
  if (receipt.selected_server_id !== attempt.target.server_id || receipt.selected_server_slug !== attempt.target.server_slug) return unknown("server_mismatch", { facts });
  if (receipt.package !== installed.package || receipt.version_name !== installed.version_name || Number(receipt.version_code) !== installed.version_code) return unknown("build_mismatch", { facts });
  return { status: "ok", reason: "app_reports_frozen_account_and_server", facts };
}

/**
 * The callback / redirect receipt, from release_auto_login.mjs's summary.json (sign-in phase,
 * --no-capture --defer-app-session --strict-cleanup --account-ref-nonce): the app exchanged the
 * callback itself (its own app_login_complete exchanged line) for the frozen account.
 */
export function assessCallback(summary, attempt) {
  const unknown = (reason) => ({ status: "unknown", reason });
  if (!summary || typeof summary !== "object") return unknown("sign_in_summary_missing");
  if (summary.phase !== "sign-in" || summary.no_capture !== true || summary.defer_app_session !== true || summary.strict_cleanup !== true) return unknown("sign_in_run_not_in_prelogin_mode");
  const verify = (summary.steps ?? []).find((s) => s.name === "verify-signed-in");
  const delivered = (summary.steps ?? []).find((s) => s.name === "deliver-callback-to-app");
  if (summary.result !== "signed_in") return unknown(`sign_in_${summary.result ?? "missing"}`);
  if (delivered?.result !== "pass") return unknown("callback_not_delivered");
  if (verify?.result !== "pass" || verify.detail?.exchange !== "exchanged") return unknown("app_exchange_not_verified");
  if (!summary.account_user_ref || summary.account_user_ref !== attempt.target.account_user_ref) return unknown("account_mismatch");
  return { status: "ok", reason: "app_exchanged_callback", screen: verify.detail?.screen ?? null };
}

/**
 * Data capture during the login window. "off" only when ALL readings exist and are clean: the
 * recorder confirmed stopped before; no recorder seen by the poll during the window (and the poll
 * actually ran); no recorder after; no file written on the device's shared storage since the window
 * started (the scan ran); the harness made no capture call; the sign-in run used --no-capture and
 * wrote 0 screenshots. A missing reading is "unknown".
 */
export function assessCaptureWindow({ recorderStoppedBefore, recorderWatch, recorderRunningAfter, filesWritten, signInSummary, harnessCaptures }) {
  const unknown = (reason, extra = {}) => ({ status: "unknown", reason, ...extra });
  if (recorderStoppedBefore !== true) return unknown("recorder_not_stopped_before_login");
  if (harnessCaptures !== 0) return unknown(Number.isFinite(harnessCaptures) ? "harness_captured_during_login" : "harness_capture_count_unknown");
  if (!recorderWatch || !Number.isInteger(recorderWatch.polls) || recorderWatch.polls < 1 || recorderWatch.failed) return unknown("recorder_watch_missing");
  if (recorderWatch.seen) return unknown("recorder_ran_during_login");
  if (recorderRunningAfter !== false) return unknown(recorderRunningAfter === true ? "recorder_ran_during_login" : "recorder_state_unknown_after");
  if (!Array.isArray(filesWritten)) return unknown("device_file_scan_missing");
  if (filesWritten.length) return unknown("files_written_during_login", { files: filesWritten.slice(0, 20) });
  if (signInSummary && (signInSummary.no_capture !== true || signInSummary.screenshots_written !== 0)) return unknown("sign_in_run_captured");
  return { status: "off", reason: "no_capture_in_login_window" };
}

/**
 * The cleanup receipt (Argus decision 4): three things recorded SEPARATELY.
 *   local_cleanup          what this attempt owns on the device, each item positively read back:
 *                          capture file / browser role / capture browser (sign-in run), the app
 *                          signed out on the device (sign-out run), the receipt writer disabled
 *                          (dumpsys read back "disabled"). status "complete" / "unknown"
 *                          ("not_started" when the login never started).
 *   web_session            the web session the sign-in opened: "signed_out_verified" only when its
 *                          refresh token was refused after logout; "not_opened"; else "unknown".
 *   app_server_revocation  "app_reported_2xx" (the app's own bounded report of a 2xx for its
 *                          POST /auth/logout; NEVER "revoked"/"ok"), "no_app_session", or "unknown".
 */
export function assessCleanup({ loginStarted, signInSummary, signOutSummary, receiverState }) {
  if (loginStarted !== true) {
    return { local_cleanup: { status: "not_started", items: {} }, web_session: "not_opened", app_server_revocation: "no_app_session" };
  }
  const signIn = signInSummary?.cleanup ?? null;
  const signOut = signOutSummary?.cleanup ?? null;
  const callbackSent = (signInSummary?.steps ?? []).some((s) => s.name === "deliver-callback-to-app");
  const items = {
    capture_file: signIn && ["deleted", "not_applicable"].includes(signIn.capture_file) ? "ok" : "unknown",
    browser: signIn && ["restored", "not_changed"].includes(signIn.browser) ? "ok" : "unknown",
    capture_browser: signIn && ["uninstalled", "not_installed_by_run"].includes(signIn.capture_browser) ? "ok" : "unknown",
    app_signed_out_on_device:
      signInSummary && !callbackSent ? "no_app_session"
        : signOut && ["app_reported_server_logout_2xx", "local_cleared_server_unconfirmed"].includes(signOut.app_session) ? "ok" : "unknown",
    receipt_writer: receiverState === "disabled" ? "ok" : "unknown",
  };
  const open = Object.keys(items).filter((k) => !["ok", "no_app_session"].includes(items[k]));
  return {
    local_cleanup: { status: open.length ? "unknown" : "complete", items, unproven: open },
    web_session: signIn?.web_session === "signed_out" ? "signed_out_verified" : signIn?.web_session === "not_opened" ? "not_opened" : "unknown",
    app_server_revocation:
      signInSummary && !callbackSent ? "no_app_session"
        : signOut?.app_session === "app_reported_server_logout_2xx" ? "app_reported_2xx" : "unknown",
    app_logout_evidence: signOut?.app_logout_evidence ?? null,
  };
}

/**
 * The attempt's outcome (Argus decision 5). rc: 0 pass, 1 fail, 3 incomplete / not run.
 *  - not eligible: not_run.
 *  - capture, callback or readiness not good: incomplete (never a product fail or pass).
 *  - the agent's rc and verdict.json must agree (0 + "pass", 1 + "fail"); else incomplete.
 *  - fail on a correctly prepared app: fail, with cleanup statuses as notes.
 *  - pass: only when local cleanup is complete and the web session is signed_out_verified/not_opened;
 *    else incomplete. The app's server-side revocation is a note (app_reported_2xx or unknown),
 *    never a condition and never called revoked.
 */
export function attemptOutcome({ eligibility, capture, callback, readiness, cleanup, agentRc, agentVerdict }) {
  const notes = cleanup ? { local_cleanup: cleanup.local_cleanup?.status ?? "unknown", web_session: cleanup.web_session ?? "unknown", app_server_revocation: cleanup.app_server_revocation ?? "unknown" } : { local_cleanup: "unknown", web_session: "unknown", app_server_revocation: "unknown" };
  const result = (outcome, rc, reason) => ({ outcome, rc, reason, agent_rc: Number.isFinite(agentRc) ? agentRc : null, agent_verdict: agentVerdict ?? null, notes });
  if (!eligibility?.eligible) return result("not_run", 3, `refused: ${eligibility?.reason ?? "no eligibility"}`);
  if (capture?.status !== "off") return result("incomplete", 3, `capture: ${capture?.reason ?? "missing"}`);
  if (callback?.status !== "ok") return result("incomplete", 3, `callback: ${callback?.reason ?? "missing"}`);
  if (readiness?.status !== "ok") return result("incomplete", 3, `readiness: ${readiness?.reason ?? "missing"}`);
  if (agentRc === 1 && agentVerdict === "fail") return result("fail", 1, "agent: product fail after verified preparation");
  if (agentRc !== 0 || agentVerdict !== "pass") return result("incomplete", 3, `agent rc ${Number.isFinite(agentRc) ? agentRc : "missing"} / verdict ${agentVerdict ?? "missing"}`);
  if (notes.local_cleanup !== "complete") return result("incomplete", 3, `pass not accepted: local cleanup ${notes.local_cleanup}`);
  if (!["signed_out_verified", "not_opened"].includes(notes.web_session)) return result("incomplete", 3, `pass not accepted: web session ${notes.web_session}`);
  return result("pass", 0, "all receipts ok, local cleanup complete, agent pass");
}

/**
 * What the workflow's "Report verdict" may use (fail closed): the outcome recomputed from the sealed
 * receipts must equal outcome.json; every receipt must carry the attempt id; the agent rc and
 * verdict.json read NOW must equal what the outcome was computed from. Any mismatch or missing
 * piece: rc 3. Returns { rc, reason }.
 */
export function reportGate({ files, agentRc, agentVerdict }) {
  const bad = (reason) => ({ rc: 3, reason });
  const attempt = files["attempt.json"];
  const outcome = files["outcome.json"];
  if (!outcome) return bad("outcome missing");
  if (!files["eligibility.json"]?.eligible) return outcome.outcome === "not_run" ? { rc: 3, reason: "not run" } : bad("eligibility missing");
  if (!attempt || !ATTEMPT_ID.test(attempt.attempt_id ?? "")) return bad("attempt missing");
  for (const name of ["receipt-capture.json", "receipt-callback.json", "receipt-ready.json", "receipt-cleanup.json", "outcome.json"]) {
    if (files[name] && files[name].attempt_id !== attempt.attempt_id) return bad(`${name} belongs to another attempt`);
  }
  const recomputed = attemptOutcome({
    eligibility: files["eligibility.json"],
    capture: files["receipt-capture.json"],
    callback: files["receipt-callback.json"],
    readiness: files["receipt-ready.json"],
    cleanup: files["receipt-cleanup.json"],
    agentRc,
    agentVerdict,
  });
  if (recomputed.outcome !== outcome.outcome || recomputed.rc !== outcome.rc) return bad(`outcome.json (${outcome.outcome}) does not match the receipts and agent result now (${recomputed.outcome})`);
  return { rc: recomputed.rc, reason: recomputed.reason };
}

// ---------- the upload boundary ----------

/** The only files that may leave the runner from qa-prelogin/. */
export const UPLOAD_FILES = ["eligibility.json", "refusal.json", "attempt.json", "login-started.json", "receipt-capture.json", "receipt-callback.json", "receipt-ready.json", "receipt-cleanup.json", "outcome.json", "outcome.rc"];
const UNSAFE_VALUE = /:\/\/|bearer|eyJ[A-Za-z0-9_-]{8}|code=|token|password|cookie|refresh_|set-cookie|@[a-z0-9-]+\.[a-z]/i;

/**
 * Checks one file for the upload boundary: allow-listed name, small, JSON (or a single rc digit),
 * no sensitive key, no value that looks like a URL, token, cookie, callback, email or long
 * non-hex secret, and none of the given secret values. Returns null when fine, else the reason.
 */
export function uploadProblem(name, text, secrets = []) {
  if (!UPLOAD_FILES.includes(name)) return "not_allow_listed";
  if (text.length > 64 * 1024) return "too_large";
  if (secrets.some((s) => typeof s === "string" && s.length >= 4 && text.includes(s))) return "secret_value";
  if (name === "outcome.rc") return /^[013]\n?$/.test(text) ? null : "rc_malformed";
  let value;
  try { value = JSON.parse(text); } catch { return "not_json"; }
  let problem = null;
  const walk = (v, at) => {
    if (problem) return;
    if (Array.isArray(v)) { v.forEach((x, i) => walk(x, `${at}[${i}]`)); return; }
    if (v && typeof v === "object") {
      for (const [k, x] of Object.entries(v)) {
        if (SENSITIVE_KEY.test(k)) { problem = `sensitive_key ${at}${k}`; return; }
        walk(x, `${at}${k}.`);
      }
      return;
    }
    if (typeof v !== "string") return;
    if (UNSAFE_VALUE.test(v)) { problem = `unsafe_value at ${at}`; return; }
    for (const run of v.match(/[A-Za-z0-9_\-+/=]{24,}/g) ?? []) {
      // Hex ids/hashes and lowercase word-like identifiers are fine; anything else this long is opaque.
      if (!/^(pla_)?[0-9a-f]+$/.test(run) && !/^[a-z]+([-_./][a-z0-9]+)*$/.test(run)) { problem = `opaque_value at ${at}`; return; }
    }
  };
  walk(value, "");
  return problem;
}

// ---------- orchestration (device injected) ----------

/** Flags the sign-in run must get; the login refuses to run it any other way. */
export const SIGN_IN_FLAGS = ["--no-capture", "--defer-app-session", "--strict-cleanup"];

/**
 * The login window for one attempt. `device` (adb in release_prelogin.mjs, fakes in tests):
 *   stopRecorder() -> true|false|null            startWindow() -> bool (device marker written)
 *   watchRecorder() -> { stop(): Promise<{ polls, seen, failed }> }
 *   recorderRunning() -> true|false|null         filesWrittenSince() -> string[] | null
 *   captureCount() -> number                     enableReceiver() -> "enabled"|"disabled"|"default"|"unknown"
 *   runSignIn(flags) -> summary.json | null      selectServer(name)
 *   installed() -> { ok, package, version_name, version_code }
 *   appProcess() -> "pid:start" | null           newRequestId() -> 32 hex
 *   requestReadiness({ attemptId, nonce, requestId })
 *   readReadinessLog() -> logcat threadtime text sleep(ms)
 */
export async function runPreloginLogin({ attempt, device, readinessTries = 6, readinessWaitMs = 2000 }) {
  validateAttempt(attempt);
  const out = { capture: null, callback: { status: "unknown", reason: "not_reached" }, readiness: { status: "unknown", reason: "not_reached" }, signInSummary: null, ok: false, loginEnd: null };
  const capturesBefore = device.captureCount();
  const recorderStoppedBefore = await device.stopRecorder();
  if (recorderStoppedBefore !== true) {
    out.capture = assessCaptureWindow({ recorderStoppedBefore });
    return out;
  }
  if ((await device.startWindow()) !== true) {
    out.capture = { status: "unknown", reason: "window_marker_failed" };
    return out;
  }
  const watch = device.watchRecorder();
  try {
    const state = await device.enableReceiver();
    if (state !== "enabled") {
      out.readiness = { status: "unknown", reason: `receipt_writer_not_enabled (${state})` };
      return out;
    }
    out.signInSummary = await device.runSignIn([...SIGN_IN_FLAGS, "--account-ref-nonce", attempt.nonce]);
    out.callback = assessCallback(out.signInSummary, attempt);
    if (out.callback.status !== "ok") return out;
    await device.selectServer(attempt.target.server_name);
    const installed = await device.installed();
    for (let i = 0; i < readinessTries; i += 1) {
      const requestId = device.newRequestId();
      const processBefore = await device.appProcess();
      await device.requestReadiness({ attemptId: attempt.attempt_id, nonce: attempt.nonce, requestId });
      await device.sleep(readinessWaitMs);
      const lines = parseReadinessLog(await device.readReadinessLog());
      const processAfter = await device.appProcess();
      out.readiness = assessReadiness({ lines, attempt, requestId, processBefore, processAfter, installed });
      // The app may still be applying the server choice: ask again (new request, same attempt).
      if (!["no_server_selected", "receipt_missing", "no_answer_to_this_request"].includes(out.readiness.reason)) break;
    }
    out.loginEnd = out.readiness.facts?.receipt_stamp ?? null;
    return out;
  } finally {
    let recorderWatch = null;
    try { recorderWatch = await watch.stop(); } catch {}
    let filesWritten = null;
    try { filesWritten = await device.filesWrittenSince(); } catch {}
    let recorderRunningAfter = null;
    try { recorderRunningAfter = await device.recorderRunning(); } catch {}
    out.capture ??= assessCaptureWindow({
      recorderStoppedBefore,
      recorderWatch,
      recorderRunningAfter,
      filesWritten,
      signInSummary: out.signInSummary,
      harnessCaptures: device.captureCount() - capturesBefore,
    });
    out.ok = out.capture.status === "off" && out.callback.status === "ok" && out.readiness.status === "ok" && Boolean(out.loginEnd);
  }
}

/**
 * The attempt's cleanup: the app's own Sign out (release_auto_login --phase sign-out), then the
 * readiness receiver disabled again and read back. Always runs, whatever the login did.
 *   device: runSignOut() -> summary.json | null, disableReceiver() -> component state
 */
export async function runPreloginCleanup({ loginStarted, signInSummary, device }) {
  if (loginStarted !== true) return assessCleanup({ loginStarted });
  let signOutSummary = null;
  let receiverState = "unknown";
  try { signOutSummary = await device.runSignOut(); } catch {}
  try { receiverState = await device.disableReceiver(); } catch {}
  return assessCleanup({ loginStarted, signInSummary, signOutSummary, receiverState });
}

/** Secret values (from env) must not appear in any receipt; fails closed. */
export function receiptsLeak(texts, secrets) {
  const list = secrets.filter((s) => typeof s === "string" && s.length >= 4);
  return texts.some((t) => list.some((s) => String(t).includes(s)));
}
