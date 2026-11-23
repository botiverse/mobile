// Frozen per-attempt specification & execution budget binding tests
// (#raft-mobile-reconcile:#212, #257; botiverse/testbed@93f821e2
// docs/case-model.md#reading-the-specification-for-a-checked-android-attempt).
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import {
  ATTEMPT_SPEC_CONTRACT_VERSION,
  AttemptSpecError,
  canonicalAttemptSpecDocument,
  fetchAttemptSpecification,
  platformForAppSlug,
  sha256Utf8,
  verifyAttemptSpecification,
} from "./review_attempt_spec.mjs";
import {
  CLAIM_SPEC_BINDING_CONTRACT,
  ClaimSpecBindingError,
  extractClaimSpecBinding,
  verifyClaimSpecBinding,
} from "./claim_spec_binding.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REVIEW_AGENT = path.join(HERE, "review_agent.mjs");
const STAMP_REPORT = path.join(HERE, "stamp_report.sh");
const CLAIM_BINDING_CLI = path.join(HERE, "claim_spec_binding.mjs");

// Minimal deterministic YAML/JSON stub: handles both JSON specs and our CRLF
// YAML fixture without requiring npm yaml to be installed in test env.
const parseFixtureYaml = (text) => {
  const trimmed = String(text).trim();
  if (trimmed.startsWith("{")) return JSON.parse(trimmed);
  const idMatch = trimmed.match(/^id:\s*(\S+)/m);
  const assertions = [...trimmed.matchAll(/-\s+id:\s*([a-z0-9_-]+)/gi)].map((m) => ({ id: m[1] }));
  return { ...(idMatch ? { id: idMatch[1] } : {}), assertions };
};

function makeFrozenSpec({
  runId = "run-257",
  runCaseId = "rc-1",
  caseId = "settings-open",
  caseRevision = 1,
  attempt = 1,
  platform = "android",
  fullYaml = "id: settings-open\r\ngoal: 原规格\r\nassertions:\r\n  - id: settings-visible\r\nandroid:\r\n  steps: [{goal: Open settings}]\r\nios:\r\n  steps: [{goal: Open iOS settings}]\r\n",
  platformSliceYaml = "id: settings-open\r\ngoal: 原规格\r\nassertions:\r\n  - id: settings-visible\r\nsteps:\r\n  - goal: Open settings\r\n",
  renderedGoal = "Verify settings open on Android\nAssertion settings-visible must hold.",
  executionBudget = { max_turns: 45, max_minutes: 40, no_progress_turns: 8 },
} = {}) {
  const doc = {
    contract_version: ATTEMPT_SPEC_CONTRACT_VERSION,
    case_id: caseId,
    case_revision: caseRevision,
    platform,
    full_yaml: fullYaml,
    full_yaml_sha256: sha256Utf8(fullYaml),
    platform_slice_yaml: platformSliceYaml,
    platform_slice_sha256: sha256Utf8(platformSliceYaml),
    rendered_goal: renderedGoal,
    rendered_goal_sha256: sha256Utf8(renderedGoal),
  };
  return {
    ...doc,
    run_id: runId,
    run_case_id: runCaseId,
    attempt,
    binding_status: "verified",
    spec_sha256: sha256Utf8(canonicalAttemptSpecDocument(doc)),
    execution_budget: executionBudget,
  };
}

test("verifyAttemptSpecification validates CRLF bytes, digests, identity, budget, and platform-slice assertions", () => {
  // Login acceptance protected-password case: Android slice replaces assertions
  // from the shared layer. Reviewer must extract assertions from platform_slice_yaml.
  const fullYaml = [
    "id: login-email-password",
    "assertions:",
    "  - id: shared-only-assertion",
    "android:",
    "  login_acceptance: protected-password",
    "  assertions:",
    "    - id: home-channel-list-visible",
    "  steps: [{goal: Check home}]",
    "ios:",
    "  steps: [{goal: Follow iOS flow}]",
    "",
  ].join("\r\n");
  const sliceYaml = [
    "id: login-email-password",
    "login_acceptance: protected-password",
    "assertions:",
    "  - id: home-channel-list-visible",
    "steps:",
    "  - goal: Check home",
    "",
  ].join("\r\n");
  const payload = makeFrozenSpec({
    runId: "run-login",
    runCaseId: "rc-login-2",
    caseId: "login-email-password",
    caseRevision: 4,
    attempt: 2,
    platform: "android",
    fullYaml,
    platformSliceYaml: sliceYaml,
    renderedGoal: "Inspect only the signed-in app\r\nVerify home-channel-list-visible",
    executionBudget: { max_turns: 30, max_minutes: 25, no_progress_turns: 6 },
  });

  const verified = verifyAttemptSpecification(
    payload,
    {
      runId: "run-login",
      runCaseId: "rc-login-2",
      caseId: "login-email-password",
      caseRevision: 4,
      attempt: 2,
      platform: "android",
      reportSpecSha256: payload.spec_sha256,
      reportExecutionBudgetJson: JSON.stringify(payload.execution_budget),
    },
    parseFixtureYaml,
  );
  assert.equal(verified.status, "verified");
  assert.equal(verified.full_yaml, fullYaml, "CRLF preserved verbatim");
  assert.equal(verified.platform_slice_yaml, sliceYaml, "slice CRLF preserved verbatim");
  assert.deepEqual(verified.assertion_ids, ["home-channel-list-visible"]);
  assert.deepEqual(verified.execution_budget, { max_turns: 30, max_minutes: 25, no_progress_turns: 6 });
  assert.equal(verified.spec_sha256, payload.spec_sha256);
});

