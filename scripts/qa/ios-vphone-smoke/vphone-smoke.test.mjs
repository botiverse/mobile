import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  createRedactor,
  createVphoneDriver,
  elementCenter,
  findElements,
  imagePasteScenario,
  nodeMatches,
  parseRpcEnvelope,
  pointToPixels,
} from "./vphone-driver.mjs";
import {
  bottommostElementCenter,
  CASE_IDS,
  CASE_REGISTRY,
  classifyCaseOutcome,
  renderReport,
  runSmoke,
  serverIdentityVisible,
  serverSettingsPoint,
  signedInRouteVisible,
  signingInVisible,
} from "./run-smoke.mjs";
import {
  analyzeRealtimeLog,
  assertMinimum126,
  assertSingleOpenRoundTrip,
  avatarForMessage,
  chooseQaServers,
  isFreshKmpEvidence,
  kmpRowEnabled,
  offlineIpv4Config,
  qaChannelFromInbox,
  realtimeFixtureText,
} from "./navigation-realtime-cases.mjs";

test("startup recognizes an already signed-in nested channel route", () => {
  assert.equal(signedInRouteVisible({ elements: [{ label: "Back", role: "button" }] }), true);
  assert.equal(signedInRouteVisible({ elements: [{ label: "Activity", role: "button" }] }), true);
  assert.equal(signedInRouteVisible({ elements: [{ label: "Continue with Browser", role: "button" }] }), false);
  assert.equal(serverIdentityVisible({ elements: [{ label: "OranixSlockTest", role: "button" }] }, "OranixSlockTest"), true);
  assert.equal(serverIdentityVisible({ elements: [{ label: "Other", role: "button" }] }, "OranixSlockTest"), false);
  assert.equal(signingInVisible({ elements: [{ label: "Signing in…", role: "button", enabled: false }] }), true);
  assert.equal(signingInVisible({ elements: [{ label: "Sign in to Raft", role: "button" }] }), false);
  assert.deepEqual(bottommostElementCenter([
    { frame: { x: 200, y: 690, width: 157, height: 48 } },
    { frame: { x: 276, y: 530, width: 81, height: 48 } },
  ]), { x: 279, y: 714 });
});

test("server Settings fallback derives the lower-middle action from the merged popup frame", () => {
  const tree = { elements: [
    { label: "Current server: QA", role: "button", frame: { x: 310, y: 769, width: 62, height: 62 } },
    { label: "QA, /qa, Other", role: "button", frame: { x: 8, y: 335, width: 377, height: 426 } },
  ] };
  assert.deepEqual(serverSettingsPoint(tree, "QA"), { x: 197, y: 740 });
  assert.equal(serverSettingsPoint({ elements: tree.elements.slice(0, 1) }, "QA"), null);
});

test("raw guest taps convert points to pixels with the reported screen scale", () => {
  assert.deepEqual(pointToPixels({ scale: 3 }, { x: 278, y: 570 }), { x: 834, y: 1710 });
  assert.throws(() => pointToPixels({}, { x: 1, y: 2 }), /positive scale/);
});

test("realtime channel lookup uses the canonical inbox groups contract", () => {
  const inbox = { items: [], groups: [{ channelId: "c-1", channelName: "all", channelType: "channel" }] };
  assert.deepEqual(qaChannelFromInbox(inbox, "#all"), { id: "c-1", name: "all" });
  assert.throws(() => qaChannelFromInbox({ channels: [{ id: "wrong", name: "all" }] }, "all"), /unavailable/);
});

test("RPC envelopes return result and preserve refused error codes", () => {
  assert.deepEqual(parseRpcEnvelope('{"ok":true,"result":{"count":2}}'), { count: 2 });
  assert.throws(
    () => parseRpcEnvelope('{"ok":false,"guest_error":{"code":"permission","message":"denied"}}'),
    (error) => error.code === "permission" && /denied/.test(error.message),
  );
  assert.throws(() => parseRpcEnvelope('{"result":{}}'), /did not return an \{ok,result\} envelope/);
  assert.throws(() => parseRpcEnvelope("not json"), /invalid JSON/);
});

