import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath, pathToFileURL } from "node:url";

// Runs the real ios_deterministic_repro.mjs entry point in a child process with its iOS driver
// replaced by a scripted fake (a module resolve hook swaps ./ios_driver.mjs). The fake plays a
// cold simulator: sign-in form, then the server picker, and records every tap. This script reads
// no goal, so none of the acceptance agent's server guards apply to it: it must pick the QA
// server row itself, and must never fall back to another row (artin, 2026-08-31).

const script = fileURLToPath(new URL("./ios_deterministic_repro.mjs", import.meta.url));
const QA_SERVER = "OranixSlockTest";

const SIGN_IN_DUMP = [
  '@e1 [text] "Sign In" [0,100][390,140]',
  '@e2 [text] "Email" [20,200][370,230]',
  '@e3 [text] "Password" [20,300][370,330]',
].join("\n");

function pickerDump(rows) {
  return ['@e1 [text] "Choose server" [0,80][390,120]', ...rows.map(
    (row, i) => `@e${i + 2} [button] "${row}" [20,${200 + i * 100}][370,${260 + i * 100}]`,
  )].join("\n");
}

const FAKE_DRIVER = String.raw`
import fs from "node:fs";
const scenario = JSON.parse(fs.readFileSync(process.env.FAKE_IOS_SCENARIO, "utf8"));
const log = (entry) => fs.appendFileSync(process.env.FAKE_IOS_LOG, JSON.stringify(entry) + "\n");
const bounds = (line) => {
  const m = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(line);
  return m && { x1: +m[1], y1: +m[2], x2: +m[3], y2: +m[4] };
};
export function createIosDriver() {
  let state = "signin";
  return {
    async waitReady() {},
    async screenshotPng() { return Buffer.from("png"); },
    async dumpUi() {
      if (state === "signin") return scenario.signIn;
      if (state === "picker") return scenario.picker;
      return '@e1 [text] "Home" [0,0][390,40]';
    },
    async tap(x, y) {
      let row = null;
      if (state === "picker") {
        const line = scenario.picker.split("\n").find((l) => {
          const b = bounds(l);
          return b && x >= b.x1 && x <= b.x2 && y >= b.y1 && y <= b.y2;
        });
        row = line ? /"([^"]*)"/.exec(line)[1] : null;
        if (row && row !== "Choose server") state = "home";
      }
      log({ type: "tap", x, y, screen: row === null ? "other" : "picker", row });
    },
    async text() {},
    async key(name) { if (name === "enter" && state === "signin") state = "picker"; return ""; },
    async dismissSystemAlerts() { return { present: false, accepted: false }; },
  };
}
`;

function runRepro(pickerRows) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "ios-deterministic-repro-test-"));
  const fakeDriver = path.join(dir, "fake_ios_driver.mjs");
  fs.writeFileSync(fakeDriver, FAKE_DRIVER);
  const hooks = path.join(dir, "hooks.mjs");
  fs.writeFileSync(hooks, `
export async function resolve(specifier, context, next) {
  if (specifier === "./ios_driver.mjs" && context.parentURL?.endsWith("/ios_deterministic_repro.mjs")) {
    return { url: ${JSON.stringify(pathToFileURL(fakeDriver).href)}, shortCircuit: true };
  }
  return next(specifier, context);
}
`);
  const preload = path.join(dir, "preload.mjs");
  // Register the driver swap, and collapse the script's fixed settle sleeps.
  fs.writeFileSync(preload, `
import { register } from "node:module";
register(${JSON.stringify(pathToFileURL(hooks).href)});
const realSetTimeout = globalThis.setTimeout;
globalThis.setTimeout = (fn, _ms, ...args) => realSetTimeout(fn, 0, ...args);
`);
  const scenarioFile = path.join(dir, "scenario.json");
  fs.writeFileSync(scenarioFile, JSON.stringify({ signIn: SIGN_IN_DUMP, picker: pickerDump(pickerRows) }));
  const logFile = path.join(dir, "driver.jsonl");
  const out = path.join(dir, "out");
  const result = spawnSync(
    process.execPath,
    ["--import", preload, script, "--ios-io-dir", path.join(dir, "io"), "--out", out],
    {
      cwd: dir,
      encoding: "utf8",
      timeout: 30_000,
      env: {
        PATH: process.env.PATH,
        HOME: dir,
        QA_TEST_EMAIL: "qa@example.test",
        QA_TEST_PASSWORD: "dummy-password-not-a-secret",
        FAKE_IOS_SCENARIO: scenarioFile,
        FAKE_IOS_LOG: logFile,
      },
    },
  );
  const taps = fs.existsSync(logFile)
    ? fs.readFileSync(logFile, "utf8").trim().split("\n").map((line) => JSON.parse(line))
    : [];
  const verdictFile = path.join(out, "verdict.json");
  const verdict = fs.existsSync(verdictFile) ? JSON.parse(fs.readFileSync(verdictFile, "utf8")) : null;
  fs.rmSync(dir, { recursive: true, force: true });
  return { ...result, pickerTaps: taps.filter((t) => t.screen === "picker"), verdict };
}

test("selects the QA server row even when the production server is listed first", () => {
  const result = runRepro(["Raft Android, /raft", `${QA_SERVER}, /oranix`, "qa-server-b-slock, /qa-b"]);
  assert.deepEqual(
    result.pickerTaps.map((t) => t.row),
    [`${QA_SERVER}, /oranix`],
    `exactly one picker tap, on the QA server row\n${result.stderr}`,
  );
  assert.ok(result.verdict, `the run must reach a verdict\n${result.stderr}`);
  assert.doesNotMatch(result.verdict.reason, /server picker shown/);
});

test("refuses to pick any server when the QA server row is missing", () => {
  const result = runRepro(["Raft Android, /raft", "qa-server-b-slock, /qa-b"]);
  assert.deepEqual(result.pickerTaps, [], "no server row may be tapped as a fallback");
  assert.equal(result.status, 2, result.stderr);
  assert.equal(result.verdict?.verdict, "error");
  assert.match(result.verdict.reason, new RegExp(`${QA_SERVER} row not found`));
});
