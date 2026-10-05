import assert from "node:assert/strict";
import test from "node:test";
import {
  capSources,
  caseIdNeedles,
  dedupeSources,
  excerptSource,
  isEligibleIssue,
  panelApiKey,
  panelDecision,
  patchScopeProblem,
  resolvePanelSpec,
  resultComment,
  selectFixQueueIssues,
  sourceAuthorityProblem,
} from "./fix_queue.mjs";

function issue(number, overrides = {}) {
  return {
    number,
    title: `[visual-drift] components.ui.case-${number}: drift`,
    body: "**Comparison:** `react__android`",
    state: "OPEN",
    labels: [{ name: "visual-drift" }, { name: "verified" }],
    assignees: [],
    createdAt: `2026-07-${String(number).padStart(2, "0")}T00:00:00Z`,
    ...overrides,
  };
}

test("eligibility is open + verified + visual-drift + unassigned + not already consumed", () => {
  assert.equal(isEligibleIssue(issue(1)), true);
  assert.equal(isEligibleIssue(issue(2, { state: "CLOSED" })), false);
  assert.equal(isEligibleIssue(issue(3, { assignees: [{ login: "owner" }] })), false);
  assert.equal(isEligibleIssue(issue(4, { labels: [{ name: "visual-drift" }] })), false);
  assert.equal(isEligibleIssue(issue(5, { labels: [{ name: "visual-drift" }, { name: "verified" }, { name: "fix-queue:processing" }] })), false);
  assert.equal(isEligibleIssue(issue(6, { labels: [{ name: "visual-drift" }, { name: "verified" }, { name: "fix-queue:refuted" }] })), false);
  assert.equal(isEligibleIssue(issue(7, { labels: [{ name: "visual-drift" }, { name: "verified" }, { name: "fix-queue:agent-ready" }] })), false);
});

test("selection drains oldest first while enforcing case and token caps", () => {
  const issues = [issue(3), issue(1), issue(2)];
  assert.deepEqual(selectFixQueueIssues(issues, 3, 150_000).map((entry) => entry.number), [1, 2]);
  assert.deepEqual(selectFixQueueIssues(issues, 1, 225_000).map((entry) => entry.number), [1]);
  assert.deepEqual(selectFixQueueIssues(issues, 3, 75_000).map((entry) => entry.number), [1]);
});

test("panel requires all three valid voters and majority refutation", () => {
  assert.deepEqual(panelDecision([{ error: "x" }, { error: "y" }, { refuted: false }], 2), { verdict: "REVIEW", valid: 1, refuted: 0 });
  assert.deepEqual(panelDecision([{ refuted: false }, { refuted: false }, { error: "timeout" }], 2), { verdict: "REVIEW", valid: 2, refuted: 0 });
  assert.deepEqual(panelDecision([{ refuted: true }, { refuted: true }, { refuted: false }], 2), { verdict: "REFUTED", valid: 3, refuted: 2 });
  assert.deepEqual(panelDecision([{ refuted: true }, { refuted: false }, { refuted: false }], 2), { verdict: "UNREFUTED", valid: 3, refuted: 1 });
});

test("source authority fails closed before model calls", () => {
  const complete = {
    manifest: { id: "case" },
    analysis: { id: "case" },
    sources: { react: [{ path: "a.tsx" }], android: [{ path: "a.kt" }] },
  };
  assert.equal(sourceAuthorityProblem(complete), null);
  assert.match(sourceAuthorityProblem({ ...complete, manifest: null }), /canonical shared manifest/);
  assert.match(sourceAuthorityProblem({ ...complete, analysis: null }), /published comparison analysis/);
  assert.match(sourceAuthorityProblem({ ...complete, sources: { ...complete.sources, react: [] } }), /React\/RUI/);
  assert.match(sourceAuthorityProblem({ ...complete, sources: { ...complete.sources, android: [] } }), /Android\/KMP/);
});

