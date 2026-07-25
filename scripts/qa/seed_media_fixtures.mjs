#!/usr/bin/env node
// task #354 smoke follow-up (artin: fixture option a): the image acceptance
// cases can never pass on a bare emulator/workspace — the photo picker sees
// an empty media store and the QA workspace has no image message to preview.
// Two idempotent seeds fix that class of harness miss:
//   emit-image  — write the deterministic QA fixture PNG for `adb push`
//   workspace   — ensure the QA channel contains one image-bearing fixture
//                 message (API login → upload → post), skipping when present
// Seeding is best-effort: a failure is loudly logged but never fails the
// acceptance job — the case itself surfaces a missing fixture.
import fs from "node:fs";
import process from "node:process";

// 96x96 deterministic checker PNG with a blue border (251 bytes).
const FIXTURE_PNG_BASE64 =
  "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAIAAABt+uBvAAAAwklEQVR42u3dsQ2AIBAFUCaxdhymczonsYEFwJzNJeYe+S0k" +
  "vu7nF7aj3/KShgAQoDSgcZ3LPIGzu/vHdwABAgQIECBA9YAqfHzkHUCAAAECBAgQIECAAAHKBFJWtXlAgAABAgQIECBAgADZ" +
  "xbR5QIAAAQIECBAgQIAA2cWUVUCAAAECBAgQIECAAAGyi2nzgAABAgQIECBAgAABsotp84AAAQIECBAgQIAAAQJkF9PmAQEC" +
  "BAgQIEBfgMSfWQABSs4EmG4IcxsqxD8AAAAASUVORK5CYII=";

export const FIXTURE_MESSAGE_MARKER = "[qa-fixture-image]";
export const FIXTURE_FILE_NAME = "qa-fixture-image.png";

export function fixturePngBytes() {
  return Buffer.from(FIXTURE_PNG_BASE64, "base64");
}

export function isPngSignature(bytes) {
  const sig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  return bytes.length > sig.length && sig.every((b, i) => bytes[i] === b);
}

export function pickServer(servers, preferredName) {
  if (!Array.isArray(servers) || servers.length === 0) return null;
  const pattern = new RegExp(preferredName || "oranix", "i");
  return servers.find((s) => pattern.test(s?.name || "")) || servers[0];
}

