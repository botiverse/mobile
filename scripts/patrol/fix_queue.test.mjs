import assert from "node:assert/strict";
import test from "node:test";
import {
  capSources,
  dedupeSources,
  isEligibleIssue,
  panelDecision,
  resultComment,
  selectFixQueueIssues,
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

test("panel requires at least two valid voters and majority refutation", () => {
  assert.deepEqual(panelDecision([{ error: "x" }, { error: "y" }, { refuted: false }], 2), { verdict: "REVIEW", valid: 1, refuted: 0 });
  assert.deepEqual(panelDecision([{ refuted: true }, { refuted: true }, { refuted: false }], 2), { verdict: "REFUTED", valid: 3, refuted: 2 });
  assert.deepEqual(panelDecision([{ refuted: true }, { refuted: false }, { refuted: false }], 2), { verdict: "UNREFUTED", valid: 3, refuted: 1 });
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
    model: "minimax/MiniMax-M3",
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
    model: "minimax/MiniMax-M3",
    reservedTokens: 75_000,
  });
  assert.match(comment, /Do not apply/);
  assert.match(comment, /misses elegant theme/);
});
