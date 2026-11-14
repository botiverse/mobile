// Argus's #4806 replays of the task v2 scenario and the agent test-data script, against fake
// servers / fake CLIs only (no network, no device, no real credential).
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { run as runTaskV2 } from "./scenarios/task_v2.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

// ---------- task_v2.mjs with a fake web API and no device ----------
function taskCtx(web, options = { "server-name": "OranixSlockTest", channel: "qa-fixture" }) {
  const out = fs.mkdtempSync(path.join(os.tmpdir(), "task-v2-replay-"));
  return {
    out,
    ctx: {
      summary: {}, PKG: "build.raft.app", OUT: out, account: {},
      option: (n, d) => options[n] ?? d,
      log: () => {}, label: () => "", find: () => null, tapNode: () => {}, shell: () => "", nodes: () => [],
      sleep: async () => {}, launchApp: () => {}, dismissSystemDialog: async () => false, nextStepIndex: () => 1,
      adb: () => "", step: async (_n, fn) => fn(), web,
    },
  };
}

test("task_v2 (Argus probe): servers 403 -> nothing covered -> partial, not pass", async () => {
  const { ctx, out } = taskCtx(async () => ({ status: 403, json: { error: "simulated fixture inaccessible" } }));
  const report = await runTaskV2(ctx);
  assert.ok(Object.values(report.results).every((r) => r.result === "not_covered"));
  assert.notEqual(report.result, "pass");
  assert.equal(report.result, "partial");
  assert.ok(report.not_pass_reasons.includes("s1: not_covered"));
  fs.rmSync(out, { recursive: true, force: true });
});

test("task_v2 (Argus probe): setup write 503 + task DELETE 503 -> unknown, not pass; cleanup reported", async () => {
  const calls = [];
  const { ctx, out } = taskCtx(async (m, p) => {
    calls.push(`${m} ${p}`);
    if (p === "servers") return { status: 200, json: [{ id: "fake-server", name: "OranixSlockTest", slug: "orani" }] };
    if (p === "channels") return { status: 200, json: [{ id: "first", name: "qa-fixture", type: "channel" }, { id: "second", name: "general", type: "channel" }] };
    if (m === "POST" && p === "tasks/channel/first") return { status: 201, json: { tasks: [{ id: "known-created", taskNumber: 1, title: "fake", sourceMessageId: "source" }] } };
    if (p === "tasks/known-created/conversation") return { status: 503, json: { error: "synthetic setup failure" } };
    if (m === "DELETE") return { status: 503, json: { error: "synthetic delete failed" } };
    if (p.startsWith("tasks/server")) return { status: 200, json: [] };
    return { status: 500, json: { error: "synthetic" } };
  });
  const report = await runTaskV2(ctx);
  assert.equal(report.result, "unknown");
  assert.ok(report.cleanup.deleted_tasks.length >= 1 && report.cleanup.deleted_tasks.every((d) => d.outcome === "unknown"));
  assert.ok(report.not_pass_reasons.some((r) => /cleanup delete task/.test(r)));
  assert.ok(!calls.some((c) => c.includes("second")), "never touches another channel");
  fs.rmSync(out, { recursive: true, force: true });
});

test("task_v2 (Argus probe): ambiguous create 503 -> stops as unknown, one POST, never general", async () => {
  const calls = [];
  const { ctx, out } = taskCtx(async (m, p) => {
    calls.push([m, p]);
    if (p === "servers") return { status: 200, json: [{ id: "s", name: "OranixSlockTest" }] };
    if (p === "channels") return { status: 200, json: [{ id: "qa", name: "qa-fixture", type: "channel" }, { id: "general", name: "general", type: "channel" }] };
    if (p === "tasks/channel/qa") return { status: 503, json: { error: "simulated response lost after server-side create" } };
    if (p === "tasks/channel/general") return { status: 403, json: { error: "refused" } };
    if (p.startsWith("tasks/server")) return { status: 200, json: [] };
    return { status: 500, json: { error: "unknown" } };
  });
  const report = await runTaskV2(ctx);
  const posts = calls.filter(([m]) => m === "POST").map(([, p]) => p);
  assert.deepEqual(posts, ["tasks/channel/qa"]);
  assert.equal(report.result, "unknown");
  assert.equal(report.unknown_writes.length, 1);
  assert.equal(report.results.s5.result, "not_covered");
  fs.rmSync(out, { recursive: true, force: true });
});

