// The panel reviewer must judge the CURRENT attempt of a run, not its retry
// history (raft-mobile-reconcile #214; Testbed #325 review_context.attempt_ids).
// Synthetic reports only. The end-to-end tests run review_agent.mjs against a
// fake Testbed, a fake model and a JSON-backed YAML stub.
import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { AttemptScopeError, panelReviewBody, panelReviewPath, receiptAttemptIds, scopeReviewReport, submitPanelVote } from "./review_attempt_scope.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SCRIPT = path.join(HERE, "review_agent.mjs");
const sha = (s) => crypto.createHash("sha256").update(s).digest("hex");

const verdictAt = (step) => JSON.stringify({ annotations: { assertion: [{ assertion_id: "a1", step, result: "pass" }] } });
const shot = (id, runCaseId, step = 1) => ({ id, kind: "screenshot", run_case_id: runCaseId, step_index: step, seq: 1, captured_at: 1, sha256: sha(`bytes:${id}`) });

// open-thread was retried: rc-old (attempt 1) -> rc-new (attempt 2).
// settings ran once: rc-set.
function retriedReport(extra = {}) {
  return {
    run: { id: "run-1", status: "in_review", app_slug: "raft-android" },
    cases: [
      { id: "rc-old", case_id: "open-thread", case_revision: 1, attempt: 1, retry_of: null, status: "harness", verdict_json: verdictAt(1) },
      { id: "rc-new", case_id: "open-thread", case_revision: 1, attempt: 2, retry_of: "rc-old", status: "judging", verdict_json: verdictAt(1) },
      { id: "rc-set", case_id: "settings", case_revision: 1, attempt: 1, retry_of: null, status: "judging", verdict_json: verdictAt(1) },
    ],
    review_context: { attempt_ids: ["rc-new", "rc-set"] },
    evidence: [shot("ev-old", "rc-old"), shot("ev-new", "rc-new"), shot("ev-set", "rc-set")],
    reviews: [],
    ...extra,
  };
}

test("only the current attempts' cases, evidence and bound votes are selected", () => {
  const report = retriedReport({ reviews: [
    { id: "v-unbound", role: "panel", reviewer: "p1" },
    { id: "v-old", role: "panel", reviewer: "p2", attempt_ids: ["rc-old", "rc-set"] },
    { id: "v-current", role: "panel", reviewer: "p3", attempt_ids: ["rc-set", "rc-new"] },
  ] });
  const scope = scopeReviewReport(report);
  assert.equal(scope.mode, "attempt_bound");
  assert.deepEqual(scope.attemptIds, ["rc-new", "rc-set"]);
  assert.deepEqual(scope.cases.map((c) => c.id), ["rc-new", "rc-set"]);
  assert.deepEqual(scope.evidence.map((e) => e.id), ["ev-new", "ev-set"]);
  // After a retry an unbound historical vote does not count for this round.
  assert.deepEqual(scope.reviews.map((r) => r.id), ["v-current"]);
  assert.deepEqual(scope.superseded, { case_ids: ["rc-old"], evidence_ids: ["ev-old"], review_ids: ["v-unbound", "v-old"] });
});

test("with review_context an unbound vote never counts, even on the first attempt", () => {
  const report = { run: { id: "r" }, cases: [{ id: "rc-1", case_id: "c" }], review_context: { attempt_ids: ["rc-1"] },
    evidence: [shot("e", "rc-1")], reviews: [
      { id: "v", role: "panel", reviewer: "p1" },
      { id: "v-null", role: "panel", reviewer: "p2", attempt_ids: null },
      { id: "v-bound", role: "panel", reviewer: "p3", attempt_ids: ["rc-1"] },
    ] };
  const scope = scopeReviewReport(report);
  assert.deepEqual(scope.reviews.map((r) => r.id), ["v-bound"]);
  assert.deepEqual(scope.superseded.review_ids, ["v", "v-null"]);
});

