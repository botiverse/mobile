// Argus's round-2 replays of #4806 (fake data only), as tests of the fixed behaviour:
//   signal-inflight  SIGTERM while the key mint is in flight; the key commits later
//   unbound-s5       s5 must not read/open a matching task in another channel
//   mint-missing-id  mint 201 with a key but no credentialId
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { run as runTaskV2 } from "./scenarios/task_v2.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const SCRIPT = path.join(ROOT, "scripts/qa/old_cli_task.mjs");
const ARGS = ["--env", "staging", "--server-name", "TestSrv", "--channel", "qa-test"];

test("round 2 / signal-inflight: SIGTERM mid-mint -> no later side effects; the late key is revoked by its exact id", async () => {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), "r2-signal-"));
  const stub = path.join(d, "fake-net.cjs");
  fs.writeFileSync(stub, `const fs=require('fs');const wait=ms=>new Promise(r=>setTimeout(r,ms));const res=(status,json)=>({status,json:async()=>json});global.fetch=async(url,opts={})=>{const p=new URL(url).pathname, m=opts.method||'GET';fs.appendFileSync(process.env.FAKE_TRACE,m+' '+p+'\\n');if(p.endsWith('/auth/login'))return res(200,{accessToken:'fake-token',refreshToken:'fake-refresh'});if(p.endsWith('/servers'))return res(200,[{id:'s',name:'TestSrv'}]);if(p.endsWith('/agents')&&m==='GET')return res(200,[{id:'a',name:'qa-old-cli-task',external:true}]);if(p.endsWith('/channels'))return res(200,[{id:'c',name:'qa-test',type:'channel'}]);if(p.endsWith('/members'))return res(200,{});if(p.includes('/tasks/channel/'))return res(200,[]);if(p.endsWith('/credentials')&&m==='POST'){fs.writeFileSync(process.env.WAIT_MINT,'inflight');await wait(500);fs.writeFileSync(process.env.LATE_COMMIT,'committed');return res(201,{apiKey:'fake-key',credentialId:'fake-id'});}if(p.endsWith('/credentials')&&m==='GET')return res(200,{credentials:[]});if(p.includes('/credentials/')&&m==='DELETE')return res(204,{});if(p.endsWith('/auth/logout')){await wait(1000);return res(200,{})}if(p.endsWith('/auth/refresh'))return res(401,{code:'session_revoked'});throw Error('fake unexpected '+p)};`);
  const out = path.join(d, "out.json");
  const trace = path.join(d, "trace");
  const flag = path.join(d, "inflight");
  const commit = path.join(d, "commit");
  const bin = path.join(d, "bin");
  fs.mkdirSync(bin);
  fs.writeFileSync(path.join(bin, "npm"), `#!/bin/sh\necho NPM >> "$FAKE_TRACE"\nexit 77\n`, { mode: 0o755 });
  const env = { ...process.env, PATH: `${bin}:${process.env.PATH}`, NODE_OPTIONS: `--require=${stub}`, QA_LOGIN_EMAIL: "fake@example.invalid", QA_LOGIN_PASSWORD: "fake", FAKE_TRACE: trace, WAIT_MINT: flag, LATE_COMMIT: commit, GITHUB_STEP_SUMMARY: "", TMPDIR: d };
  const child = spawn(process.execPath, [SCRIPT, ...ARGS, "--out", out], { env, stdio: "ignore" });
  for (let i = 0; i < 500 && !fs.existsSync(flag); i += 1) await new Promise((r) => setTimeout(r, 20));
  assert.ok(fs.existsSync(flag), "the mint request reached the fake server");
  child.kill("SIGTERM");
  const code = await new Promise((r) => child.on("close", r));
  const calls = fs.readFileSync(trace, "utf8").trim().split("\n");
  const result = JSON.parse(fs.readFileSync(out, "utf8"));
  const intent = JSON.parse(fs.readFileSync(path.join(d, "old-cli-key-intent.json"), "utf8"));
  const keyFiles = [];
  const walk = (p) => { for (const e of fs.readdirSync(p, { withFileTypes: true })) { const v = path.join(p, e.name); if (e.isDirectory()) walk(v); else if (e.name === "credential.json") keyFiles.push(v); } };
  walk(d);
  fs.rmSync(d, { recursive: true, force: true });
  assert.equal(result.key_mint, "ok", "the late mint answer was awaited");
  assert.notEqual(code, 0);
  assert.notEqual(result.result, "pass");
  assert.ok(calls.includes("DELETE /api/agents/a/credentials/fake-id"), calls.join("\n"));
  assert.ok(!calls.includes("NPM"), "no npm after the signal");
  assert.equal(keyFiles.length, 0, "no key profile written after the signal");
  assert.equal(calls.filter((c) => c === "POST /api/agents/a/credentials").length, 1, "never minted again");
  assert.ok("key" in result.cleanup, "the key is a cleanup item");
  assert.notEqual(result.cleanup_complete, true);
  assert.equal(intent.credential_name, result.credential_name);
});

