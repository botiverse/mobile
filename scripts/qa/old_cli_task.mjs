#!/usr/bin/env node
// Test data for task v2 scenario s5 (scripts/qa/scenarios/task_v2.mjs): ONE task created by an OLD
// Raft CLI (@botiverse/raft@0.0.26, sends `X-Raft-Client: cli` and no version) without a source,
// so the server posts the agent's own title message as the task's source
// (slock services/taskConversationsGate.ts isPreTaskSourceCli). Run only in CI
// (release-auto-login.yml mode=old_cli_task): the QA password and the agent key never leave the runner.
//
//   1. POST /auth/login as the QA account; pick the server (--server-name, default OranixSlockTest)
//   2. the external agent --agent-name: reused when it exists (must be external), else
//      POST /api/agents { name, external: true }
//   3. POST /api/channels/:id/members { agentId } for --channel
//   4. POST /api/agents/:id/credentials -> sk_agent key, written to a RAFT_PROFILE credential.json
//      (apiKey, agentId, serverUrl, serverId) in a temp RAFT_HOME
//   5. old CLI: `raft task create --target "#channel" --title <title>` (skipped when this agent
//      already made a task with this title there, so a re-run does not make a second one)
//      task_source_required -> stop, nothing else is tried
//   6. current CLI: `raft task view "#channel:#N"`
//   7. revoke the key (DELETE /api/agents/:id/credentials/:credentialId, read back revokedAt),
//      sign the web session out (proven by its refresh token being refused), delete the temp profile.
//   The agent and the task stay (the mobile s5 checks need them).
//
// Bound to ONE server and channel (--server-name, --channel: required, exact). A write with no clear
// answer (5xx / timeout; a CLI write that fails) stops the run as "unknown": never retried, never
// re-minted, never sent elsewhere. "pass" (exit 0) only when the action passed AND cleanup is
// proven: the run's key revoked (read back), the web session refused, the temp key file gone. Any
// cleanup failure or unknown -> non-zero exit; the summary keeps non-secret correlation (credential
// id / name, web session family id prefix) to reconcile that exact key / session by hand.
//
// Prints only non-secret results; every token is masked with ::add-mask:: before anything else.
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import { ENVIRONMENTS } from "./release_auto_login_lib.mjs";
import { classifyWrite } from "./scenarios/scenario_outcome.mjs";

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] !== undefined ? args[i + 1] : fallback;
};
const ENV_NAME = option("env", "production");
const preset = ENVIRONMENTS[ENV_NAME];
if (!preset) throw new Error(`unknown --env ${ENV_NAME}`);
const API_BASE = preset.apiBaseUrl;
const SERVER_URL = new URL(API_BASE).origin;
const SERVER_NAME = option("server-name", "");
const AGENT_NAME = option("agent-name", "qa-old-cli-task");
const CHANNEL = String(option("channel", "")).replace(/^#/, "");
if (!SERVER_NAME || !CHANNEL) throw new Error("--server-name and --channel are required (one exact test server and channel)");
const TITLE = option("title", "QA: 老版本 CLI 建的任务（task v2 放量前验证，可删）");
const ACTION = option("action", "task");
if (!["task", "artifacts"].includes(ACTION)) throw new Error(`unknown --action ${ACTION}`);
const OLD_CLI = "@botiverse/raft@0.0.26";
const NEW_CLI = "@botiverse/raft@latest";

const secrets = new Set();
const mask = (value) => {
  if (typeof value !== "string" || value.length < 4 || secrets.has(value)) return;
  secrets.add(value);
  process.stdout.write(`::add-mask::${value}\n`);
};
const redact = (text) => {
  let out = String(text ?? "");
  for (const s of secrets) out = out.split(s).join("***");
  return out.replace(/sk_agent_[A-Za-z0-9_-]+/g, "sk_agent_***");
};
const log = (line) => console.log(redact(`[old-cli-task] ${line}`));
class Stop extends Error {}
class Unknown extends Stop {}
const stop = (message) => { throw new Stop(message); };
// A write whose outcome is not known: recorded, and the run stops (no retry, no other target).
const unknownWrites = [];
const stopUnknown = (what, detail) => {
  unknownWrites.push(`${what}: ${detail}`);
  throw new Unknown(`${what}: no clear answer (${detail}); stopping, not retrying`);
};
// A server write: ok -> its JSON; refused -> stop; unknown -> stopUnknown.
async function write(method, pathname, options, what) {
  let out;
  try {
    out = await api(method, pathname, options);
  } catch (error) {
    if (error instanceof Stop && !/HTTP 429/.test(error.message)) throw error;
    out = error instanceof Error ? error : new Error(String(error));
  }
  const kind = classifyWrite(out);
  if (kind === "ok") return out.json;
  if (kind === "unknown") stopUnknown(what, out instanceof Error ? "request failed" : `HTTP ${out.status}`);
  stop(`${what}: refused, HTTP ${out.status} ${redact(JSON.stringify(out.json ?? "")).slice(0, 300)}`);
}

let session = null;
let serverId = null;
async function api(method, pathname, { body, server = true } = {}) {
  const response = await fetch(`${API_BASE}${pathname}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      Origin: new URL(preset.webUrl).origin,
      "User-Agent": "raft-mobile-qa-old-cli-task",
      ...(session ? { Authorization: `Bearer ${session.accessToken}` } : {}),
      ...(server && serverId ? { "X-Server-Id": serverId } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(30_000),
  });
  let json = null;
  try { json = await response.json(); } catch {}
  if (response.status === 429) stop(`${method} ${pathname}: rate-limited (HTTP 429); not retrying`);
  return { status: response.status, json };
}
const ok = (out, what) => {
  if (out.status < 200 || out.status >= 300) stop(`${what}: HTTP ${out.status} ${redact(JSON.stringify(out.json ?? "")).slice(0, 400)}`);
  return out.json;
};
function findMessage(json, id) {
  const seen = new Set();
  const walk = (value) => {
    if (!value || typeof value !== "object" || seen.has(value)) return null;
    seen.add(value);
    if (!Array.isArray(value) && value.id === id && "content" in value) return value;
    for (const child of Array.isArray(value) ? value : Object.values(value)) {
      const hit = walk(child);
      if (hit) return hit;
    }
    return null;
  };
  return walk(json);
}
const listOf = (json, key) => (Array.isArray(json) ? json : Array.isArray(json?.[key]) ? json[key] : []);

function runCli(dir, cliArgs, env, input) {
  const result = spawnSync(process.execPath, [path.join(dir, "node_modules/@botiverse/raft/dist/raft.js"), ...cliArgs], {
    env, encoding: "utf8", timeout: 120_000, ...(input !== undefined ? { input } : {}),
  });
  return { status: result.status, stdout: redact(result.stdout), stderr: redact(result.stderr) };
}
function installCli(spec) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "raft-cli-"));
  fs.writeFileSync(path.join(dir, "package.json"), "{\"private\":true}\n");
  const out = spawnSync("npm", ["install", "--ignore-scripts", "--no-audit", "--no-fund", "--silent", spec], { cwd: dir, encoding: "utf8", timeout: 300_000 });
  if (out.status !== 0) stop(`npm install ${spec} failed: ${redact(out.stderr).slice(0, 400)}`);
  const version = JSON.parse(fs.readFileSync(path.join(dir, "node_modules/@botiverse/raft/package.json"), "utf8")).version;
  log(`installed ${spec} -> ${version}`);
  return dir;
}

// --action artifacts: two small single-file HTML Artifacts pushed by the agent with the CURRENT CLI
// into the channel, each followed by its link sent as the agent (a push posts nothing; the card
// appears once the link is sent), for the Android artifact viewer reuse check (Hands dd109a01,
// fix #4547). An Artifact already there with the same title is not pushed again.
const ARTIFACTS = [
  { key: "A", title: "QA artifact A (可删)", marker: "QA-ARTIFACT-A-PAGE", color: "#1d4ed8" },
  { key: "B", title: "QA artifact B (可删)", marker: "QA-ARTIFACT-B-PAGE", color: "#b91c1c" },
];
async function pushArtifacts(cliEnv) {
  const cliDir = installCli(NEW_CLI);
  const listed = runCli(cliDir, ["artifact", "list", "--target", `#${CHANNEL}`, "--limit", "50"], cliEnv);
  console.log(`----- artifact list --target #${CHANNEL} (exit ${listed.status}) -----\n${listed.stdout.split("\n").slice(0, 30).join("\n")}${listed.stderr}\n-----`);
  if (listed.status !== 0) stop("raft artifact list failed");
  summary.artifacts = {};
  const urlIn = (text) => text.match(/https:\/\/\S+\/artifacts\/[A-Za-z0-9_-]+/)?.[0] ?? null;
  // A send into a channel with messages the agent has not read is held as a draft: read first.
  const readChannel = () => {
    const out = runCli(cliDir, ["message", "read", "--target", `#${CHANNEL}`], cliEnv);
    if (out.status !== 0) stop(`raft message read failed: ${(out.stdout + out.stderr).slice(0, 300)}`);
    return out.stdout;
  };
  for (const a of ARTIFACTS) {
    const line = listed.stdout.split("\n").find((l) => l.includes(a.title));
    let url = null;
    if (line) {
      const id = line.match(/\bpt_[A-Za-z0-9-]+/)?.[0] ?? null;
      url = urlIn(line);
      if (!url && id) {
        const read = runCli(cliDir, ["artifact", "read", id], cliEnv);
        url = urlIn(read.stdout);
      }
      summary.artifacts[a.key] = { title: a.title, id, url, pushed_now: false };
      log(`${a.title} is already in #${CHANNEL} (${id}); not pushing it again`);
    } else {
      const dir = fs.mkdtempSync(path.join(os.tmpdir(), `qa-artifact-${a.key}-`));
      fs.writeFileSync(path.join(dir, "index.html"), `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${a.title}</title>
<style>body{font-family:sans-serif;margin:0;padding:32px;background:${a.color};color:#fff}h1{font-size:40px}p{font-size:22px}</style>
</head><body>
<h1>QA artifact ${a.key}</h1>
<p>${a.marker}</p>
<p>Test page for the Android Artifact viewer reuse check (Hands dd109a01). Safe to delete.</p>
</body></html>
`);
      const pushed = runCli(cliDir, ["artifact", "push", dir, "--target", `#${CHANNEL}`, "--title", a.title], cliEnv);
      console.log(`----- artifact push ${a.key} (exit ${pushed.status}) -----\n${pushed.stdout}${pushed.stderr}\n-----`);
      if (pushed.status !== 0) stopUnknown(`raft artifact push ${a.key}`, `exit ${pushed.status}`);
      url = pushed.stdout.match(/^Open: (\S+)/m)?.[1] ?? null;
      summary.artifacts[a.key] = { title: a.title, id: pushed.stdout.match(/\((pt_[A-Za-z0-9-]+)\)/)?.[1] ?? null, url, pushed_now: true };
    }
    if (!url) stop(`no link for ${a.key}`);
    const seen = readChannel();
    if (seen.includes(url)) {
      summary.artifacts[a.key].link_sent = "already_in_channel";
      log(`the link of ${a.key} is already in #${CHANNEL}`);
      continue;
    }
    let sent = runCli(cliDir, ["message", "send", "--target", `#${CHANNEL}`], cliEnv, `${url}\n`);
    if (/SEND_HELD_AS_DRAFT/.test(sent.stdout + sent.stderr)) {
      readChannel();
      sent = runCli(cliDir, ["message", "send", "--target", `#${CHANNEL}`], cliEnv, `${url}\n`);
    }
    console.log(`----- message send (link of ${a.key}) (exit ${sent.status}) -----\n${sent.stdout.split("\n").slice(0, 8).join("\n")}${sent.stderr.slice(0, 600)}\n-----`);
    if (sent.status !== 0) stopUnknown(`sending the link of ${a.key}`, `exit ${sent.status}`);
    summary.artifacts[a.key].link_sent = true;
  }
  summary.result = "pass";
}

const RUN_KEY_NAME = `mobile QA ${ACTION === "task" ? "old CLI task" : "artifacts"} (run ${process.env.GITHUB_RUN_ID ?? "local"}.${process.env.GITHUB_RUN_ATTEMPT ?? "1"} ${crypto.randomBytes(4).toString("hex")})`;
let raftHome = null;
const summary = { env: ENV_NAME, action: ACTION, server: SERVER_NAME, agent: AGENT_NAME, channel: `#${CHANNEL}`, ...(ACTION === "task" ? { title: TITLE } : {}) };
let credential = null;
let agentId = null;
let cleanupStarted = null;
function cleanupOnce() {
  cleanupStarted ??= cleanup();
  return cleanupStarted;
}
// A cancelled / killed job still cleans up (SIGTERM / SIGINT), then exits non-zero.
for (const signal of ["SIGTERM", "SIGINT"]) {
  process.once(signal, () => {
    summary.result = "unknown";
    summary.error = `stopped by ${signal}`;
    cleanupOnce().finally(() => process.exit(1));
  });
}
try {
  const email = process.env.QA_LOGIN_EMAIL ?? "";
  const password = process.env.QA_LOGIN_PASSWORD ?? "";
  if (!email || !password) stop("QA_LOGIN_EMAIL / QA_LOGIN_PASSWORD are not set");
  mask(email); // the password is a GitHub secret: already masked, never echoed here

  const login = await api("POST", "auth/login", { body: { email, password } });
  if (login.status !== 200) stop(`POST /auth/login: HTTP ${login.status} ${login.json?.code ?? ""}`);
  if (login.json?.secondFactorRequired || login.json?.challenge) stop("the QA account asks for a second factor; not answering it");
  if (!login.json?.accessToken) stop("POST /auth/login answered without an access token");
  mask(login.json.accessToken);
  mask(login.json.refreshToken);
  session = { accessToken: login.json.accessToken, refreshToken: login.json.refreshToken ?? "" };
  log("signed in as the QA account");

  const servers = listOf(ok(await api("GET", "servers", { server: false }), "list servers"), "servers");
  const server = servers.find((s) => s.name === SERVER_NAME) ?? servers.find((s) => s.slug === SERVER_NAME.toLowerCase());
  if (!server) stop(`the QA account is not in ${SERVER_NAME}`);
  serverId = server.id;
  log(`server ${server.name} (${server.id})`);

  // ---- the external agent ----
  const agents = listOf(ok(await api("GET", "agents"), "list agents"), "agents");
  let agent = agents.find((a) => a.name === AGENT_NAME && !a.deletedAt);
  if (agent) {
    if (agent.external !== true) stop(`an agent named ${AGENT_NAME} exists but is not external; not touching it`);
    summary.agent_reused = true;
    log(`reusing external agent ${AGENT_NAME} (${agent.id})`);
  } else {
    if (ACTION !== "task") stop(`${AGENT_NAME} does not exist; --action ${ACTION} only uses an existing agent`);
    agent = await write("POST", "agents", {
      body: { name: AGENT_NAME, external: true, description: "QA: makes one task with the old Raft CLI 0.0.26 for the task v2 mobile check (s5). Safe to delete after the release." },
    }, "create the external agent");
    if (agent?.external !== true) stop(`created ${AGENT_NAME} but the server did not report it external`);
    summary.agent_reused = false;
    log(`created external agent ${AGENT_NAME} (${agent.id})`);
  }
  agentId = agent.id;
  summary.agent_id = agentId;

  // ---- the channel ----
  const channels = listOf(ok(await api("GET", "channels"), "list channels"), "channels");
  const matches = channels.filter((c) => (c.name === CHANNEL || c.id === CHANNEL) && !["dm", "thread", "task"].includes(c.type));
  if (matches.length !== 1) stop(`#${CHANNEL} on ${SERVER_NAME}: ${matches.length} channels match exactly; not writing anywhere`);
  const channel = matches[0];
  summary.channel_id = channel.id;
  await write("POST", `channels/${channel.id}/members`, { body: { agentId } }, `add ${AGENT_NAME} to #${CHANNEL}`);
  log(`${AGENT_NAME} is a member of #${CHANNEL}`);

  const ownTask = async () => {
    const tasks = listOf(ok(await api("GET", `tasks/channel/${channel.id}`), "list channel tasks"), "tasks");
    return tasks.filter((t) => t.createdById === agentId && String(t.title ?? "").trim() === TITLE.trim())
      .sort((a, b) => Date.parse(b.createdAt ?? 0) - Date.parse(a.createdAt ?? 0))[0] ?? null;
  };
  const existing = ACTION === "task" ? await ownTask() : null;

  // ---- the agent's key, in a profile the old CLI reads ----
  // A unique name per attempt: if the answer is lost, that exact key is found by name and revoked
  // (never a second mint).
  summary.credential_name = RUN_KEY_NAME;
  let minted;
  try {
    minted = await write("POST", `agents/${agentId}/credentials`, { body: { name: RUN_KEY_NAME } }, "mint the agent key");
  } catch (error) {
    if (error instanceof Unknown) {
      const listed = await api("GET", `agents/${agentId}/credentials`, { server: false }).catch(() => null);
      const mine = listOf(listed?.json, "credentials").filter((c) => c.name === RUN_KEY_NAME);
      summary.mint_reconciliation = listed?.status === 200 ? `${mine.length} key(s) named for this run` : "could not list the agent's keys";
      if (mine.length === 1) credential = { id: mine[0].id };
    }
    throw error;
  }
  if (typeof minted?.apiKey !== "string") stop("the credential mint answered without a key");
  mask(minted.apiKey);
  credential = { id: minted.credentialId };
  log(`minted a key for ${AGENT_NAME} (credential ${minted.credentialId}, scopes ${(minted.scopes ?? []).join(",")})`);
  raftHome = fs.mkdtempSync(path.join(os.tmpdir(), "raft-home-"));
  const profileDir = path.join(raftHome, "profiles", "qa-old-cli");
  fs.mkdirSync(profileDir, { recursive: true, mode: 0o700 });
  fs.writeFileSync(path.join(profileDir, "credential.json"), JSON.stringify({ apiKey: minted.apiKey, agentId, serverUrl: SERVER_URL, serverId }), { mode: 0o600 });
  const cliEnv = Object.fromEntries(Object.entries(process.env).filter(([k]) => !/^(SLOCK_|RAFT_|QA_LOGIN_)/.test(k)));
  Object.assign(cliEnv, { RAFT_HOME: raftHome, RAFT_PROFILE: "qa-old-cli", NO_COLOR: "1" });

  if (ACTION === "artifacts") await pushArtifacts(cliEnv);
  else {
  // ---- old CLI create ----
  let task = existing;
  if (existing) {
    summary.created_now = false;
    log(`${AGENT_NAME} already made #${existing.taskNumber} with this title in #${CHANNEL}; not creating another`);
  } else {
    const oldDir = installCli(OLD_CLI);
    const created = runCli(oldDir, ["task", "create", "--target", `#${CHANNEL}`, "--title", TITLE], cliEnv);
    console.log(`----- ${OLD_CLI} task create (exit ${created.status}) -----\n${created.stdout}${created.stderr}\n-----`);
    summary.old_cli_exit = created.status;
    if (/task_source_required/.test(created.stdout + created.stderr)) {
      summary.old_cli_error = "task_source_required";
      stop("the old CLI got task_source_required; stopping as instructed (no other method tried)");
    }
    if (created.status !== 0) stopUnknown("old CLI task create", `exit ${created.status}`);
    summary.created_now = true;
    for (let i = 0; i < 5 && !task; i += 1) {
      task = await ownTask();
      if (!task) await new Promise((r) => setTimeout(r, 2000));
    }
    if (!task) stop("the old CLI reported success but no task by this agent with this title is listed");
  }
  summary.task_number = task.taskNumber;
  summary.task_id = task.id;
  summary.source_message_id = task.sourceMessageId ?? null;
  summary.created_by_name = task.createdByName ?? null;

  // The source message, through the QA account (REST): sender and text.
  if (task.sourceMessageId) {
    const ctx = await api("GET", `messages/context/${task.sourceMessageId}`);
    const message = findMessage(ctx.json, task.sourceMessageId);
    if (message) {
      summary.source_message = { sender_type: message.senderType ?? null, sender_id: message.senderId ?? null, sender_is_agent: message.senderId === agentId, content: String(message.content ?? "").slice(0, 300), createdAt: message.createdAt ?? null };
    }
  }

  // ---- current CLI view ----
  const newDir = installCli(NEW_CLI);
  const view = runCli(newDir, ["task", "view", `#${CHANNEL}:#${task.taskNumber}`], cliEnv);
  const viewText = `${view.stdout}${view.stderr}`;
  console.log(`----- ${NEW_CLI} task view #${CHANNEL}:#${task.taskNumber} (exit ${view.status}) -----\n${viewText.split("\n").slice(0, 25).join("\n")}\n-----`);
  summary.view_exit = view.status;
  summary.view_created_by = viewText.split("\n").find((l) => /^\s*Created by\b/i.test(l))?.trim() ?? null;
  summary.view_source = viewText.split("\n").find((l) => /^\s*Source\b/i.test(l))?.trim() ?? null;
  const sourceOk = summary.source_message ? summary.source_message.sender_is_agent : /@?qa-old-cli-task:/.test(summary.view_source ?? "");
  summary.result = task.sourceMessageId && sourceOk ? "pass" : "check";
  }
} catch (error) {
  summary.result = error instanceof Unknown ? "unknown" : "fail";
  summary.error = redact(error instanceof Stop ? error.message : error?.stack ?? String(error));
  console.error(`[old-cli-task] FAILED: ${summary.error}`);
} finally {
  await cleanupOnce();
}

// ---------- cleanup: exact key, exact web session, local key file; proven or reported ----------
async function cleanup() {
  const done = {};
  // The run's key: revoked (DELETE) and read back as revoked.
  if (credential?.id && agentId) {
    summary.credential_id_prefix = String(credential.id).slice(0, 8);
    const del = await api("DELETE", `agents/${agentId}/credentials/${credential.id}`, { server: false }).catch((e) => e);
    summary.key_revoke_http = del instanceof Error ? "request_failed" : del.status;
    const listed = await api("GET", `agents/${agentId}/credentials`, { server: false }).catch(() => null);
    const row = listOf(listed?.json, "credentials").find((c) => c.id === credential.id);
    summary.key_revoked = listed?.status === 200 && row ? Boolean(row.revokedAt) : "unknown";
    done.key = summary.key_revoked === true ? "ok" : summary.key_revoked === false ? "failed" : "unknown";
    log(`revoke the run's key: HTTP ${summary.key_revoke_http}, read back ${summary.key_revoked === true ? "revoked" : summary.key_revoked}`);
  } else if (summary.mint_reconciliation && !credential?.id) {
    done.key = "unknown";
  }
  // The web session: logout, proven by its refresh token being refused afterwards.
  if (session?.refreshToken) {
    summary.web_session_family_prefix = jwtFamilyPrefix(session.accessToken);
    const out = await api("POST", "auth/logout", { body: { refreshToken: session.refreshToken }, server: false }).catch((e) => e);
    summary.web_logout_http = out instanceof Error ? "request_failed" : out.status;
    const check = await api("POST", "auth/refresh", { body: { refreshToken: session.refreshToken }, server: false }).catch((e) => e);
    summary.web_refresh_check = check instanceof Error ? "request_failed" : check.status;
    if (!(check instanceof Error) && check.status === 401) {
      summary.web_session_signed_out = true;
      done.web = "ok";
    } else if (!(check instanceof Error) && check.status === 200) {
      // Still live (now rotated): sign the rotated one out too; still reported as not signed out.
      if (check.json?.refreshToken) {
        mask(check.json.accessToken);
        mask(check.json.refreshToken);
        await api("POST", "auth/logout", { body: { refreshToken: check.json.refreshToken }, server: false }).catch(() => {});
      }
      summary.web_session_signed_out = false;
      done.web = "failed";
    } else {
      summary.web_session_signed_out = "unknown";
      done.web = "unknown";
    }
    log(`sign the web session out: HTTP ${summary.web_logout_http}, refresh after: ${summary.web_refresh_check}`);
  }
  // The local key file: always removed.
  if (raftHome) {
    try { fs.rmSync(raftHome, { recursive: true, force: true }); } catch {}
    summary.local_key_file_removed = !fs.existsSync(raftHome);
    done.local = summary.local_key_file_removed ? "ok" : "failed";
  }
  const states = Object.values(done);
  summary.cleanup = done;
  summary.cleanup_complete = states.every((s) => s === "ok");
  if (unknownWrites.length) summary.unknown_writes = unknownWrites;
  if (summary.result === "pass" && !summary.cleanup_complete) {
    summary.result = states.includes("unknown") ? "unknown" : "cleanup_incomplete";
  }
  const text = redact(JSON.stringify(summary, null, 2));
  console.log(text);
  const out = option("out", "");
  if (out) fs.writeFileSync(out, `${text}\n`);
  if (process.env.GITHUB_STEP_SUMMARY) {
    fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `## Old CLI task\n\n\`\`\`json\n${text}\n\`\`\`\n`);
  }
  process.exitCode = summary.result === "pass" ? 0 : 1;
}

// The session family id from the access token's payload (an id, not a credential), 8 characters.
function jwtFamilyPrefix(token) {
  try {
    const payload = JSON.parse(Buffer.from(String(token).split(".")[1], "base64url").toString("utf8"));
    return typeof payload.familyId === "string" ? payload.familyId.slice(0, 8) : null;
  } catch {
    return null;
  }
}
