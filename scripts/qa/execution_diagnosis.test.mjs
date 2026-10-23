import assert from "node:assert/strict";
import test from "node:test";
import {
  bindDiagnosisToLedger, effectiveExecutorVerdict, normalizeExecutionLimits,
  normalizeSelfDiagnosis, observationFingerprint, repeatedActionKey,
} from "./execution_diagnosis.mjs";

test("unverified, navigation, fixture, budget, and infra conclusions cannot become product fail", () => {
  for (const classification of ["navigation_contract", "fixture", "budget", "infra"]) {
    const diagnosis = normalizeSelfDiagnosis({
      classification, confidence: "high", target_surface_reached: classification === "budget",
      surface_evidence_steps: classification === "budget" ? [4] : [],
      first_blocked_step: "open-target", missing_guidance: [], first_product_failure: null,
    });
    assert.ok(diagnosis);
    assert.equal(effectiveExecutorVerdict("fail", diagnosis), "harness");
  }
  assert.equal(effectiveExecutorVerdict("fail", null), "harness");
});

test("product fail requires reached target and a concrete contradictory action", () => {
  const diagnosis = normalizeSelfDiagnosis({
    classification: "product", confidence: "high", target_surface_reached: true, surface_evidence_steps: [18], surface_checkpoint_step: 18, first_blocked_step: "save",
    missing_guidance: [], first_product_failure: {
      action: "tap Save", expected: "renamed header", observed: "old header", action_step: 19, evidence_steps: [19],
    },
  });
  assert.ok(diagnosis);
  const bound = bindDiagnosisToLedger(diagnosis, {
    actions: new Map([[19, { performed: true, identity_sha256: "a".repeat(64), action_type: "tap", safe_summary: "tap:aaaaaaaaaaaa" }]]),
    observations: new Map([[18, "b".repeat(64)], [19, "c".repeat(64)]]),
    checkpoints: new Map([[18, { milestone: "target-open", screenshot_sha256: "b".repeat(64) }]]),
  });
  assert.equal(effectiveExecutorVerdict("fail", bound), "fail");
  assert.equal(bound.first_product_failure.action, "tap:aaaaaaaaaaaa");
  assert.equal(effectiveExecutorVerdict("pass", bound), "harness");
  assert.equal(effectiveExecutorVerdict("pass", null), "pass");
  assert.equal(effectiveExecutorVerdict("fail", { ...bound, target_surface_reached: false }), "harness");
  assert.equal(effectiveExecutorVerdict("fail", bindDiagnosisToLedger(diagnosis, {
    actions: new Map(), observations: new Map([[18, "b".repeat(64)], [19, "c".repeat(64)]]), checkpoints: new Map([[18, { milestone: "target-open", screenshot_sha256: "b".repeat(64) }]]),
  })), "harness");
  assert.equal(effectiveExecutorVerdict("fail", bindDiagnosisToLedger({ ...diagnosis, first_product_failure: { ...diagnosis.first_product_failure, action_step: 999, evidence_steps: [999] } }, {
    actions: new Map([[19, { performed: true, identity_sha256: "a".repeat(64), action_type: "tap", safe_summary: "tap:aaaaaaaaaaaa" }]]), observations: new Map([[18, "b".repeat(64)], [19, "c".repeat(64)]]), checkpoints: new Map([[18, { milestone: "target-open", screenshot_sha256: "b".repeat(64) }]]),
  })), "harness");
  assert.equal(effectiveExecutorVerdict("fail", bindDiagnosisToLedger({ ...diagnosis, first_product_failure: { ...diagnosis.first_product_failure, evidence_steps: [18] } }, {
    actions: new Map([[19, { performed: true, identity_sha256: "a".repeat(64), action_type: "tap", safe_summary: "tap:aaaaaaaaaaaa" }]]), observations: new Map([[18, "b".repeat(64)], [19, "c".repeat(64)]]), checkpoints: new Map([[18, { milestone: "target-open", screenshot_sha256: "b".repeat(64) }]]),
  })), "harness");
  assert.equal(effectiveExecutorVerdict("fail", bindDiagnosisToLedger(diagnosis, {
    actions: new Map([[19, { performed: false, identity_sha256: "a".repeat(64), action_type: "tap", safe_summary: "tap:aaaaaaaaaaaa" }]]), observations: new Map([[18, "b".repeat(64)], [19, "c".repeat(64)]]), checkpoints: new Map([[18, { milestone: "target-open", screenshot_sha256: "b".repeat(64) }]]),
  })), "harness");
  const unrelatedAction = bindDiagnosisToLedger(diagnosis, {
    actions: new Map([[19, { performed: true, identity_sha256: "d".repeat(64), action_type: "wait", safe_summary: "wait:dddddddddddd" }]]),
    observations: new Map([[18, "b".repeat(64)], [19, "c".repeat(64)]]),
    checkpoints: new Map([[18, { milestone: "target-open", screenshot_sha256: "b".repeat(64) }]]),
  });
  assert.equal(effectiveExecutorVerdict("fail", unrelatedAction), "harness");
  assert.equal(effectiveExecutorVerdict("fail", bindDiagnosisToLedger(diagnosis, {
    actions: new Map([[19, { performed: true, identity_sha256: "a".repeat(64), action_type: "tap", safe_summary: "tap:aaaaaaaaaaaa" }]]),
    observations: new Map([[18, "b".repeat(64)], [19, "c".repeat(64)]]), checkpoints: new Map(),
  })), "harness");
});