test("verifyAttemptSpecification fails closed on identity, digest, line-ending, or budget tampering", () => {
  const base = makeFrozenSpec();
  const expected = {
    runId: "run-257",
    runCaseId: "rc-1",
    caseId: "settings-open",
    caseRevision: 1,
    attempt: 1,
    platform: "android",
    reportSpecSha256: base.spec_sha256,
    reportExecutionBudgetJson: JSON.stringify(base.execution_budget),
  };

  const mutations = [
    ["wrong contract_version", { ...base, contract_version: "testbed-attempt-spec/v2" }, /contract_version/],
    ["unverified binding_status", { ...base, binding_status: "legacy" }, /binding_status/],
    ["wrong run_id", { ...base, run_id: "run-other" }, /run_id mismatch/],
    ["wrong run_case_id", { ...base, run_case_id: "rc-other" }, /run_case_id mismatch/],
    ["wrong case_id", { ...base, case_id: "other-case" }, /case_id mismatch/],
    ["wrong case_revision", { ...base, case_revision: 2 }, /case_revision mismatch/],
    ["wrong attempt", { ...base, attempt: 2 }, /attempt mismatch/],
    ["wrong platform", { ...base, platform: "ios" }, /platform mismatch/],
    ["CRLF normalized to LF in full_yaml", { ...base, full_yaml: base.full_yaml.replaceAll("\r\n", "\n") }, /full_yaml_sha256 mismatch/],
    ["tampered platform_slice_yaml", { ...base, platform_slice_yaml: base.platform_slice_yaml + " " }, /platform_slice_sha256 mismatch/],
    ["tampered rendered_goal", { ...base, rendered_goal: base.rendered_goal + "!" }, /rendered_goal_sha256 mismatch/],
    ["tampered spec_sha256", { ...base, spec_sha256: "f".repeat(64) }, /spec_sha256 mismatch/],
    ["zero max_turns budget", { ...base, execution_budget: { max_turns: 0, max_minutes: 40, no_progress_turns: 8 } }, /execution_budget/],
    ["missing budget", { ...base, execution_budget: null }, /execution_budget/],
  ];
  for (const [label, mutated, pattern] of mutations) {
    assert.throws(() => verifyAttemptSpecification(mutated, expected, parseFixtureYaml), (err) => {
      assert.ok(err instanceof AttemptSpecError, label);
      assert.match(err.message, pattern, label);
      return true;
    }, label);
  }
});

test("verifyAttemptSpecification cross-checks run report spec_sha256, execution_budget_json, and first-dispatch admission digests", () => {
  const base = makeFrozenSpec({
    runId: "run-257",
    runCaseId: "rc-1",
    caseId: "settings-open",
    caseRevision: 1,
    attempt: 1,
    platform: "android",
    executionBudget: { max_turns: 45, max_minutes: 40, no_progress_turns: 8 },
  });
  const matchingAdmission = {
    confirmation_id: "run-257",
    run_case_id: "rc-1",
    attempt: 1,
    selection: {
      case_id: "settings-open",
      case_revision: 1,
      platform: "android",
      case_sha256: base.full_yaml_sha256,
      goal_sha256: base.rendered_goal_sha256,
      spec_sha256: base.spec_sha256,
    },
  };
  const expectedBase = {
    runId: "run-257",
    runCaseId: "rc-1",
    caseId: "settings-open",
    caseRevision: 1,
    attempt: 1,
    platform: "android",
    reportSpecSha256: base.spec_sha256,
    reportExecutionBudgetJson: JSON.stringify(base.execution_budget),
    admission: matchingAdmission,
  };

  // Positive control: report row + first-dispatch admission match /spec.
  const ok = verifyAttemptSpecification(base, expectedBase, parseFixtureYaml);
  assert.equal(ok.spec_sha256, base.spec_sha256);
  assert.deepEqual(ok.execution_budget, { max_turns: 45, max_minutes: 40, no_progress_turns: 8 });

  // Argus review finding #1: report says 45 turns, /spec tampered to 60 turns -> must fail closed.
  const driftedSpecBudget = { ...base, execution_budget: { max_turns: 60, max_minutes: 40, no_progress_turns: 8 } };
  assert.throws(
    () => verifyAttemptSpecification(driftedSpecBudget, expectedBase, parseFixtureYaml),
    /execution_budget disagrees with run report/,
  );

  // Report spec_sha256 or execution_budget_json missing/null/mismatched -> fail closed.
  assert.throws(
    () => verifyAttemptSpecification(base, { ...expectedBase, reportSpecSha256: "0".repeat(64) }, parseFixtureYaml),
    /spec_sha256 disagrees with run report/,
  );
  assert.throws(
    () => verifyAttemptSpecification(base, { ...expectedBase, reportSpecSha256: null }, parseFixtureYaml),
    /run report spec_sha256 is missing, invalid, or unbound/,
  );
  assert.throws(
    () => verifyAttemptSpecification(base, { ...expectedBase, reportSpecSha256: undefined }, parseFixtureYaml),
    /run report spec_sha256 is missing, invalid, or unbound/,
  );
  // Argus review finding #4: omitting report execution_budget_json while tampering /spec 45 -> 60 must fail closed.
  const { reportExecutionBudgetJson: _omitBudget, ...expectedWithoutBudget } = expectedBase;
  assert.throws(
    () => verifyAttemptSpecification(driftedSpecBudget, expectedWithoutBudget, parseFixtureYaml),
    /run report execution_budget_json is missing or empty/,
  );
  assert.throws(
    () => verifyAttemptSpecification(base, { ...expectedBase, reportExecutionBudgetJson: null }, parseFixtureYaml),
    /run report execution_budget_json is missing or empty/,
  );

  // First-dispatch admission digest & identity mismatches -> fail closed.
  const badAdmissions = [
    ["case_sha256 mismatch", { ...matchingAdmission, selection: { ...matchingAdmission.selection, case_sha256: "0".repeat(64) } }, /case_sha256 disagrees/],
    ["goal_sha256 mismatch", { ...matchingAdmission, selection: { ...matchingAdmission.selection, goal_sha256: "0".repeat(64) } }, /goal_sha256 disagrees/],
    ["spec_sha256 mismatch", { ...matchingAdmission, selection: { ...matchingAdmission.selection, spec_sha256: "0".repeat(64) } }, /spec_sha256 disagrees/],
    ["missing spec_sha256", { ...matchingAdmission, selection: { ...matchingAdmission.selection, spec_sha256: undefined } }, /admission\.selection\.spec_sha256/],
    ["wrong confirmation_id", { ...matchingAdmission, confirmation_id: "run-other" }, /confirmation_id mismatch/],
    ["wrong attempt", { ...matchingAdmission, attempt: 2 }, /admission attempt mismatch/],
    ["wrong case_id", { ...matchingAdmission, selection: { ...matchingAdmission.selection, case_id: "other" } }, /admission case_id mismatch/],
    // Argus review finding #5: first-dispatch admission with swapped run_case_id + corrupted case_sha256 must fail closed.
    ["first-dispatch wrong run_case_id + corrupted case_sha256", {
      ...matchingAdmission,
      run_case_id: "rc-other",
      selection: { ...matchingAdmission.selection, case_sha256: "0".repeat(64) },
    }, /admission run_case_id mismatch/],
  ];
  for (const [label, admission, pattern] of badAdmissions) {
    assert.throws(
      () => verifyAttemptSpecification(base, { ...expectedBase, admission }, parseFixtureYaml),
      pattern,
      label,
    );
  }

  // Genuine retry (attempt 2) carries its own run_case_id, spec_sha256, and budget
  // while report.admission still references attempt 1: must verify cleanly.
  const retrySpec = makeFrozenSpec({
    runId: "run-257",
    runCaseId: "rc-2",
    caseId: "settings-open",
    caseRevision: 1,
    attempt: 2,
    platform: "android",
    renderedGoal: "Retry goal on Android for attempt 2",
    executionBudget: { max_turns: 60, max_minutes: 50, no_progress_turns: 10 },
  });
  const verifiedRetry = verifyAttemptSpecification(
    retrySpec,
    {
      runId: "run-257",
      runCaseId: "rc-2",
      caseId: "settings-open",
      caseRevision: 1,
      attempt: 2,
      platform: "android",
      reportSpecSha256: retrySpec.spec_sha256,
      reportExecutionBudgetJson: JSON.stringify(retrySpec.execution_budget),
      admission: matchingAdmission,
    },
    parseFixtureYaml,
  );
  assert.equal(verifiedRetry.status, "verified");
  assert.equal(verifiedRetry.run_case_id, "rc-2");
  assert.equal(verifiedRetry.attempt, 2);
  assert.deepEqual(verifiedRetry.execution_budget, { max_turns: 60, max_minutes: 50, no_progress_turns: 10 });
});