test("a report without review_context (older Testbed) keeps the full history and says so", () => {
  const { review_context: _, ...report } = retriedReport({ reviews: [{ id: "v", role: "panel", reviewer: "p1" }] });
  const scope = scopeReviewReport(report);
  assert.equal(scope.mode, "legacy");
  assert.equal(scope.attemptIds, null);
  assert.equal(scope.cases.length, 3);
  assert.equal(scope.evidence.length, 3);
  assert.equal(scope.reviews.length, 1);
  assert.match(scope.notice, /no review_context/);
});

test("review_context without a usable attempt set refuses to review", () => {
  for (const ctx of [{ attempt_ids: null }, { attempt_ids: [] }, {}, null, { attempt_ids: ["rc-new", "rc-new"] }, { attempt_ids: [""] }]) {
    assert.throws(() => scopeReviewReport(retriedReport({ review_context: ctx })), AttemptScopeError, JSON.stringify(ctx));
  }
  assert.throws(() => scopeReviewReport(retriedReport({ review_context: { attempt_ids: ["rc-gone"] } })), /not found in report cases: rc-gone/);
});

test("the vote body carries expected_attempt_ids only when attempt-bound, in report order", () => {
  const vote = { reviewer: "p1", verdict: "passed", reason: "ok", detail_json: {} };
  const bound = panelReviewBody(scopeReviewReport(retriedReport()), vote);
  assert.deepEqual(bound, { ...vote, expected_attempt_ids: ["rc-new", "rc-set"] });
  const { review_context: _, ...legacy } = retriedReport();
  assert.deepEqual(panelReviewBody(scopeReviewReport(legacy), vote), vote);
});

// ---- vote submission: attempt-bound route and receipt check ------------------

const VOTE = { reviewer: "p1", verdict: "passed", reason: "ok", detail_json: {} };
const legacyReport = () => { const { review_context: _, ...r } = retriedReport(); return r; };
function fakePost(status, body) {
  const calls = [];
  return { calls, post: async (p, b) => { calls.push({ path: p, body: b }); return { status, body }; } };
}

test("attempt-bound votes use the attempt-panel-review route; legacy votes keep panel-review", () => {
  assert.equal(panelReviewPath(scopeReviewReport(retriedReport()), "run-1"), "/api/runs/run-1/attempt-panel-review");
  assert.equal(panelReviewPath(scopeReviewReport(legacyReport()), "run-1"), "/api/runs/run-1/panel-review");
});

test("receipt attempt ids are read from the top-level field of the Testbed receipt", () => {
  assert.deepEqual(receiptAttemptIds({ ok: true, review_id: "r", attempt_ids: ["a", "b"] }), ["a", "b"]);
  assert.equal(receiptAttemptIds({ ok: true, receipt: { attempt_ids: ["a"] } }), undefined);
  assert.equal(receiptAttemptIds({ ok: true }), undefined);
  assert.equal(receiptAttemptIds(null), undefined);
});

test("attempt-bound vote is submitted when the new route echoes exactly the expected attempts", async () => {
  // Shape of botiverse/testbed#327 @ 4292b69b panelReview(); a replay has duplicate=true and the original id.
  for (const receipt of [{ ok: true, review_id: "rv-1", attempt_ids: ["rc-new", "rc-set"], duplicate: false, votes: { passed: 1, failed: 0 }, finalized: null },
    { ok: true, review_id: "rv-1", attempt_ids: ["rc-new", "rc-set"], duplicate: true, votes: { passed: 1, failed: 0 }, finalized: null }]) {
    const f = fakePost(200, receipt);
    const r = await submitPanelVote({ scope: scopeReviewReport(retriedReport()), runId: "run-1", vote: VOTE, post: f.post });
    assert.equal(r.stage, "submitted");
    assert.deepEqual(f.calls, [{ path: "/api/runs/run-1/attempt-panel-review", body: { ...VOTE, expected_attempt_ids: ["rc-new", "rc-set"] } }]);
  }
});

test("attempt-bound vote on a Worker without the route (404) stays unconfirmed and never falls back", async () => {
  const f = fakePost(404, { error: "not found" });
  const r = await submitPanelVote({ scope: scopeReviewReport(retriedReport()), runId: "run-1", vote: VOTE, post: f.post });
  assert.equal(r.stage, "unconfirmed");
  assert.equal(r.status, 404);
  assert.match(r.reason, /404/);
  assert.deepEqual(f.calls.map((c) => c.path), ["/api/runs/run-1/attempt-panel-review"]);
});

