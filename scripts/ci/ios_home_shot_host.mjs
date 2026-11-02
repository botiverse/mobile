#!/usr/bin/env node
// Host half of the signed-in home screenshot (ios-simulator-app.yml, home_screenshot=true).
// The device half is SignedInHomeShotTests in compose/iosApp/iosAppUITests/QaAcceptanceLoopTests.swift;
// the two take turns over JSON files in --io (see that class for the sequence).
//
// Sign-in without typing into the web page: once the test has tapped "Sign in to Raft", the app
// has started a real app-login request (POST auth/app-login/start) and keeps it in its MMKV
// store. This script reads that request id from the app's data container, signs the QA account
// in through the API (QA_TEST_EMAIL / QA_TEST_PASSWORD, CI secrets), approves the request the
// way the web page's "Confirm Sign-In" does (POST auth/app-login/requests/:id/approve), and
// hands the returned callback URL to the app with `simctl openurl`. The app then exchanges the
// code with its own PKCE verifier, exactly as after a browser sign-in.
//
// Never printed: credentials, the access token, the approval code or the callback URL.
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { judgeVisibleServers } from "../qa/visible_servers_probe.mjs";

const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, value, index, all) => {
    if (value.startsWith("--")) pairs.push([value.slice(2), all[index + 1]]);
    return pairs;
  }, []),
);
const { udid, bundle, io, out } = args;
if (!udid || !bundle || !io || !out) {
  console.error("usage: ios_home_shot_host.mjs --udid U --bundle B --io DIR --out DIR");
  process.exit(2);
}
const base = (process.env.QA_API_BASE || "https://api.raft.build/api/").replace(/\/?$/, "/");
fs.mkdirSync(out, { recursive: true });

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
let shotIndex = 0;
function screenshot(name) {
  const file = path.join(out, `${name}.png`);
  try {
    execFileSync("xcrun", ["simctl", "io", udid, "screenshot", file], { stdio: "ignore" });
    return file;
  } catch {
    return null;
  }
}
function progressShot(stage) {
  shotIndex += 1;
  screenshot(`progress-${String(shotIndex).padStart(2, "0")}-${stage}`);
}
function readJson(name) {
  try {
    return JSON.parse(fs.readFileSync(path.join(io, `${name}.json`), "utf8"));
  } catch {
    return null;
  }
}
function writeJson(name, value) {
  const file = path.join(io, `${name}.json`);
  fs.writeFileSync(`${file}.tmp`, JSON.stringify(value));
  fs.renameSync(`${file}.tmp`, file);
}
function die(message) {
  console.error(`home-shot: ${message}`);
  progressShot("failed");
  process.exit(1);
}
async function waitForFile(name, timeoutMs, stage) {
  const deadline = Date.now() + timeoutMs;
  let lastShot = 0;
  while (Date.now() < deadline) {
    const value = readJson(name);
    if (value) return value;
    if (Date.now() - lastShot > 20_000) {
      progressShot(stage);
      lastShot = Date.now();
    }
    await sleep(1000);
  }
  return null;
}

// The pending app-login record ({"generation":…,"requestId":…}) under the slockAppLogin
// MMKV namespace. MMKV appends, so the last match is the current one.
function pendingRequestId() {
  let container;
  try {
    container = execFileSync("xcrun", ["simctl", "get_app_container", udid, bundle, "data"], { encoding: "utf8" }).trim();
  } catch {
    return null;
  }
  const files = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.name.startsWith("slockAppLogin") && !entry.name.endsWith(".crc")) files.push(full);
    }
  };
  try {
    walk(container);
  } catch {
    return null;
  }
  let found = null;
  for (const file of files) {
    const text = fs.readFileSync(file).toString("latin1");
    for (const match of text.matchAll(/"requestId"\s*:\s*"([A-Za-z0-9_.:-]{8,200})"/g)) found = match[1];
  }
  return found;
}

async function api(method, route, { token, body } = {}) {
  const res = await fetch(`${base}${route}`, {
    method,
    headers: {
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  let json = null;
  try {
    json = await res.json();
  } catch {}
  return { status: res.status, ok: res.ok, json };
}

async function main() {
  const tapped = await waitForFile("signin-tapped", 300_000, "launch");
  if (!tapped?.ok) die(`test did not reach the sign-in tap: ${tapped?.error ?? "timeout"}`);
  console.log("home-shot: sign-in tapped; waiting for the app's pending request");

  let requestId = null;
  const pendingDeadline = Date.now() + 60_000;
  while (!requestId && Date.now() < pendingDeadline) {
    requestId = pendingRequestId();
    if (!requestId) await sleep(1000);
  }
  progressShot("signin-sheet");
  if (!requestId) die("no pending app-login request in the app container (did the tap start sign-in?)");
  console.log("home-shot: pending app-login request found");

  const email = process.env.QA_TEST_EMAIL;
  const password = process.env.QA_TEST_PASSWORD;
  if (!email || !password) die("QA_TEST_EMAIL / QA_TEST_PASSWORD not set");
  // One login per run (the endpoint rate-limits repeated logins).
  const login = await api("POST", "auth/login", { body: { email, password } });
  if (!login.ok || !login.json?.accessToken) die(`QA login failed HTTP ${login.status}`);
  const token = login.json.accessToken;
  const user = login.json.user;

  // Same guard as qa-acceptance-ios.yml: the QA account must not see the production server.
  const servers = await api("GET", "servers", { token });
  if (!servers.ok) die(`GET servers failed HTTP ${servers.status}`);
  const list = Array.isArray(servers.json) ? servers.json : servers.json?.servers;
  const verdict = judgeVisibleServers(list, user ? { id: user.id, email: user.email } : null);
  console.log(`home-shot: visible-servers guard ${verdict.ok ? "PASS" : "FAIL"} [${verdict.code}]`);
  if (!verdict.ok) die(`visible-servers guard: ${verdict.detail}`);

  const approved = await api("POST", `auth/app-login/requests/${encodeURIComponent(requestId)}/approve`, { token });
  if (!approved.ok) die(`approve failed HTTP ${approved.status} code=${approved.json?.code ?? "?"}`);
  const callback = approved.json?.redirectUrl ?? "";
  if (!/^raft(-debug|-alpha|-beta)?:\/\/login\/callback\?/.test(callback)) die("approve returned an unexpected redirect");
  execFileSync("xcrun", ["simctl", "openurl", udid, callback], { stdio: "ignore" });
  writeJson("callback-opened", { ok: true });
  console.log("home-shot: request approved, callback opened in the app");

  const ready = await waitForFile("home-ready", 300_000, "after-callback");
  if (!ready?.ok) die(`home not reached: ${ready?.error ?? "timeout"}`);
  fs.writeFileSync(path.join(out, "home-ready.json"), JSON.stringify(ready, null, 2));
  const file = screenshot("home");
  writeJson("shot-done", { ok: Boolean(file) });
  if (!file) die("simctl screenshot failed");
  console.log(`home-shot: saved ${file}`);
  console.log(`home-shot: iOS ${ready.ios}; tab bar buttons: ${ready.tabBarButtons.map((b) => b.label).join(" | ")}`);
}

main().catch((err) => die(`threw ${err?.message ?? err}`));