test("fetchAttemptSpecification maps 409 LEGACY_UNBOUND / FROZEN_SPEC_UNAVAILABLE to incomplete and fails closed on 404", async () => {
  const rc = { id: "rc-old", case_id: "settings-open", case_revision: 1, attempt: 1, platform: "android" };
  for (const code of ["LEGACY_UNBOUND", "FROZEN_SPEC_UNAVAILABLE"]) {
    const got = await fetchAttemptSpecification({
      getJson: async (p) => {
        assert.equal(p, "/api/runs/run-old/cases/rc-old/spec");
        return { status: 409, body: { code } };
      },
      runId: "run-old",
      runCase: rc,
      defaultPlatform: "android",
      parseYaml: parseFixtureYaml,
    });
    assert.equal(got.status, "incomplete");
    assert.equal(got.code, code);
    assert.equal(got.http_status, 409);
  }

  for (const [status, body] of [[404, { code: "ATTEMPT_NOT_FOUND" }], [409, { code: "UNKNOWN_CONFLICT" }], [500, { error: "boom" }]]) {
    await assert.rejects(
      fetchAttemptSpecification({
        getJson: async () => ({ status, body }),
        runId: "run-old",
        runCase: rc,
        defaultPlatform: "android",
        parseYaml: parseFixtureYaml,
      }),
      AttemptSpecError,
      `HTTP ${status} must fail closed`,
    );
  }
  assert.deepEqual(
    ["raft-android", "raft-ios", "raft-ohos", "harmony-app"].map(platformForAppSlug),
    ["android", "ios", "ohos", "ohos"],
  );
});

// ---- End-to-end review_agent.mjs + stamp_report.sh + claim_spec_binding -----

const FAKE_REVIEW_NET = `
const fs = require("fs");
const state = JSON.parse(fs.readFileSync(process.env.FAKE_STATE, "utf8"));
const trace = (x) => fs.appendFileSync(process.env.FAKE_TRACE, JSON.stringify(x) + "\\n");
const res = (status, body) => ({ ok: status < 400, status, text: async () => JSON.stringify(body) });
global.fetch = async (url, opts = {}) => {
  const p = new URL(url).pathname, m = opts.method || "GET";
  trace({ req: m + " " + p });
  if (m === "GET" && p.startsWith("/api/cases/")) {
    trace({ forbidden_catalog_call: p });
    return res(500, { error: "catalog endpoint must never be called" });
  }
  if (m === "GET" && p === "/api/runs/" + state.report.run.id) return res(200, state.report);
  const specMatch = p.match(/^\\/api\\/runs\\/([^/]+)\\/cases\\/([^/]+)\\/spec$/);
  if (m === "GET" && specMatch) {
    const entry = state.specs[decodeURIComponent(specMatch[2])];
    if (!entry) return res(404, { code: "ATTEMPT_NOT_FOUND" });
    return res(entry.status, entry.body);
  }
  const ev = p.match(/^\\/api\\/runs\\/[^/]+\\/evidence\\/(.+)$/);
  if (m === "GET" && ev) {
    trace({ evidence: ev[1] });
    return { ok: true, status: 200, arrayBuffer: async () => Buffer.from("bytes:" + ev[1]) };
  }
  if (m === "POST" && p === "/api/runs/" + state.report.run.id + "/attempt-panel-review") {
    const body = JSON.parse(opts.body);
    trace({ post: body, path: p });
    return res(200, { ok: true, review_id: "rv-257", attempt_ids: body.expected_attempt_ids, duplicate: false, votes: { passed: 1, failed: 0 }, finalized: null });
  }
  throw new Error("unexpected " + m + " " + p);
};
`;

