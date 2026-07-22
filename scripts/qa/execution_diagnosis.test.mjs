import assert from "node:assert/strict";
import test from "node:test";
import fs from "node:fs";
import { effectiveExecutorVerdict, normalizeSelfDiagnosis, repeatedActionKey } from "./execution_diagnosis.mjs";

test("unverified, navigation, fixture, budget, and infra conclusions cannot become product fail", () => {
  for (const classification of ["navigation_contract", "fixture", "budget", "infra"]) {
    const diagnosis = normalizeSelfDiagnosis({
      classification, target_surface_reached: classification === "budget",
      first_blocked_step: "open-target", missing_guidance: [], first_product_failure: null,
    });
    assert.ok(diagnosis);
    assert.equal(effectiveExecutorVerdict("fail", diagnosis), "harness");
  }
  assert.equal(effectiveExecutorVerdict("fail", null), "harness");
});

test("product fail requires reached target and a concrete contradictory action", () => {
  const diagnosis = normalizeSelfDiagnosis({
    classification: "product", target_surface_reached: true, first_blocked_step: "save",
    missing_guidance: [], first_product_failure: {
      action: "tap Save", expected: "renamed header", observed: "old header", evidence_step: 19,
    },
  });
  assert.ok(diagnosis);
  assert.equal(effectiveExecutorVerdict("fail", diagnosis), "fail");
  assert.equal(effectiveExecutorVerdict("pass", diagnosis), "pass");
  assert.equal(effectiveExecutorVerdict("fail", { ...diagnosis, target_surface_reached: false }), "harness");
});

test("repeated action key groups tiny coordinate jitter but separates actual actions", () => {
  assert.equal(repeatedActionKey({ action: "tap", x: 101, y: 199 }), repeatedActionKey({ action: "tap", x: 109, y: 205 }));
  assert.notEqual(repeatedActionKey({ action: "tap", x: 101, y: 199 }), repeatedActionKey({ action: "swipe", direction: "up" }));
});

test("Android and iOS workflows pass frozen minutes and no-progress budgets to the executor", () => {
  for (const path of [".github/workflows/qa-acceptance.yml", ".github/workflows/qa-acceptance-ios.yml"]) {
    const source = fs.readFileSync(new URL(`../../${path}`, import.meta.url), "utf8");
    assert.match(source, /max_minutes:/);
    assert.match(source, /no_progress_turns:/);
    assert.match(source, /--max-minutes "\$QA_MAX_MINUTES"/);
    assert.match(source, /--no-progress-turns "\$QA_NO_PROGRESS_TURNS"/);
  }
});

test("failure diagnosis workflow uses an independent reviewer token and the shared capacity path", () => {
  const workflow = fs.readFileSync(new URL("../../.github/workflows/qa-failure-diagnosis.yml", import.meta.url), "utf8");
  const agent = fs.readFileSync(new URL("./failure_diagnosis_agent.mjs", import.meta.url), "utf8");
  assert.match(workflow, /STAMP_REVIEW_TOKEN/);
  assert.match(agent, /\/api\/diagnoses\/\$\{runCaseId\}\/model-capacity\/acquire/);
  assert.match(agent, /finally \{\s*await releaseCapacity\(leaseId\)/);
  assert.match(agent, /\/api\/diagnoses\/\$\{runCaseId\}/);
});
