#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { performance } from "node:perf_hooks";
import { fileURLToPath } from "node:url";
import {
  VPhoneError,
  createRedactor,
  createVphoneDriver,
  elementCenter,
  errorText,
  findElements,
  flattenElements,
  imagePasteScenario,
  pointToPixels,
  sleep,
} from "./vphone-driver.mjs";
import { createNavigationRealtimeCases } from "./navigation-realtime-cases.mjs";

export const SMOKE_TEXT = "smoke test, please ignore";
export const CASE_IDS = [
  "01-cold-start",
  "02-channel-thread",
  "03-photos",
  "04-safari-text",
  "04-image-empty",
  "04-image-text",
  "05-activity",
  "06-saved",
  "07-tasks",
  "09-ottie",
  "10-appearance-relogin",
  "11-double-tap-replies",
  "11-double-tap-avatar",
  "11-double-tap-link",
  "12-realtime-connect",
  "12-realtime-offline",
  "12-realtime-channel-switch",
  "12-realtime-background",
  "12-realtime-server-switch",
];

const IMAGE_PNG_BASE64 =
  "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAIAAABt+uBvAAAAwklEQVR42u3dsQ2AIBAFUCaxdhymczonsYEFwJzNJeYe+S0k" +
  "vu7nF7aj3/KShgAQoDSgcZ3LPIGzu/vHdwABAgQIECBAgADZxbR5QIAAAQIECBAgQIECAAAGxi2nzgAABAgQIECBAgADZ" +
  "xbR5QIAAAQIECBAgQIAAAQJkF9PmAQECBAgQIECAAAGxi2nzgAABAgQIECBAgAABsotp84AAAQIECBAgQIAAAQJkF9PmAQEC" +
  "BAgQIEBfgMSfWQABSs4EmG4IcxsqxD8AAAAASUVORK5CYII=";

class NotCovered extends Error {
  constructor(reason) {
    super(reason);
    this.name = "NotCovered";
    this.status = "NOT_COVERED";
  }
}

export function classifyCaseOutcome(error) {
  if (!error) return "PASS";
  if (error?.status === "NOT_COVERED" || error instanceof NotCovered) return "NOT_COVERED";
  return "FAIL";
}

function booleanEnv(value, name) {
  if (value === undefined || value === "") return false;
  if (/^(1|true|yes)$/i.test(value)) return true;
  if (/^(0|false|no)$/i.test(value)) return false;
  throw new Error(`${name} must be 0/1, false/true, or no/yes`);
}

