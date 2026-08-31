// iOS driver for the QA acceptance executor (agent-in-CI, iOS leg).
//
// Speaks the file protocol served by iosAppUITests/QaAcceptanceLoopTests.swift:
// one xcodebuild test run keeps the app alive for the whole acceptance run and
// executes actions written to $QA_LOOP_IO_DIR (cmd-<n>.json -> res-<n>.json).
// Screenshots bypass the harness entirely (`xcrun simctl io <udid> screenshot`).
//
// Coordinate contract: the model works in screenshot PIXELS (same as Android).
// The harness taps in normalized 0..1 and describes frames in POINTS; this
// driver owns both conversions, so the agent core stays platform-blind.

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

export function createIosDriver({ ioDir, udid = "booted", bundle = "build.raft.app" }) {
  fs.mkdirSync(ioDir, { recursive: true });
  let seq = 0;
  let sizePx = null; // set by the first screenshot
  let pointScale = null; // px per point, set by the first describe

  const simctl = (args, extra = {}) =>
    execFileSync("xcrun", ["simctl", ...args], { maxBuffer: 64 * 1024 * 1024, timeout: 60000, ...extra });

  // PNG IHDR: width/height as big-endian u32 at offsets 16/20.
  const pngSize = (buf) => ({ w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) });

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

  function screenSizePx() {
    if (!sizePx) screenshotPng();
    return sizePx;
  }

  function screenshotPng() {
    const f = path.join(ioDir, "shot.png");
    simctl(["io", udid, "screenshot", f]);
    const buf = fs.readFileSync(f);
    sizePx = pngSize(buf);
    return buf;
  }

  async function describeNodes() {
    const nodes = (await send({ type: "describe" })) || [];
    // Point->pixel scale from the widest node (the app window spans the screen).
    const rootW = Math.max(1, ...nodes.map((n) => n.frame?.[2] || 0));
    pointScale = screenSizePx().w / rootW;
    return nodes;
  }

  const px = (v) => Math.round(v * pointScale);

  return {
    platform: "ios",

    // The workflow starts the xcodebuild loop test in the background; app
    // launch + ready.json can trail the agent process by minutes on a cold
    // derived-data cache.
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
    // ⚠️ screenSize() is PIXELS. A case written as `24 / screenSize().w` therefore
    // asks for 24 *pixels*, which on a 3x device is 8 points — and a case whose
    // whole subject is "inside the platform edge inset" then measures a different
    // inset than the one it names. Cases specified in points must convert here.
    // Returns null until the first describe/dumpUi has established pointScale, so
    // a caller cannot silently get a 1:1 answer before the scale is known.
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
    // task #232/#233: normalized press-and-drag. swipe() is a whole-screen flick
    // and cannot express a start point, so it can neither reach the screen edge
    // (interactive-pop) nor make a deliberately short movement. Fractions are
    // 0..1 of the screen; durationSeconds is the press hold, so a larger value is
    // a slower drag.
    // ⚠️ durationSeconds is the DRAG duration, not a press hold. It is converted
    // to a velocity here because XCUITest expresses drag speed as points/second:
    // press(forDuration:thenDragTo:) alone holds and then flicks at the default
    // speed, which silently turns "slow short drag" into "flick" (run
    // 33161205106 group 4). Pass holdSeconds separately if a press is wanted.
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
      // Go through the loop protocol so the XCTest-side XCUIApplication
      // rebinds to the new process. An external `simctl launch` starts the
      // process but leaves the harness handle stale — every later action then
      // fails "Application <bundle> is not running" (task #8).
      return send({ type: "launch" }, 120000);
    },

    // Same semantic compression as the Android uiautomator dump: editable
    // nodes always survive (login fields), clickable icon-only nodes get [BTN],
    // bounds are pixel-space "[x1,y1][x2,y2]" so tap math is identical.
    async dumpUi() {
      const nodes = await describeNodes();
      const lines = [];
      for (const n of nodes) {
        const label = n.label || n.id || "";
        const value = n.value || "";
        if (!label && !value && !n.focused && !n.editable && !n.clickable) continue;
        const [fx, fy, fw, fh] = n.frame || [0, 0, 0, 0];
        const bounds = `[${px(fx)},${px(fy)}][${px(fx + fw)},${px(fy + fh)}]`;
        const flags = `${n.editable ? "[INPUT] " : ""}${n.focused ? "[FOCUSED] " : ""}${!n.editable && n.clickable && !label ? "[BTN] " : ""}`;
        const shown = n.editable && value ? value : label || value;
        lines.push(`${flags}"${shown}" ${bounds}`);
      }
      const compact = lines.join("\n") || "(no labelled nodes on screen)";
      return compact.length > 20000 ? compact.slice(0, 20000) + "\n[truncated]" : compact;
    },

    // Same policy as the Android tapTextResolve ladder (#1113/#1116):
    // case-sensitive exact -> ci exact -> ci substring; ties by smallest
    // area. Bounds come fresh from a describe at call time.
    async tapText(wanted) {
      const nodes = (await describeNodes()).filter((n) => n.frame);
      const norm = (s) => String(s).trim().toLowerCase();
      const w = norm(wanted);
      const exact = String(wanted).trim();
      const lab = (n) => n.label || n.id || "";
      let matches = nodes.filter((n) => lab(n).trim() === exact || String(n.value || "").trim() === exact);
      if (!matches.length) matches = nodes.filter((n) => norm(lab(n)) === w || norm(n.value || "") === w);
      if (!matches.length) matches = nodes.filter((n) => norm(lab(n)).includes(w) || norm(String(n.value || "")).includes(w));
      if (!matches.length) return { feedback: `tap_text: no on-screen node matches "${wanted}" — use dump_ui to see what is visible` };
      const clickables = matches.filter((n) => n.clickable);
      const pool = clickables.length ? clickables : matches;
      const area = (n) => Math.max(1, n.frame[2]) * Math.max(1, n.frame[3]);
      const pick = pool.reduce((a, b) => (area(b) < area(a) ? b : a));
      const [fx, fy, fw, fh] = pick.frame;
      const x = px(fx + fw / 2), y = px(fy + fh / 2);
      const note = matches.length > 1 ? ` (${matches.length} matches; picked the smallest${clickables.length ? " clickable" : ""})` : "";
      return { x, y, feedback: `tapped "${lab(pick) || pick.value}" at [${x},${y}]${note}` };
    },

    async focusedFieldReport() {
      try {
        const nodes = await describeNodes();
        const f = nodes.find((n) => n.focused);
        if (!f) return "typed; no focused field found in describe — verify via screenshot";
        const [fx, fy, fw, fh] = f.frame || [0, 0, 0, 0];
        return `typed; focused field now contains "${String(f.value || "").slice(0, 80)}" at [${px(fx)},${px(fy)}][${px(fx + fw)},${px(fy + fh)}]`;
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
