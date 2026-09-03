// iOS driver for the QA acceptance executor (agent-in-CI, iOS leg).
//
// Default inspect/act path is agent-device (same contract as ios-use after
// PR #2331). Screenshots stay on `xcrun simctl io screenshot` so the model
// still works in screenshot PIXELS. Frames from snapshot --json are POINTS;
// this driver owns the conversion.
//
// `--driver qa-loop` / IOS_QA_DRIVER=qa-loop keeps the QaAcceptanceLoopTests
// file protocol. Product-log verdicts stay outside this module.

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

export const DRIVER_AGENT_DEVICE = "agent-device";
export const DRIVER_QA_LOOP = "qa-loop";

export function resolveIosQaDriver(explicit) {
  const raw = explicit || process.env.IOS_QA_DRIVER || DRIVER_AGENT_DEVICE;
  if (raw === DRIVER_AGENT_DEVICE || raw === DRIVER_QA_LOOP) return raw;
  throw new Error(`unknown iOS QA driver ${JSON.stringify(raw)}; use agent-device or qa-loop`);
}

export function resolveAgentDeviceBinary(explicit) {
  const raw = explicit || process.env.AGENT_DEVICE_BIN || process.env.IOS_USE_AGENT_DEVICE;
  if (raw) {
    if (fs.existsSync(raw)) return raw;
    throw new Error(`agent-device binary not found: ${raw}`);
  }
  try {
    const found = execFileSync("which", ["agent-device"], { encoding: "utf8" }).trim();
    if (found) return found;
  } catch {
    /* fall through */
  }
  throw new Error(
    "agent-device is not on PATH. Install agent-device@0.20.10 or set AGENT_DEVICE_BIN. Fallback: IOS_QA_DRIVER=qa-loop",
  );
}

export function unwrapSnapshotPayload(parsed) {
  if (!parsed || typeof parsed !== "object") return { nodes: [] };
  if (Array.isArray(parsed.nodes)) return parsed;
  if (parsed.data && Array.isArray(parsed.data.nodes)) return parsed.data;
  if (Array.isArray(parsed)) return { nodes: parsed };
  return { nodes: [] };
}

export function parseJsonStdout(stdout) {
  const text = String(stdout ?? "").trim();
  if (!text) throw new Error("agent-device produced empty stdout");
  try {
    return JSON.parse(text);
  } catch {
    const start = text.indexOf("{");
    const end = text.lastIndexOf("}");
    if (start >= 0 && end > start) return JSON.parse(text.slice(start, end + 1));
    throw new Error(`agent-device output is not JSON: ${text.slice(0, 240)}`);
  }
}

function isEditableNode(node) {
  if (node.editable === true) return true;
  const role = String(node.role || node.type || "").toLowerCase();
  return /textfield|textarea|searchfield|securetextfield|edit/.test(role);
}

function isClickableNode(node) {
  if (node.clickable === true || node.hittable === true) return true;
  const role = String(node.role || node.type || "").toLowerCase();
  return /button|link|tab|cell|switch/.test(role);
}

export function snapshotToDescribeNodes(rawNodes) {
  return (rawNodes || []).map((node) => {
    const rect = node.rect || {};
    const frame = [
      Number(rect.x || 0),
      Number(rect.y || 0),
      Number(rect.width || 0),
      Number(rect.height || 0),
    ];
    return {
      label: node.label || "",
      id: node.identifier || node.ref || "",
      value: node.value || "",
      frame,
      focused: node.focused === true,
      editable: isEditableNode(node),
      clickable: isClickableNode(node),
      ref: node.ref,
    };
  });
}

export function compactDumpUi(nodes, toPx) {
  const lines = [];
  for (const n of nodes) {
    const label = n.label || n.id || "";
    const value = n.value || "";
    if (!label && !value && !n.focused && !n.editable && !n.clickable) continue;
    const [fx, fy, fw, fh] = n.frame || [0, 0, 0, 0];
    const bounds = `[${toPx(fx)},${toPx(fy)}][${toPx(fx + fw)},${toPx(fy + fh)}]`;
    const flags = `${n.editable ? "[INPUT] " : ""}${n.focused ? "[FOCUSED] " : ""}${!n.editable && n.clickable && !label ? "[BTN] " : ""}`;
    const shown = n.editable && value ? value : label || value;
    lines.push(`${flags}"${shown}" ${bounds}`);
  }
  const compact = lines.join("\n") || "(no labelled nodes on screen)";
  return compact.length > 20000 ? compact.slice(0, 20000) + "\n[truncated]" : compact;
}