const FAKE_REVIEW_MODEL = `
import fs from "node:fs";
export function builtinModels() {
  return {
    getModel: () => ({ id: "fake" }),
    complete: async (_model, ctx) => {
      const prompt = ctx.messages[0].content[0].text;
      const shotLabels = ctx.messages[0].content
        .filter((c) => c.type === "text" && c.text.startsWith("RAW SCREENSHOT"))
        .map((c) => c.text);
      fs.appendFileSync(process.env.FAKE_TRACE, JSON.stringify({ model_prompt: prompt, model_shots: shotLabels }) + "\\n");
      const detail = Object.fromEntries(
        Object.entries(JSON.parse(process.env.FAKE_ASSERTIONS)).map(([caseId, ids]) => [
          caseId,
          Object.fromEntries(ids.map((id) => [id, { ok: true, note: "verified on raw assertion frame" }])),
        ])
      );
      return { stopReason: "stop", usage: { totalTokens: 10 }, content: [{ type: "text", text: JSON.stringify({ verdict: "passed", reason: "ok", detail_json: detail, anomalies: [] }) }] };
    },
  };
}
`;

const FAKE_REVIEW_YAML = `
export function parse(text) {
  const trimmed = String(text).trim();
  if (trimmed.startsWith("{")) return JSON.parse(trimmed);
  const idMatch = trimmed.match(/^id:\\s*(\\S+)/m);
  const assertions = [...trimmed.matchAll(/-\\s+id:\\s*([a-z0-9_-]+)/gi)].map((m) => ({ id: m[1] }));
  return { ...(idMatch ? { id: idMatch[1] } : {}), assertions };
}
`;

