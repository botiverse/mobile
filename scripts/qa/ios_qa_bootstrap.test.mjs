import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { validateLoginResponse } from "./mobile_login_bootstrap_lib.mjs";
import {
  buildSmokeLogin,
  classifyDeviceListedAfterDelete,
  classifyHostProcessRead,
  classifyLaunchctlRead,
  combineProcessStates,
  findLaunchdSimPid,
  hasBootstrapLine,
  iosBootGeneration,
  iosCodeImages,
  iosInboxPaths,
  iosLaunchArgs,
  launchctlAppAlive,
  parsePsLstartUtc,
  requireRunOwnedSimulator,
  requireSimulatorUdid,
  parseSimctlDeviceList,
  simulatorCleanupFacts,
  simulatorDeletionReceipt,
  simulatorOwnershipReceipt,
  verifySimulatorDeletionReceipt,
} from "./ios_qa_bootstrap_lib.mjs";

const udid = "0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0";
const digest = (text) => `sha256:${crypto.createHash("sha256").update(text).digest("hex")}`;

test("launchd_sim of exactly this Simulator is found in the host process list", () => {
  const ps = [
    "  1 /sbin/launchd",
    `4242 /Library/Developer/PrivateFrameworks/CoreSimulator.framework/Resources/bin/launchd_sim /Users/r/Library/Developer/CoreSimulator/Devices/${udid}/data/var/run/launchd_bootstrap.plist`,
    "4300 /Library/Developer/PrivateFrameworks/CoreSimulator.framework/Resources/bin/launchd_sim /Users/r/Library/Developer/CoreSimulator/Devices/11111111-2222-3333-4444-555555555555/data/var/run/launchd_bootstrap.plist",
    `4400 /usr/bin/grep launchd_sim /Devices/${udid}/`,
  ].join("\n");
  assert.equal(findLaunchdSimPid(ps, udid.toLowerCase()), 4242);
  assert.throws(() => findLaunchdSimPid("  1 /sbin/launchd", udid), /found 0/);
  const twice = `${ps}\n4500 /x/launchd_sim /Users/r/Library/Developer/CoreSimulator/Devices/${udid}/data/y`;
  assert.throws(() => findLaunchdSimPid(twice, udid), /found 2/);
});

test("ps lstart (C locale, UTC) parses to epoch seconds", () => {
  assert.equal(parsePsLstartUtc("Sat Nov 14 10:00:00 2026\n"), Date.UTC(2026, 10, 14, 10, 0, 0) / 1000);
  assert.equal(parsePsLstartUtc("Wed Jan  7 09:05:03 2026"), Date.UTC(2026, 0, 7, 9, 5, 3) / 1000);
  assert.throws(() => parsePsLstartUtc(""), /launchd_sim start/);
  assert.throws(() => parsePsLstartUtc("Sa 14 Nov 2026 10:00:00"), /launchd_sim start/);
});

test("boot generation matches the app formula and changes with the launchd_sim start", () => {
  // Same vector as compose/iosApp/scripts/test_qa_session_bootstrap_contract.sh.
  assert.equal(
    iosBootGeneration(udid.toLowerCase(), 1731500000),
    digest(`ios-simulator-boot/v2|${udid}|1731500000`),
  );
  assert.notEqual(iosBootGeneration(udid, 1731500001), iosBootGeneration(udid, 1731500000));
  // Bounded collision, by construction: the input has one-second resolution, so two boots of the same
  // UDID whose launchd_sim starts fall in the same UTC second (e.g. 10:00:00.100 and 10:00:00.900)
  // get the same generation. Documented limit; a different second or a different UDID never collides.
  const sameSecond = [Date.UTC(2026, 10, 14, 10, 0, 0, 100), Date.UTC(2026, 10, 14, 10, 0, 0, 900)].map((ms) => Math.floor(ms / 1000));
  assert.equal(iosBootGeneration(udid, sameSecond[0]), iosBootGeneration(udid, sameSecond[1]));
  assert.notEqual(iosBootGeneration(udid, sameSecond[0]), iosBootGeneration("11111111-2222-3333-4444-555555555555", sameSecond[0]));
  assert.throws(() => iosBootGeneration(udid, 0), /launchd_sim/);
  assert.throws(() => iosBootGeneration("booted", 1), /UDID/);
  assert.throws(() => requireSimulatorUdid("../x"), /UDID/);
});

test("inbox paths stay inside the app data container", () => {
  const paths = iosInboxPaths("/Users/r/Library/Developer/CoreSimulator/Devices/X/data/Containers/Data/Application/Y", "req-1");
  assert.equal(paths.dir.endsWith("/Library/Application Support/qa-bootstrap/inbox"), true);
  assert.equal(paths.file, `${paths.dir}/req-1.session`);
  assert.equal(paths.tmp, `${paths.dir}/.req-1.session.tmp`);
  assert.throws(() => iosInboxPaths("/data", "../escape"), /request id/);
  assert.throws(() => iosInboxPaths("relative", "req"), /container/);
});

test("code identity covers the Xcode Debug dylib when present", () => {
  assert.deepEqual(iosCodeImages("/a/iosApp", () => false), ["/a/iosApp"]);
  assert.deepEqual(iosCodeImages("/a/iosApp", (p) => p === "/a/iosApp.debug.dylib"), ["/a/iosApp", "/a/iosApp.debug.dylib"]);
});

test("launch arguments carry only the request id and digest", () => {
  const sha = digest("x");
  assert.deepEqual(iosLaunchArgs(udid, "build.raft.app", "req", sha), [
    "simctl", "launch", "--terminate-running-process", udid, "build.raft.app",
    "-qaBootstrapRequestId", "req", "-qaBootstrapEnvelopeDigest", sha,
  ]);
  assert.throws(() => iosLaunchArgs(udid, "build.raft.app", "req", "sha256:XYZ"), /digest/);
  assert.throws(() => iosLaunchArgs(udid, "build.raft.app; rm", "req", sha), /bundle id/);
});

