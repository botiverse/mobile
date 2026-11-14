// Argus's round-3 replays of #4806 (fake data only): the web session is a mandatory cleanup item
// once POST auth/login was sent, as the agent key is once its mint was sent.
//   late-login-signal       login committed, SIGTERM while its answer is pending
//   lost-login-response     login committed, answer lost
//   partial-web-login       200 with an access token but no refreshToken
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const SCRIPT = path.join(ROOT, "scripts/qa/old_cli_task.mjs");
const ARGS = ["--env", "staging", "--server-name", "TestSrv", "--channel", "qa-test"];
const baseEnv = (d, stub, extra = {}) => ({ ...process.env, NODE_OPTIONS: `--require=${stub}`, QA_LOGIN_EMAIL: "fake@example.invalid", QA_LOGIN_PASSWORD: "fake", GITHUB_STEP_SUMMARY: "", TMPDIR: d, ...extra });

test("round 3 / late-login-signal: SIGTERM while the login answer is pending -> cleanup waits, signs that exact session out, proves it refused", async () => {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), "r3-login-sig-"));
  const stub = path.join(d, "fake-net.cjs");
  const trace = path.join(d, "trace");
  const pending = path.join(d, "pending");
  const committed = path.join(d, "committed");
  const out = path.join(d, "out.json");
  fs.writeFileSync(stub, `const fs=require('fs');const wait=ms=>new Promise(r=>setTimeout(r,ms));const res=(status,json)=>({status,json:async()=>json});global.fetch=async(url,opts={})=>{const p=new URL(url).pathname,m=opts.method||'GET';const body=opts.body?JSON.parse(opts.body):{};fs.appendFileSync(process.env.FAKE_TRACE,m+' '+p+(body.refreshToken?' '+body.refreshToken:'')+'\\n');if(p.endsWith('/auth/login')){fs.writeFileSync(process.env.COMMITTED,'session created');fs.writeFileSync(process.env.PENDING,'yes');await wait(200);return res(200,{accessToken:'fake-access',refreshToken:'fake-refresh'});}if(p.endsWith('/servers'))return res(200,[{id:'s',name:'TestSrv'}]);if(p.endsWith('/agents')&&m==='GET')return res(200,[{id:'a',name:'qa-old-cli-task',external:true}]);if(p.endsWith('/channels'))return res(200,[{id:'c',name:'qa-test',type:'channel'}]);if(p.endsWith('/members'))return res(200,{});if(p.includes('/tasks/channel/'))return res(200,[]);if(p.endsWith('/auth/logout'))return res(200,{});if(p.endsWith('/auth/refresh'))return res(401,{});throw Error('unexpected '+m+' '+p)};`);
  const child = spawn(process.execPath, [SCRIPT, ...ARGS, "--out", out], { env: baseEnv(d, stub, { FAKE_TRACE: trace, PENDING: pending, COMMITTED: committed }), stdio: "ignore" });
  for (let i = 0; i < 500 && !fs.existsSync(pending); i += 1) await new Promise((r) => setTimeout(r, 10));
  assert.ok(fs.existsSync(pending));
  child.kill("SIGTERM");
  const code = await new Promise((r) => child.on("close", r));
  const summary = JSON.parse(fs.readFileSync(out, "utf8"));
  const calls = fs.readFileSync(trace, "utf8").trim().split("\n");
  const intent = JSON.parse(fs.readFileSync(path.join(d, "old-cli-session-intent.json"), "utf8"));
  fs.rmSync(d, { recursive: true, force: true });
  assert.equal(code, 1);
  assert.notEqual(summary.result, "pass");
  assert.equal(summary.web_login, "ok");
  assert.equal(summary.cleanup.web, "ok");
  assert.equal(summary.web_session_signed_out, true);
  // That exact session: logout and the refresh check with its refresh token.
  assert.ok(calls.includes("POST /api/auth/logout fake-refresh"));
  assert.ok(calls.includes("POST /api/auth/refresh fake-refresh"));
  // Nothing after the signal: no further reads or writes of the run.
  assert.ok(!calls.some((c) => c.startsWith("GET /api/servers") || c.startsWith("POST /api/agents")), calls.join("\n"));
  assert.equal(calls.filter((c) => c.startsWith("POST /api/auth/login")).length, 1);
  assert.ok(intent.login_requested_at);
});

