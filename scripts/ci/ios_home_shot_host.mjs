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
// (GET artifacts/conversations/:id), then that channel's recent messages for a link to one
// (`/artifacts/<id>`, `artifacts.raft.build/<id>` or the content host `a-pt-<uuid>`).
const ARTIFACT_LINK = /\/artifacts\/|artifacts\.raft\.build\/|a-pt-[0-9a-f-]{36}\.raftusercontent\.com/;
async function findArtifactMessage(token, server) {
  const all = await api("GET", "artifacts/dashboard/all", { token, serverId: server.id });
  const visible = listOf(all.json, "rows").length || listOf(all.json, "items").length || listOf(all.json, "artifacts").length;
  console.log(`home-shot: artifact: dashboard/all HTTP ${all.status}${all.ok ? "" : ` code=${all.json?.code ?? "?"}`}; keys ${Object.keys(all.json ?? {}).join(",")}; ${visible} on the first page`);
  const channels = listOf((await api("GET", "channels", { token, serverId: server.id })).json, "channels");
  console.log(`home-shot: artifact: ${channels.length} channels on the server`);
  const statuses = {};
  let withArtifacts = 0;
  let fallback = null;
  for (const channel of channels.slice(0, 60)) {
    const page = await api("GET", `artifacts/conversations/${encodeURIComponent(channel.id)}`, { token, serverId: server.id });
    const key = page.ok ? String(page.status) : `${page.status}:${page.json?.code ?? "?"}`;
    statuses[key] = (statuses[key] ?? 0) + 1;
    const cards = listOf(page.json, "rows").map((row) => row.card).filter(Boolean);
    if (!page.ok || cards.length === 0) continue;
    withArtifacts += 1;
    const history = await api("GET", `messages/channel/${encodeURIComponent(channel.id)}?limit=100`, { token, serverId: server.id });
    const messages = listOf(history.json, "messages");
    const linking = messages.filter((m) => typeof m?.content === "string" && (ARTIFACT_LINK.test(m.content) || cards.some((c) => m.content.includes(c.id))));
    console.log(`home-shot: artifact: a channel with ${cards.length} Artifacts; messages HTTP ${history.status}, ${messages.length} read, ${linking.length} linking one`);
    for (const message of linking) {
      const card = cards.find((c) => message.content.includes(c.id)) ?? null;
      if (card) return { channel, message, title: card.title };
      fallback ??= { channel, message, title: cards[0].title };
    }
  }
  console.log(`home-shot: artifact: conversations answers ${JSON.stringify(statuses)}; ${withArtifacts} channels with Artifacts`);
  return fallback;
}

// QA test data, only when the QA account sees no Artifact linked from a message (coordinator OK,
// 11-04): in a `qa-*` channel only, an EXISTING HTML attachment there is saved as an Artifact
// owned by the QA account (POST artifacts/save-attachment; saving it again returns the same
// one), and one message linking it is posted in that same channel. Nothing else is written;
// the ids are printed so the data can be found and deleted.
const QA_CHANNEL = /^qa-/;
const isHtml = (a) => /\.html?$/i.test(a?.filename ?? a?.name ?? "") || /^text\/html\b/i.test(a?.mimeType ?? a?.contentType ?? "");
async function seedArtifactMessage(token, server, webBase) {
  const channels = listOf((await api("GET", "channels", { token, serverId: server.id })).json, "channels")
    .filter((c) => QA_CHANNEL.test(c?.name ?? ""))
    .sort((a, b) => (b.name === "qa-test-create-58291") - (a.name === "qa-test-create-58291"));
  console.log(`home-shot: artifact seed: ${channels.length} qa-* channels`);
  const kinds = {};
  for (const channel of channels) {
    // Up to 200 messages back, newest first (`before` = the oldest seq read).
    let attachment = null;
    let before = null;
    for (let page = 0; page < 2 && !attachment; page += 1) {
      const query = `limit=100${before != null ? `&before=${before}` : ""}`;
      const history = await api("GET", `messages/channel/${encodeURIComponent(channel.id)}?${query}`, { token, serverId: server.id });
      const messages = listOf(history.json, "messages");
      const all = messages.flatMap((m) => (Array.isArray(m?.attachments) ? m.attachments : []));
      for (const a of all) {
        const kind = (a?.mimeType ?? a?.contentType ?? String(a?.filename ?? a?.name ?? "").split(".").pop() ?? "?").toString().slice(0, 40);
        kinds[kind] = (kinds[kind] ?? 0) + 1;
      }
      attachment = all.find(isHtml) ?? null;
      const seqs = messages.map((m) => Number(m?.seq)).filter(Number.isFinite);
      if (messages.length < 100 || seqs.length === 0) break;
      before = Math.min(...seqs);
    }
    if (!attachment?.id) continue;
    const linked = await saveAndLink(token, server, webBase, channel, attachment, null);
    if (linked) return linked;
  }
  console.log(`home-shot: artifact seed: no HTML attachment in qa-* channels; attachment kinds seen ${JSON.stringify(kinds)}`);
  // None to reuse (coordinator OK, 11-04): post one small self-made HTML file in one qa-* channel.
  const channel = channels[0];
  if (!channel) return null;
  const form = new FormData();
  form.append("channelId", channel.id);
  form.append("files", new Blob([QA_FIXTURE_HTML], { type: "text/html" }), "qa-desktop-mode-test.html");
  const res = await fetch(`${base}attachments/upload`, { method: "POST", headers: { Authorization: `Bearer ${token}`, "X-Server-Id": server.id }, body: form });
  const uploaded = await res.json().catch(() => null);
  const attachment = uploaded?.attachments?.[0];
  if (!res.ok || !attachment?.id) die(`artifact seed: upload in #${channel.name} HTTP ${res.status}`);
  const filePost = await api("POST", "messages", {
    token,
    serverId: server.id,
    body: { channelId: channel.id, content: "QA desktop-mode test — safe to delete (iOS Simulator CI, ios-simulator-app.yml)", attachmentIds: [attachment.id] },
  });
  if (!filePost.ok) die(`artifact seed: posting the file in #${channel.name} HTTP ${filePost.status} code=${filePost.json?.code ?? "?"}`);
  const fileMessage = filePost.json?.message ?? filePost.json;
  console.log(`home-shot: artifact seed: QA DATA uploaded attachment ${attachment.id} in message ${fileMessage?.id} in #${channel.name}`);
  return saveAndLink(token, server, webBase, channel, attachment, fileMessage?.id ?? null);
}

