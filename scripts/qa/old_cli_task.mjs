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
//   7. revoke the key (DELETE /api/agents/:id/credentials/:credentialId), sign the web session out.
//   The agent and the task stay (the mobile s5 checks need them).
//
// Prints only non-secret results; every token is masked with ::add-mask:: before anything else.
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { ENVIRONMENTS } from "./release_auto_login_lib.mjs";

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
const SERVER_NAME = option("server-name", "OranixSlockTest");
const AGENT_NAME = option("agent-name", "qa-old-cli-task");
const CHANNEL = option("channel", "qa-test-create-7842").replace(/^#/, "");
const TITLE = option("title", "QA: 老版本 CLI 建的任务（task v2 放量前验证，可删）");
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
const stop = (message) => { throw new Stop(message); };

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

function runCli(dir, cliArgs, env) {
  const result = spawnSync(process.execPath, [path.join(dir, "node_modules/@botiverse/raft/dist/raft.js"), ...cliArgs], {
    env, encoding: "utf8", timeout: 120_000,
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

const summary = { env: ENV_NAME, server: SERVER_NAME, agent: AGENT_NAME, channel: `#${CHANNEL}`, title: TITLE };
let credential = null;
let agentId = null;
let exitCode = 0;
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
    agent = ok(await api("POST", "agents", {
      body: { name: AGENT_NAME, external: true, description: "QA: makes one task with the old Raft CLI 0.0.26 for the task v2 mobile check (s5). Safe to delete after the release." },
    }), "create the external agent");
    if (agent?.external !== true) stop(`created ${AGENT_NAME} but the server did not report it external`);
    summary.agent_reused = false;
    log(`created external agent ${AGENT_NAME} (${agent.id})`);
  }
  agentId = agent.id;
  summary.agent_id = agentId;

  // ---- the channel ----
  const channels = listOf(ok(await api("GET", "channels"), "list channels"), "channels");
  const channel = channels.find((c) => c.name === CHANNEL && !["dm", "thread", "task"].includes(c.type));
  if (!channel) stop(`#${CHANNEL} not found on ${SERVER_NAME}`);
  summary.channel_id = channel.id;
  ok(await api("POST", `channels/${channel.id}/members`, { body: { agentId } }), `add ${AGENT_NAME} to #${CHANNEL}`);
  log(`${AGENT_NAME} is a member of #${CHANNEL}`);

  const ownTask = async () => {
    const tasks = listOf(ok(await api("GET", `tasks/channel/${channel.id}`), "list channel tasks"), "tasks");
    return tasks.filter((t) => t.createdById === agentId && String(t.title ?? "").trim() === TITLE.trim())
      .sort((a, b) => Date.parse(b.createdAt ?? 0) - Date.parse(a.createdAt ?? 0))[0] ?? null;
  };
  const existing = await ownTask();

  // ---- the agent's key, in a profile the old CLI reads ----
  const minted = ok(await api("POST", `agents/${agentId}/credentials`, {
    body: { name: `mobile QA old CLI task (run ${process.env.GITHUB_RUN_ID ?? "local"})` },
  }), "mint the agent key");
  if (typeof minted?.apiKey !== "string") stop("the credential mint answered without a key");
  mask(minted.apiKey);
  credential = { id: minted.credentialId };
  log(`minted a key for ${AGENT_NAME} (credential ${minted.credentialId}, scopes ${(minted.scopes ?? []).join(",")})`);
  const raftHome = fs.mkdtempSync(path.join(os.tmpdir(), "raft-home-"));
  const profileDir = path.join(raftHome, "profiles", "qa-old-cli");
  fs.mkdirSync(profileDir, { recursive: true, mode: 0o700 });
  fs.writeFileSync(path.join(profileDir, "credential.json"), JSON.stringify({ apiKey: minted.apiKey, agentId, serverUrl: SERVER_URL, serverId }), { mode: 0o600 });
  const cliEnv = Object.fromEntries(Object.entries(process.env).filter(([k]) => !/^(SLOCK_|RAFT_|QA_LOGIN_)/.test(k)));
  Object.assign(cliEnv, { RAFT_HOME: raftHome, RAFT_PROFILE: "qa-old-cli", NO_COLOR: "1" });

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
    if (created.status !== 0) stop(`the old CLI task create exited ${created.status}`);
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
} catch (error) {
  exitCode = 1;
  summary.result = "fail";
  summary.error = redact(error instanceof Stop ? error.message : error?.stack ?? String(error));
  console.error(`[old-cli-task] FAILED: ${summary.error}`);
} finally {
  // The key was only for this run.
  if (credential?.id && agentId) {
    const out = await api("DELETE", `agents/${agentId}/credentials/${credential.id}`, { server: false }).catch((e) => ({ status: String(e) }));
    summary.key_revoked = out.status === 204;
    log(`revoke the run's key: HTTP ${out.status}`);
  }
  if (session?.refreshToken) {
    const out = await api("POST", "auth/logout", { body: { refreshToken: session.refreshToken }, server: false }).catch((e) => ({ status: String(e) }));
    summary.web_session_signed_out = out.status === 200;
    log(`sign the web session out: HTTP ${out.status}`);
  }
  const text = redact(JSON.stringify(summary, null, 2));
  console.log(text);
  const out = option("out", "");
  if (out) fs.writeFileSync(out, `${text}\n`);
  if (process.env.GITHUB_STEP_SUMMARY) {
    fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `## Old CLI task\n\n\`\`\`json\n${text}\n\`\`\`\n`);
  }
  process.exitCode = exitCode;
}