export function resolveConfig(env = process.env, now = new Date()) {
  const scriptDir = path.dirname(fileURLToPath(import.meta.url));
  const repoRoot = path.resolve(scriptDir, "../../..");
  return {
    cli: env.VPHONE_CLI || "vphone-launchpad-cli",
    machine: env.VPHONE_MACHINE || "",
    bundleId: env.QA_BUNDLE_ID || "build.raft.app",
    serverName: env.QA_SERVER_NAME || "OranixSlockTest",
    channel: (env.QA_CHANNEL || "all").replace(/^#/, ""),
    altChannel: (env.QA_ALT_CHANNEL || "qa-test-create-58291").replace(/^#/, ""),
    apiBase: env.QA_API_BASE || "https://api.raft.build/api",
    out: path.resolve(env.QA_OUT || path.join(repoRoot, "artifacts", "ios-vphone-smoke", now.toISOString().replace(/[:.]/g, "-"))),
    email: env.QA_EMAIL || "",
    password: env.QA_PASSWORD || "",
    stopAfter: booleanEnv(env.QA_STOP_AFTER, "QA_STOP_AFTER"),
  };
}

function publicConfig(config) {
  return {
    cli: config.cli,
    machine: config.machine,
    bundleId: config.bundleId,
    serverName: config.serverName,
    channel: config.channel,
    altChannel: config.altChannel,
    apiBase: config.apiBase,
    out: config.out,
    credentialsProvided: Boolean(config.email && config.password),
    stopAfter: config.stopAfter,
  };
}

function escapeMarkdown(value) {
  return String(value ?? "").replaceAll("|", "\\|").replace(/[\r\n]+/g, " ").slice(0, 1000);
}

export function renderReport(summary, redact = (value) => value) {
  const counts = summary.counts || { PASS: 0, FAIL: 0, NOT_COVERED: 0 };
  const lines = [
    "# Raft iOS vphone smoke",
    "",
    `- Started: ${summary.startedAt}`,
    `- Finished: ${summary.finishedAt}`,
    `- Machine: ${summary.config?.machine || "unknown"}`,
    `- Bundle: ${summary.config?.bundleId || "unknown"}`,
    `- Server / channel: ${summary.config?.serverName || "unknown"} / #${summary.config?.channel || "unknown"}`,
    `- Overall: **${summary.ok ? "PASS" : "FAIL"}** (${counts.PASS} PASS, ${counts.FAIL} FAIL, ${counts.NOT_COVERED} NOT_COVERED)`,
    "",
    "PASS means the assertions ran and passed. FAIL means an assertion, prerequisite, or harness step failed. NOT_COVERED means the observed environment could not support the case; it is not a product verdict.",
    "",
    "| Case | Status | ms | Detail | Evidence |",
    "| --- | --- | ---: | --- | --- |",
  ];
  for (const result of summary.cases || []) {
    const evidence = (result.evidence || []).map((item) => `\`${item}\``).join("<br>");
    lines.push(`| ${result.id} | ${result.status} | ${result.durationMs} | ${escapeMarkdown(redact(result.detail))} | ${evidence || "—"} |`);
  }
  if (summary.startupError) lines.push("", "## Startup error", "", "```text", redact(summary.startupError), "```");
  lines.push("", "## Boundaries", "", "- `apps.install` bypasses installd and is not TestFlight or distribution evidence.", "- Direct guest image seeding tests UIPasteboard behavior; it does not claim Safari Copy produced an image.", "- Source, CI checks, and runtime device evidence are separate.", "");
  return `${lines.join("\n")}\n`;
}

function resultCounts(results) {
  return {
    PASS: results.filter((item) => item.status === "PASS").length,
    FAIL: results.filter((item) => item.status === "FAIL").length,
    NOT_COVERED: results.filter((item) => item.status === "NOT_COVERED").length,
  };
}

function makeEvidence(config, redact, id) {
  const directory = path.join(config.out, id);
  fs.mkdirSync(directory, { recursive: true });
  const files = [];
  const relative = (file) => path.relative(config.out, file) || file;
  return {
    directory,
    files,
    writeJson(name, value) {
      const file = path.join(directory, `${name}.json`);
      fs.writeFileSync(file, `${JSON.stringify(redact(value), null, 2)}\n`);
      files.push(relative(file));
      return file;
    },
    writeText(name, value) {
      const file = path.join(directory, name);
      fs.writeFileSync(file, redact(String(value)));
      files.push(relative(file));
      return file;
    },
    writeBinary(name, value) {
      const file = path.join(directory, name);
      fs.writeFileSync(file, value);
      files.push(relative(file));
      return file;
    },
    async capture(driver, name) {
      const captured = await driver.captureEvidence(directory, name);
      files.push(relative(captured.tree));
      if (captured.png) files.push(relative(captured.png));
      if (captured.pngSkipped) this.writeText(`${name}-png-skipped.txt`, "PNG suppressed because a credential-bearing screen may be visible.\n");
      return captured;
    },
  };
}

async function waitAny(driver, selectors, timeoutMs = 15_000) {
  const deadline = Date.now() + timeoutMs;
  let visible = 0;
  while (Date.now() <= deadline) {
    const tree = await driver.uiTree();
    visible = flattenElements(tree).length;
    for (const selector of selectors) {
      const elements = findElements(tree, selector, { includeDisabled: true });
      if (elements.length) return { tree, selector, element: elements[0] };
    }
    if (Date.now() + 300 > deadline) break;
    await sleep(300);
  }
  throw new VPhoneError(`waited ${timeoutMs} ms for any of ${JSON.stringify(selectors)}; visible=${visible}`, { code: "wait_timeout" });
}

async function tapAny(driver, selectors) {
  const tree = await driver.uiTree();
  for (const selector of selectors) {
    if (findElements(tree, selector).length) return driver.tapElement(selector);
  }
  throw new VPhoneError(`no tappable element among ${JSON.stringify(selectors)}`, { code: "tap_failed" });
}

export function bottommostElementCenter(elements) {
  return elements.map((element) => elementCenter(element)).filter(Boolean).sort((a, b) => b.y - a.y)[0] || null;
}

export function signingInVisible(tree) {
  return flattenElements(tree).some((element) => {
    const label = String(element?.label || "");
    return label === "Signing in…" || label === "Signing in...";
  });
}

export function serverIdentityVisible(tree, serverName) {
  return findElements(tree, { text: `Current server: ${serverName}`, role: "button", match: "exact" }).length > 0 ||
    findElements(tree, { text: serverName, role: "button", match: "exact" }).length > 0;
}

function homeVisible(tree, serverName) {
  return findElements(tree, { text: "Home", role: "button", match: "exact" }).length > 0 &&
    (serverName ? serverIdentityVisible(tree, serverName) : findElements(tree, { text: "Current server:", role: "button", match: "contains" }).length > 0);
}

export function signedInRouteVisible(tree) {
  return homeVisible(tree) ||
    findElements(tree, { text: "Back", role: "button", match: "exact" }).length > 0 ||
    ["Activity", "Tasks", "Search"].some((tab) => findElements(tree, { text: tab, role: "button", match: "exact" }).length > 0);
}

async function openServerMenu(driver, serverName) {
  await tapAny(driver, [
    { text: "Current server:", role: "button", match: "contains" },
    { text: serverName, role: "button", match: "exact" },
  ]);
}

async function ensureServer(driver, serverName) {
  const tree = await driver.uiTree();
  if (serverIdentityVisible(tree, serverName)) return;
  await openServerMenu(driver, serverName);
  await driver.waitFor({ text: serverName, match: "contains" }, { timeoutMs: 15_000 });
  await driver.tapElement({ text: serverName, match: "contains" });
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    if (serverIdentityVisible(await driver.uiTree(), serverName)) return;
    await sleep(300);
  }
  throw new VPhoneError(`server identity did not settle on ${serverName}`, { code: "identity_mismatch" });
}

async function browserSignIn(driver, config) {
  if (!config.email || !config.password) {
    throw new VPhoneError("signed-out session requires both QA_EMAIL and QA_PASSWORD", { code: "credentials_required" });
  }
  driver.setSensitiveScreen(true);
  const landing = await driver.uiTree();
  const alreadySigningIn = signingInVisible(landing);
  if (!alreadySigningIn) {
    const termsCheckbox = flattenElements(landing).find((element) => {
      if (element.role !== "button" || String(element.label || "")) return false;
      const center = elementCenter(element);
      return center && center.x < 80 && center.y > 350 && center.y < 600;
    });
    if (termsCheckbox) {
      const center = elementCenter(termsCheckbox);
      await driver.inputTap(center.x, center.y);
    }
    await tapAny(driver, [
      { text: "Sign in to Raft", role: "button", match: "exact" },
      { text: "Continue with Browser", role: "button", match: "contains" },
      { text: "Sign in with Browser", role: "button", match: "contains" },
      { text: "Continue", role: "button", match: "exact" },
      { text: "Sign in", role: "button", match: "exact" },
      { text: "Log in", role: "button", match: "exact" },
    ]);
  }
  let credentialsEntered = false;
  let systemConsentTapped = false;
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    const tree = await driver.uiTree();
    if (homeVisible(tree, config.serverName)) {
      await ensureServer(driver, config.serverName);
      driver.setSensitiveScreen(false);
      return;
    }
    if (!systemConsentTapped && signingInVisible(tree)) {
      await sleep(1500);
      const device = await driver.rpc("device.screen");
      const { width, height } = screenPoints(device);
      const point = pointToPixels(device, { x: width * 0.69, y: height * 0.582 });
      await driver.guestTapPixels(point.x, point.y);
      systemConsentTapped = true;
      await sleep(1000);
      continue;
    }
    const server = findElements(tree, { text: config.serverName, role: "button", match: "contains" })[0];
    const open = findElements(tree, { text: "Open", role: "button", match: "exact" })[0];
    const confirm = findElements(tree, { text: "Confirm sign-in", role: "button", match: "exact" })[0];
    const email = findElements(tree, { identifier: "email", match: "contains" })[0] ||
      findElements(tree, { text: "Email", match: "contains" })[0] ||
      findElements(tree, { role: "textField", match: "contains" })[0];
    if (server) {
      await driver.tapElement({ text: config.serverName, role: "button", match: "contains" });
    } else if (open) {
      await driver.tapElement({ text: "Open", role: "button", match: "exact" });
    } else if (confirm) {
      await driver.tapElement({ text: "Confirm sign-in", role: "button", match: "exact" });
    } else if (email && !credentialsEntered) {
      await tapAny(driver, [
        { identifier: "email", match: "contains" },
        { text: "Email", match: "contains" },
        { role: "textField", match: "contains" },
      ]);
      await driver.secretType(config.email);
      await tapAny(driver, [
        { identifier: "password", match: "contains" },
        { text: "Password", match: "contains" },
        { role: "secureTextField", match: "contains" },
      ]);
      await driver.secretType(config.password);
      await tapAny(driver, [
        { text: "Sign in", role: "button", match: "exact" },
        { text: "Log in", role: "button", match: "exact" },
        { text: "Continue", role: "button", match: "exact" },
      ]);
      credentialsEntered = true;
    }
    await sleep(400);
  }
  throw new VPhoneError("browser sign-in did not return to Home within 60 seconds", { code: "login_timeout" });
}

