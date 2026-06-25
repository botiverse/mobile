#!/usr/bin/env -S tsx
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

type Action = "open" | "status" | "on" | "off" | "reset" | "report" | "export";

type Options = {
  action?: Action;
  adb: string;
  device?: string;
  component: string;
  outDir: string;
  prefix: string;
  noLaunch: boolean;
  launchWaitMs: number;
  afterTapWaitMs: number;
  maxSwipes: number;
  pretty: boolean;
  help: boolean;
};

type UiNode = {
  text: string;
  desc: string;
  bounds?: [number, number, number, number];
  boundsText: string;
};

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, "..");

const defaults = {
  adb: process.env.ADB || "adb",
  device: process.env.ANDROID_SERIAL,
  component: "ai.slock.android/.MainActivity",
  outDir: join(repoRoot, "artifacts", "android-recomposition-profiler", timestamp()),
  prefix: "diagnostics-profiler",
  launchWaitMs: 1200,
  afterTapWaitMs: 800,
  maxSwipes: 6,
};

try {
  const options = parseArgs(process.argv.slice(2));
  if (options.help || !options.action) {
    printHelp();
    process.exit(options.help ? 0 : 2);
  }

  const result = runProfilerAction(options);
  printJson({ ok: true, ...result }, options.pretty);
} catch (error) {
  printJson({ ok: false, error: error instanceof Error ? error.message : String(error) }, false);
  process.exit(1);
}

function runProfilerAction(options: Options) {
  const device = ensureDevice(options);
  const outDir = ensureOut(options.outDir);
  const actions: Array<Record<string, unknown>> = [];

  if (!options.noLaunch) {
    runAdb(options, ["-s", device, "shell", "am", "start", "-W", "-n", options.component]);
    actions.push({ action: "start_app", component: options.component });
    sleep(options.launchWaitMs);
  }

  let { xml, state } = captureUiState(options, device, outDir, `${options.prefix}-initial`);
  if (!profilerScreenVisible(xml)) {
    ({ xml, state } = openDiagnosticsScreen(options, device, outDir, actions));
  }

  if (options.action === "on" || options.action === "off") {
    const desired = options.action === "on";
    const found = ensureUiNodeVisible(
      options,
      device,
      outDir,
      actions,
      findProfilerToggleRow,
      "recomposition profiler toggle",
    );
    xml = found.xml;
    state = found.state;
    const current = profilerToggleState(found.node);
    if (current === desired) {
      actions.push({ action: "skip_toggle", enabled: current });
    } else {
      tapUiNode(options, device, found.node);
      actions.push({ action: "tap", target: "recomposition profiler", desiredEnabled: desired });
      sleep(options.afterTapWaitMs);
      ({ xml, state } = captureUiState(options, device, outDir, `${options.prefix}-after-${options.action}`));
    }
  } else if (options.action === "reset" || options.action === "report" || options.action === "export") {
    const finders = {
      reset: findProfilerResetRow,
      report: findProfilerReportRow,
      export: findProfilerExportRow,
    };
    const found = ensureUiNodeVisible(options, device, outDir, actions, finders[options.action], `${options.action} row`);
    xml = found.xml;
    state = found.state;
    tapUiNode(options, device, found.node);
    actions.push({ action: "tap", target: options.action });
    sleep(options.afterTapWaitMs);
    ({ xml, state } = captureUiState(options, device, outDir, `${options.prefix}-after-${options.action}`));
  }

  return {
    device,
    requestedAction: options.action,
    outDir,
    actions,
    statusRows: profilerStatusRows(xml),
    lastState: state,
  };
}

function openDiagnosticsScreen(
  options: Options,
  device: string,
  outDir: string,
  actions: Array<Record<string, unknown>>,
) {
  let { xml, state } = captureUiState(options, device, outDir, `${options.prefix}-open`);
  const settings = findSettingsTab(xml);
  if (settings) {
    tapUiNode(options, device, settings);
    actions.push({ action: "tap", target: "settings_tab" });
    sleep(options.afterTapWaitMs);
    ({ xml, state } = captureUiState(options, device, outDir, `${options.prefix}-settings`));
  }

  for (let attempt = 0; attempt <= options.maxSwipes; attempt += 1) {
    if (profilerScreenVisible(xml)) {
      return { xml, state };
    }
    const diagnostics = findDiagnosticsEntry(xml);
    if (diagnostics) {
      tapUiNode(options, device, diagnostics);
      actions.push({ action: "tap", target: "diagnostics_entry", attempt });
      sleep(options.afterTapWaitMs);
      ({ xml, state } = captureUiState(options, device, outDir, `${options.prefix}-diagnostics`));
      if (profilerScreenVisible(xml) || findProfilerToggleRow(xml)) {
        return { xml, state };
      }
    }
    const developer = findDeveloperEntry(xml);
    if (developer) {
      tapUiNode(options, device, developer);
      actions.push({ action: "tap", target: "developer_entry", attempt });
      sleep(options.afterTapWaitMs);
      ({ xml, state } = captureUiState(options, device, outDir, `${options.prefix}-developer`));
      continue;
    }
    if (attempt < options.maxSwipes) {
      swipeDirection(options, device, "up");
      actions.push({ action: "swipe", direction: "up", target: "diagnostics_entry", attempt });
      sleep(300);
      ({ xml, state } = captureUiState(options, device, outDir, `${options.prefix}-settings-swipe-${attempt + 1}`));
    }
  }

  throw new Error("Unable to open Settings -> Diagnostics from the current UI");
}