test("attempt-bound vote whose receipt lacks or differs in attempt_ids, or lacks review_id, stays unconfirmed", async () => {
  for (const [receipt, why] of [
    [{ ok: true, review_id: "rv-1" }, /no attempt_ids/],
    [{ ok: true, review_id: "rv-1", receipt: { attempt_ids: ["rc-new", "rc-set"] } }, /no attempt_ids/],
    [{ ok: true, review_id: "rv-1", attempt_ids: null }, /differ/],
    [{ ok: true, review_id: "rv-1", attempt_ids: ["rc-set", "rc-new"] }, /differ/],
    [{ ok: true, review_id: "rv-1", attempt_ids: ["rc-new"] }, /differ/],
    [{ ok: true, review_id: "rv-1", attempt_ids: ["rc-old", "rc-set"] }, /differ/],
    [{ ok: true, attempt_ids: ["rc-new", "rc-set"] }, /no review_id/],
    [{ ok: true, review_id: "", attempt_ids: ["rc-new", "rc-set"] }, /no review_id/],
  ]) {
    const f = fakePost(200, receipt);
    const r = await submitPanelVote({ scope: scopeReviewReport(retriedReport()), runId: "run-1", vote: VOTE, post: f.post });
    assert.equal(r.stage, "unconfirmed", JSON.stringify(receipt));
    assert.match(r.reason, why);
    assert.equal(f.calls.length, 1);
  }
});

test("legacy vote keeps the old URL, sends no attempt ids and needs none back", async () => {
  const f = fakePost(200, { ok: true });
  const r = await submitPanelVote({ scope: scopeReviewReport(legacyReport()), runId: "run-1", vote: VOTE, post: f.post });
  assert.equal(r.stage, "submitted");
  assert.deepEqual(f.calls, [{ path: "/api/runs/run-1/panel-review", body: VOTE }]);
});

test("other HTTP failures still throw", async () => {
  for (const report of [retriedReport(), legacyReport()]) {
    const f = fakePost(409, { error: "attempts changed" });
    await assert.rejects(submitPanelVote({ scope: scopeReviewReport(report), runId: "run-1", vote: VOTE, post: f.post }), /Stamp 409/);
  }
  const legacy404 = fakePost(404, { error: "not found" });
  await assert.rejects(submitPanelVote({ scope: scopeReviewReport(legacyReport()), runId: "run-1", vote: VOTE, post: legacy404.post }), /Stamp 404/);
});

// ---- review_agent.mjs end to end --------------------------------------------