async function prepareSession(driver, config) {
  const prepared = await driver.prepareMachine();
  await driver.appsLaunch();
  const foreground = await driver.appsForeground();
  const foregroundId = foreground?.bundle_id || foreground?.frontmost_bundle_id || foreground?.bundleId;
  if (foreground?.frontmost_verified === false || foreground?.verified === false) {
    throw new VPhoneError("vphone could not verify a unique foreground app", { code: "identity_mismatch" });
  }
  if (foregroundId !== config.bundleId) {
    throw new VPhoneError(`foreground identity mismatch: expected ${config.bundleId}, got ${foregroundId || "unknown"}`, { code: "identity_mismatch" });
  }
  let tree = await driver.uiTree();
  let session = "reused";
  const homeDeadline = Date.now() + 15_000;
  while (!homeVisible(tree, config.serverName) && Date.now() < homeDeadline) {
    await sleep(300);
    tree = await driver.uiTree();
  }
  if (!homeVisible(tree, config.serverName) && signedInRouteVisible(tree)) {
    await openHome(driver, config);
    tree = await driver.uiTree();
  }
  if (!homeVisible(tree, config.serverName)) {
    session = "browser-sign-in";
    await browserSignIn(driver, config);
  } else {
    await ensureServer(driver, config.serverName);
  }
  tree = (await driver.waitFor({ text: "Home", role: "button", match: "exact" }, { timeoutMs: 20_000 })).tree;
  if (!homeVisible(tree, config.serverName)) throw new VPhoneError("Home loaded without the bottom navigation/server identity", { code: "session_invalid" });
  if (!serverIdentityVisible(tree, config.serverName)) throw new VPhoneError("Home loaded with the wrong server identity", { code: "identity_mismatch" });
  driver.setSensitiveScreen(false);
  return { ...prepared, session };
}

async function openHome(driver, config) {
  await driver.appsLaunch();
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const tree = await driver.uiTree();
    const onHome = homeVisible(tree, config.serverName) &&
      !findElements(tree, { text: "Back", role: "button", match: "exact" }).length &&
      (findElements(tree, { text: "Saved", role: "button", match: "contains" }).length || findElements(tree, { text: config.channel, match: "contains" }).length);
    if (onHome) {
      if (!serverIdentityVisible(tree, config.serverName)) throw new VPhoneError("Home loaded with the wrong server identity", { code: "identity_mismatch" });
      return;
    }
    const back = findElements(tree, { text: "Back", role: "button", match: "exact" })[0];
    const home = findElements(tree, { text: "Home", role: "button", match: "exact" })[0];
    if (back) {
      const center = elementCenter(back);
      await driver.inputTap(center.x, center.y);
    } else if (home) {
      const center = elementCenter(home);
      await driver.inputTap(center.x, center.y);
    }
    await sleep(300);
  }
  throw new VPhoneError("could not navigate to the Home channel list", { code: "navigation_failed" });
}

function channelConversationVisible(tree, config) {
  return findElements(tree, { text: `Message #${config.channel}`, match: "contains" }).length > 0;
}

async function openChannel(driver, config) {
  await driver.appsTerminate();
  await openHome(driver, config);
  const tree = await driver.uiTree();
  if (!channelConversationVisible(tree, config)) {
    await tapAny(driver, [
      { text: config.channel, role: "button", match: "exact" },
      { text: `#${config.channel}`, role: "button", match: "exact" },
      { text: config.channel, role: "button", match: "contains" },
    ]);
  }
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    if (channelConversationVisible(await driver.uiTree(), config)) return;
    await sleep(300);
  }
  throw new VPhoneError(`channel #${config.channel} did not expose its outer conversation composer`, { code: "navigation_failed" });
}

async function composerElement(driver, config, { reply = false } = {}) {
  const placeholder = reply ? "Reply in thread" : `Message #${config.channel}`;
  const tree = await driver.uiTree();
  const empty = findElements(tree, { text: placeholder, match: "contains" })[0];
  if (empty) return empty;
  const filled = flattenElements(tree).filter((element) => {
    return /^(element|textField|secureTextField)$/i.test(String(element.role || "")) && String(element.value || "").length > 0 && elementCenter(element);
  }).sort((a, b) => elementCenter(a).y - elementCenter(b).y);
  if (!filled.length) throw new VPhoneError(`composer not found by placeholder ${JSON.stringify(placeholder)} or editable value`, { code: "assertion_failed" });
  return filled.at(-1);
}

async function focusComposer(driver, config, options = {}) {
  const composer = await composerElement(driver, config, options);
  if (String(composer.value || "").length > 0) {
    await driver.tapElement({ value: String(composer.value), role: String(composer.role), match: "exact" });
  } else {
    const placeholder = options.reply ? "Reply in thread" : `Message #${config.channel}`;
    await driver.tapElement({ text: placeholder, match: "contains" });
  }
}

async function assertComposerValue(driver, config, expected, options = {}) {
  const composer = await composerElement(driver, config, options);
  const actual = String(composer.value || "");
  if (!actual.includes(expected)) {
    throw new VPhoneError(`composer value ${JSON.stringify(actual)} does not contain ${JSON.stringify(expected)}`, { code: "assertion_failed" });
  }
}

async function replaceComposerText(driver, config, text, options = {}) {
  await focusComposer(driver, config, options);
  await driver.inputKey("cmd+a");
  if (text) await driver.inputType(text);
  else await driver.inputKey("delete");
}

async function sendMessage(driver, config, text, options = {}) {
  await replaceComposerText(driver, config, text, options);
  await driver.tapElement({ text: "Send", role: "button", match: "exact" });
  await driver.waitFor({ text, role: "text", match: "exact" }, { timeoutMs: 20_000 });
}

