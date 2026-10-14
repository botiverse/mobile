import { test } from "node:test";
import assert from "node:assert/strict";
import { judgeVisibleServers, PRODUCTION_SERVER_NAME, REQUIRED_QA_SERVER } from "./visible_servers_probe.mjs";

// The judgement is pure, so every branch is testable without a network. That split is the
// point: a probe whose verdict can only be exercised by contacting a real server is a probe
// whose failure modes never get tested.

const qa = (name) => ({ id: `id-${name}`, name, slug: name.toLowerCase() });
// Identity as the login response supplies it — never as the request supplied it.
const who = { id: "b8b3f81d", email: "qa-probe@example.test", name: "SlockTestOranix" };

test("the constants are the ones the rest of the fleet uses", () => {
  assert.equal(PRODUCTION_SERVER_NAME, "Raft Android");
  assert.equal(REQUIRED_QA_SERVER, "OranixSlockTest");
});

test("clean case: the two QA servers and nothing else", () => {
  // The real observed list, from the 2026-08-30 07:13 picker dump.
  const v = judgeVisibleServers([qa("OranixSlockTest"), qa("qa-server-b-slock")], who);
  assert.equal(v.ok, true, v.detail);
  assert.equal(v.code, "clean");
});

// ---- half 1: production must not be visible -------------------------------------------
test("RED when the production server appears in the list", () => {
  const v = judgeVisibleServers([qa("OranixSlockTest"), qa("Raft Android")], who);
  assert.equal(v.ok, false);
  assert.equal(v.code, "production_visible");
});

test("RED when production appears under a composite label", () => {
  // The picker renders "<name>, /<slug>"; if the API ever hands back that composite form,
  // an equality check would miss it. Containment must catch it.
  const v = judgeVisibleServers([qa("OranixSlockTest"), qa("Raft Android, /slock-android")], who);
  assert.equal(v.ok, false);
  assert.equal(v.code, "production_visible");
});

test("production wins over readability: the severe case must not report as the mild one", () => {
  // @LiBai, reviewing this file. One membership change can add the account to production AND
  // drop it from OranixSlockTest at the same time. Readability-first reported that as
  // `expected_server_missing` — a complaint about list shape that never mentions production is
  // visible. Fail-closed either way, but the receipt shows a grumble instead of the alarm.
  const v = judgeVisibleServers([qa("Raft Android"), qa("qa-server-b-slock")], who);
  assert.equal(v.ok, false);
  assert.equal(v.code, "production_visible", "an unconditional violation outranks a shape complaint");
  assert.match(v.detail, /Raft Android/, "the receipt must name what was seen");
});

// ---- half 2: the list must be READABLE, or half 1 means nothing ------------------------
// Each of these is a case where half 1 alone would report success while having observed
// nothing at all. They are asserted separately because they fail for different reasons.
test("RED on an empty list — 'could not read' must not pass as 'production absent'", () => {
  const v = judgeVisibleServers([], who);
  assert.equal(v.ok, false);
  assert.equal(v.code, "empty_list");
});

test("RED when the expected QA server is missing — we are not reading the list we think", () => {
  const v = judgeVisibleServers([qa("some-other-workspace")], who);
  assert.equal(v.ok, false);
  assert.equal(v.code, "expected_server_missing");
});

test("RED when the response is not a list at all (shape change / error envelope)", () => {
  for (const shape of [null, undefined, { error: "unauthorized" }, "nope"]) {
    const v = judgeVisibleServers(shape, who);
    assert.equal(v.ok, false);
    assert.equal(v.code, "not_a_list", `shape ${JSON.stringify(shape)} must not pass`);
  }
});

// ---- the guard against the guard ------------------------------------------------------
test("half 2 is load-bearing: dropping it would let three failures read as PASS", () => {
  // Reimplements "production check only" — the tempting single-assertion version — and shows
  // each input it would wave through. If someone later deletes the readability half, this is
  // the test that says what was lost.
  const productionCheckOnly = (servers) =>
    !(servers || []).some((s) => (s?.name || "").toLowerCase().includes(PRODUCTION_SERVER_NAME.toLowerCase()));

  for (const [label, input] of [
    ["empty list", []],
    ["wrong list", [qa("some-other-workspace")]],
    ["error envelope", []],
  ]) {
    assert.equal(productionCheckOnly(input), true, `${label}: the single-assertion version passes it`);
    assert.equal(judgeVisibleServers(input, who).ok, false, `${label}: the dual assertion must reject it`);
  }
});

// ---- identity: a verdict that cannot name whose visibility it measured is not a verdict ----
test("RED when there is no authenticated identity to bind the reading to", () => {
  for (const bad of [null, undefined, {}, { email: "qa-probe@example.test" }]) {
    const v = judgeVisibleServers([qa(REQUIRED_QA_SERVER)], bad);
    assert.equal(v.ok, false, `identity ${JSON.stringify(bad)} must not yield a verdict`);
    assert.equal(v.code, "unattributable");
  }
});

test("the clean verdict names the account it measured", () => {
  // @HuaTuo, live 2026-08-31: the QA pool's `label=alpha-qa` is an LRU selector, so consecutive
  // requests return DIFFERENT accounts — they had used three separately-issued tokens as one
  // identity. "2 servers, no production" is therefore meaningless unless it says whose.
  const v = judgeVisibleServers([qa("OranixSlockTest"), qa("qa-server-b-slock")], who);
  assert.equal(v.ok, true);
  assert.match(v.detail, /qa-probe@example\.test/, "the reading must carry the identity that produced it");
});
