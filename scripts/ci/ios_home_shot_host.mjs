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
// --artifact 1: after home, open an Artifact and screenshot it before and after Desktop Mode.
const artifactShot = args.artifact === "1";
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
// The app's own sign-in diagnostics (result/reason lines, e.g. "app_login_complete result=…"),
// read from the diagnostics files in its container. Values that could carry a code are masked.
function appLoginDiagnostics() {
  let container;
  try {
    container = execFileSync("xcrun", ["simctl", "get_app_container", udid, bundle, "data"], { encoding: "utf8" }).trim();
  } catch {
    return;
  }
  const lines = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (!entry.name.startsWith("slockAppLogin") && fs.statSync(full).size < 8_000_000) {
        const text = fs.readFileSync(full).toString("latin1");
        for (const line of text.split(/\r?\n/)) {
          if (/app_login|SlockIosAppLogin|SlockAppLogin/.test(line)) lines.push(line.slice(0, 300));
        }
      }
    }
  };
  try {
    walk(container);
  } catch {}
  const masked = lines.map((l) => l.replace(/(code|requestId|token|verifier)([=:"\s]+)[A-Za-z0-9._~-]{6,}/gi, "$1$2<masked>"));
  console.log(`home-shot: app sign-in diagnostics (${masked.length} lines)`);
  for (const line of masked.slice(-40)) console.log(`  ${line}`);
}

let callbackOpened = false;
function die(message) {
  console.error(`home-shot: ${message}`);
  if (callbackOpened) appLoginDiagnostics();
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

async function api(method, route, { token, body, serverId } = {}) {
  const res = await fetch(`${base}${route}`, {
    method,
    headers: {
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(serverId ? { "X-Server-Id": serverId } : {}),
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
  // Generous: installing the test runner on a just-downloaded runtime alone took 2.5 min (run 41505034629).
  const tapped = await waitForFile("signin-tapped", 900_000, "launch");
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
  callbackOpened = true;
  writeJson("callback-opened", { ok: true });
  console.log("home-shot: request approved, callback opened in the app");

  const ready = await waitForFile("home-ready", 300_000, "after-callback");
  if (!ready?.ok) die(`home not reached: ${ready?.error ?? "timeout"}`);
  fs.writeFileSync(path.join(out, "home-ready.json"), JSON.stringify(ready, null, 2));
  const file = screenshot("home");
  writeJson("shot-done", { ok: Boolean(file) });
  if (!file) die("simctl screenshot failed");
  appLoginDiagnostics();
  console.log(`home-shot: saved ${file}`);
  console.log(`home-shot: iOS ${ready.ios}; tab bar buttons: ${ready.tabBarButtons.map((b) => b.label).join(" | ")}`);
  if (artifactShot) await artifactDesktopShots(token, list, callback.slice(0, callback.indexOf(":")));
}

const listOf = (json, key) => (Array.isArray(json) ? json : Array.isArray(json?.[key]) ? json[key] : []);

// A message on the QA server that links an Artifact: the Artifacts of each channel
// (GET artifacts/conversations/:id), then that channel's recent messages for a link to one.
async function findArtifactMessage(token, server) {
  const channels = listOf((await api("GET", "channels", { token, serverId: server.id })).json, "channels");
  console.log(`home-shot: artifact: ${channels.length} channels on the server`);
  let fallback = null;
  for (const channel of channels.slice(0, 60)) {
    const page = await api("GET", `artifacts/conversations/${encodeURIComponent(channel.id)}`, { token, serverId: server.id });
    const cards = listOf(page.json, "rows").map((row) => row.card).filter(Boolean);
    if (!page.ok || cards.length === 0) continue;
    const messages = listOf(
      (await api("GET", `messages/channel/${encodeURIComponent(channel.id)}?limit=100`, { token, serverId: server.id })).json,
      "messages",
    );
    const linking = messages.filter((m) => typeof m?.content === "string" && m.content.includes("/artifacts/"));
    for (const message of linking) {
      const card = cards.find((c) => message.content.includes(c.id)) ?? null;
      if (card) return { channel, message, title: card.title };
      fallback ??= { channel, message, title: cards[0].title };
    }
  }
  return fallback;
}

async function artifactDesktopShots(token, servers, scheme) {
  const server = servers.find((s) => s?.name === "OranixSlockTest") ?? servers[0];
  const found = server ? await findArtifactMessage(token, server) : null;
  if (!found) {
    writeJson("artifact-target", { ok: false });
    die("artifact: no message linking an Artifact on the QA server");
  }
  console.log(`home-shot: artifact: "${found.title}" linked from a message in #${found.channel.name ?? "?"}`);
  const link = `${scheme}:///s/${encodeURIComponent(server.slug)}/channel/${encodeURIComponent(found.channel.id)}?msg=${encodeURIComponent(found.message.id)}`;
  execFileSync("xcrun", ["simctl", "openurl", udid, link], { stdio: "ignore" });
  writeJson("artifact-target", { ok: true, title: found.title });
  for (const [ready, shot] of [
    ["artifact-open", "artifact"],
    ["artifact-desktop", "artifact-desktop"],
    ["artifact-zoomed", "artifact-desktop-zoomed"],
  ]) {
    const state = await waitForFile(ready, 240_000, ready);
    if (!state?.ok) die(`${ready}: ${state?.error ?? "timeout"}`);
    const file = screenshot(shot);
    writeJson(`${ready}-shot`, { ok: Boolean(file) });
    console.log(`home-shot: saved ${file}`);
  }
}

main().catch((err) => die(`threw ${err?.message ?? err}`));
