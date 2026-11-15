import test from "node:test";
import assert from "node:assert/strict";
import {
  agentDeviceTargetArgs,
  compactDumpUi,
  mapIosKeyToAgentDevice,
  parseAlertGet,
  parseJsonStdout,
  pickTapText,
  resolveIosQaDriver,
  snapshotToDescribeNodes,
  swipePoints,
  unwrapSnapshotPayload,
} from "./ios_driver.mjs";

test("default iOS QA driver is agent-device and qa-loop remains explicit", () => {
  const previous = process.env.IOS_QA_DRIVER;
  delete process.env.IOS_QA_DRIVER;
  try {
    assert.equal(resolveIosQaDriver(), "agent-device");
    assert.equal(resolveIosQaDriver("qa-loop"), "qa-loop");
    process.env.IOS_QA_DRIVER = "qa-loop";
    assert.equal(resolveIosQaDriver(), "qa-loop");
    assert.throws(() => resolveIosQaDriver("xctest"), /unknown iOS QA driver/);
  } finally {
    if (previous === undefined) delete process.env.IOS_QA_DRIVER;
    else process.env.IOS_QA_DRIVER = previous;
  }
});

test("snapshot JSON unwraps daemon and data envelopes into describe nodes", () => {
  const payload = unwrapSnapshotPayload({
    data: {
      nodes: [
        { ref: "@e1", label: "Email", role: "textField", value: "ada", focused: true, rect: { x: 10, y: 20, width: 100, height: 40 } },
        { ref: "@e2", identifier: "add", role: "button", hittable: true, rect: { x: 300, y: 8, width: 44, height: 44 } },
      ],
    },
  });
  const nodes = snapshotToDescribeNodes(payload.nodes);
  assert.equal(nodes[0].editable, true);
  assert.equal(nodes[0].focused, true);
  assert.deepEqual(nodes[0].frame, [10, 20, 100, 40]);
  assert.equal(nodes[1].clickable, true);
  assert.equal(nodes[1].id, "add");
});

test("dumpUi keeps pixel bounds and INPUT/BTN flags", () => {
  const nodes = snapshotToDescribeNodes([
    { label: "Email", role: "textField", value: "ada", rect: { x: 10, y: 20, width: 100, height: 40 } },
    { role: "button", hittable: true, rect: { x: 300, y: 8, width: 44, height: 44 } },
  ]);
  const dump = compactDumpUi(nodes, (v) => Math.round(v * 3));
  assert.match(dump, /\[INPUT\] "ada" \[30,60\]\[330,180\]/);
  assert.match(dump, /\[BTN\] "" \[900,24\]\[1032,156\]/);
});

test("tapText prefers the smallest clickable match and returns pixel centers", () => {
  const nodes = snapshotToDescribeNodes([
    { label: "Add", role: "other", rect: { x: 0, y: 0, width: 400, height: 400 } },
    { label: "Add", role: "button", hittable: true, rect: { x: 10, y: 10, width: 20, height: 20 } },
  ]);
  const picked = pickTapText(nodes, "Add", (v) => Math.round(v * 2));
  assert.equal(picked.x, 40);
  assert.equal(picked.y, 40);
  assert.match(picked.feedback, /2 matches; picked the smallest clickable/);
});

test("parseAlertGet treats notification permission copy as present", () => {
  assert.equal(parseAlertGet("").present, false);
  assert.equal(parseAlertGet("no alert").present, false);
  assert.match("alert not found", /alert not found|no (active )?alert|NO_ALERT/i);
  assert.equal(parseAlertGet('{"title":"Raft Would Like to Send You Notifications","buttons":["Don\'t Allow","Allow"]}').present, true);
  assert.equal(parseAlertGet("Raft Would Like to Send You Notifications\nAllow").present, true);
});

test("iOS key() maps esc/enter onto keyboard commands and rejects hardware keys", () => {
  assert.deepEqual(mapIosKeyToAgentDevice("esc"), { argv: ["keyboard", "dismiss"], optional: true });
  assert.deepEqual(mapIosKeyToAgentDevice("enter"), { argv: ["keyboard", "enter"], optional: false });
  assert.throws(() => mapIosKeyToAgentDevice("del"), /no hardware key/);
});

test("CI UDIDs go to --udid so duplicate simulator names cannot steal the lease", () => {
  assert.deepEqual(
    agentDeviceTargetArgs("7065B5F0-2834-4150-99DF-84E057A22FD0"),
    ["--udid", "7065B5F0-2834-4150-99DF-84E057A22FD0"],
  );
  assert.deepEqual(agentDeviceTargetArgs("iPhone 16e"), ["--device", "iPhone 16e"]);
});

