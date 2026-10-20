#!/usr/bin/env node
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, "..");

const defaults = {
  device: process.env.ANDROID_SERIAL || "emulator-5556",
  packageName: "build.raft.app",
  component: "build.raft.app/.MainActivity",
  apk: join(repoRoot, "compose/androidApp/build/outputs/apk/debug/androidApp-debug.apk"),
  outDir: join(repoRoot, "artifacts/android-smoke", timestamp()),
  channel: "对话流专修",
  rounds: "8",
  scrollRounds: "5",
};

const options = parseArgs(process.argv.slice(2));
const device = options.device ?? defaults.device;
const packageName = options.package ?? defaults.packageName;
const component = options.component ?? defaults.component;
const apk = resolve(options.apk ?? defaults.apk);
const outDir = resolve(options.outDir ?? defaults.outDir);
const channel = options.channel ?? defaults.channel;
const rounds = Number.parseInt(options.rounds ?? defaults.rounds, 10);
const scrollRounds = Number.parseInt(options.scrollRounds ?? defaults.scrollRounds, 10);

if (options.help) {
  printHelp();
  process.exit(0);
}

mkdirSync(outDir, { recursive: true });

const summary = {
  device,
  packageName,
  component,
  apk,
  outDir,
  channel,
  rounds,
  scrollRounds,
  startedAt: new Date().toISOString(),
  steps: [],
};

try {
  if (options.build) {
    run("gradle assembleDebug", "./gradlew", ["--no-daemon", "--console=plain", ":androidApp:assembleDebug"], {
      cwd: join(repoRoot, "compose"),
      inherit: true,
    });
  }

  if (!existsSync(apk)) {
    throw new Error(`APK not found: ${apk}. Run with --build or pass --apk.`);
  }

  if (!options.skipInstall) {
    run("adb install", "adb", ["-s", device, "install", "-r", apk]);
  }

  run("wake", "adb", ["-s", device, "shell", "input", "keyevent", "WAKEUP"]);
  run("launch", "adb", ["-s", device, "shell", "am", "start", "-W", "-n", component]);
  sleep(1800);

  capture("home-launch");
  ensureHome();
  capture("home");

  run("reset gfxinfo for tabs", "adb", ["-s", device, "shell", "dumpsys", "gfxinfo", packageName, "reset"], {
    outputFile: "tabs-reset-gfxinfo.txt",
  });
  run("clear logcat for tabs", "adb", ["-s", device, "logcat", "-c"]);
  runHomeTaskSwitches(rounds);
  collect("tabs");

  ensureHome();
  tapElement(channel, "channel");
  sleep(1800);
  capture("channel");

  run("reset gfxinfo for scroll", "adb", ["-s", device, "shell", "dumpsys", "gfxinfo", packageName, "reset"], {
    outputFile: "scroll-reset-gfxinfo.txt",
  });
  run("clear logcat for scroll", "adb", ["-s", device, "logcat", "-c"]);
  runThreadScroll(scrollRounds);
  collect("scroll");

  summary.finishedAt = new Date().toISOString();
  summary.ok = true;
} catch (error) {
  summary.finishedAt = new Date().toISOString();
  summary.ok = false;
  summary.error = String(error?.stack ?? error);
  writeFileSync(join(outDir, "summary.json"), `${JSON.stringify(summary, null, 2)}\n`);
  console.error(summary.error);
  process.exit(1);
}

writeFileSync(join(outDir, "summary.json"), `${JSON.stringify(summary, null, 2)}\n`);
console.log(`Slock Android smoke artifacts: ${outDir}`);

function parseArgs(args) {
  const result = {};
  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    if (arg === "--help" || arg === "-h") {
      result.help = true;
      continue;
    }
    if (arg === "--build" || arg === "--skip-install") {
      result[toCamel(arg.slice(2))] = true;
      continue;
    }
    if (!arg.startsWith("--")) {
      throw new Error(`Unexpected argument: ${arg}`);
    }
    const key = toCamel(arg.slice(2));
    const value = args[i + 1];
    if (!value || value.startsWith("--")) {
      throw new Error(`Missing value for ${arg}`);
    }
    result[key] = value;
    i += 1;
  }
  return result;
}

function toCamel(value) {
  return value.replace(/-([a-z])/g, (_, char) => char.toUpperCase());
}

function printHelp() {
  console.log(`Usage: node scripts/slock-android-smoke.mjs [options]

Installs and launches the Slock Android debug APK, restores the Home surface,
captures screenshots/UI summaries, runs Home <-> Tasks switching, opens a
channel, scrolls the conversation flow, and exports gfxinfo/logcat artifacts.

Options:
  --build                  Run :androidApp:assembleDebug before installing.
  --skip-install           Do not install the APK.
  --device <serial>        adb device serial. Default: ${defaults.device}
  --apk <path>             APK path. Default: ${defaults.apk}
  --package <name>         Android package. Default: ${defaults.packageName}
  --component <component>  Launch component. Default: ${defaults.component}
  --channel <name>         Home channel to open. Default: ${defaults.channel}
  --rounds <n>             Home/Tasks switch rounds. Default: ${defaults.rounds}
  --scroll-rounds <n>      Up/down scroll rounds. Default: ${defaults.scrollRounds}
  --out-dir <path>         Artifact directory. Default: artifacts/android-smoke/<timestamp>
`);
}