test("smoke envelope satisfies the shared harness contract (validateLoginResponse)", () => {
  const binding = { applicationId: "build.raft.app", signingCertSha256: digest("exe"), bootGeneration: digest("boot") };
  const login = buildSmokeLogin({ ...binding, runCaseId: "ios-bootstrap-smoke", ciJobRef: "local", requestId: "smoke-1" });
  const validated = validateLoginResponse(
    { request_id: login.request_id, envelope_digest: login.envelope_digest, envelope: login.envelope },
    { runCaseId: "ios-bootstrap-smoke", ciJobRef: "local", ...binding },
  );
  assert.equal(validated.envelopeDigest, login.envelope_digest);
  assert.deepEqual(validated.bytes, login.bytes);
  const edited = buildSmokeLogin({ ...binding, runCaseId: "r", ciJobRef: "c", requestId: "s", edit: (e) => { e.boot_generation = digest("other"); } });
  assert.notEqual(edited.envelope_digest, login.envelope_digest);
  assert.equal(edited.envelope.boot_generation, digest("other"));
});

test("bootstrap lines are matched by kind, stage and code", () => {
  const log = [
    "2026-11-14 10:00:00.000 Df iosApp[123:456] [SlockQaBootstrap][info] QA_BOOTSTRAP_STAGE stage=inbox_consumed",
    "2026-11-14 10:00:00.100 Df iosApp[123:456] [SlockQaBootstrap][info] QA_BOOTSTRAP_FAILURE stage=login_consume code=unauthenticated",
  ].join("\n");
  assert.equal(hasBootstrapLine(log, "STAGE", "inbox_consumed"), true);
  assert.equal(hasBootstrapLine(log, "STAGE", "inbox"), false);
  assert.equal(hasBootstrapLine(log, "FAILURE", "login_consume", "unauthenticated"), true);
  assert.equal(hasBootstrapLine(log, "FAILURE", "login_consume", "(?!runtime_gate)[a-z0-9_]+"), true);
  assert.equal(
    hasBootstrapLine("QA_BOOTSTRAP_FAILURE stage=login_consume code=runtime_gate_release_binary", "FAILURE", "login_consume", "(?!runtime_gate)[a-z0-9_]+"),
    false,
  );
});

test("launchctl liveness only counts a running UIKitApplication job of the bundle", () => {
  const jobs = [
    "PID\tStatus\tLabel",
    "-\t0\tUIKitApplication:build.raft.app[a1b2][rb-legacy]",
    "512\t0\tUIKitApplication:build.raft.app.other[c3][rb-legacy]",
  ].join("\n");
  assert.equal(launchctlAppAlive(jobs, "build.raft.app"), false);
  assert.equal(launchctlAppAlive(`${jobs}\n777\t0\tUIKitApplication:build.raft.app[d4][rb-legacy]`, "build.raft.app"), true);
});

test("Release-exclusion source check passes on this tree", () => {
  const script = fileURLToPath(new URL("../ci/check_ios_qa_bootstrap_exclusion.py", import.meta.url));
  const result = spawnSync("python3", [script, "source"], { encoding: "utf8" });
  assert.equal(result.status, 0, result.stderr);
});

const LAUNCHCTL_HEADER = "PID\tStatus\tLabel";
const springboard = "88\t0\tcom.apple.SpringBoard";

test("launchctl read: absence needs a successful, whole read with a running positive control", () => {
  const bundle = "build.raft.app";
  assert.deepEqual(classifyLaunchctlRead({ status: 0, stdout: `${LAUNCHCTL_HEADER}\n${springboard}\n` }, bundle),
    { state: "absent", reason: "app_job_not_listed", positive_control: "com.apple.SpringBoard" });
  assert.equal(classifyLaunchctlRead({ status: 0, stdout: `${LAUNCHCTL_HEADER}\n${springboard}\n777\t0\tUIKitApplication:build.raft.app[d4][rb-legacy]` }, bundle).state, "present");
  // The finding: a failed spawn with empty stdout used to read as "process gone".
  assert.deepEqual(classifyLaunchctlRead({ status: 1, stdout: "" }, bundle), { state: "unknown", reason: "exit_1" });
  assert.deepEqual(classifyLaunchctlRead({ status: 0, stdout: "" }, bundle), { state: "unknown", reason: "incomplete_listing" });
  assert.deepEqual(classifyLaunchctlRead({ status: -1, stdout: "", timedOut: true }, bundle), { state: "unknown", reason: "timeout" });
  assert.deepEqual(classifyLaunchctlRead({ status: -1, stdout: "", error: "ENOENT" }, bundle), { state: "unknown", reason: "spawn_error" });
  // A positive control that is not running (no PID) does not count.
  assert.equal(classifyLaunchctlRead({ status: 0, stdout: `${LAUNCHCTL_HEADER}\n-\t0\tcom.apple.SpringBoard\n` }, bundle).state, "unknown");
  // Even with a failed exit status, a listed running app is not "absent"; it is unknown.
  assert.equal(classifyLaunchctlRead({ status: 1, stdout: "777\t0\tUIKitApplication:build.raft.app[d4]" }, bundle).state, "unknown");
});

test("launchctl read: a truncated listing with the positive control is unknown, not absent", () => {
  const bundle = "build.raft.app";
  // Cut mid-line (no trailing newline): the app's row may have been the part that was lost.
  assert.deepEqual(classifyLaunchctlRead({ status: 0, stdout: `${LAUNCHCTL_HEADER}\n${springboard}\n91\t0\tcom.apple.back` }, bundle),
    { state: "unknown", reason: "incomplete_listing" });
  assert.deepEqual(classifyLaunchctlRead({ status: 0, stdout: `${LAUNCHCTL_HEADER}\n${springboard}` }, bundle),
    { state: "unknown", reason: "incomplete_listing" });
  // Garbage / interleaved error text in a "successful" read is not a listing.
  assert.deepEqual(classifyLaunchctlRead({ status: 0, stdout: `${LAUNCHCTL_HEADER}\n${springboard}\nerror: lost connection\n` }, bundle),
    { state: "unknown", reason: "incomplete_listing" });
  // A cut exactly at a line boundary cannot be seen in the text: launchctl alone says absent, and the
  // independent host process query decides (see the combination test below).
  assert.equal(classifyLaunchctlRead({ status: 0, stdout: `${LAUNCHCTL_HEADER}\n${springboard}\n` }, bundle).state, "absent");
});

const DEVICE_DIR = `/Users/r/Library/Developer/CoreSimulator/Devices/${udid}`;
const launchdSimLine = `4242 /Library/Developer/PrivateFrameworks/CoreSimulator.framework/Resources/bin/launchd_sim ${DEVICE_DIR}/data/var/run/launchd_bootstrap.plist`;
const appLine = `5150 ${DEVICE_DIR}/data/Containers/Bundle/Application/AAAA-BBBB/iosApp.app/iosApp -qaBootstrapRequestId r`;