test("patch scope is restricted to located product sources and exact diff headers", () => {
  const sources = [{ path: "compose/shared/src/commonMain/kotlin/Thing.kt" }];
  assert.equal(patchScopeProblem({ patchable: true, patch: [{
    path: sources[0].path,
    diff: `--- a/${sources[0].path}\n+++ b/${sources[0].path}\n@@ -1 +1 @@`,
  }] }, sources), null);
  assert.match(patchScopeProblem({ patchable: true, patch: [{ path: "compose/shared/src/commonTest/ThingTest.kt", diff: "x" }] }, sources), /outside located/);
  const testSource = "compose/shared/src/commonTest/kotlin/ThingTest.kt";
  assert.match(patchScopeProblem({ patchable: true, patch: [{
    path: testSource,
    diff: `--- a/${testSource}\n+++ b/${testSource}\n@@ -1 +1 @@`,
  }] }, [{ path: testSource }]), /forbidden queue scope/);
  assert.match(patchScopeProblem({ patchable: true, patch: [{ path: sources[0].path, diff: "missing headers" }] }, sources), /diff headers/);
  assert.match(patchScopeProblem({ patchable: true, patch: [{
    path: sources[0].path,
    diff: `--- a/${sources[0].path}\n+++ b/${sources[0].path}\n@@ -1 +1 @@\n--- a/packages/visual-testing/shared/sharedCases.json\n+++ b/packages/visual-testing/shared/sharedCases.json\n@@ -1 +1 @@`,
  }] }, sources), /diff headers/);
});

test("source records dedupe by repo-relative path", () => {
  assert.deepEqual(dedupeSources([
    { path: "a.kt", src: "one" },
    { path: "a.kt", src: "two" },
    null,
    { path: "b.kt", src: "three" },
  ]), [
    { path: "a.kt", src: "one" },
    { path: "b.kt", src: "three" },
  ]);
});

test("source prompt input is capped across files", () => {
  assert.deepEqual(capSources([
    { path: "a.kt", src: "1234" },
    { path: "b.kt", src: "5678" },
  ], 6), [
    { path: "a.kt", src: "1234" },
    { path: "b.kt", src: "56" },
  ]);
});

test("one oversized file cannot evict later primitives from the source budget", () => {
  assert.deepEqual(capSources([
    { path: "big.tsx", src: "x".repeat(100) },
    { path: "primitive.tsx", src: "yyyy" },
  ], 20, 8), [
    { path: "big.tsx", src: "x".repeat(8) },
    { path: "primitive.tsx", src: "yyyy" },
  ]);
});

test("case ids yield component-name needles without generic noise", () => {
  assert.deepEqual(caseIdNeedles("components.ui.segmented-control.states"), ["SegmentedControl"]);
  assert.deepEqual(caseIdNeedles("components.ui.avatar-list-row.states"), ["AvatarListRow"]);
  assert.deepEqual(caseIdNeedles("components.ui.textarea.states"), ["Textarea"]);
  assert.deepEqual(caseIdNeedles("components.home.notification-center.states"), ["NotificationCenter"]);
  assert.deepEqual(caseIdNeedles("screens.settings.server-danger-modal"), ["ServerDangerModal"]);
  // generic single-word segments must not become needles
  assert.deepEqual(caseIdNeedles("components.settings.page.states"), []);
  assert.deepEqual(caseIdNeedles(""), []);
});

test("oversized files excerpt around the anchored definition, not the head", () => {
  const filler = "// filler\n".repeat(3_000);
  const src = `${filler}function LanguageRegionSection() { return null; }\n${filler}`;
  const excerpt = excerptSource({ path: "SettingsPanel.tsx", src }, ["LanguageRegionSection"], 1_000);
  assert.match(excerpt.src, /fix-queue excerpt of SettingsPanel\.tsx/);
  assert.match(excerpt.src, /function LanguageRegionSection/);
  // small files pass through untouched
  const small = { path: "a.tsx", src: "function Tiny() {}" };
  assert.equal(excerptSource(small, ["Tiny"], 1_000), small);
  // no needle hit falls back to the head
  const headOnly = excerptSource({ path: "b.tsx", src }, ["NotPresent"], 1_000);
  assert.equal(headOnly.src, src.slice(0, 1_000));
});