test("selectors match text, identifier, value, and role with exact/contains semantics", () => {
  const node = { label: "Message #all", identifier: "composer-main", value: "draft", role: "element" };
  assert.equal(nodeMatches(node, { text: "Message #all", match: "exact" }), true);
  assert.equal(nodeMatches(node, { text: "message #all", match: "exact" }), false);
  assert.equal(nodeMatches(node, { identifier: "composer", match: "contains" }), true);
  assert.equal(nodeMatches(node, { value: "draft", match: "exact", role: "element", match: "contains" }), true);
  assert.equal(nodeMatches(node, { text: "Message", identifier: "other", match: "contains" }), false);
  assert.throws(() => nodeMatches(node, { match: "exact" }), /selector needs/);
  assert.throws(() => nodeMatches(node, { text: "x", match: "fuzzy" }), /exact or contains/);
});

test("findElements honors visibility and index without weakening exact matches", () => {
  const tree = { elements: [
    { label: "Save", visible: 1 },
    { label: "Save", visible: 0 },
    { label: "Save draft", visible: 1 },
    { label: "Save", visible: 1 },
  ] };
  assert.equal(findElements(tree, { text: "Save", match: "exact" }).length, 2);
  assert.equal(findElements(tree, { text: "Save", match: "contains" }).length, 3);
  assert.equal(findElements(tree, { text: "Save", match: "exact", index: 1 })[0], tree.elements[3]);
  assert.deepEqual(findElements(tree, { text: "Save", match: "exact", index: 2 }), []);
  const disabled = { elements: [{ label: "Sign in to Raft", enabled: false, visible: 1 }] };
  assert.equal(findElements(disabled, { text: "Sign in to Raft", match: "exact" }).length, 0);
  assert.equal(findElements(disabled, { text: "Sign in to Raft", match: "exact" }, { includeDisabled: true }).length, 1);
});

test("tapElement uses ui.tap_element even when an AX frame exists", async () => {
  const driver = createVphoneDriver({ cli: "unused", machine: "unit-test" });
  const calls = [];
  driver.uiTapElement = async (selector) => {
    calls.push({ method: "ui.tap_element", selector });
    return { tapped: true };
  };
  driver.uiTree = async () => ({ elements: [
    { label: "Done", role: "button", visible: 1, frame: { x: 10, y: 20, width: 30, height: 40 } },
  ] });
  driver.inputTap = async (x, y) => calls.push({ method: "input.tap", x, y });
  const result = await driver.tapElement({ text: "Done", role: "button", match: "exact" });
  assert.equal(result.method, "ui.tap_element");
  assert.deepEqual(calls.map((call) => call.method), ["ui.tap_element"]);
  assert.deepEqual(elementCenter({ frame: [1, 2, 10, 20] }), { x: 6, y: 12 });
  assert.equal(elementCenter({ label: "no frame" }), null);
});

test("tapElement falls back to the AX frame when ui.tap_element fails", async () => {
  const driver = createVphoneDriver({ cli: "unused", machine: "unit-test" });
  const calls = [];
  driver.uiTapElement = async () => { throw new Error("selector engine unavailable"); };
  driver.uiTree = async () => ({ elements: [
    { label: "Done", role: "button", visible: 1, frame: { x: 10, y: 20, width: 30, height: 40 } },
  ] });
  driver.inputTap = async (x, y) => calls.push({ method: "input.tap", x, y });
  const result = await driver.tapElement({ text: "Done", role: "button", match: "exact" });
  assert.equal(result.method, "AX frame fallback");
  assert.deepEqual(result.center, { x: 25, y: 40 });
  assert.match(result.primaryError, /selector engine unavailable/);
  assert.deepEqual(calls, [{ method: "input.tap", x: 25, y: 40 }]);
});

