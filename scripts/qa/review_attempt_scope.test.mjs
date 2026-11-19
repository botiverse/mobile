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
import { AttemptScopeError, panelReviewBody, scopeReviewReport } from "./review_attempt_scope.mjs";

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

test("without a retry an unbound vote still refers to the only attempt set", () => {
  const report = { run: { id: "r" }, cases: [{ id: "rc-1", case_id: "c" }], review_context: { attempt_ids: ["rc-1"] },
    evidence: [shot("e", "rc-1")], reviews: [{ id: "v", role: "panel", reviewer: "p1" }] };
  assert.deepEqual(scopeReviewReport(report).reviews.map((r) => r.id), ["v"]);
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

// ---- review_agent.mjs end to end --------------------------------------------

const FAKE_NET = `
const fs = require("fs");
const crypto = require("crypto");
const report = JSON.parse(fs.readFileSync(process.env.FAKE_REPORT, "utf8"));
const trace = (x) => fs.appendFileSync(process.env.FAKE_TRACE, JSON.stringify(x) + "\\n");
const spec = (id) => JSON.stringify({ id, title: id, goal: "g", tier: "smoke", assertions: [{ id: "a1", text: "visible" }], android: {} });
const res = (status, body) => ({ ok: status < 400, status, text: async () => JSON.stringify(body) });
global.fetch = async (url, opts = {}) => {
  const p = new URL(url).pathname, m = opts.method || "GET";
  if (m === "GET" && p === "/api/runs/run-1") return res(200, report);
  if (m === "GET" && p.startsWith("/api/cases/")) { const id = decodeURIComponent(p.split("/").pop()); return res(200, { revision: 1, spec_yaml: spec(id) }); }
  const ev = p.match(/^\\/api\\/runs\\/run-1\\/evidence\\/(.+)$/);
  if (m === "GET" && ev) { trace({ evidence: ev[1] }); const b = Buffer.from("bytes:" + ev[1]); return { ok: true, status: 200, arrayBuffer: async () => b }; }
  if (m === "POST" && p === "/api/runs/run-1/panel-review") { trace({ post: JSON.parse(opts.body) }); return res(200, { ok: true }); }
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

function runAgent(report, reviewer = "panel-1") {
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
        FAKE_REPORT: path.join(d, "report.json"), FAKE_TRACE: trace, FAKE_CASES: JSON.stringify(caseIds) },
    });
    const events = fs.readFileSync(trace, "utf8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l));
    return { code: r.status, stderr: r.stderr, record: fs.existsSync(out) ? JSON.parse(fs.readFileSync(out, "utf8")) : null,
      posts: events.filter((e) => e.post).map((e) => e.post), modelSaw: events.find((e) => e.model_saw)?.model_saw ?? null,
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
  assert.deepEqual(r.posts[0].expected_attempt_ids, ["rc-new", "rc-set"]);
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