function runReviewAgent({ report, specs, assertions = {} }) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "review-spec-"));
  try {
    fs.writeFileSync(path.join(dir, "fake-net.cjs"), FAKE_REVIEW_NET);
    fs.writeFileSync(path.join(dir, "fake-model.mjs"), FAKE_REVIEW_MODEL);
    fs.writeFileSync(path.join(dir, "fake-yaml.mjs"), FAKE_REVIEW_YAML);
    fs.writeFileSync(path.join(dir, "state.json"), JSON.stringify({ report, specs }));
    const trace = path.join(dir, "trace.jsonl");
    fs.writeFileSync(trace, "");
    const out = path.join(dir, "out.json");
    const r = spawnSync(process.execPath, [REVIEW_AGENT, "--run-id", report.run.id, "--reviewer", "agent-review-1", "--out", out], {
      encoding: "utf8",
      env: {
        ...process.env,
        NODE_OPTIONS: `--require=${path.join(dir, "fake-net.cjs")}`,
        STAMP_URL: "http://testbed.invalid",
        STAMP_TOKEN: "fake",
        PI_API_KEY: "fake",
        MINIMAX_API_KEY: "",
        SLOCK_PI_AI_MODULE: path.join(dir, "fake-model.mjs"),
        SLOCK_YAML_MODULE: path.join(dir, "fake-yaml.mjs"),
        FAKE_STATE: path.join(dir, "state.json"),
        FAKE_TRACE: trace,
        FAKE_ASSERTIONS: JSON.stringify(assertions),
      },
    });
    const events = fs.readFileSync(trace, "utf8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l));
    return {
      code: r.status,
      stderr: r.stderr,
      record: fs.existsSync(out) ? JSON.parse(fs.readFileSync(out, "utf8")) : null,
      requests: events.filter((e) => e.req).map((e) => e.req),
      catalogCalls: events.filter((e) => e.forbidden_catalog_call),
      posts: events.filter((e) => e.post).map((e) => e.post),
      modelPrompt: events.find((e) => e.model_prompt)?.model_prompt ?? null,
      modelShots: events.find((e) => e.model_shots)?.model_shots ?? null,
    };
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

test("same-attempt end-to-end: dispatch, ordinary claim, and panel review match on spec, slice, goal digest, budget, and raw assertion frame across catalog upgrade", (t) => {
  const frozen = makeFrozenSpec({
    runId: "run-257",
    runCaseId: "rc-257-a2",
    caseId: "settings-open",
    caseRevision: 1,
    attempt: 2,
    platform: "android",
    executionBudget: { max_turns: 45, max_minutes: 40, no_progress_turns: 8 },
  });

  // 1. Ordinary executor claim via stamp_report.sh + workflow verify step
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "claim-257-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const bin = path.join(root, "bin");
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(bin);
  fs.mkdirSync(runnerTemp);
  fs.mkdirSync(path.join(root, "qa-evidence"));
  const claimReqFile = path.join(root, "claim-req.json");
  const claimReplyFile = path.join(root, "claim-reply.json");
  fs.writeFileSync(claimReplyFile, JSON.stringify({
    claim_token: "lease-257",
    run_case_id: frozen.run_case_id,
    case_id: frozen.case_id,
    auth_bootstrap: { required: false },
    spec_yaml: frozen.platform_slice_yaml,
    spec_sha256: frozen.spec_sha256,
    rendered_goal_sha256: frozen.rendered_goal_sha256,
    guardrails: frozen.execution_budget,
  }));
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
out=""; payload=""
while [ "$#" -gt 0 ]; do
  case "$1" in -o) out="$2"; shift 2;; --data-binary) payload="$2"; shift 2;; *) shift;; esac
done
cp "\${payload#@}" "${claimReqFile}"
cp "${claimReplyFile}" "$out"
`, { mode: 0o755 });

  const claimEnv = {
    ...process.env,
    PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`,
    RUNNER_TEMP: runnerTemp,
    STAMP_RUN_CASE_ID: frozen.run_case_id,
    STAMP_URL: "https://stamp.test",
    STAMP_TOKEN: "test-token",
    GITHUB_SHA: "a".repeat(40),
    GITHUB_RUN_ID: "501",
    GITHUB_REPOSITORY: "botiverse/mobile",
    STAMP_VERIFIED_SOURCE_COMMIT: "a".repeat(40),
    STAMP_VERIFIED_ARTIFACT_SHA256: "b".repeat(64),
    STAMP_VERIFIED_APP_VERSION: "1.28.0",
    STAMP_VERIFIED_VERSION_CODE: "12800005",
    STAMP_VERIFIED_PACKAGE_ID: "build.raft.app",
    STAMP_SEND_RENDERED_GOAL_SHA256: "true",
    QA_GOAL: frozen.rendered_goal,
    QA_MAX_TURNS: "45",
    QA_MAX_MINUTES: "40",
    QA_NO_PROGRESS_TURNS: "8",
  };
  execFileSync("bash", [STAMP_REPORT, "claim", "qa-evidence"], { cwd: root, env: claimEnv });
  const claimReq = JSON.parse(fs.readFileSync(claimReqFile, "utf8"));
  assert.equal(claimReq.rendered_goal_sha256, frozen.rendered_goal_sha256);

  const bindingFile = path.join(runnerTemp, `stamp-claim-spec-${frozen.run_case_id}.json`);
  const verifiedFile = path.join(runnerTemp, `stamp-claim-spec-verified-${frozen.run_case_id}.json`);
  execFileSync(process.execPath, [CLAIM_BINDING_CLI, "verify", "--binding", bindingFile, "--out", verifiedFile], {
    cwd: root,
    env: claimEnv,
  });
  const claimedBinding = JSON.parse(fs.readFileSync(verifiedFile, "utf8"));
  assert.equal(claimedBinding.contract_version, CLAIM_SPEC_BINDING_CONTRACT);
  assert.equal(claimedBinding.bound, true);
  assert.equal(claimedBinding.verified, true);
  assert.equal(claimedBinding.spec_sha256, frozen.spec_sha256);
  assert.equal(claimedBinding.platform_slice_sha256, frozen.platform_slice_sha256);
  assert.equal(claimedBinding.rendered_goal_sha256, frozen.rendered_goal_sha256);
  assert.equal(claimedBinding.received_goal_sha256, frozen.rendered_goal_sha256);
  assert.deepEqual(claimedBinding.execution_budget, frozen.execution_budget);

  // 2. Panel review on the same attempt after catalog moved on (no /api/cases/ calls),
  //    with 16 screenshots so raw assertion frame step-19 must still be selected & labelled.
  const evidence = Array.from({ length: 16 }, (_, idx) => {
    const step = idx + 4; // includes step 19
    const id = `ev-step-${step}`;
    return { id, kind: "screenshot", run_case_id: frozen.run_case_id, step_index: step, seq: idx + 1, captured_at: 1000 + idx, sha256: sha256Utf8(`bytes:${id}`) };
  });
  const report = {
    run: { id: "run-257", status: "in_review", app_slug: "raft-android" },
    admission: {
      confirmation_id: "run-257",
      run_case_id: "rc-257-a1",
      attempt: 1,
      selection: {
        case_id: frozen.case_id,
        case_revision: 1,
        platform: "android",
        case_sha256: frozen.full_yaml_sha256,
        goal_sha256: frozen.rendered_goal_sha256,
        spec_sha256: frozen.spec_sha256,
      },
    },
    cases: [{
      id: frozen.run_case_id,
      case_id: frozen.case_id,
      case_revision: 1,
      attempt: 2,
      platform: "android",
      spec_sha256: frozen.spec_sha256,
      execution_budget_json: JSON.stringify(frozen.execution_budget),
      status: "judging",
      verdict_json: JSON.stringify({ annotations: { assertion: [{ assertion_id: "settings-visible", step: 19, result: "pass" }] } }),
    }],
    review_context: { attempt_ids: [frozen.run_case_id] },
    evidence,
    reviews: [],
  };
  const reviewed = runReviewAgent({
    report,
    specs: { [frozen.run_case_id]: { status: 200, body: frozen } },
    assertions: { "settings-open": ["settings-visible"] },
  });
  assert.equal(reviewed.code, 0, reviewed.stderr);
  assert.deepEqual(reviewed.catalogCalls, [], "reviewer never touches mutable /api/cases/");
  assert.ok(reviewed.requests.includes(`GET /api/runs/run-257/cases/${frozen.run_case_id}/spec`));
  assert.equal(reviewed.record.stage, "submitted");

  // Raw assertion frame step-19 is selected and explicitly labelled.
  const assertionShot = reviewed.modelShots.find((s) => s.includes("evidence=ev-step-19"));
  assert.ok(assertionShot, "raw assertion frame ev-step-19 must be shown to reviewer");
  assert.match(assertionShot, /ASSERTION FRAME for check\(s\): settings-open\/settings-visible/);

  // Reviewer prompt carries frozen slice, dispatched goal, digests, and execution budget.
  assert.match(reviewed.modelPrompt, new RegExp(`spec_sha256=${frozen.spec_sha256}`));
  assert.match(reviewed.modelPrompt, new RegExp(`full_yaml_sha256=${frozen.full_yaml_sha256}`));
  assert.match(reviewed.modelPrompt, new RegExp(`platform_slice_sha256=${frozen.platform_slice_sha256}`));
  assert.match(reviewed.modelPrompt, new RegExp(`rendered_goal_sha256=${frozen.rendered_goal_sha256}`));
  assert.match(reviewed.modelPrompt, /Open settings/);
  assert.doesNotMatch(reviewed.modelPrompt, /Open iOS settings/);
  assert.match(reviewed.modelPrompt, /Verify settings open on Android/);

  // Same-attempt three-way match: dispatch == claim == review.
  const reviewedSpec = reviewed.posts[0].detail_json.attempt_specifications[0];
  assert.equal(reviewedSpec.spec_sha256, claimedBinding.spec_sha256);
  assert.equal(reviewedSpec.platform_slice_sha256, claimedBinding.platform_slice_sha256);
  assert.equal(reviewedSpec.rendered_goal_sha256, claimedBinding.rendered_goal_sha256);
  assert.equal(reviewedSpec.full_yaml_sha256, frozen.full_yaml_sha256);
  assert.deepEqual(reviewedSpec.execution_budget, claimedBinding.execution_budget);
  assert.equal(reviewedSpec.attempt, 2);
  assert.equal(reviewedSpec.case_revision, 1);
});