async function longPressLatestText(driver, text, { role = "text" } = {}) {
  const tree = await driver.uiTree();
  const selector = { text, match: "exact", ...(role ? { role } : {}) };
  const candidates = findElements(tree, selector).map((element) => ({ element, center: elementCenter(element) })).filter((item) => item.center);
  if (!candidates.length) throw new VPhoneError(`no framed message with exact text ${JSON.stringify(text)}`, { code: "assertion_failed" });
  candidates.sort((a, b) => a.center.y - b.center.y);
  const selected = candidates.at(-1);
  await driver.inputLongPress(selected.center.x, selected.center.y);
}

function screenPoints(device) {
  const screen = device?.screen || device || {};
  const width = Number(screen.width ?? screen.point_width ?? screen.pointWidth);
  const height = Number(screen.height ?? screen.point_height ?? screen.pointHeight);
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    throw new VPhoneError("device.screen did not report point dimensions", { code: "screen_unknown" });
  }
  return { width, height };
}

async function caseColdStart({ driver, config, evidence }) {
  await driver.appsTerminate();
  const started = performance.now();
  await driver.appsLaunch();
  await driver.waitFor({ text: "Home", role: "button", match: "exact" }, { timeoutMs: 45_000 });
  const tree = await driver.uiTree();
  for (const tab of ["Home", "Activity", "Tasks", "Search"]) {
    if (!findElements(tree, { text: tab, role: "button", match: "exact" }).length) throw new Error(`cold start missing ${tab} tab`);
  }
  const coldStartMs = Math.round(performance.now() - started);
  evidence.writeJson("cold-start.json", { coldStartMs, limitMs: 5000 });
  if (coldStartMs > 5000) throw new VPhoneError(`cold start ${coldStartMs} ms exceeded 5000 ms`, { code: "cold_start_slow" });
  await evidence.capture(driver, "home");
  await driver.tapElement({ text: "Activity", role: "button", match: "exact" });
  await driver.waitFor({ text: "Filter", role: "button", match: "exact" });
  await driver.tapElement({ text: "Tasks", role: "button", match: "exact" });
  await driver.waitFor({ identifier: "tasks-page-scope-select", match: "exact" });
  await driver.tapElement({ text: "Search", role: "button", match: "exact" });
  await waitAny(driver, [{ identifier: "home-search", match: "contains" }, { text: "Search", match: "contains" }]);
  await driver.tapElement({ text: "Home", role: "button", match: "exact" });
  await driver.waitFor({ text: config.channel, match: "contains" }, { timeoutMs: 30_000 });
  const device = await driver.rpc("device.screen");
  const { width, height } = screenPoints(device);
  await driver.inputSwipe([Math.round(width / 2), Math.round(height * 0.25)], [Math.round(width / 2), Math.round(height * 0.75)]);
  await driver.waitFor({ text: "Loading", match: "contains" }, { timeoutMs: 3000, intervalMs: 100 });
  await evidence.capture(driver, "refresh-loading");
  await driver.waitFor({ text: config.channel, match: "contains" }, { timeoutMs: 20_000 });
  return `cold start ${coldStartMs} ms; all four tabs switched; pull refresh displayed Loading`;
}

async function caseChannelThread({ driver, config, evidence }) {
  await openChannel(driver, config);
  await sendMessage(driver, config, SMOKE_TEXT);
  await evidence.capture(driver, "channel-sent");
  await longPressLatestText(driver, SMOKE_TEXT);
  await driver.waitFor({ text: "Reply in Thread", role: "button", match: "exact" });
  await driver.assertText("Save", { role: "button", match: "exact" });
  await evidence.capture(driver, "message-menu");
  await driver.tapElement({ text: "Reply in Thread", role: "button", match: "exact" });
  await driver.waitFor({ text: "Reply in thread", match: "contains" });
  await sendMessage(driver, config, SMOKE_TEXT, { reply: true });
  await evidence.capture(driver, "thread-sent");
  await driver.tapElement({ text: "Back", role: "button", match: "exact" });
  const { tree } = await driver.waitFor({ text: "1 reply", role: "button", match: "contains" }, { timeoutMs: 20_000 });
  const preview = findElements(tree, { text: "1 reply", role: "button", match: "contains" }).find((element) => String(element.label || "").includes(SMOKE_TEXT));
  if (!preview) throw new Error("outer reply row does not contain the exact message preview");
  await evidence.capture(driver, "outer-reply");
  return "posted exact channel text, opened long-press menu, replied in thread, and verified outer count/preview";
}

async function casePhotos({ driver, evidence }) {
  let probe;
  try {
    probe = await driver.scriptEval(`(() => {
      const status = ObjC.use("PHPhotoLibrary").authorizationStatus();
      const assets = ObjC.use("PHAsset").fetchAssetsWithMediaType_options(1, null);
      return { authorization_status: Number(status), photo_count: Number(assets.count()) };
    })()`);
  } catch (error) {
    probe = { probeError: error.message };
  }
  evidence.writeJson("photo-environment.json", probe);
  await evidence.capture(driver, "photo-environment");
  const observed = probe?.result || probe;
  throw new NotCovered(`PhotoKit probe observed ${JSON.stringify(observed).slice(0, 400)}; no harness-owned deterministic photo fixture/authorization is available, so the Photos picker and background upload are not covered`);
}

async function longPressComposer(driver, config, options = {}) {
  const composer = await composerElement(driver, config, options);
  const center = elementCenter(composer);
  if (!center) throw new VPhoneError("composer has no AX frame for long press", { code: "tap_failed" });
  const frame = composer.frame || composer.rect;
  const x = Number.isFinite(Number(frame?.x)) && Number.isFinite(Number(frame?.width))
    ? Math.round(Number(frame.x) + Number(frame.width) * 0.25)
    : center.x;
  await driver.inputLongPress(x, center.y, 1.5);
}