test("host process query: independent of launchctl, fails closed on process-query failures", () => {
  const ok = (stdout) => ({ status: 0, stdout });
  assert.deepEqual(classifyHostProcessRead(ok(`  1 /sbin/launchd\n${launchdSimLine}\n`), udid, "iosApp"),
    { state: "absent", reason: "app_process_not_listed", positive_control: "launchd_sim", evidence_ceiling: "listing_completeness_unprovable" });
  assert.equal(classifyHostProcessRead(ok(`${launchdSimLine}\n${appLine}\n`), udid, "iosApp").state, "present");
  // Process-query failures are unknown.
  assert.deepEqual(classifyHostProcessRead({ status: 1, stdout: "" }, udid, "iosApp"), { state: "unknown", reason: "exit_1" });
  assert.deepEqual(classifyHostProcessRead({ status: -1, stdout: "", timedOut: true }, udid, "iosApp"), { state: "unknown", reason: "timeout" });
  assert.deepEqual(classifyHostProcessRead({ status: -1, stdout: "", error: "ENOENT" }, udid, "iosApp"), { state: "unknown", reason: "spawn_error" });
  // Truncated (no trailing newline) or without this Simulator's launchd_sim (positive control): unknown.
  assert.deepEqual(classifyHostProcessRead(ok(`${launchdSimLine}\n  77 /usr/bin/so`), udid, "iosApp"), { state: "unknown", reason: "incomplete_listing" });
  assert.deepEqual(classifyHostProcessRead(ok("  1 /sbin/launchd\n"), udid, "iosApp"), { state: "unknown", reason: "no_positive_control" });
  // Without the executable name nothing can be looked for.
  assert.deepEqual(classifyHostProcessRead(ok(`${launchdSimLine}\n`), udid, undefined), { state: "unknown", reason: "executable_unknown" });
  // Another Simulator's copy of the app is not this one's.
  const other = appLine.replace(udid, "11111111-2222-3333-4444-555555555555");
  assert.equal(classifyHostProcessRead(ok(`${launchdSimLine}\n${other}\n`), udid, "iosApp").state, "absent");
});

test("process state is absent only when launchctl AND the host query both show absence", () => {
  const absent = { state: "absent" };
  const unknown = { state: "unknown" };
  const present = { state: "present" };
  assert.equal(combineProcessStates(absent, absent), "absent");
  // launchctl read cut at a line boundary (absent) while the host still sees the app: present.
  assert.equal(combineProcessStates(absent, present), "present");
  assert.equal(combineProcessStates(absent, unknown), "unknown");
  assert.equal(combineProcessStates(unknown, absent), "unknown");
  assert.equal(combineProcessStates(present, unknown), "present");
});

const devicesList = (devices) => ({ status: 0, stdout: JSON.stringify({ devices: { "com.apple.CoreSimulator.SimRuntime.iOS-27-0": devices } }) });

test("deletion is confirmed only by a successful, well-formed device list without the UDID", () => {
  const gone = devicesList([{ udid: "11111111-2222-3333-4444-555555555555", name: "other", state: "Booted" }]);
  assert.deepEqual(classifyDeviceListedAfterDelete(gone, udid.toLowerCase()), { state: "absent", reason: "udid_not_listed" });
  assert.deepEqual(classifyDeviceListedAfterDelete(devicesList([]), udid), { state: "absent", reason: "udid_not_listed" });
  assert.deepEqual(classifyDeviceListedAfterDelete(devicesList([{ udid, name: "raft-qa-run-222-1", state: "Shutdown" }]), udid), { state: "present", reason: "udid_still_listed" });
  assert.deepEqual(classifyDeviceListedAfterDelete({ status: 1, stdout: "" }, udid), { state: "unknown", reason: "list_exit_1" });
  assert.deepEqual(classifyDeviceListedAfterDelete({ status: -1, stdout: "", timedOut: true }, udid), { state: "unknown", reason: "list_timeout" });
  assert.deepEqual(classifyDeviceListedAfterDelete({ status: 0, stdout: '{"devices":{"r":[{"udid":' }, udid), { state: "unknown", reason: "list_unparseable" });
  for (const shape of ["[]", "{}", '{"devices":[]}', '{"devices":{"r":{}}}', '{"devices":{"r":[{"name":"x"}]}}', '{"devices":{"r":["x"]}}']) {
    assert.deepEqual(classifyDeviceListedAfterDelete({ status: 0, stdout: shape }, udid), { state: "unknown", reason: "list_unexpected_shape" }, shape);
  }
});

test("cleanup facts: only deletion confirmed by the list counts; app process and server session are never claimed", () => {
  const gone = devicesList([]);
  const ok = simulatorCleanupFacts({ deleteResult: { status: 0 }, listAfter: gone, udid });
  assert.deepEqual(ok, {
    cleanup_receipt: "simulator_deleted", simulator: "deleted_and_absent", simulator_reason: "udid_not_listed",
    listed_after_delete: "absent", app_process: "unknown", server_session: "not_proven_by_device_cleanup",
  });
  const still = devicesList([{ udid, name: "raft-qa-run-222-1", state: "Shutdown" }]);
  for (const [name, facts] of [
    ["delete failed", simulatorCleanupFacts({ deleteResult: { status: 1 }, listAfter: gone, udid })],
    ["delete timed out", simulatorCleanupFacts({ deleteResult: { status: -1, timedOut: true }, listAfter: gone, udid })],
    ["still listed", simulatorCleanupFacts({ deleteResult: { status: 0 }, listAfter: still, udid })],
    ["list failed", simulatorCleanupFacts({ deleteResult: { status: 0 }, listAfter: { status: 1, stdout: "" }, udid })],
  ]) {
    assert.equal(facts.cleanup_receipt, "unknown", name);
    assert.equal(facts.simulator, "unknown", name);
    assert.equal(facts.app_process, "unknown", name);
    assert.equal(facts.server_session, "not_proven_by_device_cleanup", name);
  }
});

const FOREIGN = "11111111-2222-3333-4444-555555555555";
const receiptFor = (record, stat = {}) => ({
  text: JSON.stringify(record),
  stat: { isFile: true, isSymbolicLink: false, mode: 0o100400, uid: 501, ...stat },
});