export function pickTapText(nodes, wanted, toPx) {
  const withFrame = (nodes || []).filter((n) => n.frame);
  const norm = (s) => String(s).trim().toLowerCase();
  const w = norm(wanted);
  const exact = String(wanted).trim();
  const lab = (n) => n.label || n.id || "";
  let matches = withFrame.filter((n) => lab(n).trim() === exact || String(n.value || "").trim() === exact);
  if (!matches.length) matches = withFrame.filter((n) => norm(lab(n)) === w || norm(n.value || "") === w);
  if (!matches.length) matches = withFrame.filter((n) => norm(lab(n)).includes(w) || norm(String(n.value || "")).includes(w));
  if (!matches.length) return { feedback: `tap_text: no on-screen node matches "${wanted}" — use dump_ui to see what is visible` };
  const clickables = matches.filter((n) => n.clickable);
  const pool = clickables.length ? clickables : matches;
  const area = (n) => Math.max(1, n.frame[2]) * Math.max(1, n.frame[3]);
  const pick = pool.reduce((a, b) => (area(b) < area(a) ? b : a));
  const [fx, fy, fw, fh] = pick.frame;
  const x = toPx(fx + fw / 2);
  const y = toPx(fy + fh / 2);
  const note = matches.length > 1 ? ` (${matches.length} matches; picked the smallest${clickables.length ? " clickable" : ""})` : "";
  return { x, y, feedback: `tapped "${lab(pick) || pick.value}" at [${x},${y}]${note}` };
}

const UDID_RE = /^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$/;

export function parseAlertGet(stdout) {
  const text = String(stdout || "").trim();
  if (!text) return { present: false, raw: text };
  let parsed = null;
  try {
    parsed = parseJsonStdout(text);
  } catch {
    parsed = null;
  }
  const data = parsed?.data && typeof parsed.data === "object" ? parsed.data : parsed;
  if (data && typeof data === "object") {
    if (data.present === false || data.found === false) return { present: false, raw: text, parsed: data };
    if (data.title || data.buttons || data.action === "alert" || (Array.isArray(data.nodes) && data.nodes.length)) {
      return { present: true, raw: text, parsed: data };
    }
  }
  if (/no (active )?alert|none/i.test(text) && !/Would Like/i.test(text)) return { present: false, raw: text };
  if (/Allow|Don't Allow|Would Like/i.test(text)) return { present: true, raw: text };
  return { present: false, raw: text, parsed };
}

export function mapIosKeyToAgentDevice(name) {
  const key = String(name || "").trim().toLowerCase();
  if (key === "esc" || key === "escape") {
    return { argv: ["keyboard", "dismiss"], optional: true };
  }
  if (key === "enter" || key === "return") {
    return { argv: ["keyboard", "enter"], optional: false };
  }
  throw new Error(
    `iOS agent-device has no hardware key ${JSON.stringify(name)}; use keyboard dismiss/enter or press a UI selector`,
  );
}

export function agentDeviceTargetArgs(selector) {
  if (UDID_RE.test(String(selector || ""))) return ["--udid", selector];
  return ["--device", selector || "booted"];
}

export function swipePoints(direction, sizePt) {
  const w = sizePt.w;
  const h = sizePt.h;
  const midX = Math.round(w * 0.5);
  const midY = Math.round(h * 0.5);
  switch (String(direction)) {
    case "left":
    case "left-edge":
      return [4, midY, Math.round(w * 0.7), midY];
    case "right":
    case "right-edge":
      return [Math.round(w * 0.7), midY, 4, midY];
    case "up":
      return [midX, Math.round(h * 0.8), midX, Math.round(h * 0.2)];
    case "down":
      return [midX, Math.round(h * 0.2), midX, Math.round(h * 0.8)];
    default:
      throw new Error(`unknown swipe direction ${direction}`);
  }
}

function pngSize(buf) {
  return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) };
}

