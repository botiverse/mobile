import assert from "node:assert/strict";
import test from "node:test";
import {
  callbackSchemeFor,
  createRedactor,
  parseCredentialFile,
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