const FAKE_NET = `
const fs = require("fs");
const crypto = require("crypto");
const report = JSON.parse(fs.readFileSync(process.env.FAKE_REPORT, "utf8"));
const trace = (x) => fs.appendFileSync(process.env.FAKE_TRACE, JSON.stringify(x) + "\\n");
const sha = (s) => crypto.createHash("sha256").update(Buffer.from(String(s), "utf8")).digest("hex");
const attemptSpec = (rc) => {
  const full = JSON.stringify({ id: rc.case_id, title: rc.case_id, goal: "g", tier: "smoke", assertions: [{ id: "a1", text: "visible" }], android: { steps: [{ goal: "check" }] } });
  const slice = JSON.stringify({ id: rc.case_id, title: rc.case_id, goal: "g", tier: "smoke", assertions: [{ id: "a1", text: "visible" }], steps: [{ goal: "check" }] });
  const goal = "Check " + rc.case_id;
  const doc = {
    contract_version: "testbed-attempt-spec/v1",
    case_id: rc.case_id,
    case_revision: Number(rc.case_revision || 1),
    platform: rc.platform || "android",
    full_yaml: full,
    full_yaml_sha256: sha(full),
    platform_slice_yaml: slice,
    platform_slice_sha256: sha(slice),
    rendered_goal: goal,
    rendered_goal_sha256: sha(goal),
  };
  return {
    ...doc,
    run_id: report.run.id,
    run_case_id: rc.id,
    attempt: Number(rc.attempt || 1),
    binding_status: "verified",
    spec_sha256: sha(JSON.stringify(doc)),
    execution_budget: { max_turns: 45, max_minutes: 40, no_progress_turns: 8 },
  };
};
const res = (status, body) => ({ ok: status < 400, status, text: async () => JSON.stringify(body) });
global.fetch = async (url, opts = {}) => {
  const p = new URL(url).pathname, m = opts.method || "GET";
  if (m === "GET" && p === "/api/runs/run-1") return res(200, report);
  if (m === "GET" && p.startsWith("/api/cases/")) throw new Error("catalog /api/cases/ must never be called by review_agent");
  const specMatch = p.match(/^\\/api\\/runs\\/run-1\\/cases\\/([^/]+)\\/spec$/);
  if (m === "GET" && specMatch) {
    const id = decodeURIComponent(specMatch[1]);
    const rc = (report.cases || []).find((c) => c.id === id);
    if (!rc) return res(404, { code: "ATTEMPT_NOT_FOUND" });
    return res(200, attemptSpec(rc));
  }
  const ev = p.match(/^\\/api\\/runs\\/run-1\\/evidence\\/(.+)$/);
  if (m === "GET" && ev) { trace({ evidence: ev[1] }); const b = Buffer.from("bytes:" + ev[1]); return { ok: true, status: 200, arrayBuffer: async () => b }; }
  if (m === "POST" && p === "/api/runs/run-1/panel-review") { trace({ post: JSON.parse(opts.body), path: p }); return res(200, { ok: true }); }
  if (m === "POST" && p === "/api/runs/run-1/attempt-panel-review") {
    const body = JSON.parse(opts.body);
    trace({ post: body, path: p });
    const mode = process.env.FAKE_BOUND || "echo";
    if (mode === "404") return res(404, { error: "not found" });
    if (mode === "none") return res(200, { ok: true });
    if (mode === "mismatch") return res(200, { ok: true, review_id: "rv-1", attempt_ids: ["rc-old", "rc-set"] });
    return res(200, { ok: true, review_id: "rv-1", attempt_ids: body.expected_attempt_ids, duplicate: false, votes: { passed: 1, failed: 0 }, finalized: null });
  }
  throw new Error("fake unexpected " + m + " " + p);
};
`;
const FAKE_MODEL = `
import fs from "node:fs";
export function builtinModels() {
  return {
    getModel: () => ({ id: "fake" }),
    complete: async (_model, ctx) => {
      const labels = ctx.messages[0].content.filter((c) => c.type === "text" && c.text.startsWith("RAW SCREENSHOT")).map((c) => c.text.match(/evidence=(\\S+)/)[1]);
      fs.appendFileSync(process.env.FAKE_TRACE, JSON.stringify({ model_saw: labels }) + "\\n");
      const detail = Object.fromEntries(JSON.parse(process.env.FAKE_CASES).map((id) => [id, { a1: { ok: true, note: "visible" } }]));
      return { stopReason: "stop", usage: {}, content: [{ type: "text", text: JSON.stringify({ verdict: "passed", reason: "all visible", detail_json: detail, anomalies: [] }) }] };
    },
  };
}
`;
const FAKE_YAML = `export const parse = (s) => JSON.parse(s); export const stringify = (v) => JSON.stringify(v);\n`;