test("ownership: only a protected receipt binding the UDID to THIS run id + attempt allows destruction", () => {
  const list = JSON.stringify({ devices: {
    "com.apple.CoreSimulator.SimRuntime.iOS-27-0": [
      { udid, name: "raft-qa-run-222-1", state: "Booted" },
      { udid: FOREIGN, name: "raft-qa-run-111-1", state: "Booted" },
      { udid: "22222222-2222-3333-4444-555555555555", name: "iPhone 14", state: "Booted" },
    ],
  } });
  const job = { runId: "222", runAttempt: "1", uid: 501 };
  const own = simulatorOwnershipReceipt({ udid, name: "raft-qa-run-222-1", runId: "222", runAttempt: "1", createdAt: "2026-11-14T00:00:00Z" });
  assert.equal(requireRunOwnedSimulator(list, udid.toLowerCase(), "raft-qa-run-222-1", { ...job, receipt: receiptFor(own) }).name, "raft-qa-run-222-1");

  // THE FINDING: run 222 handed another run's (111) name and its matching UDID.
  assert.throws(() => requireRunOwnedSimulator(list, FOREIGN, "raft-qa-run-111-1", { ...job, receipt: receiptFor(own) }), /not this job's run-owned raft-qa-run-222-1/);
  // …even with a forged receipt for that device, the run id/attempt must be this job's.
  const forged = simulatorOwnershipReceipt({ udid: FOREIGN, name: "raft-qa-run-111-1", runId: "111", runAttempt: "1", createdAt: "x" });
  assert.throws(() => requireRunOwnedSimulator(list, FOREIGN, "raft-qa-run-222-1", { ...job, receipt: receiptFor(forged) }), /belongs to run 111\/1/);
  // A previous attempt of the same run is not this attempt.
  assert.throws(() => requireRunOwnedSimulator(list, udid, "raft-qa-run-222-1", { ...job, runAttempt: "2", receipt: receiptFor(own) }), /not this job's run-owned raft-qa-run-222-2/);
  // Missing / unprotected / symlinked / foreign-owned / reshaped receipts.
  assert.throws(() => requireRunOwnedSimulator(list, udid, "raft-qa-run-222-1", { ...job, receipt: null }), /receipt is missing/);
  assert.throws(() => requireRunOwnedSimulator(list, udid, "raft-qa-run-222-1", { ...job, receipt: receiptFor(own, { mode: 0o100644 }) }), /not protected/);
  assert.throws(() => requireRunOwnedSimulator(list, udid, "raft-qa-run-222-1", { ...job, receipt: receiptFor(own, { isSymbolicLink: true, isFile: false }) }), /not a regular file/);
  assert.throws(() => requireRunOwnedSimulator(list, udid, "raft-qa-run-222-1", { ...job, receipt: receiptFor(own, { uid: 0 }) }), /another user/);
  assert.throws(() => requireRunOwnedSimulator(list, udid, "raft-qa-run-222-1", { ...job, receipt: receiptFor({ ...own, extra: 1 }) }), /unexpected shape/);
  // The receipt is for another device of this job's name (UDID mismatch).
  const otherUdid = { ...own, udid: "22222222-2222-3333-4444-555555555555" };
  assert.throws(() => requireRunOwnedSimulator(list, udid, "raft-qa-run-222-1", { ...job, receipt: receiptFor(otherUdid) }), /records raft-qa-run-222-1 22222222/);
  // No run id at all (outside CI) is refused.
  assert.throws(() => requireRunOwnedSimulator(list, udid, "raft-qa-run-222-1", { receipt: receiptFor(own) }), /GITHUB_RUN_ID/);
  // Shared / renamed / missing / ambiguous devices.
  assert.throws(() => requireRunOwnedSimulator(list, "22222222-2222-3333-4444-555555555555", "iPhone 14", { ...job, receipt: receiptFor(own) }), /not a run-owned name/);
  const renamed = list.replace('"raft-qa-run-222-1"', '"iPhone 14"');
  assert.throws(() => requireRunOwnedSimulator(renamed, udid, "raft-qa-run-222-1", { ...job, receipt: receiptFor(own) }), /named iPhone 14/);
  const gone = JSON.stringify({ devices: {} });
  assert.throws(() => requireRunOwnedSimulator(gone, udid, "raft-qa-run-222-1", { ...job, receipt: receiptFor(own) }), /not listed/);
  const twin = JSON.stringify({ devices: { a: [{ udid, name: "raft-qa-run-222-1", state: "Booted" }], b: [{ udid: FOREIGN, name: "raft-qa-run-222-1", state: "Booted" }] } });
  assert.throws(() => requireRunOwnedSimulator(twin, udid, "raft-qa-run-222-1", { ...job, receipt: receiptFor(own) }), /2 simulators are named raft-qa-run-222-1; ownership is ambiguous/);
  const dup = JSON.stringify({ devices: { a: [{ udid, name: "raft-qa-run-222-1", state: "Booted" }], b: [{ udid, name: "raft-qa-run-222-1", state: "Booted" }] } });
  assert.throws(() => requireRunOwnedSimulator(dup, udid, "raft-qa-run-222-1", { ...job, receipt: receiptFor(own) }), /listed 2 times/);
  assert.throws(() => simulatorOwnershipReceipt({ udid, name: "raft-qa-run-111-1", runId: "222", runAttempt: "1", createdAt: "x" }), /not this job's/);
});

// ---- Full entry point (ios_qa_bootstrap.mjs) with fake xcrun / ps / plutil -------------------------

const harness = fileURLToPath(new URL("./ios_qa_bootstrap.mjs", import.meta.url));
const BUNDLE = "build.raft.app";
const JOB_REF = "https://github.com/botiverse/mobile/actions/runs/222";

/**
 * A scratch PATH with fake xcrun/ps/plutil. `scenario` maps a call key to { status, stdout, stderr }:
 * list, terminate, shutdown, delete, launch, log, launchctl, container:<data|app>, ps, plutil.
 * A successful `delete` replaces `list` with `scenario.afterDelete` when given. `swapListAfter`
 * ({ key, list }) replaces `list` right after the first call with that key (ownership changes mid-run).
 * Every call is appended to <root>/calls.
 */
function fakeHost(scenario) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "ios-qa-bootstrap-"));
  const bin = path.join(root, "bin");
  fs.mkdirSync(bin);
  fs.writeFileSync(path.join(root, "scenario.json"), JSON.stringify(scenario));
  const fake = (keyExpr) => `#!${process.execPath}
const fs = require("fs");
const args = process.argv.slice(2);
const root = ${JSON.stringify(root)};
fs.appendFileSync(root + "/calls", process.argv[1].split("/").pop() + " " + args.join(" ") + "\\n");
const file = root + "/scenario.json";
const scenario = JSON.parse(fs.readFileSync(file, "utf8"));
const key = (${keyExpr})(args);
const r = scenario[key] ?? { status: 97, stderr: "fake: no scenario for " + key };
if (key === "delete" && (r.status ?? 0) === 0 && scenario.afterDelete) scenario.list = scenario.afterDelete;
if (scenario.swapListAfter && scenario.swapListAfter.key === key) { scenario.list = scenario.swapListAfter.list; delete scenario.swapListAfter; }
fs.writeFileSync(file, JSON.stringify(scenario));
if (r.stdout) process.stdout.write(r.stdout);
if (r.stderr) process.stderr.write(r.stderr);
process.exitCode = r.status ?? 0;
`;
  fs.writeFileSync(path.join(bin, "xcrun"), fake(`(a) => {
    const c = a[1];
    if (c === "get_app_container") return "container:" + a[4];
    if (c === "spawn") return a[3];
    return c;
  }`), { mode: 0o755 });
  fs.writeFileSync(path.join(bin, "ps"), fake(`() => "ps"`), { mode: 0o755 });
  fs.writeFileSync(path.join(bin, "plutil"), fake(`() => "plutil"`), { mode: 0o755 });
  fs.writeFileSync(path.join(bin, "sleep"), "#!/bin/sh\nexit 0\n", { mode: 0o755 });
  const login = buildSmokeLogin({
    applicationId: BUNDLE, signingCertSha256: digest("exe"), bootGeneration: digest("boot"),
    runCaseId: "rc-ios", ciJobRef: JOB_REF, requestId: "req-1",
  });
  fs.writeFileSync(path.join(root, "login.json"), JSON.stringify({ request_id: login.request_id, envelope_digest: login.envelope_digest, envelope: login.envelope }));
  fs.writeFileSync(path.join(root, "binding.json"), JSON.stringify({ signing_cert_sha256: digest("exe"), boot_generation: digest("boot") }));
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(runnerTemp);
  const env = { ...process.env, PATH: `${bin}:${process.env.PATH}`, RUNNER_TEMP: runnerTemp, GITHUB_RUN_ID: "222", GITHUB_RUN_ATTEMPT: "1" };
  delete env.RAFT_QA_SIMULATOR_OWNERSHIP_RECEIPT;
  const calls = () => (fs.existsSync(path.join(root, "calls")) ? fs.readFileSync(path.join(root, "calls"), "utf8").trim().split("\n") : []);
  const runHarness = (args, extraEnv = {}) => spawnSync(process.execPath, [harness, ...args], { env: { ...env, ...extraEnv }, encoding: "utf8" });
  const record = () => host.runHarness(["record-ownership", "--udid", udid, "--simulator-name", "raft-qa-run-222-1"]);
  const cleanupArgs = (deviceUdid, name) => [
    "cleanup", "--udid", deviceUdid, "--simulator-name", name, "--bundle-id", BUNDLE,
    "--response", path.join(root, "login.json"), "--binding", path.join(root, "binding.json"), "--outcome", "aborted",
    "--output", path.join(root, "cleanup.json"), "--run-case-id", "rc-ios", "--ci-job-ref", JOB_REF,
  ];
  const prepareArgs = (deviceUdid, name) => [
    "prepare", "--udid", deviceUdid, ...(name ? ["--simulator-name", name] : []), "--bundle-id", BUNDLE,
    "--response", path.join(root, "login.json"), "--binding", path.join(root, "binding.json"),
    "--run-case-id", "rc-ios", "--ci-job-ref", JOB_REF, "--ready-output", path.join(root, "ready.json"), "--timeout-seconds", "1",
  ];
  const cleanupRecord = () => JSON.parse(fs.readFileSync(path.join(root, "cleanup.json"), "utf8"));
  const host = { root, runnerTemp, calls, runHarness, record, cleanupArgs, prepareArgs, cleanupRecord };
  return host;
}