test("task_v2: an ambiguous create that did land is found by its run title and deleted", async () => {
  const deleted = [];
  let title = "";
  const { ctx, out } = taskCtx(async (m, p, { body } = {}) => {
    if (p === "servers") return { status: 200, json: [{ id: "s", name: "OranixSlockTest" }] };
    if (p === "channels") return { status: 200, json: [{ id: "qa", name: "qa-fixture", type: "channel" }] };
    if (m === "POST" && p === "tasks/channel/qa") { title = body.tasks[0].title; return { status: 504, json: {} }; }
    if (m === "GET" && p === "tasks/channel/qa") return { status: 200, json: { tasks: [{ id: "landed", taskNumber: 9, title }] } };
    if (m === "DELETE") { deleted.push(p); return { status: 200, json: {} }; }
    if (p.startsWith("tasks/server")) return { status: 200, json: [] };
    return { status: 500, json: {} };
  });
  const report = await runTaskV2(ctx);
  assert.equal(report.result, "unknown");
  assert.match(report.reconciliation, /was created \(#9\)/);
  assert.deepEqual(deleted, ["tasks/landed"]);
  fs.rmSync(out, { recursive: true, force: true });
});

test("task_v2: no channel / no server given -> refuses to start (no fallback)", async () => {
  const { ctx, out } = taskCtx(async () => { throw new Error("must not call the API"); }, { "server-name": "OranixSlockTest" });
  await assert.rejects(runTaskV2(ctx), /needs --server-name and --channel/);
  fs.rmSync(out, { recursive: true, force: true });
});

test("task_v2: two channels with the exact name -> nothing written", async () => {
  const posts = [];
  const { ctx, out } = taskCtx(async (m, p) => {
    if (m === "POST") posts.push(p);
    if (p === "servers") return { status: 200, json: [{ id: "s", name: "OranixSlockTest" }] };
    if (p === "channels") return { status: 200, json: [{ id: "a", name: "qa-fixture", type: "channel" }, { id: "b", name: "qa-fixture", type: "private" }] };
    if (p.startsWith("tasks/server")) return { status: 200, json: [] };
    return { status: 500, json: {} };
  });
  const report = await runTaskV2(ctx);
  assert.deepEqual(posts, []);
  assert.notEqual(report.result, "pass");
  fs.rmSync(out, { recursive: true, force: true });
});

// ---------- old_cli_task.mjs with a fake fetch (preloaded) and a fake npm ----------
const TITLE = "QA: 老版本 CLI 建的任务（task v2 放量前验证，可删）";
function oldCliFake({ revoke = 204, revokedAt = "2026-11-14T00:00:00Z", logout = 200, refresh = 401, mint = 201, mintDelay = 0, listNone = false, args = ["--server-name", "OranixSlockTest", "--channel", "qa-test-create-7842"] } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "old-cli-replay-"));
  const tmp = path.join(dir, "tmp");
  fs.mkdirSync(tmp);
  const trace = path.join(dir, "trace");
  const stub = path.join(dir, "fake-net.cjs");
  const config = { revoke, revokedAt, logout, refresh, mint, mintDelay, listNone, title: TITLE };
  fs.writeFileSync(stub, `
const fs = require("fs");
const C = ${JSON.stringify(config)};
let mintedName = null;
const res = (status, json) => ({ status, json: async () => json });
global.fetch = async (url, opts = {}) => {
  const u = new URL(url), method = opts.method || "GET", p = u.pathname;
  const body = opts.body ? JSON.parse(opts.body) : null;
  fs.appendFileSync(process.env.FAKE_TRACE, method + " " + p + "\\n");
  if (p.endsWith("/auth/login")) return res(200, { accessToken: "h." + Buffer.from(JSON.stringify({ familyId: "fam12345-6789" })).toString("base64url") + ".s", refreshToken: "fake-refresh" });
  if (p.endsWith("/servers")) return res(200, [{ id: "fake-server", name: "OranixSlockTest" }]);
  if (p.endsWith("/agents") && method === "GET") return res(200, [{ id: "fake-agent", name: "qa-old-cli-task", external: true }]);
  if (p.endsWith("/channels")) return res(200, [{ id: "fake-channel", name: "qa-test-create-7842", type: "channel" }, { id: "general", name: "general", type: "channel" }]);
  if (p.endsWith("/members")) return res(200, { ok: true });
  if (p.includes("/tasks/channel/")) return res(200, [{ id: "fake-task", taskNumber: 42, createdById: "fake-agent", title: C.title, sourceMessageId: "fake-source", createdAt: "2026-11-14T00:00:00Z" }]);
  if (p.endsWith("/credentials") && method === "POST") { mintedName = body.name; if (C.mintDelay) await new Promise((r) => setTimeout(r, C.mintDelay)); if (C.mint === "noid") return res(201, { apiKey: "sk_agent_fakefakefake", scopes: ["tasks"] }); return C.mint === 201 ? res(201, { apiKey: "sk_agent_fakefakefake", credentialId: "fake-credential-id", scopes: ["tasks"] }) : res(C.mint, { error: "lost" }); }
  if (p.endsWith("/credentials") && method === "GET") return res(200, { credentials: C.listNone ? [{ id: "someone-elses", name: "another run", revokedAt: null }] : [{ id: "fake-credential-id", name: mintedName, revokedAt: C.revokedAt }] });
  if (p.includes("/credentials/") && method === "DELETE") return res(C.revoke, C.revoke === 204 ? null : { error: "fake failed" });
  if (p.includes("/messages/context/")) return res(200, { id: "fake-source", senderId: "fake-agent", content: C.title });
  if (p.endsWith("/auth/logout")) return res(C.logout, { ok: C.logout === 200 });
  if (p.endsWith("/auth/refresh")) return C.refresh === 200 ? res(200, { accessToken: "rotated-access", refreshToken: "rotated-refresh" }) : res(C.refresh, { code: "session_revoked" });
  throw Error("unexpected fake request " + method + " " + p);
};`);
  const bin = path.join(dir, "bin");
  fs.mkdirSync(bin);
  fs.writeFileSync(path.join(bin, "npm"), `#!/bin/sh
echo "NPM $*" >> "$FAKE_TRACE"
mkdir -p node_modules/@botiverse/raft/dist
printf '%s' '{"version":"synthetic"}' > node_modules/@botiverse/raft/package.json
printf '%s\\n' 'console.log("Created by: @qa-old-cli-task");console.log("Source: msg=fake in #qa-test-create-7842 — @qa-old-cli-task: x")' > node_modules/@botiverse/raft/dist/raft.js
`, { mode: 0o755 });
  const outFile = path.join(dir, "out.json");
  const env = { ...process.env, PATH: `${bin}:${process.env.PATH}`, NODE_OPTIONS: `--require=${stub}`, TMPDIR: tmp, FAKE_TRACE: trace, QA_LOGIN_EMAIL: "synthetic@example.invalid", QA_LOGIN_PASSWORD: "fakepassword", GITHUB_STEP_SUMMARY: "" };
  const argv = [path.join(ROOT, "scripts/qa/old_cli_task.mjs"), "--env", "staging", ...args, "--out", outFile];
  const collect = (status, output) => {
    const keyFiles = [];
    const walk = (p) => { for (const e of fs.readdirSync(p, { withFileTypes: true })) { const v = path.join(p, e.name); if (e.isDirectory()) walk(v); else if (e.name === "credential.json") keyFiles.push(v); } };
    walk(tmp);
    const read = (f) => (fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, "utf8")) : null);
    const summary = read(outFile);
    const intent = read(path.join(dir, "old-cli-key-intent.json"));
    const calls = fs.existsSync(trace) ? fs.readFileSync(trace, "utf8").trim().split("\n").filter(Boolean) : [];
    fs.rmSync(dir, { recursive: true, force: true });
    return { exit: status, summary, intent, keyFiles, calls, stdout: output };
  };
  return { env, argv, trace, collect };
}
function oldCliRun(options) {
  const f = oldCliFake(options);
  const r = spawnSync(process.execPath, f.argv, { env: f.env, encoding: "utf8", timeout: 60_000 });
  return f.collect(r.status, r.stdout + r.stderr);
}
// SIGTERM once the fake server has received the mint request (its answer is still delayed).
async function oldCliRunKilledDuringMint(options) {
  const f = oldCliFake(options);
  const child = spawn(process.execPath, f.argv, { env: f.env });
  let output = "";
  child.stdout.on("data", (d) => { output += d; });
  child.stderr.on("data", (d) => { output += d; });
  const exited = new Promise((resolve) => child.on("exit", (code) => resolve(code)));
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    const t = fs.existsSync(f.trace) ? fs.readFileSync(f.trace, "utf8") : "";
    if (t.includes("POST /api/agents/fake-agent/credentials")) break;
    await new Promise((r) => setTimeout(r, 50));
  }
  child.kill("SIGTERM");
  const code = await Promise.race([exited, new Promise((r) => setTimeout(() => { child.kill("SIGKILL"); r("killed"); }, 40_000))]);
  return f.collect(code, output);
}

