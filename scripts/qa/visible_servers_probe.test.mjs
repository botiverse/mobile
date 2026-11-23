import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
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

test("the receipt names the account by id and never prints its email (it is uploaded as CI evidence)", () => {
  for (const list of [
    [qa("OranixSlockTest"), qa("qa-server-b-slock")],
    [qa("OranixSlockTest"), qa("Raft Android")],
  ]) {
    const v = judgeVisibleServers(list, who);
    assert.ok(v.detail.includes(who.id), v.detail);
    assert.ok(!v.detail.includes(who.email), `email leaked into: ${v.detail}`);
  }
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
  // By its user id: the email never goes into a receipt ("the receipt names the account by id ..." above).
  assert.match(v.detail, /b8b3f81d/, "the reading must carry the identity that produced it");
});

test("the claimed Android login checks the assigned API identity from the 0600 credential file before the emulator and prints fixed codes", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "qa-account-preflight-"));
  const file = path.join(directory, "input.json");
  const credFile = path.join(directory, "account");
  const calls = path.join(directory, "calls.txt");
  const runCase = "00000000-0000-4000-8000-000000000001";
  const target = { user_id: "assigned-user", server_id: "assigned-server", server_slug: "assigned-slug" };
  const input = { case_id: "login-email-password", run_case_id: runCase, attempt: 1,
    attempt_id: "pla_" + "a".repeat(32), nonce: "b".repeat(64), expected_target: target };
  const member = { id: target.server_id, slug: target.server_slug, name: REQUIRED_QA_SERVER };
  const fixture = { user: { id: target.user_id }, servers: [member] };
  const runtimeEmail = "acceptance@example.invalid";
  const runtimePassword = "acceptance-config-password";
  // The upstream auth route returns {user,accessToken,refreshToken}; GET /api/servers returns
  // the membership array with server.id/name/slug (botiverse/slock staging 22c31d25).
  const mock = String.raw`
    import assert from "node:assert/strict";
    import fs from "node:fs";
    const f=JSON.parse(process.env.FIXTURE);
    globalThis.fetch = async (url, init) => {
      const u = new URL(url);
      assert.equal(u.origin, "https://api.raft.build");
      if (process.env.DEDICATED) assert.equal(init.redirect, "error");
      fs.appendFileSync(process.env.CALLS, u.pathname + "\n");
      if (u.pathname === "/api/auth/login") {
        const expectedEmail = process.env.DEDICATED ? process.env.EXPECTED_RUNTIME_EMAIL : process.env.QA_TEST_EMAIL;
        const expectedPassword = process.env.DEDICATED ? process.env.EXPECTED_RUNTIME_PASSWORD : process.env.QA_TEST_PASSWORD;
        assert.deepEqual(JSON.parse(init.body), {email:expectedEmail, password:expectedPassword});
        return Response.json({user:f.user, accessToken:"test-access", refreshToken:"test-refresh"});
      }
      if (u.pathname === "/api/servers") {
        assert.equal(init.headers.Authorization, "Bearer test-access");
        return Response.json(f.servers, {status:f.serverStatus || 200});
      }
      assert.equal(u.pathname, "/api/auth/logout");
      assert.deepEqual(JSON.parse(init.body), {refreshToken:"test-refresh"});
      return Response.json({}, {status:f.logoutStatus || 200});
    };
  `;
  function run(name, data, expectedCode, expectedLine, options = {}) {
    fs.rmSync(file, { force: true });
    fs.rmSync(credFile, { force: true });
    fs.writeFileSync(calls, "");
    if (!options.missing) fs.writeFileSync(file, JSON.stringify(input), { mode: 0o600 });
    if (options.setupCred) options.setupCred(credFile);
    else if (!options.missingCred) {
      fs.writeFileSync(credFile, `${runtimeEmail}\n${runtimePassword}\n`, { mode: 0o600 });
      fs.chmodSync(credFile, 0o600);
    }
    const script = fileURLToPath(new URL("./visible_servers_probe.mjs", import.meta.url));
    const args = options.args ?? (options.ordinary ? [] : ["--testbed-input", file, "--credential-file", credFile]);
    const baseEnv = {
      STAMP_RUN_CASE_ID: runCase,
      QA_TEST_EMAIL: "legacy@example.invalid",
      QA_TEST_PASSWORD: "legacy-config-password",
      EXPECTED_RUNTIME_EMAIL: runtimeEmail,
      EXPECTED_RUNTIME_PASSWORD: runtimePassword,
      CALLS: calls, DEDICATED: options.ordinary ? "" : "1", FIXTURE: JSON.stringify(data),
      ...options.env,
    };
    const result = spawnSync(process.execPath, ["--import", "data:text/javascript," + encodeURIComponent(mock), script, ...args], {
      encoding: "utf8", env: baseEnv,
    });
    assert.equal(result.status, expectedCode, name + " " + result.stderr);
    const output = (result.stdout + result.stderr).trim();
    assert.equal(output, expectedLine, name);
    if (!options.ordinary) {
      for (const privateValue of [...Object.values(target), input.nonce, input.attempt_id, "legacy-config-password", runtimePassword, "legacy@example.invalid", runtimeEmail, "test-refresh", "test-access"]) {
        assert.ok(!output.includes(privateValue), name);
      }
    }
    const paths = fs.readFileSync(calls, "utf8").trim().split("\n").filter(Boolean);
    assert.deepEqual(paths, options.missing || options.missingCred || options.noCalls ? [] : options.ordinary ? ["/api/auth/login", "/api/servers"] :
      ["/api/auth/login", "/api/servers", "/api/auth/logout"], name);
    if (!options.missing) assert.deepEqual(JSON.parse(fs.readFileSync(file)), input, "the device login can consume the same input");
    if (!options.missingCred && !options.setupCred) {
      assert.equal(fs.readFileSync(credFile, "utf8"), `${runtimeEmail}\n${runtimePassword}\n`, "the device login can consume the same credential file");
    }
  }
  try {
    run("assigned", fixture, 0, "QA account preflight: account_and_server_confirmed");
    run("missing credential file does not fall back to QA_TEST_*", fixture, 2, "QA account preflight: unavailable (credential)", {
      missingCred: true,
    });
    run("missing --credential-file flag fails closed without falling back to env", fixture, 2, "QA account preflight: unavailable (credential)", {
      args: ["--testbed-input", file], noCalls: true,
      env: { QA_LOGIN_ACCEPTANCE_EMAIL: runtimeEmail, QA_LOGIN_ACCEPTANCE_PASSWORD: runtimePassword },
    });
    run("group-readable credential file fails closed", fixture, 2, "QA account preflight: unavailable (credential)", {
      setupCred: (p) => { fs.writeFileSync(p, `${runtimeEmail}\n${runtimePassword}\n`, { mode: 0o640 }); fs.chmodSync(p, 0o640); },
      noCalls: true,
    });
    run("malformed credential file fails closed", fixture, 2, "QA account preflight: unavailable (credential)", {
      setupCred: (p) => { fs.writeFileSync(p, "no-password-line\n", { mode: 0o600 }); fs.chmodSync(p, 0o600); },
      noCalls: true,
    });
    run("another user", { ...fixture, user: {id:"another-user"} }, 1, "QA account preflight: configured_user_mismatch");
    for (const servers of [[{...member, id:"another-id"}], [{...member, slug:"another-slug"}], [member, member]]) {
      run("different or ambiguous membership", { ...fixture, servers }, 1, "QA account preflight: configured_membership_mismatch");
    }
    run("production visibility still blocks", { ...fixture, servers:[member,{id:"different",name:PRODUCTION_SERVER_NAME,slug:"wrong"}] },
      1, "QA account preflight: production_visible");
    run("unavailable input", fixture, 2, "QA account preflight: unavailable (contract)", { missing:true });
    run("rate limit", { ...fixture, serverStatus:429 }, 2, "QA account preflight: unavailable (ratelimit)");
    run("unconfirmed logout", { ...fixture, logoutStatus:503 }, 2, "QA account preflight: unavailable (infra)");
    run("ordinary receipt", fixture, 0, `visible-servers probe: PASS [clean] QA account ${target.user_id} sees 1: ${REQUIRED_QA_SERVER}`, { ordinary:true });
  } finally {
    fs.rmSync(directory, { recursive:true, force:true });
  }
});