function ensureUiNodeVisible(
  options: Options,
  device: string,
  outDir: string,
  actions: Array<Record<string, unknown>>,
  finder: (xml: string) => UiNode | undefined,
  label: string,
) {
  let { xml, state } = captureUiState(options, device, outDir, `${options.prefix}-find`);
  for (let attempt = 0; attempt <= options.maxSwipes; attempt += 1) {
    const node = finder(xml);
    if (node && isTappableRow(node)) {
      return { xml, state, node };
    }
    if (attempt < options.maxSwipes) {
      swipeDirection(options, device, "up");
      actions.push({ action: "swipe", direction: "up", target: label, attempt });
      sleep(300);
      ({ xml, state } = captureUiState(options, device, outDir, `${options.prefix}-find-swipe-${attempt + 1}`));
    }
  }
  throw new Error(`Unable to find ${label} on the Diagnostics screen`);
}

function captureUiState(options: Options, device: string, outDir: string, prefix: string) {
  const base = `${safePrefix(prefix)}-${timestamp()}`;
  const screenshotPath = join(outDir, `${base}.png`);
  const uixmlPath = join(outDir, `${base}.xml`);
  const uiautomatorLogPath = join(outDir, `${base}-uiautomator.txt`);

  const png = runAdb(options, ["-s", device, "exec-out", "screencap", "-p"], { binary: true });
  writeFileSync(screenshotPath, png.stdoutBytes);
  const dump = runAdb(options, ["-s", device, "shell", "uiautomator", "dump", "/sdcard/window.xml"]);
  writeFileSync(uiautomatorLogPath, `${dump.stdout}${dump.stderr}`, "utf8");
  const xml = runAdb(options, ["-s", device, "shell", "cat", "/sdcard/window.xml"]).stdout;
  writeFileSync(uixmlPath, xml, "utf8");
  return {
    xml,
    state: { screenshotPath, uixmlPath, uiautomatorLogPath },
  };
}

function uiNodes(xml: string): UiNode[] {
  const nodes: UiNode[] = [];
  for (const match of xml.matchAll(/<node\b([^>]*)>/g)) {
    const attrs = new Map<string, string>();
    for (const attr of match[1].matchAll(/([A-Za-z0-9_-]+)="([^"]*)"/g)) {
      attrs.set(attr[1], decodeXml(attr[2]));
    }
    const boundsText = attrs.get("bounds") || "";
    nodes.push({
      text: attrs.get("text") || "",
      desc: attrs.get("content-desc") || "",
      bounds: parseBounds(boundsText),
      boundsText,
    });
  }
  return nodes;
}

function findUiNode(xml: string, predicate: (node: UiNode) => boolean) {
  return uiNodes(xml).find(predicate);
}

function findSettingsTab(xml: string) {
  return (
    findUiNode(xml, (node) => nodeText(node).trim().toLowerCase() === "settings") ||
    findUiNode(xml, (node) => containsNodeText(node, "SETTINGS"))
  );
}

function findDiagnosticsEntry(xml: string) {
  return (
    findUiNode(xml, (node) => startsWithDesc(node, "Diagnostics,") && containsNodeText(node, "Open auth")) ||
    findUiNode(xml, (node) => startsWithDesc(node, "Diagnostics,"))
  );
}

function findDeveloperEntry(xml: string) {
  return findUiNode(xml, (node) => nodeText(node).trim().toLowerCase() === "developer");
}

function profilerScreenVisible(xml: string) {
  return Boolean(
    findProfilerToggleRow(xml) ||
      findUiNode(xml, (node) => containsNodeText(node, "RECOMPOSITION PROFILER")) ||
      findUiNode(xml, (node) => startsWithDesc(node, "Shared profiler status")),
  );
}

function findProfilerToggleRow(xml: string) {
  return findUiNode(xml, (node) => startsWithDesc(node, "Recomposition profiler,"));
}

