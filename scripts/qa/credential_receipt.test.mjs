import assert from "node:assert/strict";
import test from "node:test";
import { credentialReceipt, extractStableSubject } from "./credential_receipt.mjs";

test("extracts only one canonical authority-tagged stable principal", () => {
  assert.equal(extractStableSubject(["07-23 I/SlockSharedAuth: shared_auth_session QA_CREDENTIAL_IDENTITY version=1 principalId=user-123 authGeneration=7"]), "user-123");
  assert.equal(extractStableSubject([
    JSON.stringify({ eventMessage: "[SlockSharedAuth][info] shared_auth_session QA_CREDENTIAL_IDENTITY version=1 principalId=user-1 authGeneration=7" }),
  ]), "user-1");
  assert.equal(extractStableSubject(["I/SlockSharedAuth: shared_auth_session QA_CREDENTIAL_IDENTITY version=1 principalId=user-1 authGeneration=7",
    "I/SlockSharedAuth: shared_auth_session QA_CREDENTIAL_IDENTITY version=1 principalId=user-2 authGeneration=8"]), null);
  assert.equal(extractStableSubject(['{"principalId":"attacker"}', "rendered message principalId=attacker"]), null);
  assert.equal(extractStableSubject(["I/MessageRenderer: [SlockSharedAuth][info] shared_auth_session QA_CREDENTIAL_IDENTITY version=1 principalId=attacker authGeneration=7"]), null,
    "the authority logger category/prefix is mandatory");
  assert.equal(extractStableSubject(["I/SlockSharedAuth: shared_auth_session QA_CREDENTIAL_IDENTITY version=2 principalId=user-1 authGeneration=7"]), null);
  assert.equal(extractStableSubject(["I/SlockSharedAuth: shared_auth_session QA_CREDENTIAL_IDENTITY version=1 principalId=user-1 authGeneration=0"]), null);
  assert.equal(extractStableSubject(["userId=some-other-user"]), null);
});

test("credential receipt includes email and nullable subject without password material", () => {
  assert.deepEqual(credentialReceipt("qa@example.test", [
    "I/SlockSharedAuth: shared_auth_session QA_CREDENTIAL_IDENTITY version=1 principalId=user-123 authGeneration=42",
  ]), {
    login_email: "qa@example.test",
    stable_subject: "user-123",
  });
  assert.deepEqual(credentialReceipt("qa@example.test"), {
    login_email: "qa@example.test",
    stable_subject: null,
  });
  assert.equal(credentialReceipt(" qa@example.test"), null);
});