function runAgent(report, reviewer = "panel-1", bound = "echo") {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), "review-attempts-"));
  try {
    for (const [name, body] of [["fake-net.cjs", FAKE_NET], ["fake-model.mjs", FAKE_MODEL], ["fake-yaml.mjs", FAKE_YAML]]) fs.writeFileSync(path.join(d, name), body);
    fs.writeFileSync(path.join(d, "report.json"), JSON.stringify(report));
    const trace = path.join(d, "trace");
    fs.writeFileSync(trace, "");
    const out = path.join(d, "out.json");
    const caseIds = [...new Set(report.cases.filter((c) => !report.review_context?.attempt_ids || report.review_context.attempt_ids.includes(c.id)).map((c) => c.case_id))];
    const r = spawnSync(process.execPath, [SCRIPT, "--run-id", "run-1", "--reviewer", reviewer, "--out", out], {
      encoding: "utf8",
      env: { ...process.env, NODE_OPTIONS: `--require=${path.join(d, "fake-net.cjs")}`, STAMP_URL: "http://testbed.invalid", STAMP_TOKEN: "fake", PI_API_KEY: "fake", MINIMAX_API_KEY: "",
        SLOCK_PI_AI_MODULE: path.join(d, "fake-model.mjs"), SLOCK_YAML_MODULE: path.join(d, "fake-yaml.mjs"),
        FAKE_REPORT: path.join(d, "report.json"), FAKE_TRACE: trace, FAKE_BOUND: bound, FAKE_CASES: JSON.stringify(caseIds) },
    });
    const events = fs.readFileSync(trace, "utf8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l));
    return { code: r.status, stderr: r.stderr, record: fs.existsSync(out) ? JSON.parse(fs.readFileSync(out, "utf8")) : null,
      posts: events.filter((e) => e.post).map((e) => e.post), postPaths: events.filter((e) => e.post).map((e) => e.path), modelSaw: events.find((e) => e.model_saw)?.model_saw ?? null,
      fetchedEvidence: events.filter((e) => e.evidence).map((e) => e.evidence) };
  } finally {
    fs.rmSync(d, { recursive: true, force: true });
  }
}

test("review_agent judges only the current attempts and binds its vote to them", () => {
  const r = runAgent(retriedReport());
  assert.equal(r.code, 0, r.stderr);
  assert.deepEqual(r.modelSaw.sort(), ["ev-new", "ev-set"]);
  assert.ok(!r.fetchedEvidence.includes("ev-old"), "superseded evidence is never fetched");
  assert.equal(r.posts.length, 1);
  assert.deepEqual(r.postPaths, ["/api/runs/run-1/attempt-panel-review"]);
  assert.deepEqual(r.posts[0].expected_attempt_ids, ["rc-new", "rc-set"]);
  assert.equal(r.record.stage, "submitted");
  assert.deepEqual(r.record.post_receipt.attempt_ids, ["rc-new", "rc-set"]);
  assert.deepEqual(Object.keys(r.posts[0].detail_json.assertions).sort(), ["open-thread", "settings"]);
  assert.deepEqual(r.record.attempt_scope.attempt_ids, ["rc-new", "rc-set"]);
  assert.deepEqual(r.record.review_input.cases.map((c) => c.case_id), ["open-thread", "settings"]);
});

test("review_agent on an older Testbed report reviews the full history, logs it, and sends no attempt ids", () => {
  const { review_context: _, ...legacy } = retriedReport();
  const r = runAgent(legacy);
  assert.equal(r.code, 0, r.stderr);
  assert.match(r.stderr, /no review_context \(older Testbed\)/);
  assert.deepEqual(r.modelSaw.sort(), ["ev-new", "ev-old", "ev-set"]);
  assert.equal(r.posts.length, 1);
  assert.ok(!("expected_attempt_ids" in r.posts[0]));
  assert.deepEqual(r.postPaths, ["/api/runs/run-1/panel-review"]);
  assert.equal(r.record.stage, "submitted");
  assert.equal(r.record.attempt_scope.mode, "legacy");
});

test("review_agent refuses to vote when review_context cannot identify the current attempts", () => {
  for (const attempt_ids of [null, []]) {
    const r = runAgent(retriedReport({ review_context: { attempt_ids } }));
    assert.notEqual(r.code, 0);
    assert.match(r.stderr, /current attempts cannot be uniquely determined/);
    assert.equal(r.posts.length, 0);
    assert.equal(r.modelSaw, null);
    assert.equal(r.record.stage, "error");
  }
});

test("review_agent re-votes after a retry when its own earlier vote is unbound or bound to superseded attempts", () => {
  for (const stale of [{ id: "v1", role: "panel", reviewer: "panel-1", verdict: "failed", detail_json: "{}" },
    { id: "v1", role: "panel", reviewer: "panel-1", verdict: "failed", detail_json: "{}", attempt_ids: ["rc-old", "rc-set"] }]) {
    const r = runAgent(retriedReport({ reviews: [stale] }));
    assert.equal(r.code, 0, r.stderr);
    assert.equal(r.posts.length, 1, "the stale vote does not count for this round");
    assert.deepEqual(r.posts[0].expected_attempt_ids, ["rc-new", "rc-set"]);
    assert.deepEqual(r.record.attempt_scope.superseded.review_ids, ["v1"]);
  }
});