test("secretType keeps credentials out of every spawn argv and removes the local secret file", async () => {
  const calls = [];
  const spawnImpl = (cli, args) => {
    calls.push(args);
    const child = new EventEmitter();
    child.stdout = new EventEmitter();
    child.stderr = new EventEmitter();
    child.kill = () => {};
    const output = args[1] === "push" ? { ok: true, path: args.at(-1) } : { ok: true, result: {} };
    process.nextTick(() => {
      child.stdout.emit("data", Buffer.from(JSON.stringify(output)));
      child.emit("close", 0, null);
    });
    return child;
  };
  const driver = createVphoneDriver({ cli: "unused", machine: "unit-test", spawnImpl });
  await driver.secretType("qa@example.com");
  await driver.secretType("p@ssw0rd");
  const serialized = JSON.stringify(calls);
  assert.equal(serialized.includes("qa@example.com"), false);
  assert.equal(serialized.includes("p@ssw0rd"), false);
  const localSecret = calls.find((args) => args[1] === "push")?.[3];
  assert.ok(localSecret);
  assert.equal(fs.existsSync(localSecret), false);
  assert.deepEqual(calls.map((args) => args[1]), ["push", "rpc", "rpc", "rpc", "push", "rpc", "rpc", "rpc"]);
});

test("case classification distinguishes PASS, FAIL, and NOT_COVERED", () => {
  assert.equal(classifyCaseOutcome(null), "PASS");
  assert.equal(classifyCaseOutcome(new Error("assertion")), "FAIL");
  assert.equal(classifyCaseOutcome({ status: "NOT_COVERED", message: "empty library" }), "NOT_COVERED");
  assert.equal(classifyCaseOutcome({ status: "FAIL" }), "FAIL");
});

test("registry is ordered and includes independent navigation and realtime groups", () => {
  assert.deepEqual(CASE_REGISTRY.map((definition) => definition.id), CASE_IDS);
  assert.deepEqual(CASE_IDS, [
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
  ]);
  assert.equal(new Set(CASE_IDS).size, CASE_IDS.length);
  for (const definition of CASE_REGISTRY) {
    assert.equal(typeof definition.run, "function");
    assert.ok(definition.timeoutMs > 0);
  }
});

test("image-only clipboard semantics split empty composer from existing text plus image", () => {
  const clipboard = { has_image: true, has_text: false };
  assert.equal(imagePasteScenario({ composerText: "", clipboard }), "image-only-empty-composer");
  assert.equal(imagePasteScenario({ composerText: "draft", clipboard }), "image-only-text-composer");
  assert.throws(() => imagePasteScenario({ composerText: "", clipboard: { has_image: false, has_text: false } }), /image-only/);
  assert.throws(() => imagePasteScenario({ composerText: "draft", clipboard: { has_image: true, has_text: true } }), /image-only/);
});

test("redaction masks raw and URL-encoded credentials recursively in errors and evidence", () => {
  const redact = createRedactor(["qa@example.com", "p w"]);
  const evidence = redact({
    message: "qa@example.com used p w and qa%40example.com",
    nested: [{ password: "p w", token: "abc" }],
  });
  const serialized = JSON.stringify(evidence);
  assert.equal(serialized.includes("qa@example.com"), false);
  assert.equal(serialized.includes("qa%40example.com"), false);
  assert.equal(serialized.includes("p w"), false);
  assert.equal(serialized.includes("abc"), false);
  assert.match(evidence.message, /\[REDACTED\]/);
  assert.equal(redact("failure for qa@example.com").includes("qa@example.com"), false);
});