test("old_cli_task positive control: key revoked (read back), web session refused, key file gone -> pass, exit 0", () => {
  const r = oldCliRun();
  assert.equal(r.exit, 0, r.stdout.slice(-800));
  assert.equal(r.summary.result, "pass");
  assert.equal(r.summary.key_revoked, true);
  assert.equal(r.summary.web_session_signed_out, true);
  assert.equal(r.summary.local_key_file_removed, true);
  assert.equal(r.keyFiles.length, 0);
  assert.equal(r.summary.credential_id_prefix, "fake-cre");
  assert.equal(r.summary.web_session_family_prefix, "fam12345");
  // Only GitHub's own masking command carries it (the runner consumes that line).
  assert.ok(!r.stdout.split("\n").filter((l) => !l.startsWith("::add-mask::")).join("\n").includes("sk_agent_fakefakefake"), "the key is never printed");
});

test("old_cli_task (Argus probe): revoke 503 + logout 503 -> not pass, exit 1, key file removed, correlation kept", () => {
  const r = oldCliRun({ revoke: 503, revokedAt: null, logout: 503, refresh: 503 });
  assert.equal(r.exit, 1);
  assert.notEqual(r.summary.result, "pass");
  assert.equal(r.summary.key_revoked, false);
  assert.equal(r.summary.web_session_signed_out, "unknown");
  assert.equal(r.summary.cleanup_complete, false);
  assert.equal(r.keyFiles.length, 0);
  assert.ok(r.summary.credential_id_prefix && r.summary.credential_name && r.summary.web_session_family_prefix);
  // Never a second mint, never a global logout.
  assert.equal(r.calls.filter((c) => c === "POST /api/agents/fake-agent/credentials").length, 1);
});