// A 1280px-wide page that says which width it was laid out at.
const QA_FIXTURE_HTML = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>QA desktop-mode test — safe to delete</title><style>
body{margin:0;font:16px -apple-system,system-ui,sans-serif;color:#111}
header{background:#1f6feb;color:#fff;padding:16px 24px;font-size:22px;font-weight:600}
.ruler{position:relative;height:48px;border-bottom:2px solid #111;width:1280px}
.ruler span{position:absolute;top:0;height:48px;border-left:2px solid #d00;padding-left:4px;font-size:14px;color:#d00}
.cols{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;padding:24px}
.cols div{background:#eef3fb;border:1px solid #9bb;padding:24px;min-height:160px}
#w{font-weight:700}
@media (max-width:800px){.cols{grid-template-columns:1fr}}
</style></head><body><header>QA desktop-mode test — safe to delete</header>
<div class="ruler">${[0,160,320,480,640,800,960,1120,1270].map((x)=>`<span style="left:${x}px">${x}</span>`).join("")}</div>
<p style="padding:0 24px">Laid out at <span id="w"></span> CSS px wide. Four columns side by side = desktop layout; one column = phone layout.</p>
<div class="cols"><div>Column 1</div><div>Column 2</div><div>Column 3</div><div>Column 4</div></div>
<script>document.getElementById("w").textContent=String(document.documentElement.clientWidth)</script>
</body></html>`;

async function saveAndLink(token, server, webBase, channel, attachment, fileMessageId) {
  {
    const saved = await api("POST", "artifacts/save-attachment", { token, serverId: server.id, body: { attachmentId: attachment.id } });
    if (!saved.ok) {
      console.log(`home-shot: artifact seed: save-attachment in #${channel.name} HTTP ${saved.status} code=${saved.json?.code ?? "?"}`);
      return null;
    }
    const artifact = saved.json?.artifact ?? {};
    const card = artifact.card ?? artifact;
    const id = card.id ?? artifact.id;
    const title = card.title ?? artifact.title ?? attachment.filename ?? "";
    if (!id) die("artifact seed: save-attachment answered without an id");
    const url = `${webBase}/artifacts/${id}`;
    const posted = await api("POST", "messages", { token, serverId: server.id, body: { channelId: channel.id, content: url } });
    if (!posted.ok) die(`artifact seed: posting the link in #${channel.name} HTTP ${posted.status} code=${posted.json?.code ?? "?"}`);
    const message = posted.json?.message ?? posted.json;
    console.log(`home-shot: artifact seed: QA DATA artifact=${id} (created=${saved.json?.created}) from attachment ${attachment.id} in #${channel.name} (${channel.id}); link message=${message?.id}`);
    fs.writeFileSync(
      path.join(out, "qa-seed.json"),
      JSON.stringify({ server: server.slug, channel: channel.name, channelId: channel.id, artifactId: id, attachmentId: attachment.id, fileMessageId, linkMessageId: message?.id }, null, 2),
    );
    return { channel, message, title };
  }
}

async function artifactDesktopShots(token, servers, scheme) {
  const server = servers.find((s) => s?.name === "OranixSlockTest") ?? servers[0];
  let found = server ? await findArtifactMessage(token, server) : null;
  if (!found && server) found = await seedArtifactMessage(token, server, process.env.QA_WEB_BASE || "https://app.raft.build");
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
  // Activity -> the Artifact's row (#4316): the test taps it if Activity lists the Artifact.
  const activity = await waitForFile("activity-open", 180_000, "activity");
  const shot = screenshot("activity-artifact");
  writeJson("activity-open-shot", { ok: Boolean(shot) });
  console.log(`home-shot: activity row: ${JSON.stringify(activity)}`);
  try {
    const trace = execFileSync(
      "xcrun",
      ["simctl", "spawn", udid, "log", "show", "--last", "20m", "--style", "compact", "--predicate", 'eventMessage CONTAINS "artifact_open"'],
      { encoding: "utf8", maxBuffer: 32 * 1024 * 1024 },
    );
    const lines = trace.split("\n").filter((l) => l.includes("artifact_open"));
    fs.writeFileSync(path.join(out, "artifact-open-trace.txt"), lines.join("\n"));
    console.log(`home-shot: artifact_open trace (${lines.length} lines)`);
    for (const line of lines.slice(-40)) console.log(`  ${line.slice(0, 300)}`);
  } catch (err) {
    console.log(`home-shot: artifact_open trace unavailable: ${err?.message ?? err}`);
  }
}

main().catch((err) => die(`threw ${err?.message ?? err}`));