function findProfilerResetRow(xml: string) {
  return findUiNode(xml, (node) => startsWithDesc(node, "Reset scene window,"));
}

function findProfilerReportRow(xml: string) {
  return findUiNode(xml, (node) => startsWithDesc(node, "Refresh report,"));
}

function findProfilerExportRow(xml: string) {
  return findUiNode(xml, (node) => startsWithDesc(node, "Export report,"));
}

function profilerToggleState(row: UiNode) {
  const trailing = row.desc.split(",").at(-1)?.trim().toLowerCase();
  if (trailing === "on") return true;
  if (trailing === "off") return false;
  return undefined;
}

function profilerStatusRows(xml: string) {
  const prefixes = [
    "Shared profiler status",
    "Recomposition profiler",
    "Reset scene window",
    "Refresh report",
    "Export report",
  ];
  return uiNodes(xml)
    .filter((node) => prefixes.some((prefix) => startsWithDesc(node, prefix)))
    .map((node) => ({ desc: node.desc, bounds: node.boundsText }));
}

function containsNodeText(node: UiNode, needle: string) {
  return nodeText(node).toLowerCase().includes(needle.toLowerCase());
}

function startsWithDesc(node: UiNode, prefix: string) {
  return node.desc.toLowerCase().startsWith(prefix.toLowerCase());
}

function nodeText(node: UiNode) {
  return [node.desc, node.text].filter(Boolean).join(" ");
}

function parseBounds(value: string): UiNode["bounds"] {
  const match = value.match(/\[(\d+),(\d+)]\[(\d+),(\d+)]/);
  if (!match) return undefined;
  return [Number(match[1]), Number(match[2]), Number(match[3]), Number(match[4])];
}

function isTappableRow(node: UiNode) {
  if (!node.bounds) return false;
  const [, top, , bottom] = node.bounds;
  return bottom - top >= 80;
}

function tapUiNode(options: Options, device: string, node: UiNode) {
  if (!node.bounds) {
    throw new Error(`Node has no tappable bounds: ${nodeText(node)}`);
  }
  const [left, top, right, bottom] = node.bounds;
  const text = nodeText(node).trim().toLowerCase();
  const x = text === "settings" && top > 1500 ? left + Math.min(48, Math.max(1, Math.floor((right - left) / 4))) : Math.floor((left + right) / 2);
  const y = Math.floor((top + bottom) / 2);
  runAdb(options, ["-s", device, "shell", "input", "tap", String(x), String(y)]);
}

function swipeDirection(options: Options, device: string, direction: "up" | "down" | "left" | "right") {
  const [x1, y1, x2, y2] = directionToSwipe(options, device, direction);
  runAdb(options, ["-s", device, "shell", "input", "swipe", String(x1), String(y1), String(x2), String(y2), "350"]);
}

function directionToSwipe(options: Options, device: string, direction: "up" | "down" | "left" | "right") {
  const [width, height] = screenSize(options, device);
  const midX = Math.floor(width / 2);
  const midY = Math.floor(height / 2);
  const left = Math.floor(width / 4);
  const right = width - left;
  const top = Math.max(120, Math.floor(height / 4));
  const bottom = height - top;
  if (direction === "up") return [midX, bottom, midX, top];
  if (direction === "down") return [midX, top, midX, bottom];
  if (direction === "left") return [right, midY, left, midY];
  return [left, midY, right, midY];
}

function screenSize(options: Options, device: string): [number, number] {
  const output = runAdb(options, ["-s", device, "shell", "wm", "size"]).stdout;
  const match = output.match(/Physical size:\s*(\d+)x(\d+)/);
  if (!match) {
    throw new Error(`Unable to parse screen size from: ${output.trim()}`);
  }
  return [Number(match[1]), Number(match[2])];
}

function ensureDevice(options: Options) {
  if (options.device) return options.device;
  const output = runAdb(options, ["devices"]).stdout;
  const devices = output
    .split(/\r?\n/)
    .slice(1)
    .map((line) => line.trim().split(/\s+/))
    .filter((parts) => parts.length >= 2 && parts[1] === "device")
    .map((parts) => parts[0]);
  if (devices.length === 0) {
    throw new Error("No online adb devices. Start an emulator/device or pass --device.");
  }
  return devices[0];
}