test("report rendering retains all three states and redacts details", () => {
  const redact = createRedactor(["secret@example.com"]);
  const markdown = renderReport({
    startedAt: "2026-01-01T00:00:00.000Z",
    finishedAt: "2026-01-01T00:00:01.000Z",
    ok: false,
    config: { machine: "vm", bundleId: "build.raft.app", serverName: "QA", channel: "all" },
    counts: { PASS: 1, FAIL: 1, NOT_COVERED: 1 },
    cases: [
      { id: "01", status: "PASS", durationMs: 10, detail: "ok", evidence: ["01/tree.json"] },
      { id: "02", status: "FAIL", durationMs: 20, detail: "secret@example.com failed", evidence: [] },
      { id: "03", status: "NOT_COVERED", durationMs: 0, detail: "no photos", evidence: [] },
    ],
  }, redact);
  assert.match(markdown, /1 PASS, 1 FAIL, 1 NOT_COVERED/);
  assert.match(markdown, /01 \| PASS/);
  assert.match(markdown, /03 \| NOT_COVERED/);
  assert.equal(markdown.includes("secret@example.com"), false);
  assert.match(markdown, /not TestFlight/);
  assert.match(markdown, /does not claim Safari Copy produced an image/);
});

test("runner always writes summary/report and marks unexecuted cases FAIL after startup failure", async (t) => {
  const out = fs.mkdtempSync(path.join(os.tmpdir(), "vphone-smoke-test-"));
  t.after(() => fs.rmSync(out, { recursive: true, force: true }));
  const config = {
    cli: "unused",
    machine: "unit-test",
    bundleId: "build.raft.app",
    serverName: "QA",
    channel: "all",
    out,
    email: "secret@example.com",
    password: "pw",
    stopAfter: false,
  };
  const redact = createRedactor([config.email, config.password]);
  const driver = {
    async prepareMachine() { throw new Error("login failed for secret@example.com"); },
    cancelPending() {},
    async clipboardClear() {},
  };
  const summary = await runSmoke({
    config,
    driver,
    redact,
    selectedIds: ["01-cold-start", "03-photos"],
    log: () => {},
  });
  assert.equal(summary.ok, false);
  assert.deepEqual(summary.cases.map((item) => item.status), ["FAIL", "FAIL"]);
  assert.equal(fs.existsSync(path.join(out, "summary.json")), true);
  assert.equal(fs.existsSync(path.join(out, "report.md")), true);
  const persisted = fs.readFileSync(path.join(out, "summary.json"), "utf8") + fs.readFileSync(path.join(out, "report.md"), "utf8");
  assert.equal(persisted.includes("secret@example.com"), false);
  assert.equal(persisted.includes("pw"), false);
  assert.match(persisted, /startup failed/);
});

test("1.26 gate rejects old or unknown builds before navigation/realtime coverage", () => {
  assert.equal(assertMinimum126({ CFBundleShortVersionString: "1.26.0" }), "1.26.0");
  assert.equal(assertMinimum126({ info: { version_name: "2.0.0" } }), "2.0.0");
  assert.throws(() => assertMinimum126({ version: "1.25.0" }), /require Raft >= 1.26/);
  assert.throws(() => assertMinimum126({ versionCode: 12600002 }), /marketing version/);
});

test("single-open evidence requires a destination and original conversation after one Back", () => {
  const destination = { elements: [
    { label: "Back", role: "button" },
    { label: "Reply in thread", role: "element" },
  ] };
  const original = { elements: [{ label: "Message #all", role: "element" }] };
  const isDestination = (tree) => findElements(tree, { text: "Back", role: "button", match: "exact" }).length > 0;
  const isOriginal = (tree) => !isDestination(tree) && findElements(tree, { text: "Message #all", match: "contains" }).length > 0;
  assert.deepEqual(assertSingleOpenRoundTrip({ destinationTree: destination, returnedTree: original, isDestination, isOriginal }), {
    destinationOpened: true,
    oneBackReturnedToOriginal: true,
    duplicateDestinationOnBack: false,
  });
  assert.throws(
    () => assertSingleOpenRoundTrip({ destinationTree: destination, returnedTree: destination, isDestination, isOriginal }),
    /duplicate destination may remain/,
  );
  assert.throws(
    () => assertSingleOpenRoundTrip({ destinationTree: original, returnedTree: original, isDestination, isOriginal }),
    /destination was not established/,
  );
});