test("coordinate swipes stay in points so left-edge can reach interactive-pop", () => {
  assert.deepEqual(swipePoints("left-edge", { w: 390, h: 844 }), [4, 422, 273, 422]);
  assert.deepEqual(swipePoints("up", { w: 390, h: 844 }), [195, 675, 195, 169]);
});

test("parseJsonStdout accepts a leading banner then a JSON object", () => {
  const parsed = parseJsonStdout("agent-device 0.20.10\n{\"nodes\":[]}\n");
  assert.deepEqual(parsed, { nodes: [] });
});

// ---- secret input boundary (fake secret only) ----
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createIosDriver } from "./ios_driver.mjs";

const FAKE_IOS_SECRET = "Fake-iOS-Pa55-QX7";

function withSecretEnv(fn) {
  return async () => {
    const saved = { p: process.env.QA_TEST_PASSWORD, path: process.env.PATH, d: process.env.IOS_QA_DRIVER };
    process.env.QA_TEST_PASSWORD = FAKE_IOS_SECRET;
    try { await fn(); } finally {
      for (const [k, v] of [["QA_TEST_PASSWORD", saved.p], ["PATH", saved.path], ["IOS_QA_DRIVER", saved.d]]) {
        if (v === undefined) delete process.env[k]; else process.env[k] = v;
      }
    }
  };
}

function scan(dir) {
  const hits = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) hits.push(...scan(p));
    else if (fs.readFileSync(p, "latin1").includes(FAKE_IOS_SECRET)) hits.push(p);
  }
  return hits;
}

test("qa-loop driver never writes a secret into the command-file protocol (fails closed)", withSecretEnv(async () => {
  const ioDir = fs.mkdtempSync(path.join(os.tmpdir(), "qa-loop-secret-"));
  // Stand-in harness: acknowledge every command so a leaking path completes.
  const responder = setInterval(() => {
    for (const f of fs.readdirSync(ioDir)) {
      const m = /^cmd-(\d+)\.json$/.exec(f);
      if (m && !fs.existsSync(path.join(ioDir, `res-${m[1]}.json`))) fs.writeFileSync(path.join(ioDir, `res-${m[1]}.json`), '{"ok":true}');
    }
  }, 20);
  try {
    const drv = createIosDriver({ ioDir, driver: "qa-loop" });
    // A caller handing the raw value to text() (ios_deterministic_repro did) must be refused.
    await drv.text(FAKE_IOS_SECRET).catch(() => {});
    assert.deepEqual(scan(ioDir), [], "secret written to the qa-loop command file");
    await assert.rejects(() => drv.text(FAKE_IOS_SECRET), /secret/);
    assert.equal(typeof drv.secureText, "function", "driver must expose secureText (and refuse it)");
    await assert.rejects(() => drv.secureText(FAKE_IOS_SECRET), /refuses secret input/);
    assert.deepEqual(scan(ioDir), [], "secret written to the qa-loop I/O dir");
  } finally {
    clearInterval(responder);
    fs.rmSync(ioDir, { recursive: true, force: true });
  }
}));

test("agent-device driver never puts a secret in agent-device argv (fails closed)", withSecretEnv(async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "agent-device-secret-"));
  const log = path.join(root, "argv.log");
  const bin = path.join(root, "bin");
  fs.mkdirSync(bin);
  const fake = `#!/bin/sh\nprintf '%s\\n' "$*" >> "${log}"\necho '{}'\n`;
  fs.writeFileSync(path.join(bin, "agent-device"), fake, { mode: 0o755 });
  fs.writeFileSync(path.join(bin, "xcrun"), fake, { mode: 0o755 });
  fs.writeFileSync(log, "");
  process.env.PATH = `${bin}:${process.env.PATH}`;
  try {
    const drv = createIosDriver({ ioDir: path.join(root, "io"), driver: "agent-device", binary: path.join(bin, "agent-device") });
    await drv.text(FAKE_IOS_SECRET).catch(() => {});
    assert.ok(!fs.readFileSync(log, "utf8").includes(FAKE_IOS_SECRET), "secret in agent-device/xcrun argv");
    await assert.rejects(() => drv.text(FAKE_IOS_SECRET), /secret/);
    assert.equal(typeof drv.secureText, "function", "driver must expose secureText (and refuse it)");
    await assert.rejects(() => drv.secureText(FAKE_IOS_SECRET), /refuses secret input/);
    assert.ok(!fs.readFileSync(log, "utf8").includes(FAKE_IOS_SECRET), "secret in agent-device/xcrun argv");
    assert.deepEqual(scan(root).filter((p) => p !== log), []);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}));