test("old_cli_task: logout 200 alone is not proof; a still-working refresh fails the cleanup", () => {
  const r = oldCliRun({ logout: 200, refresh: 200 });
  assert.equal(r.exit, 1);
  assert.equal(r.summary.web_session_signed_out, false);
  assert.equal(r.summary.result, "cleanup_incomplete");
  // The rotated session is signed out too.
  assert.equal(r.calls.filter((c) => c === "POST /api/auth/logout").length, 2);
});

test("old_cli_task: revoke 204 but the key reads back live -> not pass", () => {
  const r = oldCliRun({ revoke: 204, revokedAt: null });
  assert.equal(r.exit, 1);
  assert.equal(r.summary.key_revoked, false);
  assert.notEqual(r.summary.result, "pass");
});

test("old_cli_task: a mint with no clear answer -> the key named for this run is revoked, never re-minted, unknown", () => {
  const r = oldCliRun({ mint: 503 });
  assert.equal(r.exit, 1);
  assert.equal(r.summary.result, "unknown");
  assert.match(r.summary.mint_reconciliation, /1 key\(s\) named for this run/);
  assert.equal(r.calls.filter((c) => c === "POST /api/agents/fake-agent/credentials").length, 1);
  assert.ok(r.calls.includes("DELETE /api/agents/fake-agent/credentials/fake-credential-id"));
});