function runAdb(
  options: Options,
  args: string[],
  runOptions: { binary?: boolean; check?: boolean } = {},
) {
  const result = spawnSync(options.adb, args, {
    encoding: runOptions.binary ? "buffer" : "utf8",
    stdio: "pipe",
  });
  if (result.error) {
    throw result.error;
  }
  const status = result.status ?? 1;
  const stdoutBytes = Buffer.isBuffer(result.stdout) ? result.stdout : Buffer.from(String(result.stdout || ""), "utf8");
  const stderrBytes = Buffer.isBuffer(result.stderr) ? result.stderr : Buffer.from(String(result.stderr || ""), "utf8");
  const stdout = stdoutBytes.toString("utf8");
  const stderr = stderrBytes.toString("utf8");
  if (runOptions.check !== false && status !== 0) {
    throw new Error(`adb ${args.join(" ")} failed (${status}): ${stdout}${stderr}`);
  }
  return { status, stdout, stderr, stdoutBytes };
}

function parseArgs(args: string[]): Options {
  const result: Options = {
    adb: defaults.adb,
    device: defaults.device,
    component: defaults.component,
    outDir: defaults.outDir,
    prefix: defaults.prefix,
    noLaunch: false,
    launchWaitMs: defaults.launchWaitMs,
    afterTapWaitMs: defaults.afterTapWaitMs,
    maxSwipes: defaults.maxSwipes,
    pretty: false,
    help: false,
  };

  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];
    if (arg === "--help" || arg === "-h") {
      result.help = true;
      continue;
    }
    if (arg === "--pretty" || arg === "--no-launch") {
      result[toCamel(arg.slice(2)) as "pretty" | "noLaunch"] = true;
      continue;
    }
    if (!arg.startsWith("--") && !result.action) {
      if (!isAction(arg)) throw new Error(`Unknown action: ${arg}`);
      result.action = arg;
      continue;
    }
    if (!arg.startsWith("--")) {
      throw new Error(`Unexpected argument: ${arg}`);
    }
    const value = args[index + 1];
    if (!value || value.startsWith("--")) {
      throw new Error(`Missing value for ${arg}`);
    }
    setOption(result, toCamel(arg.slice(2)), value);
    index += 1;
  }

  return result;
}

function setOption(options: Options, key: string, value: string) {
  if (key === "adb") options.adb = value;
  else if (key === "device") options.device = value;
  else if (key === "component") options.component = value;
  else if (key === "outDir") options.outDir = resolve(value);
  else if (key === "prefix") options.prefix = value;
  else if (key === "launchWaitMs") options.launchWaitMs = numberOption(key, value);
  else if (key === "afterTapWaitMs") options.afterTapWaitMs = numberOption(key, value);
  else if (key === "maxSwipes") options.maxSwipes = numberOption(key, value);
  else throw new Error(`Unknown option: --${key.replace(/[A-Z]/g, (char) => `-${char.toLowerCase()}`)}`);
}

function numberOption(key: string, value: string) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed)) {
    throw new Error(`${key} must be a number`);
  }
  return parsed;
}

function isAction(value: string): value is Action {
  return ["open", "status", "on", "off", "reset", "report", "export"].includes(value);
}

function toCamel(value: string) {
  return value.replace(/-([a-z])/g, (_, char: string) => char.toUpperCase());
}

function ensureOut(value: string) {
  const path = resolve(value);
  mkdirSync(path, { recursive: true });
  return path;
}

function safePrefix(value: string) {
  return (value.trim() || "state").replace(/[^A-Za-z0-9_.-]+/g, "_");
}

function timestamp() {
  return new Date().toISOString().replace(/[:.]/g, "-");
}

function sleep(ms: number) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function decodeXml(value: string) {
  return value
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

function printJson(value: Record<string, unknown>, pretty: boolean) {
  console.log(JSON.stringify(value, null, pretty ? 2 : 0));
}

function printHelp() {
  console.log(`Usage: tsx scripts/slock-recomposition-profiler.ts <action> [options]

Open Settings > Diagnostics and control the Kuikly recomposition profiler.

Actions:
  open      Open the Diagnostics screen and capture state.
  status    Capture profiler rows without tapping controls.
  on        Enable the profiler if it is currently off.
  off       Disable the profiler if it is currently on.
  reset     Tap Reset scene window.
  report    Tap Refresh report.
  export    Tap Export report.

Options:
  --adb <path>               adb executable. Default: ${defaults.adb}
  --device <serial>          adb serial. Default: ANDROID_SERIAL or first online device.
  --component <component>    Launch component. Default: ${defaults.component}
  --out-dir <path>           Artifact directory. Default: artifacts/android-recomposition-profiler/<timestamp>
  --prefix <name>            Artifact filename prefix. Default: ${defaults.prefix}
  --no-launch                Use the current app screen instead of launching first.
  --launch-wait-ms <n>       Wait after launch. Default: ${defaults.launchWaitMs}
  --after-tap-wait-ms <n>    Wait after taps. Default: ${defaults.afterTapWaitMs}
  --max-swipes <n>           Max scroll attempts. Default: ${defaults.maxSwipes}
  --pretty                   Pretty-print JSON output.
`);
}