export function pickChannel(channels, preferredName) {
  if (!Array.isArray(channels) || channels.length === 0) return null;
  const wanted = (preferredName || "all").replace(/^#/, "").toLowerCase();
  return (
    channels.find((c) => (c?.name || "").replace(/^#/, "").toLowerCase() === wanted) ||
    channels[0]
  );
}

export function hasFixtureMessage(messages) {
  return (Array.isArray(messages) ? messages : []).some(
    (m) =>
      typeof m?.content === "string" &&
      m.content.includes(FIXTURE_MESSAGE_MARKER) &&
      (m.attachments?.length ?? 0) > 0,
  );
}

async function requestJson(fetchImpl, url, options) {
  const response = await fetchImpl(url, options);
  const text = await response.text();
  let body = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = null;
  }
  return { status: response.status, body };
}

export async function seedWorkspaceFixture({
  apiBase,
  email,
  password,
  serverName,
  channelName,
  fetchImpl = fetch,
  log = (line) => console.log(line),
}) {
  const base = String(apiBase).replace(/\/$/, "");
  const login = await requestJson(fetchImpl, `${base}/auth/login`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (login.status !== 200 || !login.body?.accessToken) {
    log(`seed_media_fixtures: login failed status=${login.status}`);
    return { seeded: false, reason: "login_failed" };
  }
  const auth = { Authorization: `Bearer ${login.body.accessToken}` };

  const servers = await requestJson(fetchImpl, `${base}/auth/servers`, { headers: auth });
  const server = pickServer(servers.body, serverName);
  if (!server?.id) {
    log(`seed_media_fixtures: no server available status=${servers.status}`);
    return { seeded: false, reason: "no_server" };
  }
  const scoped = { ...auth, "X-Server-Id": server.id };

  const inbox = await requestJson(
    fetchImpl,
    `${base}/channels/inbox?filter=channels&limit=50&offset=0`,
    { headers: scoped },
  );
  const channelList = Array.isArray(inbox.body) ? inbox.body : inbox.body?.channels;
  const channel = pickChannel(channelList, channelName);
  if (!channel?.id) {
    log(`seed_media_fixtures: no channel available status=${inbox.status}`);
    return { seeded: false, reason: "no_channel" };
  }

  const recent = await requestJson(
    fetchImpl,
    `${base}/messages/channel/${channel.id}?limit=50`,
    { headers: scoped },
  );
  const messageList = Array.isArray(recent.body) ? recent.body : recent.body?.messages;
  if (hasFixtureMessage(messageList)) {
    log(`seed_media_fixtures: fixture already present in #${channel.name || channel.id}`);
    return { seeded: false, reason: "already_present", channelId: channel.id };
  }

  const form = new FormData();
  form.append("channelId", channel.id);
  form.append(
    "files",
    new Blob([fixturePngBytes()], { type: "image/png" }),
    FIXTURE_FILE_NAME,
  );
  const upload = await requestJson(fetchImpl, `${base}/attachments/upload`, {
    method: "POST",
    headers: scoped,
    body: form,
  });
  const attachmentId =
    upload.body?.attachments?.[0]?.id || upload.body?.[0]?.id || upload.body?.id;
  if (upload.status >= 300 || !attachmentId) {
    log(`seed_media_fixtures: upload failed status=${upload.status}`);
    return { seeded: false, reason: "upload_failed" };
  }

  const message = await requestJson(fetchImpl, `${base}/messages`, {
    method: "POST",
    headers: { ...scoped, "content-type": "application/json" },
    body: JSON.stringify({
      channelId: channel.id,
      content: `${FIXTURE_MESSAGE_MARKER} deterministic media fixture for image acceptance cases`,
      attachmentIds: [attachmentId],
    }),
  });
  if (message.status >= 300) {
    log(`seed_media_fixtures: message post failed status=${message.status}`);
    return { seeded: false, reason: "message_failed" };
  }
  log(`seed_media_fixtures: seeded image fixture into #${channel.name || channel.id}`);
  return { seeded: true, channelId: channel.id, attachmentId };
}

async function main() {
  const mode = process.argv[2];
  if (mode === "emit-image") {
    const outIndex = process.argv.indexOf("--out");
    const out = outIndex >= 0 ? process.argv[outIndex + 1] : FIXTURE_FILE_NAME;
    fs.writeFileSync(out, fixturePngBytes());
    console.log(`seed_media_fixtures: wrote ${out}`);
    return;
  }
  if (mode === "workspace") {
    const email = process.env.QA_TEST_EMAIL || "";
    const password = process.env.QA_TEST_PASSWORD || "";
    if (!email || !password) {
      console.log("seed_media_fixtures: QA credentials absent, skipping workspace seed");
      return;
    }
    try {
      await seedWorkspaceFixture({
        apiBase: process.env.QA_API_BASE || "https://api.raft.build/api",
        email,
        password,
        serverName: process.env.QA_SERVER_NAME || "",
        channelName: process.env.QA_FIXTURE_CHANNEL || "all",
      });
    } catch (error) {
      // Best-effort by contract: never fail the acceptance job from seeding.
      console.log(`seed_media_fixtures: workspace seed error ${error?.message || error}`);
    }
    return;
  }
  console.error("usage: seed_media_fixtures.mjs <emit-image --out <path> | workspace>");
  process.exitCode = 2;
}

const invokedDirectly = process.argv[1]?.endsWith("seed_media_fixtures.mjs");
if (invokedDirectly) await main();