test("round 2 / unbound-s5: a matching task in another channel is never read, opened or screenshotted", async () => {
  const realNow = Date.now;
  let ticks = 1_000_000;
  Date.now = () => { ticks += 31_000; return ticks; };
  const d = fs.mkdtempSync(path.join(os.tmpdir(), "r2-channel-"));
  const calls = [];
  const deep = [];
  const steps = [];
  try {
    const ctx = {
      summary: {}, PKG: "build.raft.app", OUT: d, account: {},
      option: (n, def) => ({ "server-name": "TestSrv", channel: "qa-fixture" }[n] ?? def),
      log: () => {}, label: () => "", find: () => null, tapNode: () => {},
      shell: (s) => { if (s.startsWith("am start")) deep.push(s); return ""; },
      nodes: () => [], sleep: async () => {}, launchApp: () => {}, dismissSystemDialog: async () => false, nextStepIndex: () => 1, adb: () => "",
      step: async (n, fn) => { steps.push(n); return fn(); },
      web: async (m, p) => {
        calls.push([m, p]);
        if (p === "servers") return { status: 200, json: [{ id: "s", name: "TestSrv", slug: "testsrv" }] };
        if (p === "channels") return { status: 200, json: [{ id: "qa", name: "qa-fixture", type: "channel" }] };
        if (p === "tasks/channel/qa" && m === "POST") return { status: 403, json: {} };
        if (p.startsWith("tasks/server")) return { status: 200, json: [{ id: "foreign", taskNumber: 8, title: "unrelated task", channelId: "foreign-channel", channelName: "general", sourceMessageId: "foreign-msg", createdById: "agent", createdByType: "agent", sourceSenderType: "agent", sourceSenderId: "agent", isLegacy: false, createdAt: "2026-01-01T00:00:00Z" }] };
        if (p === "messages/context/foreign-msg") return { status: 200, json: { id: "foreign-msg", content: "unrelated task", createdAt: "2026-01-01T00:00:01Z" } };
        return { status: 500, json: {} };
      },
    };
    const r = await runTaskV2(ctx);
    assert.ok(!calls.some(([, p]) => p.includes("foreign")), "no read of the foreign task's message");
    assert.ok(!deep.some((x) => x.includes("foreign-channel")), "no link into the foreign channel");
    assert.equal(deep.length, 0);
    assert.ok(!steps.some((n) => /^s5-(diag|open)/.test(n)), "no s5 screen steps");
    assert.equal(r.results.s5.result, "not_covered");
    assert.notEqual(r.result, "pass");
  } finally {
    Date.now = realNow;
    fs.rmSync(d, { recursive: true, force: true });
  }
});

test("round 2 / unbound-s5: setup refused before the channel was chosen -> s5 not_covered, no task listing", async () => {
  const calls = [];
  const d = fs.mkdtempSync(path.join(os.tmpdir(), "r2-noch-"));
  const ctx = {
    summary: {}, PKG: "build.raft.app", OUT: d, account: {}, option: (n, def) => ({ "server-name": "TestSrv", channel: "qa-fixture" }[n] ?? def),
    log: () => {}, label: () => "", find: () => null, tapNode: () => {}, shell: () => "", nodes: () => [], sleep: async () => {}, launchApp: () => {},
    dismissSystemDialog: async () => false, nextStepIndex: () => 1, adb: () => "", step: async (_n, fn) => fn(),
    web: async (m, p) => { calls.push(p); if (p === "servers") return { status: 200, json: [{ id: "s", name: "TestSrv" }] }; if (p === "channels") return { status: 403, json: {} }; return { status: 500, json: {} }; },
  };
  const r = await runTaskV2(ctx);
  fs.rmSync(d, { recursive: true, force: true });
  assert.equal(r.results.s5.result, "not_covered");
  assert.ok(!calls.some((p) => p.startsWith("tasks/server")));
});

