import assert from "node:assert/strict";
import test from "node:test";
import {
  canonicalJson,
  parseReadyLine,
  readyPayload,
  selectApkSigningCertificateSha256,
  sha256Digest,
  validateLoginResponse,
} from "./mobile_login_bootstrap_lib.mjs";

const digestA = `sha256:${"a".repeat(64)}`;
const digestB = `sha256:${"b".repeat(64)}`;
const rotatedLegacyDigest = "sha256:b4919381d8f9cede4f8eb8f6d132f6dfe0d6d15bf6d1f63057d5eba045161a45";
const rotatedCurrentDigest = "sha256:5f08bbe82a27ccdad26c13523a60b5670d3aa7633e8ed2fd71fa6b5751f9b0b1";

const rotatedSignerOutput = [
  `Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: ${rotatedCurrentDigest.slice(7)}`,
  `Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: ${rotatedLegacyDigest.slice(7)}`,
].join("\n");

function loginResponse() {
  const envelope = {
    version: "qa-bootstrap-login/v1",
    run_case_id: "case-1",
    attempt: 2,
    ci_job_ref: "https://github.com/botiverse/mobile/actions/runs/1",
    principal_id: "user-1",
    server_id: "server-1",
    target_route: "compose_slock_app_shell",
    application_id: "build.raft.app.alpha",
    signing_cert_sha256: digestA,
    boot_generation: digestB,
    user: { id: "user-1", email: "qa@example.test" },
    access_token: "access-secret",
    refresh_token: "refresh-secret",
  };
  return { request_id: "request-1", envelope, envelope_digest: sha256Digest(Buffer.from(canonicalJson(envelope))), expires_at: "soon" };
}

const expected = {
  runCaseId: "case-1",
  ciJobRef: "https://github.com/botiverse/mobile/actions/runs/1",
  applicationId: "build.raft.app.alpha",
  signingCertSha256: digestA,
  bootGeneration: digestB,
};

test("APK signer selection matches the platform signer for each rotated SDK range", () => {
  assert.equal(selectApkSigningCertificateSha256(rotatedSignerOutput, 24), rotatedLegacyDigest);
  assert.equal(selectApkSigningCertificateSha256(rotatedSignerOutput, 32), rotatedLegacyDigest);
  assert.equal(selectApkSigningCertificateSha256(rotatedSignerOutput, 33), rotatedCurrentDigest);
  assert.equal(selectApkSigningCertificateSha256(rotatedSignerOutput, 36), rotatedCurrentDigest);
  assert.equal(
    selectApkSigningCertificateSha256(
      `Signer #1 certificate SHA-256 digest: ${"a".repeat(64)}`,
      34,
    ),
    digestA,
  );
});

test("APK signer selection fails closed for no match, overlap, and extra signers", () => {
  assert.throws(
    () => selectApkSigningCertificateSha256(rotatedSignerOutput, 23),
    /exactly one signing certificate for the Android device SDK/,
  );
  assert.throws(
    () => selectApkSigningCertificateSha256([
      rotatedSignerOutput,
      `Signer (minSdkVersion=30, maxSdkVersion=40) certificate SHA-256 digest: ${"c".repeat(64)}`,
    ].join("\n"), 34),
    /exactly one signing certificate for the Android device SDK/,
  );
  assert.throws(
    () => selectApkSigningCertificateSha256([
      `Signer #1 certificate SHA-256 digest: ${"a".repeat(64)}`,
      `Signer #2 certificate SHA-256 digest: ${"b".repeat(64)}`,
    ].join("\n"), 34),
    /exactly one signing certificate for the Android device SDK/,
  );
  assert.throws(
    () => selectApkSigningCertificateSha256([
      `Signer (minSdkVersion=33) certificate SHA-256 digest: ${"a".repeat(64)}`,
      `Signer #1 certificate SHA-256 digest: ${"b".repeat(64)}`,
    ].join("\n"), 34),
    /signer certificate record is invalid/,
  );
});

test("canonical login envelope validates exact keys, digest and frozen binding", () => {
  const result = validateLoginResponse(loginResponse(), expected);
  assert.equal(result.envelope.access_token, "access-secret");
  result.bytes.fill(0);
  assert.throws(() => validateLoginResponse({ ...loginResponse(), envelope_digest: digestA }, expected), /digest mismatch/);
  assert.throws(() => validateLoginResponse({ ...loginResponse(), envelope: { ...loginResponse().envelope, extra: true } }, expected), /keys/);
  assert.throws(
    () => validateLoginResponse(loginResponse(), { ...expected, signingCertSha256: digestB }),
    /binding is invalid/,
  );
});

test("READY parser rejects another attempt and emits token-free Stamp payload", () => {
  const line = [
    "I/SlockQaBootstrap: QA_BOOTSTRAP_READY",
    `envelopeDigest=${loginResponse().envelope_digest}`,
    "runCaseId=case-1", "attempt=2", `ciJobRef=${expected.ciJobRef}`,
    "principalId=user-1", "serverId=server-1", "sessionGeneration=session-1",
    "route=compose_slock_app_shell", "applicationId=build.raft.app.alpha",
    `bootGeneration=${digestB}`, "pid=42", "processInstanceId=process-1", "processGeneration=100",
    "deviceReadyElapsedRealtimeNanos=200", `readyReceiptDigest=${loginResponse().envelope_digest}`,
    `runtimeEnvelopeDigest=${digestA}`, `routeReadyProofDigest=${digestB}`,
  ].join(" ");
  const login = validateLoginResponse(loginResponse(), expected);
  login.bytes.fill(0);
  const ready = parseReadyLine(line, {
    envelopeDigest: login.envelopeDigest, runCaseId: "case-1", attempt: 2, ciJobRef: expected.ciJobRef,
    principalId: "user-1", serverId: "server-1", targetRoute: "compose_slock_app_shell",
    applicationId: "build.raft.app.alpha", bootGeneration: digestB,
  });
  assert.ok(ready);
  const payload = readyPayload(login, ready);
  assert.equal(payload.runtime_envelope.pid, 42);
  assert.equal(JSON.stringify(payload).includes("access-secret"), false);
  assert.equal(parseReadyLine(line.replace("attempt=2", "attempt=3"), { ...expected, envelopeDigest: login.envelopeDigest, attempt: 2 }), null);
});
