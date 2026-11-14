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