test("old_cli_task: no --channel -> refuses to start, writes nothing", () => {
  const r = oldCliRun({ args: ["--server-name", "OranixSlockTest"] });
  assert.notEqual(r.exit, 0);
  assert.deepEqual(r.calls, []);
});

test("old_cli_task (Argus round 2): SIGTERM during a slow mint -> no profile / CLI after it; the minted key is revoked and read back", async () => {
  const r = await oldCliRunKilledDuringMint({ mintDelay: 2500 });
  assert.equal(r.exit, 1);
  assert.equal(r.summary.result, "unknown");
  assert.match(r.summary.error, /stopped by SIGTERM/);
  // The intent was recorded before the call.
  assert.equal(r.intent.credential_name, r.summary.credential_name);
  // After the signal: no key file, no npm install, no CLI.
  assert.equal(r.keyFiles.length, 0);
  assert.ok(!r.calls.some((c) => c.startsWith("NPM")), r.calls.join("\n"));
  // The key the slow mint made is revoked and read back; never minted twice.
  assert.equal(r.summary.key_mint, "ok");
  assert.ok(r.calls.includes("DELETE /api/agents/fake-agent/credentials/fake-credential-id"));
  assert.equal(r.summary.key_revoked, true);
  assert.equal(r.calls.filter((c) => c === "POST /api/agents/fake-agent/credentials").length, 1);
});

test("old_cli_task (Argus round 2): SIGTERM while the mint never answers -> key unknown with its run name, exit 1, no retry", async () => {
  const r = await oldCliRunKilledDuringMint({ mintDelay: 600_000, args: ["--server-name", "OranixSlockTest", "--channel", "qa-test-create-7842", "--mint-settle-ms", "800"] });
  assert.equal(r.exit, 1);
  assert.equal(r.summary.key_mint, "pending");
  assert.equal(r.summary.key_revoked, "unknown");
  assert.match(r.summary.key_unknown_reason, /reconcile by credential_name/);
  assert.equal(r.summary.cleanup.key, "unknown");
  assert.ok(r.summary.credential_name && r.intent.credential_name === r.summary.credential_name);
  assert.equal(r.calls.filter((c) => c === "POST /api/agents/fake-agent/credentials").length, 1);
  assert.ok(!r.calls.some((c) => c.startsWith("DELETE")));
  assert.equal(r.keyFiles.length, 0);
  // The web session is still signed out and checked.
  assert.equal(r.summary.web_session_signed_out, true);
});

test("old_cli_task (Argus round 2): mint 201 with apiKey but no credentialId -> unknown; exact per-run key found by name, revoked, read back; exit 1", () => {
  const r = oldCliRun({ mint: "noid" });
  assert.equal(r.exit, 1);
  assert.equal(r.summary.result, "unknown");
  assert.equal(r.summary.key_mint, "incomplete");
  assert.match(r.summary.mint_reconciliation, /1 key\(s\) named for this run/);
  assert.ok(r.calls.includes("DELETE /api/agents/fake-agent/credentials/fake-credential-id"));
  assert.equal(r.summary.key_revoked, true);
  assert.equal(r.keyFiles.length, 0);
  assert.equal(r.calls.filter((c) => c === "POST /api/agents/fake-agent/credentials").length, 1);
});

test("old_cli_task (Argus round 2): mint 201 without credentialId and no key of this run's name -> key unknown, nothing revoked, exit 1", () => {
  const r = oldCliRun({ mint: "noid", listNone: true });
  assert.equal(r.exit, 1);
  assert.notEqual(r.summary.result, "pass");
  assert.equal(r.summary.key_revoked, "unknown");
  assert.equal(r.summary.cleanup.key, "unknown");
  assert.equal(r.summary.cleanup_complete, false);
  assert.ok(!r.calls.some((c) => c.startsWith("DELETE")), "another run's key is never touched");
});

test("old_cli_task: a mint never sent (login refused before it) -> key not_applicable; the run still fails", () => {
  const r = oldCliRun({ args: ["--server-name", "NoSuchServer", "--channel", "qa-test-create-7842"] });
  assert.equal(r.exit, 1);
  assert.equal(r.summary.cleanup.key, "not_applicable");
  assert.ok(!r.calls.some((c) => c.includes("/credentials")));
});