const listDevices = devicesList;
const ownDevices = [
  { udid, name: "raft-qa-run-222-1", state: "Booted" },
  { udid: FOREIGN, name: "raft-qa-run-111-1", state: "Booted" },
];
const foreignOnly = listDevices([ownDevices[1]]);
const healthyCleanup = {
  list: listDevices(ownDevices),
  afterDelete: foreignOnly,
  terminate: { status: 0 },
  shutdown: { status: 0 },
  delete: { status: 0 },
  launchctl: { status: 0, stdout: `${LAUNCHCTL_HEADER}\n${springboard}\n` },
  ps: { status: 0, stdout: `  1 /sbin/launchd\n${launchdSimLine}\n` },
  "container:app": { status: 0, stdout: `${DEVICE_DIR}/data/Containers/Bundle/Application/AAAA-BBBB/iosApp.app\n` },
  plutil: { status: 0, stdout: "iosApp\n" },
};
const xcrunCalls = (host) => host.calls().filter((line) => line.startsWith("xcrun"));

test("entry point: cleanup deletes this job's Simulator and reports only what deletion proves (positive control)", () => {
  const host = fakeHost(healthyCleanup);
  assert.equal(host.record().status, 0);
  assert.equal(fs.statSync(path.join(host.runnerTemp, "raft-qa-simulator-ownership.json")).mode & 0o777, 0o400);
  assert.notEqual(host.record().status, 0, "the receipt is written exactly once");
  const result = host.runHarness(host.cleanupArgs(udid, "raft-qa-run-222-1"));
  assert.equal(result.status, 0, result.stderr);
  const rec = host.cleanupRecord();
  assert.equal(rec.cleanup_receipt, "simulator_deleted");
  assert.equal(rec.simulator, "deleted_and_absent");
  assert.equal(rec.app_process, "unknown", "deletion does not prove a process exited");
  assert.equal(rec.server_session, "not_proven_by_device_cleanup");
  assert.equal(rec.simulator_deletion.listed_after_delete, "absent");
  assert.equal(JSON.stringify(rec).includes('"clean"'), false);
  // ownership → shutdown → ownership → delete → fresh list.
  const calls = xcrunCalls(host);
  const shutdownAt = calls.findIndex((line) => line.includes(" shutdown "));
  const deleteAt = calls.findIndex((line) => line.includes(" delete "));
  assert.match(calls[shutdownAt - 1], / list devices -j/);
  assert.match(calls[deleteAt - 1], / list devices -j/);
  assert.match(calls[deleteAt + 1], / list devices -j/);
  assert.equal(calls.filter((line) => line.includes(" delete ")).length, 1);
  assert.equal(calls.some((line) => / (uninstall|get_app_container \S+ \S+ data|listapps) /.test(line)), false, "no inference from app/container reads");
});