async function caseSafariText({ driver, config, evidence }) {
  const paragraph = "This domain is for use in documentation examples without needing permission. This is not a service; avoid relying on it for testing and monitoring purposes.";
  await driver.appsLaunch("com.apple.mobilesafari");
  await driver.appsOpenUrl("https://example.com/", "com.apple.mobilesafari");
  await driver.waitFor({ text: paragraph, match: "exact" }, { timeoutMs: 30_000 });
  await longPressLatestText(driver, paragraph, { role: null });
  const copyTree = await driver.uiTree();
  const copyButton = findElements(copyTree, { text: "Copy", match: "exact" })[0];
  const copyCenter = elementCenter(copyButton);
  if (!copyCenter) throw new VPhoneError("Safari Copy button has no AX frame", { code: "tap_failed" });
  await sleep(500);
  await driver.inputTap(copyCenter.x, copyCenter.y);
  await sleep(500);
  const clipboard = await driver.clipboardGet();
  evidence.writeJson("safari-text-clipboard.json", clipboard);
  if (clipboard?.has_text !== true || !String(clipboard.text || "").includes("relying")) {
    throw new Error("Safari Copy did not put the expected paragraph fragment on the guest clipboard");
  }
  await openChannel(driver, config);
  await focusComposer(driver, config);
  await longPressComposer(driver, config);
  await driver.waitFor({ text: "Paste", match: "exact" }, { timeoutMs: 3000 });
  await driver.tapElement({ text: "Paste", match: "exact" });
  await assertComposerValue(driver, config, "relying");
  await evidence.capture(driver, "long-press-pasted");
  await replaceComposerText(driver, config, "");
  await driver.inputKey("cmd+v");
  await assertComposerValue(driver, config, "relying");
  await evidence.capture(driver, "keyboard-pasted");
  await replaceComposerText(driver, config, "");
  await driver.clipboardClear();
  return "Safari text Copy pasted through both the long-press menu and Cmd+V";
}

async function seedImageClipboard(driver, evidence, name) {
  const fixture = evidence.writeBinary("image-fixture.png", Buffer.from(IMAGE_PNG_BASE64, "base64"));
  const guestPath = "/private/var/tmp/raft-vphone-smoke.png";
  await driver.guestPush(fixture, guestPath);
  const result = await driver.scriptEval(`(() => {
    const image = ObjC.use("UIImage").imageWithContentsOfFile("${guestPath}");
    if (!image) throw new Error("UIImage fixture decode failed");
    ObjC.use("UIPasteboard").generalPasteboard().setImage_(image);
    return { seeded: true, guestPath: "${guestPath}" };
  })()`);
  const clipboard = await driver.clipboardGet();
  evidence.writeJson(`${name}-clipboard.json`, { script: result, clipboard });
  if (clipboard?.has_image !== true || clipboard?.has_text !== false) {
    throw new Error(`direct guest seed did not produce an image-only clipboard: ${JSON.stringify(clipboard)}`);
  }
  return clipboard;
}

async function assertAndCloseImageEditor(driver, evidence, name) {
  await driver.waitFor({ text: "Edit image", match: "exact" }, { timeoutMs: 8000, intervalMs: 200 });
  await driver.assertText("Done", { role: "button", match: "exact" });
  await evidence.capture(driver, `${name}-editor`);
  await driver.tapElement({ text: "Cancel", role: "button", match: "exact" });
  await waitAny(driver, [{ text: "Edit image", match: "exact" }], 1000).then(
    () => { throw new Error("image editor remained open after Cancel"); },
    (error) => {
      if (error.code !== "wait_timeout") throw error;
    },
  );
}

async function imagePasteAttempt({ driver, config, evidence, composerText, mode }) {
  await driver.appsTerminate();
  await openChannel(driver, config);
  await replaceComposerText(driver, config, composerText);
  const clipboard = await seedImageClipboard(driver, evidence, mode);
  const scenario = imagePasteScenario({ composerText, clipboard });
  if (mode.endsWith("menu")) {
    await longPressComposer(driver, config);
    const menu = await driver.uiTree();
    const pasteItems = findElements(menu, { text: "Paste", match: "exact" });
    evidence.writeJson(`${mode}-menu.json`, { scenario, pasteCount: pasteItems.length });
    if (pasteItems.length !== 1) throw new Error(`#4779 ${scenario}: expected exactly one image Paste, saw ${pasteItems.length}`);
    await driver.tapElement({ text: "Paste", match: "exact" });
  } else {
    await driver.inputKey("cmd+v");
  }
  await assertAndCloseImageEditor(driver, evidence, mode);
  if (composerText) await assertComposerValue(driver, config, composerText);
  return scenario;
}

async function caseImageVariant(context, composerText) {
  const attempts = [];
  for (const suffix of ["menu", "cmdv"]) {
    const mode = `${composerText ? "text" : "empty"}-${suffix}`;
    try {
      const scenario = await imagePasteAttempt({ ...context, composerText, mode });
      attempts.push({ mode, status: "PASS", scenario });
    } catch (error) {
      attempts.push({ mode, status: "FAIL", detail: error.message });
      try { await context.evidence.capture(context.driver, `${mode}-failed`); } catch { /* original assertion is authoritative */ }
    }
  }
  context.evidence.writeJson("image-attempts.json", attempts);
  await context.driver.clipboardClear();
  const failures = attempts.filter((item) => item.status === "FAIL");
  if (failures.length) throw new Error(`#4779 image-only paste must open the fixed image editor on 1.26: ${failures.map((item) => `${item.mode}: ${item.detail}`).join("; ")}`);
  return `image-only clipboard opened the image editor by menu and Cmd+V with ${composerText ? "existing composer text preserved" : "an empty composer"}`;
}

async function caseActivity({ driver, config, evidence }) {
  await openHome(driver, config);
  await driver.tapElement({ text: "Activity", role: "button", match: "exact" });
  await driver.waitFor({ text: "Filter", role: "button", match: "exact" });
  const { tree } = await driver.waitFor({ text: SMOKE_TEXT, role: "button", match: "contains" }, { timeoutMs: 20_000 });
  const rows = findElements(tree, { text: SMOKE_TEXT, role: "button", match: "contains" });
  if (!rows.length) throw new Error(`Activity has no row containing the exact message ${SMOKE_TEXT}`);
  const statuses = flattenElements(tree).map((element) => String(element.label || "")).filter((label) => /\b(In Progress|Done|Todo|Backlog|Closed)\b/.test(label));
  if (!statuses.length) throw new Error("Activity has no visible task status");
  evidence.writeJson("activity-observed.json", { exactMessageRows: rows.length, statuses });
  await driver.tapElement({ text: SMOKE_TEXT, role: "button", match: "contains" });
  await driver.waitFor({ text: SMOKE_TEXT, role: "text", match: "exact" }, { timeoutMs: 15_000 });
  await evidence.capture(driver, "activity-open");
  return `Activity opened the exact message and exposed task status (${statuses[0].slice(0, 100)})`;
}

