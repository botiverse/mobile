import assert from "node:assert/strict";
import test from "node:test";
import {
  callbackSchemeFor,
  createRedactor,
  parseCredentialFile,
  runOutcome,
  summarizeCleanup,
  validateCallbackUrl,
  validateCapturedLoginUrl,
} from "./release_auto_login_lib.mjs";

const web = { webUrl: "https://raft-app-staging.botiverse.dev" };

test("captured login URL must be the app-login page of the expected origin with one request id", () => {
  assert.deepEqual(validateCapturedLoginUrl("https://raft-app-staging.botiverse.dev/login/app?request=r1", web), {
    requestId: "r1",
    host: "raft-app-staging.botiverse.dev",
  });
  assert.throws(() => validateCapturedLoginUrl("https://evil.example/login/app?request=r1", web), /expected raft-app-staging/);
  assert.throws(() => validateCapturedLoginUrl("http://raft-app-staging.botiverse.dev/login/app?request=r1", web), /not https/);
  assert.throws(() => validateCapturedLoginUrl("https://raft-app-staging.botiverse.dev/login?request=r1", web), /path/);
  assert.throws(() => validateCapturedLoginUrl("https://raft-app-staging.botiverse.dev/login/app?request=a&request=b", web), /exactly one/);
});

test("callback must return to this app's scheme for the same request with only requestId and code", () => {
  assert.deepEqual(validateCallbackUrl("raft://login/callback?requestId=r1&code=abc_DEF-1", { scheme: "raft", requestId: "r1" }), { code: "abc_DEF-1" });
  assert.throws(() => validateCallbackUrl("raft://login/callback?requestId=r2&code=c", { scheme: "raft", requestId: "r1" }), /state mismatch/);
  assert.throws(() => validateCallbackUrl("raft-alpha://login/callback?requestId=r1&code=c", { scheme: "raft", requestId: "r1" }), /not raft:/);
  assert.throws(() => validateCallbackUrl("raft://login/callback?requestId=r1&error=denied", { scheme: "raft", requestId: "r1" }), /exactly requestId and code/);
  assert.throws(() => validateCallbackUrl("raft://login/callback?requestId=r1&code=c'x", { scheme: "raft", requestId: "r1" }), /unsafe/);
});

test("redactor masks registered secrets and callback-shaped text", () => {
  const redact = createRedactor();
  redact.add("hunter2-password");
  assert.equal(redact("pw hunter2-password"), "pw [masked]");
  assert.equal(redact("go raft://login/callback?requestId=x&code=y now"), "go raft://login/callback?[masked] now");
  assert.equal(redact('{"accessToken":"t"}'), '{"accessToken":"[masked]"}');
});

test("credential file and callback scheme", () => {
  assert.deepEqual(parseCredentialFile("qa@example.com\nsecret"), { email: "qa@example.com", password: "secret" });
  assert.throws(() => parseCredentialFile("nope"));
  assert.equal(callbackSchemeFor("build.raft.app"), "raft");
  assert.equal(callbackSchemeFor("build.raft.app.alpha"), "raft-alpha");
});

const cleanAll = {
  capture_file: "deleted",
  browser: "restored",
  capture_browser: "uninstalled",
  web_session: "signed_out",
  app_session: "signed_out_via_app",
};

test("cleanup aggregation: every step must report an ok status", () => {
  assert.deepEqual(summarizeCleanup(cleanAll), { ok: true, failures: [] });
  assert.deepEqual(
    summarizeCleanup({ capture_file: "not_applicable", browser: "not_changed", capture_browser: "kept", web_session: "not_opened", app_session: "not_requested" }),
    { ok: true, failures: [] },
  );
  assert.deepEqual(summarizeCleanup({ ...cleanAll, browser: "failed", app_session: "local_cleared_server_unconfirmed" }), {
    ok: false,
    failures: [
      { step: "browser", status: "failed" },
      { step: "app_session", status: "local_cleared_server_unconfirmed" },
    ],
  });
  const { web_session, ...missing } = cleanAll;
  assert.deepEqual(summarizeCleanup(missing).failures, [{ step: "web_session", status: "missing" }]);
});

test("strict cleanup turns a cleanup failure into a non-zero exit; sign-in failure always fails", () => {
  const dirty = { ...cleanAll, capture_browser: "failed" };
  assert.equal(runOutcome({ signedIn: true, cleanup: cleanAll, strict: true }).exitCode, 0);
  assert.deepEqual(
    [runOutcome({ signedIn: true, cleanup: dirty, strict: true }).result, runOutcome({ signedIn: true, cleanup: dirty, strict: true }).exitCode],
    ["cleanup_failed", 1],
  );
  const lenient = runOutcome({ signedIn: true, cleanup: dirty, strict: false });
  assert.deepEqual([lenient.result, lenient.exitCode, lenient.cleanupOk], ["signed_in", 0, false]);
  assert.deepEqual([runOutcome({ signedIn: false, cleanup: cleanAll, strict: false }).result, runOutcome({ signedIn: false, cleanup: cleanAll, strict: false }).exitCode], ["failed", 1]);
});
