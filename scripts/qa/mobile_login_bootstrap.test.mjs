import assert from "node:assert/strict";
import test from "node:test";
import {
  canonicalJson,
  parseReadyLine,
  readyPayload,
  sha256Digest,
  validateLoginResponse,
} from "./mobile_login_bootstrap_lib.mjs";

const digestA = `sha256:${"a".repeat(64)}`;
const digestB = `sha256:${"b".repeat(64)}`;

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

test("canonical login envelope validates exact keys, digest and frozen binding", () => {
  const result = validateLoginResponse(loginResponse(), expected);
  assert.equal(result.envelope.access_token, "access-secret");
  result.bytes.fill(0);
  assert.throws(() => validateLoginResponse({ ...loginResponse(), envelope_digest: digestA }, expected), /digest mismatch/);
  assert.throws(() => validateLoginResponse({ ...loginResponse(), envelope: { ...loginResponse().envelope, extra: true } }, expected), /keys/);
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