test("round 3 / lost-login-response: the login answer is lost after a commit -> web unknown, cleanup not complete, exit 1, no retry", () => {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), "r3-login-loss-"));
  const stub = path.join(d, "fake.cjs");
  const out = path.join(d, "out.json");
  const calls = path.join(d, "calls");
  fs.writeFileSync(stub, `const fs=require('fs');global.fetch=async(url,opts={})=>{fs.appendFileSync(process.env.CALLS,(opts.method||'GET')+' '+new URL(url).pathname+'\\n');fs.writeFileSync(process.env.COMMITTED,'fake session created');throw Error('simulated response lost after upstream commit')};`);
  const r = spawnSync(process.execPath, [SCRIPT, ...ARGS, "--out", out], { env: baseEnv(d, stub, { COMMITTED: path.join(d, "committed"), CALLS: calls }), encoding: "utf8" });
  const s = JSON.parse(fs.readFileSync(out, "utf8"));
  const sent = fs.readFileSync(calls, "utf8").trim().split("\n");
  fs.rmSync(d, { recursive: true, force: true });
  assert.equal(r.status, 1);
  assert.notEqual(s.result, "pass");
  assert.equal(s.web_login, "unknown");
  assert.equal(s.cleanup.web, "unknown");
  assert.equal(s.cleanup_complete, false);
  assert.deepEqual(sent, ["POST /api/auth/login"], "one login, never retried, no global logout");
});

test("round 3 / partial-web-login: 200 with an access token but no refreshToken -> web unknown, cleanup not complete, nothing else done", () => {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), "r3-login-partial-"));
  const stub = path.join(d, "fake.cjs");
  const out = path.join(d, "out.json");
  const trace = path.join(d, "trace");
  fs.writeFileSync(stub, `const fs=require('fs');const res=(status,json)=>({status,json:async()=>json});global.fetch=async(url,opts={})=>{const p=new URL(url).pathname,m=opts.method||'GET';fs.appendFileSync(process.env.FAKE_TRACE,m+' '+p+'\\n');if(p.endsWith('/auth/login'))return res(200,{accessToken:'fake-access'});if(p.endsWith('/servers'))return res(200,[]);throw Error('unexpected '+p)};`);
  const r = spawnSync(process.execPath, [SCRIPT, ...ARGS, "--out", out], { env: baseEnv(d, stub, { FAKE_TRACE: trace }), encoding: "utf8" });
  const s = JSON.parse(fs.readFileSync(out, "utf8"));
  const calls = fs.readFileSync(trace, "utf8").trim().split("\n");
  fs.rmSync(d, { recursive: true, force: true });
  assert.equal(r.status, 1);
  assert.equal(s.result, "unknown");
  assert.equal(s.web_login, "incomplete");
  assert.equal(s.cleanup.web, "unknown");
  assert.equal(s.cleanup_complete, false);
  assert.deepEqual(calls, ["POST /api/auth/login"]);
});

test("round 3 / never sent: no QA credentials -> no login call, web not_applicable, still a failing run", () => {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), "r3-login-unsent-"));
  const stub = path.join(d, "fake.cjs");
  const out = path.join(d, "out.json");
  fs.writeFileSync(stub, `global.fetch=async()=>{throw Error('no call expected')};`);
  const r = spawnSync(process.execPath, [SCRIPT, ...ARGS, "--out", out], { env: baseEnv(d, stub, { QA_LOGIN_PASSWORD: "" }), encoding: "utf8" });
  const s = JSON.parse(fs.readFileSync(out, "utf8"));
  fs.rmSync(d, { recursive: true, force: true });
  assert.equal(r.status, 1);
  assert.equal(s.cleanup.web, "not_applicable");
  assert.equal(s.web_login, "none");
});