test("review-independent: dual line-boundary truncations can hide a live app and return clean", () => {
  // Argus (77005df3): launchctl and ps each cut at a newline right after their positive control, hiding
  // a running app, gave cleanup_receipt=clean. Now process listings never feed the verdict.
  const completeLaunchctl = `${LAUNCHCTL_HEADER}\n${springboard}\n777\t0\tUIKitApplication:build.raft.app[d4][rb-legacy]\n`;
  const completePs = `  1 /sbin/launchd\n${launchdSimLine}\n${appLine}\n`;
  const truncatedLaunchctl = `${LAUNCHCTL_HEADER}\n${springboard}\n`;
  const truncatedPs = `  1 /sbin/launchd\n${launchdSimLine}\n`;
  for (const [name, launchctl, ps, observed] of [
    ["app visible in both listings", completeLaunchctl, completePs, "present"],
    ["both silently truncated at newline", truncatedLaunchctl, truncatedPs, "absent"],
  ]) {
    for (const [deleteCase, overrides, receipt, status] of [
      ["delete confirmed", {}, "simulator_deleted", 0],
      ["delete failed", { delete: { status: 1, stderr: "Unable to delete\n" } }, "unknown", 2],
    ]) {
      const host = fakeHost({ ...healthyCleanup, launchctl: { status: 0, stdout: launchctl }, ps: { status: 0, stdout: ps }, ...overrides });
      assert.equal(host.record().status, 0);
      const r = host.runHarness(host.cleanupArgs(udid, "raft-qa-run-222-1"));
      const rec = host.cleanupRecord();
      assert.equal(r.status, status, `${name}/${deleteCase}`);
      assert.equal(rec.cleanup_receipt, receipt, `${name}/${deleteCase}`);
      assert.notEqual(rec.cleanup_receipt, "clean");
      assert.equal(rec.app_process, "unknown", `${name}/${deleteCase}: never inferred from listings`);
      assert.equal(rec.app_process_observations.combined, observed, `${name}/${deleteCase}: kept as a diagnostic`);
    }
  }
});

test("entry point: delete failure, a UDID still listed, or an unreadable list after delete is unknown and non-zero", () => {
  const stillListed = listDevices([{ ...ownDevices[0], state: "Shutdown" }, ownDevices[1]]);
  for (const [name, overrides, reason] of [
    ["delete exits 1", { delete: { status: 1, stderr: "Invalid device state\n" } }, "delete_exit_1"],
    ["delete exits 0 but the UDID is still listed", { afterDelete: stillListed }, "udid_still_listed"],
    ["list after delete fails", { afterDelete: { status: 1, stderr: "CoreSimulatorService unavailable\n" } }, "list_exit_1"],
    ["list after delete is malformed", { afterDelete: { status: 0, stdout: '{"devices":{"r":[{"name":"x"}]}}' } }, "list_unexpected_shape"],
  ]) {
    const host = fakeHost({ ...healthyCleanup, ...overrides });
    assert.equal(host.record().status, 0);
    const r = host.runHarness(host.cleanupArgs(udid, "raft-qa-run-222-1"));
    assert.equal(r.status, 2, name);
    const rec = host.cleanupRecord();
    assert.equal(rec.cleanup_receipt, "unknown", name);
    assert.equal(rec.simulator, "unknown", name);
    assert.equal(rec.simulator_reason, reason, name);
    assert.match(r.stderr, /not proven deleted/, name);
  }
});

test("entry point: run 222 handed run 111's name and matching foreign UDID is refused before anything destructive", () => {
  const host = fakeHost(healthyCleanup);
  let result = host.runHarness(host.cleanupArgs(FOREIGN, "raft-qa-run-111-1"));
  assert.equal(result.status, 2);
  assert.match(result.stderr, /not this job's run-owned raft-qa-run-222-1/);
  assert.equal(host.record().status, 0);
  result = host.runHarness(host.cleanupArgs(FOREIGN, "raft-qa-run-111-1"));
  assert.equal(result.status, 2);
  result = host.runHarness(host.cleanupArgs(FOREIGN, "raft-qa-run-222-1"));
  assert.equal(result.status, 2);
  assert.match(result.stderr, /receipt records raft-qa-run-222-1/);
  result = host.runHarness(["delete-simulator", "--udid", FOREIGN, "--simulator-name", "raft-qa-run-111-1"]);
  assert.equal(result.status, 2);
  result = host.runHarness(["smoke", "--udid", FOREIGN, "--simulator-name", "raft-qa-run-111-1", "--bundle-id", BUNDLE, "--out", path.join(host.root, "smoke")]);
  assert.equal(result.status, 2);
  const destructive = host.calls().filter((line) => / (terminate|uninstall|shutdown|delete|launch) /.test(line));
  assert.deepEqual(destructive, [], "nothing destructive may run on a Simulator this job does not own");
  const fresh = fakeHost(healthyCleanup);
  result = fresh.runHarness(fresh.cleanupArgs(udid, "raft-qa-run-222-1"));
  assert.equal(result.status, 2);
  assert.match(result.stderr, /receipt is missing/);
  assert.deepEqual(fresh.calls().filter((line) => / (terminate|shutdown|delete) /.test(line)), []);
  assert.notEqual(fresh.runHarness(["record-ownership", "--udid", FOREIGN, "--simulator-name", "raft-qa-run-111-1"]).status, 0);
  const ambiguous = fakeHost({ ...healthyCleanup, list: listDevices([...ownDevices, { udid: "22222222-2222-3333-4444-555555555555", name: "raft-qa-run-222-1", state: "Booted" }]) });
  assert.notEqual(ambiguous.record().status, 0);
});

test("entry point: ownership lost between shutdown and delete stops before delete", () => {
  const host = fakeHost({ ...healthyCleanup, swapListAfter: { key: "shutdown", list: listDevices([{ udid, name: "shared-iPhone", state: "Shutdown" }]) } });
  assert.equal(host.record().status, 0);
  const result = host.runHarness(host.cleanupArgs(udid, "raft-qa-run-222-1"));
  assert.equal(result.status, 2);
  assert.match(result.stderr, /named shared-iPhone/);
  assert.equal(host.calls().some((line) => / delete /.test(line)), false);
});

test("entry point: delete-simulator is the same verified deletion, and a no-op when this job's device is already gone", () => {
  const host = fakeHost(healthyCleanup);
  assert.equal(host.record().status, 0);
  const out = path.join(host.root, "simulator-cleanup.json");
  let result = host.runHarness(["delete-simulator", "--udid", udid, "--simulator-name", "raft-qa-run-222-1", "--bundle-id", BUNDLE, "--output", out]);
  assert.equal(result.status, 0, result.stderr);
  const rec = JSON.parse(fs.readFileSync(out, "utf8"));
  assert.equal(rec.cleanup_receipt, "simulator_deleted");
  assert.equal(rec.app_process, "unknown");
  // Second run (e.g. after `cleanup` already deleted it): no second delete.
  result = host.runHarness(["delete-simulator", "--udid", udid, "--simulator-name", "raft-qa-run-222-1", "--output", out]);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /already deleted/);
  assert.equal(host.calls().filter((line) => / delete /.test(line)).length, 1);
  // Still verifies this job's receipt even then.
  result = host.runHarness(["delete-simulator", "--udid", udid, "--simulator-name", "raft-qa-run-222-1"], { GITHUB_RUN_ATTEMPT: "2" });
  assert.equal(result.status, 2);
  // Delete failure is non-zero.
  const failing = fakeHost({ ...healthyCleanup, delete: { status: 1 } });
  assert.equal(failing.record().status, 0);
  assert.equal(failing.runHarness(["delete-simulator", "--udid", udid, "--simulator-name", "raft-qa-run-222-1"]).status, 2);
});