test("review_agent records evidence_incomplete on 409 LEGACY_UNBOUND / FROZEN_SPEC_UNAVAILABLE and stops with zero new votes while preserving existing votes", () => {
  for (const code of ["LEGACY_UNBOUND", "FROZEN_SPEC_UNAVAILABLE"]) {
    const report = {
      run: { id: "run-legacy", status: "in_review", app_slug: "raft-android" },
      cases: [{ id: "rc-legacy", case_id: "settings-open", case_revision: 1, attempt: 1, platform: "android", spec_sha256: null, status: "judging", verdict_json: "{}" }],
      review_context: { attempt_ids: ["rc-legacy"] },
      evidence: [],
      reviews: [],
    };
    const r = runReviewAgent({
      report,
      specs: { "rc-legacy": { status: 409, body: { code } } },
    });
    assert.equal(r.code, 2, `${code}: ${r.stderr}`);
    assert.deepEqual(r.catalogCalls, [], `${code}: must never fall back to /api/cases/`);
    assert.equal(r.modelPrompt, null, `${code}: must never call model`);
    assert.equal(r.posts.length, 0, `${code}: must submit zero new panel votes`);
    assert.equal(r.record.stage, "evidence_incomplete", code);
    assert.equal(r.record.evidence_incomplete, true, code);
    assert.equal(r.record.vote_submitted, false, code);
    assert.equal(r.record.incomplete_attempt_specs[0].run_case_id, "rc-legacy", code);
    assert.equal(r.record.incomplete_attempt_specs[0].code, code, code);
    assert.match(r.record.incomplete_reason, new RegExp(code));
    assert.match(r.record.incomplete_reason, /rc-legacy/);
    assert.equal(r.record.existing_review, null, code);

    // If an existing vote is already in Testbed, preserve it with zero new POSTs.
    const existing = {
      id: "v-existing",
      role: "panel",
      reviewer: "agent-review-1",
      verdict: "passed",
      comment: "pre-existing vote",
      detail_json: JSON.stringify({ review_input_sha256: "older-hash" }),
      attempt_ids: ["rc-legacy"],
      created_at: 12345,
    };
    const replay = runReviewAgent({
      report: { ...report, reviews: [existing] },
      specs: { "rc-legacy": { status: 409, body: { code } } },
    });
    assert.equal(replay.code, 2, `${code} with existing vote: ${replay.stderr}`);
    assert.equal(replay.posts.length, 0, `${code}: zero new POSTs when existing vote exists`);
    assert.equal(replay.modelPrompt, null, code);
    assert.equal(replay.record.stage, "evidence_incomplete", code);
    assert.equal(replay.record.vote_submitted, false, code);
    assert.equal(replay.record.existing_review.id, "v-existing", code);
  }
});

test("review_agent fails closed on 404, tampered digest, report budget drift, or admission mismatch without model call or voting", () => {
  const valid = makeFrozenSpec({
    runId: "run-bad",
    runCaseId: "rc-bad",
    executionBudget: { max_turns: 45, max_minutes: 40, no_progress_turns: 8 },
  });
  const tampered = { ...valid, full_yaml_sha256: "0".repeat(64) };
  const budgetDrifted = { ...valid, execution_budget: { max_turns: 60, max_minutes: 40, no_progress_turns: 8 } };
  const ev = { id: "ev-1", kind: "screenshot", run_case_id: "rc-bad", step_index: 1, seq: 1, captured_at: 1, sha256: sha256Utf8("bytes:ev-1") };
  const baseReport = {
    run: { id: "run-bad", status: "in_review", app_slug: "raft-android" },
    admission: {
      confirmation_id: "run-bad",
      run_case_id: "rc-bad",
      attempt: 1,
      selection: {
        case_id: valid.case_id,
        case_revision: 1,
        platform: "android",
        case_sha256: valid.full_yaml_sha256,
        goal_sha256: valid.rendered_goal_sha256,
        spec_sha256: valid.spec_sha256,
      },
    },
    cases: [{
      id: "rc-bad",
      case_id: "settings-open",
      case_revision: 1,
      attempt: 1,
      platform: "android",
      spec_sha256: valid.spec_sha256,
      execution_budget_json: JSON.stringify(valid.execution_budget),
      status: "judging",
      verdict_json: "{}",
    }],
    review_context: { attempt_ids: ["rc-bad"] },
    evidence: [ev],
    reviews: [],
  };
  const admissionGoalMismatch = {
    ...baseReport,
    admission: { ...baseReport.admission, selection: { ...baseReport.admission.selection, goal_sha256: "f".repeat(64) } },
  };
  const admissionWrongRunCaseId = {
    ...baseReport,
    admission: {
      ...baseReport.admission,
      run_case_id: "rc-other",
      selection: { ...baseReport.admission.selection, case_sha256: "f".repeat(64) },
    },
  };
  const missingReportBudget = {
    ...baseReport,
    cases: [(() => { const { execution_budget_json: _, ...rest } = baseReport.cases[0]; return rest; })()],
  };
  const missingReportSpecSha = {
    ...baseReport,
    cases: [(() => { const { spec_sha256: _, ...rest } = baseReport.cases[0]; return rest; })()],
  };
  for (const [label, report, specs] of [
    ["404", baseReport, {}],
    ["digest-tamper", baseReport, { "rc-bad": { status: 200, body: tampered } }],
    ["report-budget-drift-45-vs-60", baseReport, { "rc-bad": { status: 200, body: budgetDrifted } }],
    ["report-missing-budget-with-60-spec", missingReportBudget, { "rc-bad": { status: 200, body: budgetDrifted } }],
    ["report-missing-spec-sha", missingReportSpecSha, { "rc-bad": { status: 200, body: valid } }],
    ["admission-goal-mismatch", admissionGoalMismatch, { "rc-bad": { status: 200, body: valid } }],
    ["admission-wrong-run-case-id-on-first-attempt", admissionWrongRunCaseId, { "rc-bad": { status: 200, body: valid } }],
  ]) {
    const r = runReviewAgent({ report, specs });
    assert.notEqual(r.code, 0, label);
    assert.equal(r.posts.length, 0, label);
    assert.equal(r.modelPrompt, null, label);
    assert.equal(r.record.stage, "error", label);
  }
});

