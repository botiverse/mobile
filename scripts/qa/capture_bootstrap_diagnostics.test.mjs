import test from "node:test";
import assert from "node:assert/strict";
import { filterDiagnosticTags, redactDiagnosticsText } from "./capture_bootstrap_diagnostics.mjs";

test("redacts credentials, bootstrap codes and session ids but keeps diagnostics", () => {
  const input = [
    "11-13 10:00:00.000  1234  1250 I okhttp: Authorization: Bearer abc.def.ghi",
    "11-13 10:00:00.000  1234  1250 I okhttp: Cookie: sid=s3cr3t; other=1",
    'body {"accessToken":"tok-123","refresh_token":"r-456","password":"hunter2"}',
    "url https://x.test/cb?code=ok&auth_code=AC-789&bootstrapCode=QB-1",
    "QA_BOOTSTRAP_READY ticketDigest=sha256:aa sessionGeneration=sha256:bb runCaseId=rc-1",
    "jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl",
    "QA_BOOTSTRAP_FAILURE stage=shell_gate code=recovery_required hasCachedSession=true",
  ].join("\n");
  const out = redactDiagnosticsText(input);
  for (const secret of ["abc.def.ghi", "s3cr3t", "tok-123", "r-456", "hunter2", "AC-789", "QB-1", "sha256:aa", "sha256:bb", "eyJhbGci"]) {
    assert.equal(out.includes(secret), false, `${secret} leaked:\n${out}`);
  }
  assert.match(out, /code=recovery_required/);
  assert.match(out, /hasCachedSession=true/);
  assert.match(out, /runCaseId=rc-1/);
  assert.match(out, /code=ok/);
});

test("keeps only bootstrap-relevant threadtime tags", () => {
  const out = filterDiagnosticTags([
    "11-13 10:00:00.000  1234  1250 I SlockQaBootstrap: QA_BOOTSTRAP_STAGE stage=x",
    "11-13 10:00:00.000  1234  1250 E AndroidRuntime: FATAL EXCEPTION",
    "11-13 10:00:00.000  1234  1250 I Other: noise",
  ].join("\n"));
  assert.equal(out.split("\n").length, 2);
  assert.doesNotMatch(out, /noise/);
});