const prepareScenario = (host) => {
  const dataDir = path.join(host.root, "data-container");
  fs.mkdirSync(dataDir);
  const file = path.join(host.root, "scenario.json");
  const s = JSON.parse(fs.readFileSync(file, "utf8"));
  s["container:data"] = { status: 0, stdout: `${dataDir}\n` };
  s.log = { status: 0, stdout: "Filtering the log data…\n" };
  s.launch = { status: 0, stdout: `${BUNDLE}: 1234\n` };
  fs.writeFileSync(file, JSON.stringify(s));
  return path.join(dataDir, "Library", "Application Support", "qa-bootstrap", "inbox");
};

test("review-independent: prepare targets a foreign simulator without ownership receipt", () => {
  // Argus (77005df3): prepare wrote into run 111's data container and launched with
  // --terminate-running-process without ever consulting ownership. Now refused before either.
  for (const [name, name2, withReceipt] of [
    ["foreign device, no receipt", "raft-qa-run-111-1", false],
    ["foreign device, this job's receipt", "raft-qa-run-111-1", true],
    ["foreign device under this job's name", "raft-qa-run-222-1", true],
  ]) {
    const host = fakeHost({ ...healthyCleanup });
    const inbox = prepareScenario(host);
    if (withReceipt) assert.equal(host.record().status, 0);
    const r = host.runHarness(host.prepareArgs(FOREIGN, name2));
    assert.equal(r.status, 2, name);
    assert.doesNotMatch(r.stderr, /READY was not observed/, name);
    assert.equal(fs.existsSync(inbox), false, `${name}: nothing written`);
    assert.equal(host.calls().some((line) => / (launch|terminate|get_app_container) /.test(line)), false, `${name}: no container access, no launch`);
    assert.ok(host.calls().some((line) => line.includes("simctl list devices -j")), `${name}: ownership consulted`);
  }
  // Without --simulator-name at all, prepare is refused too.
  const bare = fakeHost({ ...healthyCleanup });
  prepareScenario(bare);
  assert.equal(bare.runHarness(bare.prepareArgs(udid)).status, 2);
  assert.equal(bare.calls().some((line) => / launch /.test(line)), false);
});

test("entry point: prepare re-verifies ownership right before launch (positive and negative)", () => {
  // Positive control: this job's device passes both checks, the envelope is written, the app is launched.
  const host = fakeHost({ ...healthyCleanup });
  const inbox = prepareScenario(host);
  assert.equal(host.record().status, 0);
  const r = host.runHarness(host.prepareArgs(udid, "raft-qa-run-222-1"));
  assert.equal(r.status, 2);
  assert.match(r.stderr, /READY was not observed/, "got past both ownership checks to the (fake) READY wait");
  assert.equal(fs.existsSync(inbox), true);
  const calls = xcrunCalls(host);
  const writeAt = calls.findIndex((line) => line.includes(" get_app_container ") && line.endsWith(" data"));
  const launchAt = calls.findIndex((line) => line.includes(" launch "));
  assert.match(calls[writeAt - 1], / list devices -j/, "ownership right before the container write");
  assert.match(calls[launchAt - 1], / list devices -j/, "ownership right before launch");
  // Negative: ownership changes after the inbox write (device renamed when the log stream starts).
  const lost = fakeHost({ ...healthyCleanup, swapListAfter: { key: "log", list: listDevices([{ udid, name: "shared-iPhone", state: "Booted" }]) } });
  prepareScenario(lost);
  const s = JSON.parse(fs.readFileSync(path.join(lost.root, "scenario.json"), "utf8"));
  s.swapListAfter = { key: "log", list: listDevices([{ udid, name: "shared-iPhone", state: "Booted" }]) };
  fs.writeFileSync(path.join(lost.root, "scenario.json"), JSON.stringify(s));
  assert.equal(lost.record().status, 0);
  const r2 = lost.runHarness(lost.prepareArgs(udid, "raft-qa-run-222-1"));
  assert.equal(r2.status, 2);
  assert.match(r2.stderr, /named shared-iPhone/);
  assert.equal(lost.calls().some((line) => / launch /.test(line)), false, "no launch after ownership was lost");
});

// ---- Round 4 (Argus, 41151acd): an unlisted device is not a deleted device ------------------------

test("device list parsing is strict: any schema violation throws", () => {
  assert.equal(parseSimctlDeviceList(JSON.stringify({ devices: { r: [{ udid, name: "n", state: "Booted" }], s: [] } })).length, 1);
  assert.equal(parseSimctlDeviceList(JSON.stringify({ devices: {} })).length, 0);
  for (const text of ["", "{", "{}", '{"devices":[]}', '{"devices":null}', '{"devices":{"runtime":[{"name":"x"}]}}',
    '{"devices":{"r":{}}}', '{"devices":{"r":["x"]}}', `{"devices":{"r":[{"udid":"${udid}","name":"n"}]}}`, `{"devices":{"r":[{"udid":"${udid}","state":"Booted"}]}}`]) {
    assert.throws(() => parseSimctlDeviceList(text), /device list/, text);
  }
});