test("claim_spec_binding enforces run_case_id parity, fails closed on goal/budget mismatch, preserves claim token, and allows legacy unbound claims", (t) => {
  const frozen = makeFrozenSpec({ executionBudget: { max_turns: 45, max_minutes: 40, no_progress_turns: 8 } });
  const bound = extractClaimSpecBinding({
    run_case_id: "rc-1",
    case_id: "settings-open",
    spec_yaml: frozen.platform_slice_yaml,
    spec_sha256: frozen.spec_sha256,
    rendered_goal_sha256: frozen.rendered_goal_sha256,
    guardrails: frozen.execution_budget,
  }, { runCaseId: "rc-1" });
  assert.equal(bound.bound, true);
  // Argus review finding #2: mismatched or missing run_case_id must fail closed.
  assert.throws(
    () => extractClaimSpecBinding({
      run_case_id: "rc-other",
      case_id: "settings-open",
      spec_yaml: frozen.platform_slice_yaml,
      spec_sha256: frozen.spec_sha256,
      rendered_goal_sha256: frozen.rendered_goal_sha256,
      guardrails: frozen.execution_budget,
    }, { runCaseId: "rc-1" }),
    /run_case_id mismatch/,
  );
  assert.throws(
    () => extractClaimSpecBinding({
      case_id: "settings-open",
      spec_yaml: frozen.platform_slice_yaml,
      spec_sha256: frozen.spec_sha256,
      rendered_goal_sha256: frozen.rendered_goal_sha256,
      guardrails: frozen.execution_budget,
    }, { runCaseId: "rc-1" }),
    /bound claim response is missing run_case_id/,
  );
  assert.throws(
    () => extractClaimSpecBinding({
      run_case_id: "rc-1",
      case_id: "settings-open",
      spec_yaml: frozen.platform_slice_yaml,
      spec_sha256: frozen.spec_sha256,
      rendered_goal_sha256: frozen.rendered_goal_sha256,
      guardrails: frozen.execution_budget,
    }, {}),
    /bound claim requires non-empty requested run_case_id/,
  );
  assert.throws(
    () => extractClaimSpecBinding({ run_case_id: "rc-other", case_id: "settings-open", claim_token: "t" }, { runCaseId: "rc-1" }),
    /run_case_id mismatch/,
  );
  assert.throws(
    () => verifyClaimSpecBinding(bound, { goal: frozen.rendered_goal, maxTurns: "45", maxMinutes: "40", noProgressTurns: "8" }, { requireGoal: true, requireBudget: true }),
    /bound claim verification requires non-empty requested run_case_id/,
  );
  assert.throws(
    () => verifyClaimSpecBinding(bound, { goal: frozen.rendered_goal, maxTurns: "45", maxMinutes: "40", noProgressTurns: "8" }, { requireGoal: true, requireBudget: true, expectedRunCaseId: "   " }),
    /bound claim verification requires non-empty requested run_case_id/,
  );
  assert.throws(
    () => verifyClaimSpecBinding(bound, { goal: frozen.rendered_goal, maxTurns: "45", maxMinutes: "40", noProgressTurns: "8" }, { requireGoal: true, requireBudget: true, expectedRunCaseId: "rc-other" }),
    /run_case_id mismatch/,
  );
  assert.throws(
    () => verifyClaimSpecBinding({ ...bound, run_case_id: null }, { goal: frozen.rendered_goal, maxTurns: "45", maxMinutes: "40", noProgressTurns: "8" }, { requireGoal: true, requireBudget: true, expectedRunCaseId: "rc-1" }),
    /bound claim spec binding is missing run_case_id/,
  );
  assert.throws(
    () => verifyClaimSpecBinding(bound, { goal: "tampered goal", maxTurns: "45", maxMinutes: "40", noProgressTurns: "8" }, { requireGoal: true, requireBudget: true, expectedRunCaseId: "rc-1" }),
    ClaimSpecBindingError,
  );
  assert.throws(
    () => verifyClaimSpecBinding(bound, { goal: frozen.rendered_goal, maxTurns: "80", maxMinutes: "60", noProgressTurns: "10" }, { requireGoal: true, requireBudget: true, expectedRunCaseId: "rc-1" }),
    ClaimSpecBindingError,
  );
  const legacy = extractClaimSpecBinding({ run_case_id: "rc-legacy", case_id: "settings-open", claim_token: "t" }, { runCaseId: "rc-legacy" });
  assert.equal(legacy.bound, false);
  assert.equal(verifyClaimSpecBinding(legacy, { goal: "", maxTurns: "30", maxMinutes: "20", noProgressTurns: "8" }, { requireGoal: true, requireBudget: true, expectedRunCaseId: "rc-legacy" }).mode, "legacy_unbound");

  // End-to-end stamp_report.sh claim: mismatched goal, mismatched run_case_id, or missing bound run_case_id fail closed while keeping claim token.
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "claim-mismatch-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const bin = path.join(root, "bin");
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(bin); fs.mkdirSync(runnerTemp); fs.mkdirSync(path.join(root, "qa-evidence"));
  const claimReqFile = path.join(root, "claim-req.json");
  const replyFile = path.join(root, "reply.json");
  const baseReply = {
    claim_token: "lease-keep",
    run_case_id: "rc-mis",
    case_id: "settings-open",
    auth_bootstrap: { required: false },
    spec_yaml: frozen.platform_slice_yaml,
    spec_sha256: frozen.spec_sha256,
    rendered_goal_sha256: frozen.rendered_goal_sha256,
    guardrails: frozen.execution_budget,
  };
  fs.writeFileSync(path.join(bin, "curl"), `#!/usr/bin/env bash
out=""; payload=""
while [ "$#" -gt 0 ]; do case "$1" in -o) out="$2"; shift 2;; --data-binary) payload="$2"; shift 2;; *) shift;; esac; done
cp "\${payload#@}" "${claimReqFile}"
cp "${replyFile}" "$out"
`, { mode: 0o755 });

  const env = {
    ...process.env,
    PATH: `${bin}:${path.dirname(process.execPath)}:${process.env.PATH}`,
    RUNNER_TEMP: runnerTemp,
    STAMP_RUN_CASE_ID: "rc-mis",
    STAMP_URL: "https://stamp.test",
    STAMP_TOKEN: "test-token",
    GITHUB_SHA: "a".repeat(40),
    GITHUB_RUN_ID: "502",
    GITHUB_REPOSITORY: "botiverse/mobile",
    STAMP_VERIFIED_SOURCE_COMMIT: "a".repeat(40),
    STAMP_VERIFIED_ARTIFACT_SHA256: "b".repeat(64),
    STAMP_VERIFIED_APP_VERSION: "1.28.0",
    STAMP_VERIFIED_VERSION_CODE: "12800005",
    STAMP_VERIFIED_PACKAGE_ID: "build.raft.app",
    QA_GOAL: frozen.rendered_goal,
  };
  for (const [label, replyObj, extraEnv] of [
    ["goal mismatch", baseReply, { QA_GOAL: "different goal" }],
    ["run_case_id mismatch", { ...baseReply, run_case_id: "rc-other" }, {}],
    ["missing run_case_id on bound reply", (() => { const { run_case_id: _, ...rest } = baseReply; return rest; })(), {}],
  ]) {
    fs.writeFileSync(replyFile, JSON.stringify(replyObj));
    assert.throws(() => execFileSync("bash", [STAMP_REPORT, "claim", "qa-evidence"], { cwd: root, env: { ...env, ...extraEnv }, stdio: "pipe" }), label);
    assert.equal(fs.readFileSync(path.join(runnerTemp, "stamp-claim-rc-mis"), "utf8"), "lease-keep", `${label}: claim token preserved`);
    assert.equal(fs.existsSync(path.join(runnerTemp, "stamp-claim-spec-rc-mis.json")), false, `${label}: invalid binding file must not be kept`);
  }
  assert.equal(Object.hasOwn(JSON.parse(fs.readFileSync(claimReqFile, "utf8")), "rendered_goal_sha256"), false, "rendered_goal_sha256 omitted by default so legacy runs never 409");

  // CLI record and verify must both reject bound claims when run_case_id is missing from CLI flags AND env.
  const cliResp = path.join(root, "cli-bound-reply.json");
  const cliBinding = path.join(root, "cli-bound-binding.json");
  const cliOut = path.join(root, "cli-bound-out.json");
  fs.writeFileSync(cliResp, JSON.stringify(baseReply));
  fs.writeFileSync(cliBinding, JSON.stringify(bound));
  const cleanEnv = { ...process.env, QA_GOAL: frozen.rendered_goal, QA_MAX_TURNS: "45", QA_MAX_MINUTES: "40", QA_NO_PROGRESS_TURNS: "8" };
  delete cleanEnv.STAMP_RUN_CASE_ID;
  const recMissing = spawnSync(process.execPath, [CLAIM_BINDING_CLI, "record", "--response", cliResp, "--out", cliOut], {
    encoding: "utf8",
    env: cleanEnv,
  });
  assert.notEqual(recMissing.status, 0, "record CLI must fail closed when run_case_id is absent from CLI and env");
  assert.match(recMissing.stderr, /bound claim requires non-empty requested run_case_id/);
  assert.equal(fs.existsSync(cliOut), false);
  const verMissing = spawnSync(process.execPath, [CLAIM_BINDING_CLI, "verify", "--binding", cliBinding, "--out", cliOut], {
    encoding: "utf8",
    env: cleanEnv,
  });
  assert.notEqual(verMissing.status, 0, "verify CLI must fail closed when run_case_id is absent from CLI and env");
  assert.match(verMissing.stderr, /bound claim verification requires non-empty requested run_case_id/);
  assert.equal(fs.existsSync(cliOut), false);

  // Workflow ordering check: verify step runs after claim, before preflight, and skips release-prelogin.
  const workflow = fs.readFileSync(new URL("../../.github/workflows/qa-acceptance.yml", import.meta.url), "utf8");
  const claimIdx = workflow.indexOf("name: Claim Stamp run case and resolve auth mode");
  const verifyIdx = workflow.indexOf("name: Verify claimed specification and budget binding");
  const preflightIdx = workflow.indexOf("name: Preflight — QA account must not see the production server");
  assert.ok(claimIdx > 0 && verifyIdx > claimIdx && preflightIdx > verifyIdx);
  const verifyBlock = workflow.slice(verifyIdx, workflow.indexOf("\n      - name:", verifyIdx + 1));
  assert.match(verifyBlock, /inputs\.login_mode != 'release-prelogin'/);
  assert.match(verifyBlock, /claim_spec_binding\.mjs verify/);
  assert.match(verifyBlock, /--run-case-id "\$STAMP_RUN_CASE_ID"/);
});