export function createIosDriver({
  ioDir,
  udid = "booted",
  bundle = "build.raft.app",
  driver,
  binary,
  fixturePage = process.env.QA_LOOP_FIXTURE_PAGE || "",
  fixturePageData = process.env.QA_LOOP_FIXTURE_PAGE_DATA || "",
} = {}) {
  if (!ioDir) throw new Error("createIosDriver requires ioDir");
  fs.mkdirSync(ioDir, { recursive: true });
  const resolved = resolveIosQaDriver(driver);
  const simctl = (args, extra = {}) =>
    execFileSync("xcrun", ["simctl", ...args], { maxBuffer: 64 * 1024 * 1024, timeout: 60000, ...extra });

  if (resolved === DRIVER_QA_LOOP) {
    return createQaLoopDriver({ ioDir, udid, bundle, simctl });
  }
  return createAgentDeviceDriver({
    ioDir,
    udid,
    bundle,
    binary: resolveAgentDeviceBinary(binary),
    fixturePage,
    fixturePageData,
    simctl,
  });
}

function createQaLoopDriver({ ioDir, udid, bundle, simctl }) {
  let seq = 0;
  let sizePx = null;
  let pointScale = null;

  async function send(action, timeoutMs = 90000) {
    seq += 1;
    const cmd = path.join(ioDir, `cmd-${seq}.json`);
    fs.writeFileSync(cmd + ".tmp", JSON.stringify(action));
    fs.renameSync(cmd + ".tmp", cmd);
    const res = path.join(ioDir, `res-${seq}.json`);
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (fs.existsSync(res)) {
        const out = JSON.parse(fs.readFileSync(res, "utf8"));
        if (!out.ok) throw new Error(`ios harness: ${out.error || "unknown error"}`);
        return out.data;
      }
      await new Promise((r) => setTimeout(r, 150));
    }
    throw new Error(`ios harness timeout on ${action.type} (step ${seq})`);
  }

  function screenshotPng() {
    const f = path.join(ioDir, "shot.png");
    simctl(["io", udid, "screenshot", f]);
    const buf = fs.readFileSync(f);
    sizePx = pngSize(buf);
    return buf;
  }

  function screenSizePx() {
    if (!sizePx) screenshotPng();
    return sizePx;
  }

  async function describeNodes() {
    const nodes = (await send({ type: "describe" })) || [];
    const rootW = Math.max(1, ...nodes.map((n) => n.frame?.[2] || 0));
    pointScale = screenSizePx().w / rootW;
    return nodes;
  }

  const toPx = (v) => Math.round(v * pointScale);

  return {
    platform: "ios",
    driver: DRIVER_QA_LOOP,
    async waitReady(timeoutMs = 15 * 60000) {
      const ready = path.join(ioDir, "ready.json");
      const deadline = Date.now() + timeoutMs;
      while (Date.now() < deadline) {
        if (fs.existsSync(ready)) return;
        await new Promise((r) => setTimeout(r, 1000));
      }
      throw new Error("ios harness never became ready (no ready.json)");
    },
    screenSize: () => screenSizePx(),
    screenSizePt: () => (pointScale ? { w: screenSizePx().w / pointScale, h: screenSizePx().h / pointScale, scale: pointScale } : null),
    screenshotPng,
    async tap(x, y) {
      const { w, h } = screenSizePx();
      await send({ type: "tap", x: x / w, y: y / h });
    },
    async longPress(x, y) {
      const { w, h } = screenSizePx();
      await send({ type: "long_press", x: x / w, y: y / h });
    },
    async swipe(direction) {
      await send({ type: "swipe", direction });
    },
    async drag({ from, to, durationSeconds = 0.3, holdSeconds = 0.05 }) {
      const { w, h } = screenSizePx();
      const dxPt = (to.x - from.x) * w / pointScale;
      const dyPt = (to.y - from.y) * h / pointScale;
      const distancePt = Math.sqrt(dxPt * dxPt + dyPt * dyPt);
      const velocity = Math.max(1, Math.round(distancePt / Math.max(durationSeconds, 0.01)));
      await send({
        type: "drag",
        x: from.x, y: from.y,
        x2: to.x, y2: to.y,
        holdSeconds,
        velocityPointsPerSecond: velocity,
      });
      return { distancePt: Math.round(distancePt), velocityPointsPerSecond: velocity };
    },
    async text(value) {
      await send({ type: "text", text: value });
    },
    async key(name) {
      await send({ type: "key", text: name });
    },
    async clear() {
      await send({ type: "key", text: "del", count: 60 });
    },
    openLink(url) {
      simctl(["openurl", udid, url]);
    },
    async launch() {
      return send({ type: "launch" }, 120000);
    },
    async dismissSystemAlerts() {
      return { present: false, skipped: DRIVER_QA_LOOP };
    },
    async pressLabel(text) {
      const picked = pickTapText(await describeNodes(), text, toPx);
      if (picked.x === undefined) throw new Error(picked.feedback);
      await send({ type: "tap", x: picked.x / screenSizePx().w, y: picked.y / screenSizePx().h });
      return picked;
    },
    async dumpUi() {
      return compactDumpUi(await describeNodes(), toPx);
    },
    async tapText(wanted) {
      return pickTapText(await describeNodes(), wanted, toPx);
    },
    async focusedFieldReport() {
      try {
        const nodes = await describeNodes();
        const f = nodes.find((n) => n.focused);
        if (!f) return "typed; no focused field found in describe — verify via screenshot";
        const [fx, fy, fw, fh] = f.frame || [0, 0, 0, 0];
        return `typed; focused field now contains "${String(f.value || "").slice(0, 80)}" at [${toPx(fx)},${toPx(fy)}][${toPx(fx + fw)},${toPx(fy + fh)}]`;
      } catch {
        return "typed; focus check failed — verify via screenshot";
      }
    },
    async finish() {
      try {
        await send({ type: "finish" }, 10000);
      } catch {}
    },
  };
}