test("avatar selection uses the nearest AX button above the exact message", () => {
  const tree = { elements: [
    { label: "all, General channel", role: "button", frame: { x: 0, y: 20, width: 100, height: 40 } },
    { label: "qa-user", role: "button", frame: { x: 10, y: 300, width: 40, height: 40 } },
    { label: "06:05", role: "button", frame: { x: 60, y: 305, width: 50, height: 30 } },
    { label: "smoke test, please ignore", role: "text", frame: { x: 60, y: 350, width: 200, height: 30 } },
  ] };
  assert.equal(avatarForMessage(tree, "smoke test, please ignore").label, "qa-user");
  assert.equal(avatarForMessage(tree, "missing"), null);
});

test("new realtime switch state is explicit and never guessed On", () => {
  assert.equal(kmpRowEnabled({ label: "New realtime connection (Beta), On" }), true);
  assert.equal(kmpRowEnabled({ label: "New realtime connection (Beta), Off" }), false);
  assert.equal(kmpRowEnabled({ value: "true" }), true);
  assert.equal(kmpRowEnabled({ value: "false" }), false);
  assert.equal(kmpRowEnabled({ label: "Off follows the server setting", traits: 1 }), undefined);
});

test("realtime log analysis requires latest KMP connect, connected callback, and sync resume", () => {
  const kmp = { entries: [
    { message: "connect_start transport=kmp_socketio serverId=s generation=7" },
    { message: "kmp_socket_connected serverId=s reconnect=false" },
    { message: "sync_resume_response serverId=s horizon=true" },
  ] };
  assert.deepEqual(analyzeRealtimeLog(kmp), {
    ok: true,
    reason: "kmp_connected_and_resumed",
    transport: "kmp_socketio",
    generation: 7,
    connected: true,
    resumed: true,
  });
  const legacyAfterKmp = { entries: [
    ...kmp.entries,
    { message: "connect_start transport=swift_socketio serverId=s generation=8" },
    { message: "socket_io_connected serverId=s" },
    { message: "sync_resume_response serverId=s" },
  ] };
  assert.equal(analyzeRealtimeLog(legacyAfterKmp).ok, false);
  assert.equal(analyzeRealtimeLog(legacyAfterKmp).reason, "old_client_transport");
  assert.equal(isFreshKmpEvidence(analyzeRealtimeLog(kmp), 6), true);
  assert.equal(isFreshKmpEvidence(analyzeRealtimeLog(kmp), 7), false);
  assert.equal(isFreshKmpEvidence(analyzeRealtimeLog(kmp), null), true);
  assert.equal(isFreshKmpEvidence(analyzeRealtimeLog(legacyAfterKmp), 0), false);
  assert.equal(analyzeRealtimeLog({ entries: kmp.entries.slice(0, 2) }).reason, "missing_sync_resume");
  assert.equal(analyzeRealtimeLog({ entries: [] }).reason, "missing_connect_start");
});

test("offline fixture uses a TEST-NET address and restores through a separate DHCP step", () => {
  assert.deepEqual(offlineIpv4Config({ router: "10.0.2.2", subnet_mask: "255.255.255.0" }), {
    interface: "en0", method: "manual", address: "192.0.2.1", subnet_mask: "255.255.255.0", router: "10.0.2.2", dns: [],
  });
  assert.throws(() => offlineIpv4Config({}), /preserve the guest router/);
});

test("realtime fixtures are QA-labelled and server switching requires a distinct alternate", () => {
  const text = realtimeFixtureText("server switch", "123-abc");
  assert.equal(text, "smoke realtime server-switch, please ignore [qa-123-abc]");
  const servers = [{ id: "qa", name: "QA" }, { id: "other", name: "Other" }];
  assert.deepEqual(chooseQaServers(servers, "QA"), { qa: servers[0], alternate: servers[1] });
  assert.equal(chooseQaServers([servers[0]], "QA").alternate, null);
  assert.throws(() => chooseQaServers(servers, "Missing"), /not available/);
});