test("round 2 / exact server: two servers with the name -> nothing written", () => {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), "r2-dupsrv-"));
  const stub = path.join(d, "fake-net.cjs");
  fs.writeFileSync(stub, `const fs=require('fs');const res=(s,j)=>({status:s,json:async()=>j});global.fetch=async(url,opts={})=>{const p=new URL(url).pathname,m=opts.method||'GET';fs.appendFileSync(process.env.FAKE_TRACE,m+' '+p+'\\n');if(p.endsWith('/auth/login'))return res(200,{accessToken:'t',refreshToken:'r'});if(p.endsWith('/servers'))return res(200,[{id:'s1',name:'TestSrv'},{id:'s2',name:'TestSrv'}]);if(p.endsWith('/agents')&&m==='GET')return res(200,[{id:'a',name:'qa-old-cli-task',external:true}]);if(p.endsWith('/channels'))return res(200,[{id:'c',name:'qa-test',type:'channel'}]);if(p.endsWith('/members'))return res(200,{});if(p.includes('/tasks/channel/'))return res(200,[]);if(p.endsWith('/auth/logout'))return res(200,{});if(p.endsWith('/auth/refresh'))return res(401,{});throw Error('unexpected '+m+' '+p)};`);
  const trace = path.join(d, "trace");
  const r = spawnSync(process.execPath, [SCRIPT, ...ARGS, "--out", path.join(d, "out.json")], { env: { ...process.env, NODE_OPTIONS: `--require=${stub}`, QA_LOGIN_EMAIL: "f@example.invalid", QA_LOGIN_PASSWORD: "f", FAKE_TRACE: trace, GITHUB_STEP_SUMMARY: "", TMPDIR: d }, encoding: "utf8" });
  const calls = fs.readFileSync(trace, "utf8").trim().split("\n");
  fs.rmSync(d, { recursive: true, force: true });
  assert.notEqual(r.status, 0);
  assert.ok(!calls.some((c) => /^POST \/api\/(agents|channels)/.test(c)), calls.join("\n"));
});

test("round 2 / mint-missing-id: 201 with a key but no credentialId -> unknown, reconciliation attempted, non-zero, never pass", () => {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), "r2-mint-no-id-"));
  const bin = path.join(d, "bin");
  fs.mkdirSync(bin);
  const stub = path.join(d, "fake-net.cjs");
  const trace = path.join(d, "trace");
  const out = path.join(d, "out.json");
  fs.writeFileSync(stub, `const fs=require('fs');const res=(status,json)=>({status,json:async()=>json});global.fetch=async(url,opts={})=>{const p=new URL(url).pathname,m=opts.method||'GET';fs.appendFileSync(process.env.FAKE_TRACE,m+' '+p+'\\n');if(p.endsWith('/auth/login'))return res(200,{accessToken:'fake-access',refreshToken:'fake-refresh'});if(p.endsWith('/servers'))return res(200,[{id:'s',name:'TestSrv'}]);if(p.endsWith('/agents')&&m==='GET')return res(200,[{id:'a',name:'qa-old-cli-task',external:true}]);if(p.endsWith('/channels'))return res(200,[{id:'c',name:'qa-test',type:'channel'}]);if(p.endsWith('/members'))return res(200,{});if(p.includes('/tasks/channel/'))return res(200,[{id:'t',taskNumber:1,createdById:'a',title:'QA: 老版本 CLI 建的任务（task v2 放量前验证，可删）',sourceMessageId:'msg',createdAt:'2026-01-01T00:00:00Z'}]);if(p.endsWith('/credentials')&&m==='POST')return res(201,{apiKey:'fake-key'});if(p.includes('/messages/context/'))return res(200,{id:'msg',senderId:'a',content:'x'});if(p.endsWith('/auth/logout'))return res(200,{});if(p.endsWith('/auth/refresh'))return res(401,{});if(p.includes('/credentials'))throw Error('should have called credential reconciliation');throw Error('unexpected '+p)};`);
  fs.writeFileSync(path.join(bin, "npm"), `#!/bin/sh\necho NPM >> "$FAKE_TRACE"\nmkdir -p node_modules/@botiverse/raft/dist\necho '{"version":"fake"}' > node_modules/@botiverse/raft/package.json\necho 'console.log("Source: @qa-old-cli-task: x")' > node_modules/@botiverse/raft/dist/raft.js\n`, { mode: 0o755 });
  const env = { ...process.env, NODE_OPTIONS: `--require=${stub}`, PATH: `${bin}:${process.env.PATH}`, QA_LOGIN_EMAIL: "fake@example.invalid", QA_LOGIN_PASSWORD: "fake", FAKE_TRACE: trace, GITHUB_STEP_SUMMARY: "", TMPDIR: d };
  const r = spawnSync(process.execPath, [SCRIPT, ...ARGS, "--out", out], { env, encoding: "utf8" });
  const v = JSON.parse(fs.readFileSync(out, "utf8"));
  const lines = fs.readFileSync(trace, "utf8").trim().split("\n");
  fs.rmSync(d, { recursive: true, force: true });
  assert.notEqual(r.status, 0);
  assert.notEqual(v.result, "pass");
  assert.equal(v.key_mint, "incomplete");
  assert.equal(v.cleanup.key, "unknown");
  assert.equal(v.cleanup_complete, false);
  // The read-only lookup by this run's key name was attempted; no second mint; no CLI after it.
  assert.ok(lines.includes("GET /api/agents/a/credentials"));
  assert.equal(lines.filter((x) => x === "POST /api/agents/a/credentials").length, 1);
  assert.ok(!lines.includes("NPM"));
});