function createAgentDeviceDriver({ ioDir, udid, bundle, binary, fixturePage, fixturePageData, simctl }) {
  const session = `qa-ios-${String(udid).replace(/[^A-Za-z0-9]/g, "").slice(0, 24) || "booted"}`;
  let sizePx = null;
  let pointScale = null;
  let opened = false;

  function runAd(args, { timeout = 90000 } = {}) {
    const argv = [binary, ...args, "--platform", "ios", ...agentDeviceTargetArgs(udid), "--session", session];
    try {
      return execFileSync(argv[0], argv.slice(1), {
        encoding: "utf8",
        maxBuffer: 64 * 1024 * 1024,
        timeout,
        stdio: ["ignore", "pipe", "pipe"],
      });
    } catch (error) {
      const detail = [error.stderr, error.stdout, error.message].filter(Boolean).join("\n").trim();
      throw new Error(`agent-device ${args.join(" ")} failed: ${detail.slice(0, 800)}`);
    }
  }

  function screenshotPng() {
    const f = path.join(ioDir, "shot.png");
    simctl(["io", udid, "screenshot", f]);
    const buf = fs.readFileSync(f);
    sizePx = pngSize(buf);
    return buf;
  }

  function screenSizePx() {
    if (!sizePx) screenshotPng();
    return sizePx;
  }

  function describeNodes() {
    const stdout = runAd(["snapshot", "-i", "--json"]);
    const payload = unwrapSnapshotPayload(parseJsonStdout(stdout));
    const nodes = snapshotToDescribeNodes(payload.nodes);
    const rootW = Math.max(1, ...nodes.map((n) => n.frame?.[2] || 0));
    pointScale = screenSizePx().w / rootW;
    return nodes;
  }

  const toPx = (v) => Math.round(v * pointScale);
  const toPt = (pixel) => Math.round(pixel / (pointScale || 1));

  function ensureScale() {
    if (!pointScale) describeNodes();
  }

  function launchWithFixture() {
    const env = { ...process.env };
    if (fixturePage) env.SIMCTL_CHILD_SLOCK_VISUAL_FIXTURE_PAGE = fixturePage;
    if (fixturePageData) env.SIMCTL_CHILD_SLOCK_VISUAL_FIXTURE_PAGE_DATA = fixturePageData;
    try {
      simctl(["terminate", udid, bundle], { env, timeout: 30000 });
    } catch {
      /* app may not be running yet */
    }
    simctl(["launch", "--terminate-running-process", udid, bundle], { env, timeout: 60000 });
  }

  return {
    platform: "ios",
    driver: DRIVER_AGENT_DEVICE,
    async waitReady() {
      if (fixturePage) {
        launchWithFixture();
        runAd(["open", bundle, "--foreground"], { timeout: 180000 });
      } else {
        runAd(["open", bundle], { timeout: 180000 });
      }
      opened = true;
      describeNodes();
    },
    screenSize: () => screenSizePx(),
    screenSizePt: () => (pointScale ? { w: screenSizePx().w / pointScale, h: screenSizePx().h / pointScale, scale: pointScale } : null),
    screenshotPng,
    async tap(x, y) {
      ensureScale();
      runAd(["press", String(toPt(x)), String(toPt(y)), "--settle"]);
    },
    async longPress(x, y) {
      ensureScale();
      runAd(["longpress", String(toPt(x)), String(toPt(y)), "--settle"]);
    },
    async swipe(direction) {
      ensureScale();
      const sizePt = { w: screenSizePx().w / pointScale, h: screenSizePx().h / pointScale };
      const [x1, y1, x2, y2] = swipePoints(direction, sizePt);
      runAd(["swipe", String(x1), String(y1), String(x2), String(y2)]);
    },
    async drag({ from, to }) {
      ensureScale();
      const { w, h } = screenSizePx();
      const x1 = toPt(from.x * w);
      const y1 = toPt(from.y * h);
      const x2 = toPt(to.x * w);
      const y2 = toPt(to.y * h);
      const dxPt = x2 - x1;
      const dyPt = y2 - y1;
      const distancePt = Math.round(Math.sqrt(dxPt * dxPt + dyPt * dyPt));
      runAd(["swipe", String(x1), String(y1), String(x2), String(y2)]);
      return { distancePt, velocityPointsPerSecond: null };
    },
    async text(value) {
      runAd(["type", String(value)]);
    },
    async key(name) {
      const mapped = mapIosKeyToAgentDevice(name);
      try {
        return runAd(mapped.argv);
      } catch (error) {
        const detail = String(error);
        if (mapped.optional && /UNSUPPORTED_OPERATION|already hidden|Keyboard already hidden/i.test(detail)) {
          return detail;
        }
        throw error;
      }
    },
    async clear() {
      throw new Error("iOS agent-device has no delete key; clear() cannot fake press label=delete");
    },
    openLink(url) {
      simctl(["openurl", udid, url]);
    },
    async launch() {
      runAd(["open", bundle, "--relaunch"], { timeout: 180000 });
      opened = true;
    },
    async dismissSystemAlerts({ timeoutMs = 30000 } = {}) {
      let raw = "";
      try {
        raw = runAd(["alert", "get", String(timeoutMs), "--json"], { timeout: timeoutMs + 5000 });
      } catch (error) {
        const detail = String(error);
        if (/alert not found|no (active )?alert|NO_ALERT/i.test(detail)) return { present: false, raw: detail };
        throw error;
      }
      const parsed = parseAlertGet(raw);
      if (!parsed.present) return parsed;
      const accepted = runAd(["alert", "accept", String(timeoutMs)], { timeout: timeoutMs + 5000 });
      return { present: true, accepted: true, raw, accept: accepted, parsed: parsed.parsed };
    },
    async pressLabel(text) {
      const escaped = String(text).replace(/\\/g, "\\\\").replace(/"/g, '\\"');
      return runAd(["press", `role="button" label="${escaped}" || label="${escaped}"`, "--settle"]);
    },
    async dumpUi() {
      return compactDumpUi(describeNodes(), toPx);
    },
    async tapText(wanted) {
      return pickTapText(describeNodes(), wanted, toPx);
    },
    async focusedFieldReport() {
      try {
        const nodes = describeNodes();
        const f = nodes.find((n) => n.focused);
        if (!f) return "typed; no focused field found in describe — verify via screenshot";
        const [fx, fy, fw, fh] = f.frame || [0, 0, 0, 0];
        return `typed; focused field now contains "${String(f.value || "").slice(0, 80)}" at [${toPx(fx)},${toPx(fy)}][${toPx(fx + fw)},${toPx(fy + fh)}]`;
      } catch {
        return "typed; focus check failed — verify via screenshot";
      }
    },
    async finish() {
      if (!opened) return;
      try {
        runAd(["close"], { timeout: 30000 });
      } catch {}
      opened = false;
    },
  };
}