test("review_agent on a first-round report with its own unbound vote still votes on the new route", () => {
  const firstRound = { run: { id: "run-1", status: "in_review", app_slug: "raft-android" },
    cases: [{ id: "rc-set", case_id: "settings", case_revision: 1, attempt: 1, retry_of: null, status: "judging", verdict_json: verdictAt(1) }],
    review_context: { attempt_ids: ["rc-set"] }, evidence: [shot("ev-set", "rc-set")],
    reviews: [{ id: "v-legacy", role: "panel", reviewer: "panel-1", verdict: "passed", comment: "old", detail_json: "{}", attempt_ids: null }] };
  const r = runAgent(firstRound);
  assert.equal(r.code, 0, r.stderr);
  assert.notEqual(r.record.stage, "already_submitted");
  assert.deepEqual(r.postPaths, ["/api/runs/run-1/attempt-panel-review"]);
  assert.deepEqual(r.posts[0].expected_attempt_ids, ["rc-set"]);
  assert.equal(r.record.stage, "submitted");
});

test("review_agent reuses its own vote bound to the current attempts without posting again", () => {
  const first = runAgent(retriedReport());
  const posted = first.posts[0];
  const existing = { id: "v1", role: "panel", reviewer: "panel-1", verdict: posted.verdict, comment: posted.reason,
    detail_json: JSON.stringify(posted.detail_json), attempt_ids: ["rc-new", "rc-set"] };
  const r = runAgent(retriedReport({ reviews: [existing] }));
  assert.equal(r.code, 0, r.stderr);
  assert.equal(r.posts.length, 0);
  assert.equal(r.record.stage, "already_submitted");
  assert.equal(r.record.existing_review.id, "v1");
});

test("review_agent keeps an attempt-bound vote unconfirmed on 404 or a receipt without matching ids, without falling back", () => {
  for (const [bound, why] of [["404", /returned 404/], ["none", /no attempt_ids/], ["mismatch", /differ from expected/]]) {
    const r = runAgent(retriedReport(), "panel-1", bound);
    assert.equal(r.code, 2, `${bound}: ${r.stderr}`);
    assert.deepEqual(r.postPaths, ["/api/runs/run-1/attempt-panel-review"], "the old panel-review URL is never tried");
    assert.equal(r.record.stage, "unconfirmed");
    assert.match(r.record.unconfirmed_reason, why);
    assert.match(r.stderr, /vote unconfirmed/);
  }
});

test("review_agent's evidence-incomplete vote follows the same route and confirmation rule", () => {
  // The executor asserted a1 on step 5, which has no raw screenshot.
  const incomplete = () => retriedReport({ cases: retriedReport().cases.map((c) => c.id === "rc-new" ? { ...c, verdict_json: verdictAt(5) } : c) });
  const ok = runAgent(incomplete());
  assert.equal(ok.code, 0, ok.stderr);
  assert.equal(ok.modelSaw, null, "no model call for an evidence-incomplete review");
  assert.deepEqual(ok.postPaths, ["/api/runs/run-1/attempt-panel-review"]);
  assert.equal(ok.posts[0].detail_json.evidence_incomplete, true);
  assert.equal(ok.record.stage, "submitted");
  const missing = runAgent(incomplete(), "panel-1", "404");
  assert.equal(missing.code, 2, missing.stderr);
  assert.deepEqual(missing.postPaths, ["/api/runs/run-1/attempt-panel-review"]);
  assert.equal(missing.record.stage, "unconfirmed");
  const { review_context: _, ...legacy } = incomplete();
  const old = runAgent(legacy);
  assert.equal(old.code, 0, old.stderr);
  assert.deepEqual(old.postPaths, ["/api/runs/run-1/panel-review"]);
});
