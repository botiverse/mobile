import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { validateLoginResponse } from "./mobile_login_bootstrap_lib.mjs";
import {
  buildSmokeLogin,
  classifyAppContainerRead,
  classifyLaunchctlRead,
  findLaunchdSimPid,
  hasBootstrapLine,
  iosBootGeneration,
  iosCleanupReceipt,
  iosCodeImages,
  iosInboxPaths,
  iosLaunchArgs,
  launchctlAppAlive,
  parsePsLstartUtc,
  requireRunOwnedSimulator,
  requireSimulatorUdid,
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

test("launchctl read: absence needs a successful read with a running positive control", () => {
  const bundle = "build.raft.app";
  assert.deepEqual(classifyLaunchctlRead({ status: 0, stdout: `${LAUNCHCTL_HEADER}\n${springboard}\n` }, bundle),
    { state: "absent", reason: "app_job_not_listed", positive_control: "com.apple.SpringBoard" });
  assert.equal(classifyLaunchctlRead({ status: 0, stdout: `${LAUNCHCTL_HEADER}\n${springboard}\n777\t0\tUIKitApplication:build.raft.app[d4][rb-legacy]` }, bundle).state, "present");
  // The finding: a failed spawn with empty stdout used to read as "process gone".
  assert.deepEqual(classifyLaunchctlRead({ status: 1, stdout: "" }, bundle), { state: "unknown", reason: "exit_1" });
  assert.deepEqual(classifyLaunchctlRead({ status: 0, stdout: "" }, bundle), { state: "unknown", reason: "no_positive_control" });
  assert.deepEqual(classifyLaunchctlRead({ status: -1, stdout: "", timedOut: true }, bundle), { state: "unknown", reason: "timeout" });
  assert.deepEqual(classifyLaunchctlRead({ status: -1, stdout: "", error: "ENOENT" }, bundle), { state: "unknown", reason: "spawn_error" });
  // A positive control that is not running (no PID) does not count.
  assert.equal(classifyLaunchctlRead({ status: 0, stdout: `${LAUNCHCTL_HEADER}\n-\t0\tcom.apple.SpringBoard` }, bundle).state, "unknown");
  // Even with a failed exit status, a listed running app is not "absent"; it is unknown.
  assert.equal(classifyLaunchctlRead({ status: 1, stdout: "777\t0\tUIKitApplication:build.raft.app[d4]" }, bundle).state, "unknown");
});

test("app container read: absent only when it fails exactly like a never-installed control", () => {
  const bundle = "build.raft.app";
  const controlId = "build.raft.qa.never-installed.abc123";
  const notInstalled = (id) => ({ status: 2, stdout: "", stderr: `An error was encountered processing the command (domain=NSPOSIXErrorDomain, code=2):\nFailed to get container for ${id}\nNo such file or directory\n` });
  const control = notInstalled(controlId);
  assert.deepEqual(classifyAppContainerRead(notInstalled(bundle), bundle, control, controlId), { state: "absent", reason: "not_installed_matches_control" });
  assert.equal(classifyAppContainerRead({ status: 0, stdout: "/Users/r/data/Containers/Data/Application/X\n" }, bundle, control, controlId).state, "present");
  // The finding: any nonzero used to read as "container absent".
  const shutdown = { status: 149, stdout: "", stderr: "Unable to lookup in current state: Shutdown" };
  assert.equal(classifyAppContainerRead(shutdown, bundle, control, controlId).state, "unknown");
  assert.equal(classifyAppContainerRead({ ...notInstalled(bundle), status: 1 }, bundle, control, controlId).state, "unknown");
  assert.equal(classifyAppContainerRead(notInstalled(bundle), bundle, shutdown, controlId).state, "unknown", "control must fail the same way");
  assert.equal(classifyAppContainerRead(notInstalled(bundle), bundle, { status: 0, stdout: "/x" }, controlId).state, "unknown");
  assert.equal(classifyAppContainerRead(notInstalled(bundle), bundle, { status: 2, stdout: "", stderr: "" }, controlId).state, "unknown");
  assert.equal(classifyAppContainerRead({ status: -1, stdout: "", stderr: "", timedOut: true }, bundle, control, controlId).state, "unknown");
  assert.equal(classifyAppContainerRead({ status: 0, stdout: "" }, bundle, control, controlId).state, "unknown");
});

test("cleanup receipt is clean only for confirmed absence; unknown never reads as clean", () => {
  assert.equal(iosCleanupReceipt({ processState: "absent", uninstallStatus: 0, containerState: "absent" }), "clean");
  assert.equal(iosCleanupReceipt({ processState: "unknown", uninstallStatus: 0, containerState: "absent" }), "unknown");
  assert.equal(iosCleanupReceipt({ processState: "absent", uninstallStatus: 0, containerState: "unknown" }), "unknown");
  assert.equal(iosCleanupReceipt({ processState: "absent", uninstallStatus: 1, containerState: "absent" }), "unknown");
  assert.equal(iosCleanupReceipt({ processState: "present", uninstallStatus: 0, containerState: "absent" }), "failed");
  assert.equal(iosCleanupReceipt({ processState: "absent", uninstallStatus: 0, containerState: "present" }), "failed");
});

test("cleanup and smoke refuse a Simulator this run does not own", () => {
  const list = JSON.stringify({ devices: {
    "com.apple.CoreSimulator.SimRuntime.iOS-27-0": [
      { udid, name: "raft-qa-run-123-1", state: "Booted" },
      { udid: "11111111-2222-3333-4444-555555555555", name: "iPhone 14", state: "Booted" },
    ],
  } });
  assert.equal(requireRunOwnedSimulator(list, udid.toLowerCase(), "raft-qa-run-123-1").name, "raft-qa-run-123-1");
  assert.throws(() => requireRunOwnedSimulator(list, udid, "raft-qa-run-124-1"), /not the run-owned/);
  assert.throws(() => requireRunOwnedSimulator(list, "11111111-2222-3333-4444-555555555555", "iPhone 14"), /not a run-owned name/);
  assert.throws(() => requireRunOwnedSimulator(list, "11111111-2222-3333-4444-555555555555", "raft-qa-run-123-1"), /not the run-owned/);
  assert.throws(() => requireRunOwnedSimulator(list, "22222222-2222-3333-4444-555555555555", "raft-qa-run-123-1"), /not listed/);
});