async function caseSaved({ driver, config, evidence }) {
  await openChannel(driver, config);
  await longPressLatestText(driver, SMOKE_TEXT);
  const menu = await waitAny(driver, [
    { text: "Save", role: "button", match: "exact" },
    { text: "Remove from Saved", role: "button", match: "exact" },
    { text: "Remove Saved", role: "button", match: "exact" },
    { text: "Unsave", role: "button", match: "exact" },
  ]);
  if (menu.selector.text === "Save") {
    await driver.tapElement(menu.selector);
  } else {
    await driver.appsTerminate();
    await openChannel(driver, config);
  }
  await driver.waitFor({ text: "Saved", role: "button", match: "exact" });
  await openHome(driver, config);
  await driver.tapElement({ text: "Saved", role: "button", match: "contains" });
  const { element: savedText } = await driver.waitFor({ text: SMOKE_TEXT, role: "text", match: "exact" });
  await evidence.capture(driver, "saved-list");
  const savedCenter = elementCenter(savedText);
  await driver.inputTap(savedCenter.x, savedCenter.y);
  await driver.waitFor({ text: `Message #${config.channel}`, match: "contains" });
  await driver.assertText(SMOKE_TEXT, { role: "text", match: "exact" });
  await driver.assertText("Saved", { role: "button", match: "exact" });
  await evidence.capture(driver, "saved-original");
  return "saved the message, found it under Home Saved, and reopened the original";
}

async function chooseTaskFilter(driver, identifier, value) {
  await driver.tapElement({ identifier, match: "exact" });
  const { element } = await driver.waitFor({ identifier: `${identifier}-${value}`, match: "exact" });
  const center = elementCenter(element);
  if (!center) throw new VPhoneError(`task filter ${identifier}-${value} has no AX frame`, { code: "tap_failed" });
  await driver.inputTap(center.x, center.y);
  await sleep(500);
  const { width } = screenPoints(await driver.rpc("device.screen"));
  await driver.inputTap(Math.round(width - 40), 70);
  const expected = value === "done" ? "Done" : value === "all" ? "All" : "Mine";
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    const tree = await driver.uiTree();
    const selected = findElements(tree, { text: expected, identifier, match: "contains" }).length > 0;
    const popupGone = findElements(tree, { identifier: `${identifier}-${value}`, match: "exact" }).length === 0;
    if (selected && popupGone) return;
    await sleep(150);
  }
  throw new VPhoneError(`task filter ${identifier} did not settle on ${expected}`, { code: "assertion_failed" });
}