test("deletion receipt binds this job, this device, and a proven delete", () => {
  const job = { udid, name: "raft-qa-run-222-1", runId: "222", runAttempt: "1", uid: 501 };
  const receipt = simulatorDeletionReceipt({ ...job, deletedAt: "2026-11-15T00:00:00Z" });
  const file = (record, stat = {}) => ({ text: JSON.stringify(record), stat: { isFile: true, isSymbolicLink: false, mode: 0o100400, uid: 501, ...stat } });
  assert.equal(verifySimulatorDeletionReceipt(file(receipt), job), true);
  assert.equal(verifySimulatorDeletionReceipt(null, job), "deletion_receipt_missing");
  assert.equal(verifySimulatorDeletionReceipt(file(receipt, { mode: 0o100644 }), job), "deletion_receipt_unprotected");
  assert.equal(verifySimulatorDeletionReceipt(file(receipt, { isSymbolicLink: true, isFile: false }), job), "deletion_receipt_not_regular");
  assert.equal(verifySimulatorDeletionReceipt(file(receipt, { uid: 0 }), job), "deletion_receipt_foreign_owner");
  assert.equal(verifySimulatorDeletionReceipt(file(receipt), { ...job, runAttempt: "2" }), "deletion_receipt_other_run");
  assert.equal(verifySimulatorDeletionReceipt(file(receipt), { ...job, udid: FOREIGN }), "deletion_receipt_other_device");
  assert.equal(verifySimulatorDeletionReceipt(file({ ...receipt, simulator: "unknown" }), job), "deletion_receipt_shape");
  assert.equal(verifySimulatorDeletionReceipt(file({ ...receipt, extra: 1 }), job), "deletion_receipt_shape");
});

const setList = (host, list) => {
  const file = path.join(host.root, "scenario.json");
  const s = JSON.parse(fs.readFileSync(file, "utf8"));
  s.list = list;
  fs.writeFileSync(file, JSON.stringify(s));
};
const deleteArgs = (out) => ["delete-simulator", "--udid", udid, "--simulator-name", "raft-qa-run-222-1", "--output", out];

test("review-extra: malformed list on first deletion read must not become successful already deleted", () => {
  for (const list of [{ status: 0, stdout: "{}" }, { status: 0, stdout: '{"devices":[]}' }, { status: 0, stdout: '{"devices":{"runtime":[{"name":"x"}]}}' }]) {
    const host = fakeHost(healthyCleanup);
    assert.equal(host.record().status, 0);
    setList(host, list);
    const out = path.join(host.root, "last-cleanup.json");
    const r = host.runHarness(deleteArgs(out));
    assert.equal(r.status, 2, list.stdout);
    assert.doesNotMatch(r.stdout, /already deleted/, list.stdout);
    const rec = JSON.parse(fs.readFileSync(out, "utf8"));
    assert.equal(rec.cleanup_receipt, "unknown", list.stdout);
    assert.equal(rec.simulator, "unknown", list.stdout);
    assert.equal(rec.simulator_reason, "list_unexpected_shape", list.stdout);
    assert.equal(host.calls().filter((x) => / delete /.test(x)).length, 0, list.stdout);
  }
});

test("review-extra: a genuinely unlisted simulator without deletion receipt must not infer deletion", () => {
  const host = fakeHost(healthyCleanup);
  assert.equal(host.record().status, 0);
  setList(host, foreignOnly);
  const out = path.join(host.root, "last-cleanup.json");
  const r = host.runHarness(deleteArgs(out));
  assert.equal(r.status, 2);
  assert.doesNotMatch(r.stdout, /already deleted/);
  const rec = JSON.parse(fs.readFileSync(out, "utf8"));
  assert.equal(rec.cleanup_receipt, "unknown");
  assert.equal(rec.simulator_reason, "deletion_receipt_missing");
  assert.equal(host.calls().filter((x) => / delete /.test(x)).length, 0, "and no other device is touched");
});

test("entry point: already deleted only with this job's own deletion receipt from a proven delete (positive + stale)", () => {
  const host = fakeHost(healthyCleanup);
  assert.equal(host.record().status, 0);
  const out = path.join(host.root, "cleanup-1.json");
  // A proven delete (cleanup) writes the protected receipt.
  assert.equal(host.runHarness(host.cleanupArgs(udid, "raft-qa-run-222-1")).status, 0);
  const deletion = path.join(host.runnerTemp, "raft-qa-simulator-ownership.deletion.json");
  assert.equal(fs.statSync(deletion).mode & 0o777, 0o400);
  // Positive: the final step sees a well-formed list without the UDID + the receipt: no second delete.
  let r = host.runHarness(deleteArgs(out));
  assert.equal(r.status, 0, r.stderr);
  assert.match(r.stdout, /already deleted/);
  const rec = JSON.parse(fs.readFileSync(out, "utf8"));
  assert.equal(rec.cleanup_receipt, "simulator_deleted");
  assert.equal(rec.simulator_reason, "deleted_earlier_in_this_job");
  assert.equal(rec.app_process, "unknown");
  assert.equal(host.calls().filter((x) => / delete /.test(x)).length, 1);
  // …but a malformed list still wins over the receipt.
  setList(host, { status: 0, stdout: "{}" });
  assert.equal(host.runHarness(deleteArgs(out)).status, 2);
  // A failed delete never writes the receipt.
  const failing = fakeHost({ ...healthyCleanup, delete: { status: 1 } });
  assert.equal(failing.record().status, 0);
  assert.equal(failing.runHarness(failing.cleanupArgs(udid, "raft-qa-run-222-1")).status, 2);
  assert.equal(fs.existsSync(path.join(failing.runnerTemp, "raft-qa-simulator-ownership.deletion.json")), false);
  // A tampered (writable) receipt is not accepted.
  const tampered = fakeHost(healthyCleanup);
  assert.equal(tampered.record().status, 0);
  assert.equal(tampered.runHarness(tampered.cleanupArgs(udid, "raft-qa-run-222-1")).status, 0);
  fs.chmodSync(path.join(tampered.runnerTemp, "raft-qa-simulator-ownership.deletion.json"), 0o644);
  r = tampered.runHarness(deleteArgs(path.join(tampered.root, "x.json")));
  assert.equal(r.status, 2);
  assert.equal(JSON.parse(fs.readFileSync(path.join(tampered.root, "x.json"), "utf8")).simulator_reason, "deletion_receipt_unprotected");
});
