// Pure helpers for release_auto_login.mjs (no device, no network), so the security checks
// (captured URL shape, callback binding, masking) are testable on their own.

export const ENVIRONMENTS = {
  production: { webUrl: "https://app.raft.build", apiBaseUrl: "https://api.raft.build/api/" },
  staging: { webUrl: "https://raft-app-staging.botiverse.dev", apiBaseUrl: "https://api-aws-staging.botiverse.dev/api/" },
};

/** Testbed `qa-account get --out` file: line 1 the email, line 2 the password. */
export function parseCredentialFile(text) {
  const [email = "", password = ""] = String(text).split(/\r?\n/);
  if (!email.trim().includes("@") || !password) throw new Error("credential file must hold the email on line 1 and the password on line 2");
  return { email: email.trim(), password };
}

/**
 * Masks every registered secret, plus anything shaped like an auth code, request id or token
 * in a query string or JSON body. Secrets are added as they become known (the request id once
 * captured, tokens once issued).
 */
export function createRedactor() {
  const secrets = new Set();
  const redact = (value) => {
    let text = String(value);
    for (const secret of [...secrets].sort((a, b) => b.length - a.length)) text = text.split(secret).join("[masked]");
    return text
      .replace(/\b(code|request|requestId|state|token|access_token|refresh_token)=(?!\d{3}\b)[^&\s"']+/gi, "$1=[masked]")
      .replace(/"(accessToken|refreshToken|password|code|redirectUrl)"\s*:\s*"[^"]*"/g, '"$1":"[masked]"')
      .replace(/\b(raft(?:-[a-z]+)?):\/\/login\/callback\S*/gi, "$1://login/callback?[masked]");
  };
  redact.add = (secret) => {
    if (typeof secret === "string" && secret.length >= 4) {
      secrets.add(secret);
      secrets.add(encodeURIComponent(secret));
    }
  };
  return redact;
}

/**
 * The URL the app opened in the "browser" must be the app-login page of the expected web origin
 * with exactly one `request` id: `<web>/login/app?request=<id>` (slock routes/appLogin.ts). The
 * script never builds this URL; it only checks the one the app produced with its own PKCE.
 */
export function validateCapturedLoginUrl(raw, { webUrl }) {
  let url;
  try {
    url = new URL(String(raw).trim());
  } catch {
    throw new Error("captured URL is not a URL");
  }
  const expected = new URL(webUrl);
  if (url.protocol !== "https:") throw new Error("captured URL is not https");
  if (url.origin !== expected.origin) throw new Error(`captured URL is on ${url.host}, expected ${expected.host}`);
  if (url.pathname !== "/login/app") throw new Error(`captured URL path is ${url.pathname}, expected /login/app`);
  const requests = url.searchParams.getAll("request");
  if (requests.length !== 1 || !requests[0]) throw new Error("captured URL must carry exactly one request id");
  return { requestId: requests[0], host: url.host };
}

/**
 * The approval's redirect must go back to this app's login callback for the SAME request the app
 * started (the request id is the flow's state: the app keeps its PKCE verifier against it), with a
 * code and nothing else. Mirrors the app's own strict parser (AppLoginCallbacks.kt).
 */
export function validateCallbackUrl(raw, { scheme, requestId }) {
  const prefix = `${scheme}://login/callback?`;
  const text = String(raw ?? "");
  if (!text.startsWith(prefix)) throw new Error(`redirect is not ${scheme}://login/callback`);
  if (text.includes("#")) throw new Error("redirect carries a fragment");
  if (/['"\s\\`$;&|<>]/.test(text.slice(prefix.length).replace(/&/g, ""))) throw new Error("redirect carries characters unsafe for a shell argument");
  const params = new URLSearchParams(text.slice(prefix.length));
  const names = [...params.keys()];
  if (names.length !== 2 || new Set(names).size !== 2 || !params.get("requestId") || !params.get("code")) {
    throw new Error("redirect must carry exactly requestId and code");
  }
  if (params.get("requestId") !== requestId) throw new Error("redirect requestId does not match the request the app started (state mismatch)");
  return { code: params.get("code") };
}

/** The callback scheme a package's build type registers (androidApp build.gradle.kts). */
export function callbackSchemeFor(packageId) {
  const suffix = /^build\.raft\.app\.([a-z]+)$/.exec(packageId)?.[1];
  return suffix ? `raft-${suffix}` : "raft";
}

/**
 * Each cleanup step records one status; these are the ones that count as cleaned up. Anything
 * else (including a missing step) is a cleanup failure.
 *   capture_file    – the captured login URL file on the device
 *   browser         – default browser role restored / browsers disabled for the run re-enabled
 *   capture_browser – the QA capture browser package
 *   web_session     – the web session the script opened to approve the login
 *   app_session     – the app's own session: app_reported_server_logout_2xx only when the app
 *                     signed out through its own UI and, from its own process after that tap,
 *                     logged POST auth/logout answered 2xx and result=success (assessAppLogout).
 *                     That is the app's report, not proof the server revoked that session.
 *                     not_requested (no --revoke-app-session), local_cleared_server_unconfirmed
 *                     (pm clear, or no / conflicting evidence) leave a session possibly live on
 *                     the server: a cleanup failure, so --strict-cleanup fails the run.
 *                     not_signed_in: the app never got a session (the run fails on sign-in anyway).
 */
export const CLEANUP_OK = {
  capture_file: ["deleted", "not_applicable"],
  browser: ["restored", "not_changed"],
  capture_browser: ["uninstalled", "kept", "not_installed_by_run"],
  web_session: ["signed_out", "not_opened"],
  app_session: ["app_reported_server_logout_2xx", "not_signed_in"],
};

/**
 * `--defer-app-session` (the release pre-login, scripts/qa/release_prelogin.mjs): the app must stay
 * signed in for the Testbed case that follows, so its session is signed out by that attempt's own
 * cleanup (`--phase sign-out`). Only then does this status count as done for the sign-in run.
 */
export const DEFERRED_APP_SESSION = "deferred_to_case_cleanup";

/** Which cleanup steps failed, and whether the run may still exit 0. */
export function summarizeCleanup(cleanup = {}, { deferAppSession = false } = {}) {
  const okFor = (key) => (key === "app_session" && deferAppSession ? [...CLEANUP_OK[key], DEFERRED_APP_SESSION] : CLEANUP_OK[key]);
  const failures = Object.keys(CLEANUP_OK)
    .filter((key) => !okFor(key).includes(cleanup[key]))
    .map((key) => ({ step: key, status: cleanup[key] ?? "missing" }));
  return { ok: failures.length === 0, failures };
}

/**
 * The run's result and exit code. Sign-in failing is always a failure. With strict cleanup (the
 * Testbed mode) a signed-in run whose cleanup did not fully succeed also exits non-zero.
 * `phase: "sign-out"` (no sign-in, only the app's own Sign out of a deferred session): "signed_out"
 * when every cleanup step succeeded, else "cleanup_failed".
 */
export function runOutcome({ signedIn, cleanup, strict, deferAppSession = false, phase = "sign-in" }) {
  if (phase === "sign-out") {
    const { ok, failures } = summarizeCleanup(cleanup);
    return { result: ok ? "signed_out" : "cleanup_failed", exitCode: ok ? 0 : 1, cleanupOk: ok, cleanupFailures: failures };
  }
  const { ok, failures } = summarizeCleanup(cleanup, { deferAppSession });
  if (!signedIn) return { result: "failed", exitCode: 1, cleanupOk: ok, cleanupFailures: failures };
  if (!ok && strict) return { result: "cleanup_failed", exitCode: 1, cleanupOk: ok, cleanupFailures: failures };
  return { result: "signed_in", exitCode: 0, cleanupOk: ok, cleanupFailures: failures };
}

/**
 * The app's own verdict on the callback, from its `SlockAppLogin` lines
 * (`app_login_complete result=<r> reason=<why>`, AppLogin.kt: one line per exit of complete()).
 *   exchanged – some line says result=app_shell reason=exchanged (the code is single-use, so a
 *               later duplicate-callback "ignored" line does not undo it)
 *   pending   – the last line is result=retry (the app will try the callback again)
 *   refused   – the last line is any other result (login_error, stale, ignored, …)
 *   absent    – no such line (yet)
 */
export function parseAppLoginComplete(logText) {
  const lines = [...String(logText ?? "").matchAll(/app_login_complete result=(\S+) reason=(\S+)/g)].map((m) => ({ result: m[1], reason: m[2] }));
  if (lines.some((l) => l.result === "app_shell" && l.reason === "exchanged")) return { status: "exchanged", result: "app_shell", reason: "exchanged" };
  const last = lines[lines.length - 1];
  if (!last) return { status: "absent", result: null, reason: null };
  return { status: last.result === "retry" ? "pending" : "refused", result: last.result, reason: last.reason };
}

/**
 * The app's own report of its Sign out's POST /auth/logout, from `logcat -d -v epoch --pid=<pid>`
 * (lines "<epoch> <pid> <tid> <level> <tag>: <message>"). Fails closed: counted only when
 *   - the line is the app's process (pid) and written after the sign-out tap (sinceEpoch);
 *   - AuthDataSource.logout's `auth_logout_api_result result=success` is there, and no other result;
 *   - the HTTP layer's line for exactly `POST` to exactly the route `auth/logout` (the relative
 *     route, `/api/auth/logout`, or a URL whose path is `/api/auth/logout`) answered 2xx, and no
 *     line for that request answered otherwise;
 *   - `auth_logout_cleared serverRevocation=` (when present) says success.
 * What it proves: the app reports the server answered its logout with a 2xx. Not that the server
 * revoked that session (the server answers 200 to an unknown token too): no per-session receipt.
 */
export function assessAppLogout(raw, { pid, sinceEpoch } = {}) {
  const out = { verified: false, reason: "", lines: [], api_results: [], http_statuses: [] };
  if (!pid || !Number.isFinite(sinceEpoch)) return { ...out, reason: "no_app_process_or_tap_time" };
  if (typeof raw !== "string" || !raw.trim()) return { ...out, reason: "no_app_lines" };
  const isLogoutRoute = (p) => {
    const bare = String(p).split(/[?#]/)[0];
    if (bare === "auth/logout" || bare === "/api/auth/logout") return true;
    try { return /^https?:$/.test(new URL(bare).protocol) && new URL(bare).pathname === "/api/auth/logout"; } catch { return false; }
  };
  const revocations = [];
  for (const line of raw.split("\n")) {
    const m = /^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+[VDIWEF]\s+[^:]+:\s?(.*)$/.exec(line);
    if (!m || Number(m[2]) !== Number(pid) || Number(m[1]) < sinceEpoch) continue;
    const msg = m[4];
    const api = /\bauth_logout_api_result result=(\S+)(?:\s+code=(\S+))?/.exec(msg);
    if (api) { out.api_results.push(api[1]); out.lines.push(`auth_logout_api_result result=${api[1]}${api[2] ? ` code=${api[2]}` : ""}`); continue; }
    const cleared = /\bauth_logout_cleared serverRevocation=(\S+)/.exec(msg);
    if (cleared) { revocations.push(cleared[1]); out.lines.push(`auth_logout_cleared serverRevocation=${cleared[1]}`); continue; }
    const field = (k) => new RegExp(`(?:^|\\s)${k}=(\\S+)`).exec(msg)?.[1] ?? null;
    const method = field("method");
    const route = field("path");
    const status = Number(field("status") ?? field("statusCode") ?? NaN);
    if (method === "POST" && route && isLogoutRoute(route) && Number.isFinite(status)) {
      out.http_statuses.push(status);
      const trace = field("traceId");
      out.lines.push(`http method=POST route=auth/logout status=${status}${trace ? ` traceId=${trace}` : ""}`);
    }
  }
  if (!out.api_results.length) return { ...out, reason: "no_logout_result_line" };
  if (out.api_results.some((r) => r !== "success")) return { ...out, reason: "logout_result_not_success" };
  if (!out.http_statuses.length) return { ...out, reason: "no_post_auth_logout_line" };
  if (out.http_statuses.some((s) => s < 200 || s >= 300)) return { ...out, reason: "conflicting_http_status" };
  if (revocations.some((r) => r !== "success")) return { ...out, reason: "conflicting_server_revocation" };
  return { ...out, verified: true, reason: "app_reported_2xx" };
}

/**
 * Why the sign-out's logout evidence can't be bound to the app (null when it can): logcat could not
 * be cleared before; not exactly one logout tap; or the app's process ("<pid>:<start time>") was
 * unknown or changed between the start of the sign-out, the tap and the sign-in page (a restart,
 * or a reused pid). Any of these: not verified.
 */
export function logoutProbeProblem({ logcatCleared, taps, before, atTap, after } = {}) {
  if (!logcatCleared) return "logcat_clear_failed";
  if (taps !== 1) return `logout_taps_${taps ?? 0}`;
  const valid = (p) => typeof p === "string" && /^\d+:\d+$/.test(p);
  if (!valid(before) || !valid(atTap) || !valid(after)) return "app_process_unknown";
  if (before !== atTap || atTap !== after) return "app_process_changed";
  return null;
}
