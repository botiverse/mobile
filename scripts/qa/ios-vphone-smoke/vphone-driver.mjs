import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const DEFAULT_MAX_OUTPUT = 2 * 1024 * 1024;
const DEFAULT_COMMAND_TIMEOUT = 45_000;

export class VPhoneError extends Error {
  constructor(message, { code = "vphone_error", cause, details } = {}) {
    super(message, { cause });
    this.name = "VPhoneError";
    this.code = code;
    this.details = details;
  }
}

export function createRedactor(secrets = []) {
  const values = new Set();
  const redact = (value) => {
    if (typeof value === "string") {
      let text = value;
      const replacements = [...values].sort((a, b) => b.length - a.length);
      for (const secret of replacements) {
        text = text.split(secret).join("[REDACTED]");
        const encoded = encodeURIComponent(secret);
        if (encoded !== secret) text = text.split(encoded).join("[REDACTED]");
      }
      return text
        .replace(/("(?:password|accessToken|refreshToken|token|code)"\s*:\s*")[^"]*(")/gi, "$1[REDACTED]$2")
        .replace(/\b(password|access_token|refresh_token|token|code)=[^&\s"']+/gi, "$1=[REDACTED]");
    }
    if (Array.isArray(value)) return value.map(redact);
    if (value && typeof value === "object") {
      if (Buffer.isBuffer(value)) return value;
      return Object.fromEntries(Object.entries(value).map(([key, item]) => {
        if (/^(password|accessToken|refreshToken|token|code)$/i.test(key)) return [key, "[REDACTED]"];
        return [key, redact(item)];
      }));
    }
    return value;
  };
  redact.add = (secret) => {
    if (typeof secret === "string" && secret.length > 0) values.add(secret);
  };
  for (const secret of secrets) redact.add(secret);
  return redact;
}

export function errorText(error) {
  if (error instanceof Error) return error.stack || error.message;
  return String(error);
}

export function parseJsonDocument(stdout, description = "vphone command") {
  const text = String(stdout ?? "").trim();
  if (!text) throw new VPhoneError(`${description} produced empty stdout`, { code: "empty_output" });
  try {
    return JSON.parse(text);
  } catch (cause) {
    throw new VPhoneError(`${description} produced invalid JSON: ${text.slice(0, 240)}`, {
      code: "invalid_json",
      cause,
    });
  }
}

export function parseRpcEnvelope(stdout, description = "vphone RPC") {
  const envelope = parseJsonDocument(stdout, description);
  if (!envelope || typeof envelope !== "object" || Array.isArray(envelope) || typeof envelope.ok !== "boolean") {
    throw new VPhoneError(`${description} did not return an {ok,result} envelope`, { code: "invalid_envelope" });
  }
  if (!envelope.ok) {
    const guest = envelope.guest_error || envelope.error;
    const code = guest?.code || envelope.code || "rpc_refused";
    const message = guest?.message || (typeof guest === "string" ? guest : null) || envelope.message || "request refused";
    throw new VPhoneError(`${description} failed [${code}]: ${message}`, { code, details: guest });
  }
  return Object.hasOwn(envelope, "result") ? envelope.result : envelope;
}

export function selectorFields(selector) {
  const fields = ["text", "identifier", "value", "role"].filter((key) => selector?.[key] !== undefined);
  if (!fields.length) throw new VPhoneError("selector needs text, identifier, value, or role", { code: "invalid_selector" });
  const match = selector.match || "contains";
  if (match !== "exact" && match !== "contains") {
    throw new VPhoneError(`selector match must be exact or contains, got ${match}`, { code: "invalid_selector" });
  }
  return { fields, match };
}

function candidateValues(node, field) {
  if (field === "text") return [node?.label, node?.text, node?.name];
  return [node?.[field]];
}

export function nodeMatches(node, selector) {
  const { fields, match } = selectorFields(selector);
  return fields.every((field) => {
    const wanted = String(selector[field]);
    return candidateValues(node, field).some((candidate) => {
      if (candidate === undefined || candidate === null) return false;
      const actual = String(candidate);
      return match === "exact" ? actual === wanted : actual.includes(wanted);
    });
  });
}

export function flattenElements(tree) {
  const roots = Array.isArray(tree) ? tree : tree?.elements || tree?.nodes || [];
  const flat = [];
  const visit = (node) => {
    if (!node || typeof node !== "object") return;
    flat.push(node);
    for (const child of node.children || []) visit(child);
  };
  for (const root of roots) visit(root);
  return flat;
}

export function findElements(tree, selector, { includeDisabled = false } = {}) {
  const index = selector?.index;
  const matches = flattenElements(tree).filter((node) => {
    if (node.visible === 0 || node.visible === false || (!includeDisabled && node.enabled === false)) return false;
    return nodeMatches(node, selector);
  });
  if (index === undefined) return matches;
  if (!Number.isInteger(index) || index < 0 || index >= matches.length) return [];
  return [matches[index]];
}

export function elementCenter(node) {
  const frame = node?.frame || node?.rect;
  let x;
  let y;
  let width;
  let height;
  if (Array.isArray(frame)) [x, y, width, height] = frame.map(Number);
  else if (frame && typeof frame === "object") ({ x, y, width, height } = frame);
  const values = [x, y, width, height].map(Number);
  if (!values.every(Number.isFinite) || values[2] <= 0 || values[3] <= 0) return null;
  return {
    x: Math.round(values[0] + values[2] / 2),
    y: Math.round(values[1] + values[3] / 2),
  };
}

export function pointToPixels(device, point) {
  const screen = device?.screen || device || {};
  const scale = Number(screen.scale);
  if (!Number.isFinite(scale) || scale <= 0) throw new VPhoneError("device.screen did not report a positive scale", { code: "screen_unknown" });
  return { x: Math.round(point.x * scale), y: Math.round(point.y * scale) };
}

export function imagePasteScenario({ composerText, clipboard }) {
  if (!clipboard || clipboard.has_image !== true || clipboard.has_text !== false) {
    throw new VPhoneError("image paste requires an image-only guest clipboard (has_image=true, has_text=false)", {
      code: "invalid_clipboard",
    });
  }
  return String(composerText || "").length === 0 ? "image-only-empty-composer" : "image-only-text-composer";
}

function boundedChunk(state, chunk, onOverflow) {
  if (state.closed) return;
  const remaining = state.limit - state.length;
  if (remaining > 0) {
    const slice = chunk.subarray(0, remaining);
    state.chunks.push(slice);
    state.length += slice.length;
  }
  if (chunk.length > remaining) {
    state.closed = true;
    onOverflow();
  }
}

async function runProcess(cli, args, {
  timeoutMs = DEFAULT_COMMAND_TIMEOUT,
  maxOutputBytes = DEFAULT_MAX_OUTPUT,
  spawnImpl = spawn,
  registerCancel = () => () => {},
} = {}) {
  return new Promise((resolve, reject) => {
    let child;
    try {
      child = spawnImpl(cli, args, {
        shell: false,
        stdio: ["ignore", "pipe", "pipe"],
        env: { ...process.env, NO_COLOR: "1" },
      });
    } catch (cause) {
      reject(new VPhoneError(`could not spawn ${cli}: ${cause.message}`, { code: "spawn_failed", cause }));
      return;
    }
    const stdout = { chunks: [], length: 0, limit: maxOutputBytes, closed: false };
    const stderr = { chunks: [], length: 0, limit: maxOutputBytes, closed: false };
    let settled = false;
    let timedOut = false;
    let overflow = "";
    const finish = (callback, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      unregister();
      callback(value);
    };
    const kill = () => {
      try { child.kill("SIGKILL"); } catch { /* already exited */ }
    };
    const unregister = registerCancel(kill);
    const timer = setTimeout(() => {
      timedOut = true;
      kill();
    }, timeoutMs);
    child.stdout.on("data", (chunk) => boundedChunk(stdout, chunk, () => {
      overflow = "stdout";
      kill();
    }));
    child.stderr.on("data", (chunk) => boundedChunk(stderr, chunk, () => {
      overflow = "stderr";
      kill();
    }));
    child.on("error", (cause) => finish(reject, new VPhoneError(`could not run ${cli}: ${cause.message}`, {
      code: "spawn_failed",
      cause,
    })));
    child.on("close", (code, signal) => {
      const out = Buffer.concat(stdout.chunks).toString("utf8");
      const err = Buffer.concat(stderr.chunks).toString("utf8");
      if (overflow) {
        finish(reject, new VPhoneError(`${cli} ${overflow} exceeded ${maxOutputBytes} bytes`, { code: "output_limit" }));
      } else if (timedOut) {
        finish(reject, new VPhoneError(`${cli} timed out after ${timeoutMs} ms`, { code: "command_timeout" }));
      } else {
        finish(resolve, { code, signal, stdout: out, stderr: err });
      }
    });
  });
}

function rpcParams(params) {
  if (!params || typeof params !== "object" || Array.isArray(params)) {
    throw new VPhoneError("RPC params must be one JSON object", { code: "invalid_params" });
  }
  return JSON.stringify(params);
}

function requireAbsolute(file, description) {
  const resolved = path.resolve(file);
  if (!path.isAbsolute(resolved)) throw new VPhoneError(`${description} must be absolute`, { code: "invalid_path" });
  return resolved;
}

export function createVphoneDriver({
  cli = process.env.VPHONE_CLI || "vphone-launchpad-cli",
  machine = process.env.VPHONE_MACHINE || "",
  bundleId = process.env.QA_BUNDLE_ID || "build.raft.app",
  redact = createRedactor(),
  commandTimeoutMs = DEFAULT_COMMAND_TIMEOUT,
  maxOutputBytes = DEFAULT_MAX_OUTPUT,
  spawnImpl,
} = {}) {
  if (!machine) throw new VPhoneError("VPHONE_MACHINE is required; the harness never creates machines", { code: "missing_machine" });
  const activeCancels = new Set();
  let sensitiveScreen = false;
  let commandsCancelled = false;

  async function raw(args, { timeoutMs = commandTimeoutMs, rpc = false } = {}) {
    if (commandsCancelled) throw new VPhoneError("command rejected while case cancellation is being drained", { code: "commands_cancelled" });
    let completed;
    try {
      completed = await runProcess(cli, args, {
        timeoutMs,
        maxOutputBytes,
        spawnImpl,
        registerCancel: (cancel) => {
          activeCancels.add(cancel);
          return () => activeCancels.delete(cancel);
        },
      });
    } catch (error) {
      throw new VPhoneError(redact(errorText(error)), { code: error.code || "process_failed", cause: error });
    }
    try {
      if (rpc) {
        if (completed.code !== 0 && !completed.stdout.trim()) {
          const detail = completed.stderr.trim() || `exit ${completed.code}`;
          throw new VPhoneError(`${args[3] || "vphone RPC"} failed: ${detail.slice(0, 1200)}`, { code: "process_failed" });
        }
        const result = parseRpcEnvelope(completed.stdout, args[3] || "vphone RPC");
        if (completed.code !== 0) throw new VPhoneError(`RPC succeeded but CLI exited ${completed.code}`, { code: "process_failed" });
        return result;
      }
      if (completed.code !== 0) {
        const detail = completed.stderr.trim() || completed.stdout.trim() || `exit ${completed.code}`;
        throw new VPhoneError(`${args.join(" ")} failed: ${detail.slice(0, 1200)}`, { code: "process_failed" });
      }
      return parseJsonDocument(completed.stdout, args.join(" "));
    } catch (error) {
      if (error instanceof VPhoneError && error.message.includes("[REDACTED]")) throw error;
      throw new VPhoneError(redact(errorText(error)), { code: error.code || "process_failed", cause: error });
    }
  }

  const api = {
    cli,
    machine,
    bundleId,
    redact,
    get sensitiveScreen() { return sensitiveScreen; },
    setSensitiveScreen(value) { sensitiveScreen = Boolean(value); },
    cancelPending() {
      commandsCancelled = true;
      for (const cancel of [...activeCancels]) cancel();
      activeCancels.clear();
    },
    resumeCommands() { commandsCancelled = false; },
    async rpc(method, params = {}, options = {}) {
      return raw(["guest", "rpc", machine, method, rpcParams(params)], { ...options, rpc: true });
    },
    async vmList() { return raw(["vm", "list"]); },
    async vmStart() {
      return raw(["vm", "start", machine, "--wait", "--timeout", "300"], { timeoutMs: 330_000 });
    },
    async vmWait() { return raw(["vm", "wait", machine, "--timeout", "300"], { timeoutMs: 330_000 }); },
    async vmStop() { return raw(["vm", "stop", machine], { timeoutMs: 90_000 }); },
    async guestUnlock() { return raw(["guest", "unlock", machine], { timeoutMs: 30_000 }); },
    async deviceInfo() { return api.rpc("device.info"); },
    async apps(method, params = {}, options) { return api.rpc(`apps.${method}`, params, options); },
    async appsList(params = { filter: "all", limit: 1000 }) { return api.apps("list", params); },
    async appsSearch(query) { return api.apps("search", { query }); },
    async appsLaunch(id = bundleId, url) { return api.apps("launch", { bundle_id: id, ...(url ? { url } : {}) }); },
    async appsForeground() { return api.apps("foreground"); },
    async appsInfo(id = bundleId) { return api.apps("info", { bundle_id: id }); },
    async appsTerminate(id = bundleId) { return api.apps("terminate", { bundle_id: id }); },
    async appsOpenUrl(url, id) { return api.apps("open_url", { url, ...(id ? { bundle_id: id } : {}) }); },
    async appsInstall(guestPath, registration) {
      return api.apps("install", { path: guestPath, ...(registration ? { registration } : {}) }, { timeoutMs: 180_000 });
    },
    async ui(method, params = {}, options) { return api.rpc(`ui.${method}`, params, options); },
    async uiTree(params = { max_elements: 1000, visible_only: true }) {
      let lastError;
      for (let attempt = 0; attempt < 4; attempt += 1) {
        try {
          return await api.ui("tree", params);
        } catch (error) {
          lastError = error;
          if (!/frontmost application could not be verified/i.test(error?.message || "")) throw error;
          await sleep(400);
        }
      }
      throw lastError;
    },
    async uiElementAt(x, y) { return api.ui("element_at", { x, y }); },
    async uiTapElement(selector) { return api.ui("tap_element", selector); },
    async uiWait(selector, timeoutMs = 10_000) { return api.ui("wait", { ...selector, timeout_ms: timeoutMs }); },
    async input(method, params = {}, options) { return api.rpc(`input.${method}`, params, options); },
    async inputTap(x, y) { return api.input("tap", { x, y }); },
    async guestTapPixels(x, y) {
      return raw(["guest", "send", machine, JSON.stringify({ t: "tap", x, y, screen: false })]);
    },
    async inputLongPress(x, y, seconds = 1) { return api.input("long_press", { x, y, seconds }); },
    async inputSwipe(from, to, seconds = 0.4) { return api.input("swipe", { from, to, seconds, steps: 20 }); },
    async inputKey(name) { return api.input("key", { name }); },
    async inputType(text) { return api.input("type", { text }); },
    async secretType(text) {
      redact.add(text);
      const directory = fs.mkdtempSync(path.join(os.tmpdir(), "vphone-secret-"));
      const localPath = path.join(directory, "credential.txt");
      const guestPath = `/private/var/tmp/${path.basename(directory)}.txt`;
      try {
        fs.writeFileSync(localPath, text, { mode: 0o600 });
        await api.guestPush(localPath, guestPath);
        await api.scriptEval(`(() => {
          const value = ObjC.use("NSString").stringWithContentsOfFile_encoding_error(${JSON.stringify(guestPath)}, 4, null);
          if (!value) throw new Error("credential read failed");
          ObjC.use("UIPasteboard").generalPasteboard().setString_(value);
          ObjC.use("NSFileManager").defaultManager().removeItemAtPath_error(${JSON.stringify(guestPath)}, null);
          return { transferred: true };
        })()`);
        await api.inputKey("cmd+v");
      } finally {
        try { await api.clipboardClear(); } catch { /* guest may be unavailable */ }
        fs.rmSync(directory, { recursive: true, force: true });
      }
    },
    async inputPaste(text) { return api.input("paste", { text }); },
    async clipboard(method, params = {}) { return api.rpc(`clipboard.${method}`, params); },
    async clipboardGet() { return api.clipboard("get"); },
    async clipboardSet(text) { return api.clipboard("set", { text }); },
    async clipboardClear() { return api.clipboard("clear"); },
    async scriptEval(source, timeoutMs = 30_000) {
      const result = await api.rpc("script.eval", { source, timeout_ms: timeoutMs }, { timeoutMs: timeoutMs + 15_000 });
      if (result?.ok === false || result?.crashed) {
        throw new VPhoneError(`script.eval failed: ${result?.message || result?.output || "guest script error"}`, { code: "script_failed" });
      }
      return result;
    },
    async filesPlist(guestPath) { return api.rpc("files.plist", { path: guestPath }); },
    async guestPush(localPath, guestPath) {
      return raw(["guest", "push", machine, requireAbsolute(localPath, "local path"), guestPath], { timeoutMs: 120_000 });
    },
    async screenshotToFile(file) {
      const target = requireAbsolute(file, "screenshot path");
      fs.mkdirSync(path.dirname(target), { recursive: true });
      const request = JSON.stringify({ t: "screenshot", path: target });
      const result = await raw(["guest", "send", machine, request], { timeoutMs: 45_000 });
      if (!fs.existsSync(target) || fs.statSync(target).size < 8) {
        throw new VPhoneError(`screenshot did not produce ${target}`, { code: "screenshot_missing" });
      }
      const signature = fs.readFileSync(target).subarray(0, 8);
      if (!signature.equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) {
        throw new VPhoneError(`screenshot is not a PNG: ${target}`, { code: "screenshot_invalid" });
      }
      return result;
    },
    async find(selector) {
      const tree = await api.uiTree();
      return { tree, elements: findElements(tree, selector) };
    },
    async assertText(text, { match = "exact", role, identifier, value, index } = {}) {
      const selector = { text, match, ...(role ? { role } : {}), ...(identifier ? { identifier } : {}), ...(value ? { value } : {}), ...(index === undefined ? {} : { index }) };
      const { tree, elements } = await api.find(selector);
      if (!elements.length) {
        throw new VPhoneError(`expected element not found: ${JSON.stringify(selector)}; visible=${flattenElements(tree).length}`, { code: "assertion_failed" });
      }
      return elements[0];
    },
    async assertRole(role, selector = {}) {
      const query = { ...selector, role, match: selector.match || "exact" };
      const { tree, elements } = await api.find(query);
      if (!elements.length) {
        throw new VPhoneError(`expected role not found: ${JSON.stringify(query)}; visible=${flattenElements(tree).length}`, { code: "assertion_failed" });
      }
      return elements[0];
    },
    async tapElement(selector) {
      selectorFields(selector);
      const tree = await api.uiTree();
      const element = findElements(tree, selector)[0];
      const center = elementCenter(element);
      let primaryError = "ui.tap_element declined";
      try {
        const result = await api.uiTapElement(selector);
        if (result?.tapped !== false) return { method: "ui.tap_element", result };
      } catch (error) {
        primaryError = redact(error?.message || error);
      }
      if (center) {
        await api.inputTap(center.x, center.y);
        return { method: "AX frame fallback", center, primaryError };
      }
      throw new VPhoneError(`tap failed for ${JSON.stringify(selector)}; ui.tap_element: ${primaryError}; no visible AX frame`, {
        code: "tap_failed",
      });
    },
    async waitFor(selector, { timeoutMs = 15_000, intervalMs = 350 } = {}) {
      selectorFields(selector);
      const deadline = Date.now() + timeoutMs;
      let count = 0;
      while (Date.now() <= deadline) {
        const tree = await api.uiTree();
        count = flattenElements(tree).length;
        const elements = findElements(tree, selector);
        if (elements.length) return { tree, element: elements[0] };
        if (Date.now() + intervalMs > deadline) break;
        await new Promise((resolve) => setTimeout(resolve, intervalMs));
      }
      throw new VPhoneError(`waited ${timeoutMs} ms for ${JSON.stringify(selector)}; visible=${count}`, { code: "wait_timeout" });
    },
    async captureEvidence(directory, name) {
      fs.mkdirSync(directory, { recursive: true });
      const tree = await api.uiTree();
      const treePath = path.join(directory, `${name}-tree.json`);
      fs.writeFileSync(treePath, `${JSON.stringify(redact(tree), null, 2)}\n`);
      const result = { tree: treePath, png: null, pngSkipped: sensitiveScreen };
      if (!sensitiveScreen) {
        const pngPath = path.join(directory, `${name}.png`);
        await api.screenshotToFile(pngPath);
        result.png = pngPath;
      }
      return result;
    },
    async prepareMachine() {
      const listed = await api.vmList();
      const payload = listed?.result || listed;
      const machines = Array.isArray(payload) ? payload : payload?.machines || [];
      if (!Array.isArray(machines)) throw new VPhoneError("vm list did not return a machine array", { code: "invalid_envelope" });
      const selected = machines.find((item) => item?.name === machine);
      if (!selected) throw new VPhoneError(`machine ${machine} not found; refusing to create one`, { code: "machine_missing" });
      if (selected.state === "stopped") await api.vmStart();
      else if (selected.state === "running") await api.vmWait();
      else throw new VPhoneError(`machine ${machine} is ${selected.state}; wait for host activity to finish`, { code: "machine_busy" });
      await api.guestUnlock();
      const device = await api.deviceInfo();
      const app = await api.appsInfo();
      return { device, app, machine: selected };
    },
  };
  return api;
}

export function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