async function caseTasks({ driver, config, evidence }) {
  await openHome(driver, config);
  await driver.tapElement({ text: "Tasks", role: "button", match: "exact" });
  await driver.waitFor({ identifier: "tasks-page-scope-select", match: "exact" });
  await chooseTaskFilter(driver, "tasks-page-scope-select", "mine");
  await driver.assertText("Mine", { identifier: "tasks-page-scope-select", match: "contains" });
  await evidence.capture(driver, "tasks-mine");
  await chooseTaskFilter(driver, "tasks-page-scope-select", "all");
  await driver.assertText("All", { identifier: "tasks-page-scope-select", match: "contains" });
  await chooseTaskFilter(driver, "tasks-page-filter-status", "done");
  const { tree } = await driver.waitFor({ text: "Done", identifier: "tasks-page-filter-status", match: "contains" });
  const titles = flattenElements(tree).filter((element) => {
    if (element.role !== "text" || !element.label || element.identifier) return false;
    if (/^(Tasks|Done|\d+\s*\/\s*\d+|#.*|\d+.*ago.*)$/.test(element.label)) return false;
    return Boolean(elementCenter(element));
  });
  if (!titles.length) throw new Error("Done filter has no framed task title to open");
  await evidence.capture(driver, "tasks-done");
  const titleCenter = elementCenter(titles[0]);
  await driver.inputTap(titleCenter.x, titleCenter.y);
  await driver.waitFor({ identifier: "task-head-status", match: "exact" });
  await driver.waitFor({ text: "Done", identifier: "task-head-status", match: "contains" }, { timeoutMs: 15_000 });
  await evidence.capture(driver, "task-open");
  return "switched Mine/All, applied Done, and opened a Done task conversation";
}

async function caseOttie({ driver, config, evidence }) {
  await openHome(driver, config);
  const installed = await driver.appsSearch("Ottie");
  evidence.writeJson("ottie-environment.json", installed);
  await evidence.capture(driver, "ottie-environment");
  throw new NotCovered(`Observed Ottie app search ${JSON.stringify(installed).slice(0, 400)}; this guest has no harness-controlled signed-in Ottie assistant session, so assistant drag/collapse/close/dark-mode behavior is not covered`);
}

export function serverSettingsPoint(tree, serverName) {
  const popup = flattenElements(tree).find((element) => {
    const frame = element?.frame;
    return element?.role === "button" && String(element?.label || "").includes(serverName) &&
      Number(frame?.width) > 300 && Number(frame?.height) > 300;
  });
  if (!popup) return null;
  return {
    x: Math.round(Number(popup.frame.x) + Number(popup.frame.width) / 2),
    y: Math.round(Number(popup.frame.y) + Number(popup.frame.height) * 0.95),
  };
}

async function openSettings(driver, config) {
  await openHome(driver, config);
  await openServerMenu(driver, config.serverName);
  for (let attempt = 0; attempt < 10; attempt += 1) {
    const tree = await driver.uiTree();
    if (findElements(tree, { text: "Settings", role: "button", match: "exact" }).length) {
      await driver.tapElement({ text: "Settings", role: "button", match: "exact" });
      break;
    }
    const point = serverSettingsPoint(tree, config.serverName);
    if (point) {
      await driver.inputTap(point.x, point.y);
      break;
    }
    await sleep(200);
  }
  const { width, height } = screenPoints(await driver.rpc("device.screen"));
  for (let attempt = 0; attempt < 6; attempt += 1) {
    if (findElements(await driver.uiTree(), { identifier: "settings-search-input", match: "exact" }).length) break;
    await driver.inputSwipe(
      [Math.round(width / 2), Math.round(height * 0.35)],
      [Math.round(width / 2), Math.round(height * 0.75)],
    );
    await sleep(400);
  }
  await driver.waitFor({ identifier: "settings-search-input", match: "exact" });
}

async function openSettingsRow(driver, label) {
  const tree = await driver.uiTree();
  if (!findElements(tree, { text: label, role: "button", match: "exact" }).length) {
    if (label === "Feedback") {
      if (findElements(tree, { identifier: "settings-search-clear", match: "exact" }).length) {
        await driver.tapElement({ identifier: "settings-search-clear", match: "exact" });
        await sleep(300);
      }
      const { width, height } = screenPoints(await driver.rpc("device.screen"));
      for (let attempt = 0; attempt < 5; attempt += 1) {
        await driver.inputSwipe(
          [Math.round(width / 2), Math.round(height * 0.75)],
          [Math.round(width / 2), Math.round(height * 0.35)],
        );
        await sleep(350);
        if (findElements(await driver.uiTree(), { text: label, role: "button", match: "exact" }).length) break;
      }
    } else {
      await driver.tapElement({ identifier: "settings-search-input", match: "exact" });
      await driver.inputKey("cmd+a");
      await driver.inputType(label);
    }
  }
  await driver.waitFor({ text: label, role: "button", match: "exact" });
  await driver.tapElement({ text: label, role: "button", match: "exact" });
}

async function selectThemeMode(driver, mode) {
  await driver.tapElement({ text: mode, role: "button", match: "exact" });
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    const tree = await driver.uiTree();
    const element = findElements(tree, { text: mode, role: "button", match: "exact" })[0];
    if (element && (element.selected === true || (Number(element.traits) & 8) !== 0)) return;
    await sleep(150);
  }
  throw new VPhoneError(`${mode} did not expose the selected accessibility trait`, { code: "assertion_failed" });
}

async function caseAppearanceRelogin({ driver, config, evidence }) {
  if (!config.email || !config.password) throw new Error("case 10 requires QA_EMAIL and QA_PASSWORD for the mandatory browser relogin");
  await openSettings(driver, config);
  await openSettingsRow(driver, "Appearance");
  await driver.tapElement({ text: "Theme on", role: "button", match: "contains" });
  await driver.waitFor({ text: "Light and dark", match: "exact" });
  await selectThemeMode(driver, "Dark");
  await evidence.capture(driver, "dark");
  await selectThemeMode(driver, "Light");
  await evidence.capture(driver, "light");
  await driver.tapElement({ text: "Back", role: "button", match: "exact" });
  await driver.waitFor({ text: "Appearance", role: "text", match: "exact" });
  await driver.tapElement({ text: "Back", role: "button", match: "exact" });
  await driver.waitFor({ identifier: "settings-search-input", match: "exact" });
  await openSettingsRow(driver, "Feedback");
  await driver.assertText("New Feedback", { role: "button", match: "exact" });
  await evidence.capture(driver, "feedback");
  await driver.tapElement({ text: "Back", role: "button", match: "exact" });
  await openSettings(driver, config);
  const { width, height } = screenPoints(await driver.rpc("device.screen"));
  await openSettingsRow(driver, "Account");
  await driver.waitFor({ text: "Connected accounts", match: "exact" });
  driver.setSensitiveScreen(true);
  for (let attempt = 0; attempt < 8; attempt += 1) {
    if (findElements(await driver.uiTree(), { text: "Sign out", role: "button", match: "exact" }).length) break;
    await driver.inputSwipe(
      [Math.round(width / 2), Math.round(height * 0.75)],
      [Math.round(width / 2), Math.round(height * 0.35)],
    );
    await sleep(350);
  }
  let signOutCenter = null;
  let previousCenter = null;
  for (let attempt = 0; attempt < 10; attempt += 1) {
    const { element } = await driver.waitFor({ text: "Sign out", role: "button", match: "exact" }, { timeoutMs: 5000 });
    signOutCenter = elementCenter(element);
    if (previousCenter && signOutCenter.x === previousCenter.x && signOutCenter.y === previousCenter.y) break;
    previousCenter = signOutCenter;
    await sleep(300);
  }
  evidence.writeJson("sign-out-first-tap.json", { center: signOutCenter });
  await driver.inputTap(signOutCenter.x, signOutCenter.y);
  await driver.waitFor({ text: "Sign out on this device?", match: "contains" }, { timeoutMs: 5000 });
  const confirmation = await driver.uiTree();
  const signOutButtons = findElements(confirmation, { text: "Sign out", role: "button", match: "exact" });
  if (!signOutButtons.length) throw new Error("sign-out confirmation is missing");
  const confirmCenter = bottommostElementCenter(signOutButtons);
  const signInSelectors = [
    { text: "Sign in to Raft", role: "button", match: "exact" },
    { text: "Continue with Browser", role: "button", match: "contains" },
    { text: "Sign in with Browser", role: "button", match: "contains" },
  ];
  await driver.input("double_tap", { x: confirmCenter.x, y: confirmCenter.y, interval: 0.1 });
  try {
    await waitAny(driver, signInSelectors, 4000);
  } catch (error) {
    if (error.code !== "wait_timeout") throw error;
    evidence.writeJson("sign-out-first-attempt.json", { method: "input.double_tap", center: confirmCenter, outcome: "confirmation_unchanged" });
    const confirmPixels = pointToPixels(await driver.rpc("device.screen"), confirmCenter);
    evidence.writeJson("sign-out-second-attempt.json", { method: "raw_pixel_tap", pixels: confirmPixels });
    await driver.guestTapPixels(confirmPixels.x, confirmPixels.y);
  }
  await waitAny(driver, signInSelectors, 20_000);
  await browserSignIn(driver, config);
  if (!serverIdentityVisible(await driver.uiTree(), config.serverName)) throw new VPhoneError("relogin returned to the wrong server", { code: "identity_mismatch" });
  await evidence.capture(driver, "relogin-home");
  return "rendered Dark and Light, opened Feedback, signed out, and returned to Home through browser relogin";
}

const navigationRealtimeCases = createNavigationRealtimeCases({
  openChannel,
  openHome,
  openSettings,
  openSettingsRow,
  sendMessage,
  longPressLatestText,
  smokeText: SMOKE_TEXT,
});

export const CASE_REGISTRY = [
  { id: CASE_IDS[0], timeoutMs: 120_000, run: caseColdStart },
  { id: CASE_IDS[1], timeoutMs: 120_000, run: caseChannelThread },
  { id: CASE_IDS[2], timeoutMs: 45_000, run: casePhotos },
  { id: CASE_IDS[3], timeoutMs: 120_000, run: caseSafariText },
  { id: CASE_IDS[4], timeoutMs: 120_000, run: (context) => caseImageVariant(context, "") },
  { id: CASE_IDS[5], timeoutMs: 120_000, run: (context) => caseImageVariant(context, "draft stays") },
  { id: CASE_IDS[6], timeoutMs: 60_000, run: caseActivity },
  { id: CASE_IDS[7], timeoutMs: 90_000, run: caseSaved },
  { id: CASE_IDS[8], timeoutMs: 90_000, run: caseTasks },
  { id: CASE_IDS[9], timeoutMs: 45_000, run: caseOttie },
  { id: CASE_IDS[10], timeoutMs: 150_000, run: caseAppearanceRelogin },
  { id: CASE_IDS[11], timeoutMs: 90_000, run: navigationRealtimeCases.doubleTapReplies },
  { id: CASE_IDS[12], timeoutMs: 90_000, run: navigationRealtimeCases.doubleTapAvatar },
  { id: CASE_IDS[13], timeoutMs: 90_000, run: navigationRealtimeCases.doubleTapMessageLink },
  { id: CASE_IDS[14], timeoutMs: 150_000, run: navigationRealtimeCases.realtimeConnect },
  { id: CASE_IDS[15], timeoutMs: 180_000, run: navigationRealtimeCases.realtimeOffline },
  { id: CASE_IDS[16], timeoutMs: 180_000, run: navigationRealtimeCases.realtimeChannelSwitch },
  { id: CASE_IDS[17], timeoutMs: 180_000, run: navigationRealtimeCases.realtimeBackground },
  { id: CASE_IDS[18], timeoutMs: 180_000, run: navigationRealtimeCases.realtimeServerSwitch },
];

async function executeCase(definition, context) {
  let timer;
  const work = Promise.resolve().then(() => definition.run(context));
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => {
      context.driver.cancelPending();
      reject(new VPhoneError(`case ${definition.id} exceeded ${definition.timeoutMs} ms`, { code: "case_timeout" }));
    }, definition.timeoutMs);
  });
  try {
    return await Promise.race([work, timeout]);
  } catch (error) {
    if (error?.code === "case_timeout") {
      await work.catch(() => {});
      context.driver.resumeCommands();
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

function startKeepAlive(driver, log, redact) {
  let active = true;
  let timer = null;
  const beat = async () => {
    if (!active) return;
    try {
      await driver?.guestUnlock?.();
    } catch (error) {
      log(`keepalive unlock failed: ${redact(error?.message || error)}`);
    } finally {
      if (active) {
        timer = setTimeout(beat, 20_000);
        timer.unref?.();
      }
    }
  };
  timer = setTimeout(beat, 20_000);
  timer.unref?.();
  return () => {
    active = false;
    if (timer) clearTimeout(timer);
  };
}

export async function runSmoke({ config, driver, redact, selectedIds = CASE_IDS, log = console.log, startupFailure = null }) {
  fs.mkdirSync(config.out, { recursive: true });
  const selected = CASE_REGISTRY.filter((definition) => selectedIds.includes(definition.id));
  if (!selected.length) throw new Error(`no cases selected; valid IDs: ${CASE_IDS.join(", ")}`);
  const stopKeepAlive = startKeepAlive(driver, log, redact);
  const summary = {
    startedAt: new Date().toISOString(),
    finishedAt: null,
    ok: false,
    config: redact(publicConfig(config)),
    startup: null,
    startupError: null,
    cases: [],
  };
  let startupReady = false;
  try {
    try {
      if (startupFailure) throw startupFailure;
      summary.startup = redact(await prepareSession(driver, config));
      startupReady = true;
      log(`startup: ${summary.startup.session}; artifacts ${config.out}`);
    } catch (error) {
      summary.startupError = redact(errorText(error));
      log(`startup FAIL: ${redact(error?.message || error)}`);
    }
    for (const definition of selected) {
      const evidence = makeEvidence(config, redact, definition.id);
      const started = performance.now();
      let result;
      if (!startupReady) {
        result = { id: definition.id, status: "FAIL", detail: "not executed because startup failed", evidence: evidence.files };
        evidence.writeText("startup-blocked.txt", `${summary.startupError}\n`);
      } else {
        try {
          const detail = await executeCase(definition, { driver, config, evidence });
          result = { id: definition.id, status: "PASS", detail, evidence: evidence.files };
        } catch (error) {
          const status = classifyCaseOutcome(error);
          result = { id: definition.id, status, detail: redact(error?.message || error), evidence: evidence.files };
          try { await evidence.capture(driver, "final"); } catch (captureError) {
            evidence.writeText("final-capture-error.txt", `${redact(errorText(captureError))}\n`);
          }
        }
      }
      result.durationMs = Math.round(performance.now() - started);
      result.detail = redact(result.detail);
      summary.cases.push(result);
      log(`${result.id}: ${result.status} (${result.durationMs} ms) ${result.detail}`);
    }
  } finally {
    stopKeepAlive();
    driver?.cancelPending();
    driver?.resumeCommands?.();
    try { await driver?.clipboardClear(); } catch { /* VM may be unavailable */ }
    if (config.stopAfter && driver) {
      try { await driver.vmStop(); } catch (error) {
        summary.stopError = redact(errorText(error));
        log(`QA_STOP_AFTER stop failed: ${redact(error?.message || error)}`);
      }
    }
    summary.finishedAt = new Date().toISOString();
    summary.counts = resultCounts(summary.cases);
    summary.ok = !summary.startupError && !summary.stopError && summary.counts.FAIL === 0;
    const safeSummary = redact(summary);
    fs.writeFileSync(path.join(config.out, "summary.json"), `${JSON.stringify(safeSummary, null, 2)}\n`);
    fs.writeFileSync(path.join(config.out, "report.md"), renderReport(safeSummary, redact));
  }
  return summary;
}

async function main() {
  const args = process.argv.slice(2);
  if (args.includes("--help") || args.includes("-h")) {
    console.log(`Usage: node scripts/qa/ios-vphone-smoke/run-smoke.mjs [case-id ...]\n\nCases: ${CASE_IDS.join(", ")}\nEnvironment: VPHONE_CLI VPHONE_MACHINE QA_BUNDLE_ID QA_SERVER_NAME QA_CHANNEL QA_API_BASE QA_OUT QA_EMAIL QA_PASSWORD QA_STOP_AFTER`);
    return;
  }
  const unknown = args.filter((arg) => !CASE_IDS.includes(arg));
  if (unknown.length) throw new Error(`unknown case IDs: ${unknown.join(", ")}`);
  const config = resolveConfig();
  const redact = createRedactor([config.email, config.password]);
  let driver = null;
  let startupFailure = null;
  try {
    driver = createVphoneDriver({ cli: config.cli, machine: config.machine, bundleId: config.bundleId, redact });
  } catch (error) {
    startupFailure = error;
  }
  try {
    const summary = await runSmoke({ config, driver, redact, selectedIds: args.length ? args : CASE_IDS, startupFailure });
    process.exitCode = summary.ok ? 0 : 1;
  } catch (error) {
    console.error(redact(errorText(error)));
    process.exitCode = 1;
  }
}

const invokedDirectly = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invokedDirectly) await main();