test("repeated action key groups tiny coordinate jitter but separates actual actions", () => {
  assert.equal(repeatedActionKey({ action: "tap", x: 101, y: 199 }), repeatedActionKey({ action: "tap", x: 109, y: 205 }));
  assert.notEqual(repeatedActionKey({ action: "tap", x: 101, y: 199 }), repeatedActionKey({ action: "swipe", direction: "up" }));
  assert.notEqual(repeatedActionKey({ action: "text", value: "first" }), repeatedActionKey({ action: "text", value: "second" }));
  assert.notEqual(repeatedActionKey({ action: "key", name: "back" }), repeatedActionKey({ action: "key", name: "enter" }));
  assert.notEqual(repeatedActionKey({ action: "wait", seconds: 1 }), repeatedActionKey({ action: "wait", seconds: 5 }));
  assert.notEqual(repeatedActionKey({ action: "open_link", url: "raft://one" }), repeatedActionKey({ action: "open_link", url: "raft://two" }));
  assert.notEqual(repeatedActionKey({ action: "note", milestone: "opened", note: "first" }), repeatedActionKey({ action: "note", milestone: "saved", note: "second" }));
});

test("no-progress fingerprint resets on changed UI or feedback", () => {
  const actionKey = repeatedActionKey({ action: "swipe", direction: "up" });
  const first = observationFingerprint({ actionKey, screenshotSha256: "a", feedback: "ok" });
  assert.equal(first, observationFingerprint({ actionKey, screenshotSha256: "a", feedback: "ok" }));
  assert.notEqual(first, observationFingerprint({ actionKey, screenshotSha256: "b", feedback: "ok" }));
  assert.notEqual(first, observationFingerprint({ actionKey, screenshotSha256: "a", feedback: "new rows" }));
});

test("execution budgets fail closed on malformed and out-of-range values", () => {
  assert.deepEqual(normalizeExecutionLimits({ maxTurns: 20, maxMinutes: 10, noProgressTurns: 3 }), { maxTurns: 20, maxMinutes: 10, noProgressTurns: 3 });
  assert.deepEqual(normalizeExecutionLimits({ maxTurns: 160, maxMinutes: 90, noProgressTurns: 30 }), { maxTurns: 160, maxMinutes: 90, noProgressTurns: 30 });
  for (const invalid of [
    { maxTurns: "NaN", maxMinutes: 20, noProgressTurns: 8 },
    { maxTurns: 0, maxMinutes: 20, noProgressTurns: 8 },
    { maxTurns: 45.5, maxMinutes: 20, noProgressTurns: 8 },
    { maxTurns: 45, maxMinutes: 999, noProgressTurns: 8 },
    { maxTurns: 45, maxMinutes: 20, noProgressTurns: 2 },
    { maxTurns: Infinity, maxMinutes: 20, noProgressTurns: 8 },
    { maxTurns: -1, maxMinutes: 20, noProgressTurns: 8 },
  ]) assert.equal(normalizeExecutionLimits(invalid), null);
});