test("panel spec defaults to the primary model for every voter", () => {
  const spec = resolvePanelSpec(3, {});
  assert.deepEqual(spec.map((entry) => entry.label), [
    "minimax/MiniMax-M3.1-Flash-Preview",
    "minimax/MiniMax-M3.1-Flash-Preview",
    "minimax/MiniMax-M3.1-Flash-Preview",
  ]);
  assert.deepEqual(spec.map((entry) => entry.maxTokens), [2_048, 2_048, 2_048]);
  assert.deepEqual(spec.map((entry) => entry.reasoningEffort), [null, null, null]);
});

test("heterogeneous panel spec parses per-voter models and thinking-endpoint quirks", () => {
  const spec = resolvePanelSpec(3, {
    PI_PANEL_MODELS: "minimax/MiniMax-M3.1-Flash-Preview, minimax/MiniMax-M3.1-Flash-Preview, qwen-token-plan-cn/qwen3.8-max-preview",
  });
  assert.deepEqual(spec.map((entry) => entry.label), [
    "minimax/MiniMax-M3.1-Flash-Preview",
    "minimax/MiniMax-M3.1-Flash-Preview",
    "qwen-token-plan-cn/qwen3.8-max-preview",
  ]);
  // qwen token-plan mandates enable_thinking=true and reasoning shares the
  // completion budget, so the voter needs effort on and a larger cap.
  assert.equal(spec[2].reasoningEffort, "low");
  assert.equal(spec[2].maxTokens, 8_192);
  assert.equal(spec[0].reasoningEffort, null);
  assert.equal(spec[0].maxTokens, 2_048);
});

test("panel spec fails closed on wrong count or malformed entries", () => {
  assert.throws(() => resolvePanelSpec(3, { PI_PANEL_MODELS: "minimax/MiniMax-M3.1-Flash-Preview" }), /exactly 3 voters/);
  assert.throws(() => resolvePanelSpec(3, { PI_PANEL_MODELS: "minimax,minimax/M3,qwen/x" }), /provider\/model/);
  assert.throws(() => resolvePanelSpec(3, { PI_PANEL_MODELS: "minimax/,a/b,c/d" }), /provider\/model/);
});

test("panel api key prefers per-provider env, then legacy fallbacks", () => {
  assert.equal(panelApiKey("qwen-token-plan-cn", { PI_API_KEY_QWEN_TOKEN_PLAN_CN: "qk", PI_API_KEY: "pk" }), "qk");
  assert.equal(panelApiKey("qwen-token-plan-cn", { PI_API_KEY: "pk" }), "pk");
  assert.equal(panelApiKey("minimax", { MINIMAX_API_KEY: "mk", PI_API_KEY: "pk" }), "mk");
  assert.equal(panelApiKey("minimax", { PI_API_KEY: "pk" }), "pk");
  assert.equal(panelApiKey("minimax", {}), undefined);
});

test("UNREFUTED handoff is explicit about targeted evidence, draft PR, and no auto merge", () => {
  const comment = resultComment({
    verdict: "UNREFUTED",
    tokenUsage: 123,
    truth: { reactTruth: "x" },
    patch: { patch: [{ path: "a.kt", diff: "diff" }] },
    votes: [{ refuted: false }],
  }, {
    runKey: "1.1",
    mobileSha: "mobile",
    slockSha: "slock",
    analysisGeneratedAt: "now",
    model: "minimax/MiniMax-M3.1-Flash-Preview",
    reservedTokens: 75_000,
  });
  assert.match(comment, /targeted `Visual Testing`/);
  assert.match(comment, /visual_verify\.py/);
  assert.match(comment, /draft/);
  assert.match(comment, /No automatic product merge/);
});

test("REFUTED comment preserves the precise flaw and forbids applying the patch", () => {
  const comment = resultComment({
    verdict: "REFUTED",
    specificFlaw: "misses elegant theme",
    truth: {},
    patch: {},
    votes: [],
  }, {
    runKey: "1.1",
    mobileSha: "mobile",
    slockSha: "slock",
    analysisGeneratedAt: "now",
    model: "minimax/MiniMax-M3.1-Flash-Preview",
    reservedTokens: 75_000,
  });
  assert.match(comment, /Do not apply/);
  assert.match(comment, /misses elegant theme/);
});
