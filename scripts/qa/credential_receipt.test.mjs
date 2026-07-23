import assert from "node:assert/strict";
import test from "node:test";
import { credentialReceipt, extractStableSubject } from "./credential_receipt.mjs";

test("extracts one stable principal from JSON or structured app logs", () => {
  assert.equal(extractStableSubject(['{"principalId":"user-123"}', "session principalId=user-123 ready"]), "user-123");
  assert.equal(extractStableSubject(["principalId=user-1", "principalId=user-2"]), null);
  assert.equal(extractStableSubject(["userId=some-other-user"]), null);
});

test("credential receipt includes email and nullable subject without password material", () => {
  assert.deepEqual(credentialReceipt("qa@example.test", ["principalId=user-123"]), {
    login_email: "qa@example.test",
    stable_subject: "user-123",
  });
  assert.deepEqual(credentialReceipt("qa@example.test"), {
    login_email: "qa@example.test",
    stable_subject: null,
  });
  assert.equal(credentialReceipt(" qa@example.test"), null);
});