function run(label, command, args, opts = {}) {
  summary.steps.push({ label, command, args, at: new Date().toISOString() });
  const result = spawnSync(command, args, {
    cwd: opts.cwd ?? repoRoot,
    encoding: opts.binary ? undefined : "utf8",
    stdio: opts.inherit ? "inherit" : "pipe",
  });
  if (!opts.inherit && opts.outputFile) {
    const stdout = result.stdout ?? "";
    const stderr = result.stderr ?? "";
    writeFileSync(join(outDir, opts.outputFile), `${stdout}${stderr}`);
  }
  if (result.status !== 0) {
    const stderr = result.stderr ? String(result.stderr) : "";
    const stdout = result.stdout ? String(result.stdout) : "";
    throw new Error(`${label} failed (${command} ${args.join(" ")}):\n${stdout}${stderr}`);
  }
  return result.stdout ?? "";
}

function capture(name) {
  const png = spawnSync("adb", ["-s", device, "exec-out", "screencap", "-p"], { encoding: "buffer" });
  if (png.status !== 0) {
    throw new Error(`screencap failed: ${String(png.stderr ?? "")}`);
  }
  writeFileSync(join(outDir, `${name}.png`), png.stdout);

  const xml = run(`uiautomator ${name}`, "adb", ["-s", device, "shell", "uiautomator", "dump", "/sdcard/window.xml"]);
  writeFileSync(join(outDir, `${name}-uiautomator.txt`), xml);
  const tree = run(`ui xml ${name}`, "adb", ["-s", device, "shell", "cat", "/sdcard/window.xml"]);
  writeFileSync(join(outDir, `${name}.xml`), tree);
  writeFileSync(join(outDir, `${name}-content-desc.txt`), contentDescriptions(tree).join("\n") + "\n");
  return tree;
}

function collect(prefix) {
  run(`${prefix} gfxinfo`, "adb", ["-s", device, "shell", "dumpsys", "gfxinfo", packageName], {
    outputFile: `${prefix}-gfxinfo.txt`,
  });
  const logcat = run(`${prefix} logcat`, "adb", ["-s", device, "logcat", "-d", "-v", "time"]);
  writeFileSync(join(outDir, `${prefix}-logcat.txt`), logcat);
  const filtered = logcat
    .split("\n")
    .filter((line) => /Choreographer|Skipped|jank|SlockPerfMarker|SlockPerf|read_home|write_home|realtime_batch|frame/i.test(line))
    .join("\n");
  writeFileSync(join(outDir, `${prefix}-logcat-filtered.txt`), `${filtered}\n`);
  capture(`${prefix}-after`);
}

function ensureHome() {
  for (let i = 0; i < 3; i += 1) {
    const tree = capture(`ensure-home-${i}`);
    // Labels are source case since raft-ui 0.6 ("Channels", "Home"); match either case.
    if (/content-desc="channels/i.test(tree) && tree.includes(`content-desc="${escapeAttr(channel)}"`)) {
      return;
    }
    const home = findElement(tree, "HOME");
    if (home) {
      tap(home.centerX, home.centerY);
      sleep(900);
      continue;
    }
    run("back toward home", "adb", ["-s", device, "shell", "input", "keyevent", "BACK"]);
    sleep(900);
  }
  const tree = capture("ensure-home-final");
  if (!tree.includes(`content-desc="${escapeAttr(channel)}"`)) {
    throw new Error(`Could not restore Home with channel "${channel}" visible.`);
  }
}

function runHomeTaskSwitches(count) {
  for (let i = 0; i < count; i += 1) {
    tapElement("TASKS", "bottom tab");
    sleep(700);
    tapElement("HOME", "bottom tab");
    sleep(700);
  }
}

function runThreadScroll(count) {
  for (let i = 0; i < count; i += 1) {
    run("scroll up", "adb", ["-s", device, "shell", "input", "swipe", "540", "1780", "540", "520", "420"]);
    sleep(450);
    run("scroll down", "adb", ["-s", device, "shell", "input", "swipe", "540", "520", "540", "1780", "420"]);
    sleep(450);
  }
}

function tapElement(desc, role) {
  const tree = capture(`before-tap-${slug(desc)}`);
  const element = findElement(tree, desc);
  if (!element) {
    throw new Error(`Could not find ${role} with content-desc "${desc}".`);
  }
  tap(element.centerX, element.centerY);
}

function findElement(xml, desc) {
  const escaped = escapeRegex(desc);
  const pattern = new RegExp(`<node\\b[^>]*content-desc="${escaped}"[^>]*bounds="\\[([0-9]+),([0-9]+)\\]\\[([0-9]+),([0-9]+)\\]"`, "gi");
  const matches = [...xml.matchAll(pattern)].map((match) => {
    const [, left, top, right, bottom] = match.map(Number);
    return {
      left,
      top,
      right,
      bottom,
      centerX: Math.round((left + right) / 2),
      centerY: Math.round((top + bottom) / 2),
    };
  });
  if (matches.length === 0) {
    return null;
  }
  return matches.sort((a, b) => b.bottom - a.bottom)[0];
}

function tap(x, y) {
  run(`tap ${x},${y}`, "adb", ["-s", device, "shell", "input", "tap", String(x), String(y)]);
}

function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function contentDescriptions(xml) {
  return [...xml.matchAll(/content-desc="([^"]*)"/g)].map((match) => match[1]).filter(Boolean);
}

function escapeRegex(value) {
  return escapeAttr(value).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function escapeAttr(value) {
  return String(value).replaceAll("&", "&amp;").replaceAll('"', "&quot;");
}

function slug(value) {
  return String(value)
    .replace(/[^\p{L}\p{N}]+/gu, "-")
    .replace(/^-|-$/g, "")
    .slice(0, 48) || "item";
}

function timestamp() {
  return new Date().toISOString().replace(/[:.]/g, "-");
}
