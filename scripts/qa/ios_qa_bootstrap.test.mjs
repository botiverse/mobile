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
  classifyAppContainerRead,
  classifyDeviceBooted,
  classifyHostProcessRead,
  classifyLaunchctlRead,
  combineProcessStates,
  findLaunchdSimPid,
  hasBootstrapLine,
  iosBootGeneration,
  iosCleanupReceipt,
  iosCodeImages,
  iosInboxPaths,
  iosLaunchArgs,
  launchctlAppAlive,
  parsePsLstartUtc,
  parseSimctlListApps,
  requireRunOwnedSimulator,
  requireSimulatorUdid,
  simulatorOwnershipReceipt,
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
    { state: "absent", reason: "app_process_not_listed", positive_control: "launchd_sim" });
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

const LISTAPPS = `{
    "com.apple.Preferences" =     {
        ApplicationType = System;
        Bundle = "file:///Library/Developer/CoreSimulator/Volumes/iOS/Applications/Preferences.app/";
        CFBundleIdentifier = "com.apple.Preferences";
        GroupContainers =         {
            "group.com.apple.settings" = "file:///x/";
        };
        SBAppTags =         (
        );
    };
    "com.apple.mobilesafari" =     {
        ApplicationType = System;
        CFBundleIdentifier = "com.apple.mobilesafari";
    };
}
`;
const bootedList = (state = "Booted") => ({ status: 0, stdout: JSON.stringify({ devices: { "iOS-27-0": [{ udid, name: "raft-qa-run-222-1", state }] } }) });

test("simctl listapps parsing refuses cut or malformed listings", () => {
  assert.deepEqual([...parseSimctlListApps(LISTAPPS)].sort(), ["com.apple.Preferences", "com.apple.mobilesafari"]);
  assert.equal(parseSimctlListApps(LISTAPPS.slice(0, LISTAPPS.indexOf("mobilesafari\" ="))), null, "cut mid-listing");
  assert.equal(parseSimctlListApps(""), null);
  assert.equal(parseSimctlListApps(`${LISTAPPS}}`), null, "unbalanced");
  assert.equal(parseSimctlListApps(`${LISTAPPS}\n{}`), null, "trailing content");
  assert.equal(parseSimctlListApps('{ "a" = "unterminated; }'), null);
  assert.equal(classifyDeviceBooted(bootedList(), udid).booted, true);
  assert.deepEqual(classifyDeviceBooted(bootedList("Shutdown"), udid), { booted: false, reason: "device_shutdown" });
  assert.deepEqual(classifyDeviceBooted({ status: 1, stdout: "" }, udid), { booted: false, reason: "list_exit_1" });
  assert.deepEqual(classifyDeviceBooted({ status: 0, stdout: "{" }, udid), { booted: false, reason: "list_unparseable" });
});

test("app container read: absent only for a validated not-installed answer plus independent positive controls", () => {
  const bundle = "build.raft.app";
  const controlId = "build.raft.qa.never-installed.abc123";
  const notInstalled = (id) => ({ status: 2, stdout: "", stderr: `An error was encountered processing the command (domain=NSPOSIXErrorDomain, code=2):\nFailed to get container for ${id}\nNo such file or directory\n` });
  const control = notInstalled(controlId);
  const confirmed = { listApps: { status: 0, stdout: LISTAPPS }, device: bootedList(), udid };
  assert.deepEqual(classifyAppContainerRead(notInstalled(bundle), bundle, control, controlId, confirmed),
    { state: "absent", reason: "not_installed_confirmed", positive_control: "com.apple.Preferences" });
  assert.equal(classifyAppContainerRead({ status: 0, stdout: "/Users/r/data/Containers/Data/Application/X\n" }, bundle, control, controlId, confirmed).state, "present");
  // listapps still lists the bundle: present, whatever the container read said.
  const stillListed = { ...confirmed, listApps: { status: 0, stdout: LISTAPPS.replace('"com.apple.mobilesafari";', `"${bundle}";`) } };
  assert.deepEqual(classifyAppContainerRead(notInstalled(bundle), bundle, control, controlId, stillListed), { state: "present", reason: "listed_by_listapps" });

  // THE FINDING: identical target/control infrastructure errors are not "not installed".
  const shutdown = { status: 149, stdout: "", stderr: "Unable to lookup in current state: Shutdown" };
  assert.deepEqual(classifyAppContainerRead(shutdown, bundle, shutdown, controlId, confirmed), { state: "unknown", reason: "infrastructure_error" });
  // Identical GENERIC errors (no infrastructure keyword) are unknown without the positive controls.
  const generic = { status: 1, stdout: "", stderr: "An error was encountered processing the command (domain=com.example, code=7)" };
  assert.equal(classifyAppContainerRead(generic, bundle, generic, controlId).state, "unknown");
  assert.deepEqual(classifyAppContainerRead(generic, bundle, generic, controlId, { ...confirmed, device: bootedList("Shutdown") }), { state: "unknown", reason: "device_shutdown" });
  assert.deepEqual(classifyAppContainerRead(generic, bundle, generic, controlId, { ...confirmed, device: { status: 1, stdout: "" } }), { state: "unknown", reason: "list_exit_1" });
  assert.deepEqual(classifyAppContainerRead(generic, bundle, generic, controlId, { ...confirmed, listApps: { status: 1, stdout: "", stderr: "x" } }), { state: "unknown", reason: "listapps_unavailable" });
  assert.deepEqual(classifyAppContainerRead(generic, bundle, generic, controlId, { ...confirmed, listApps: { status: 0, stdout: LISTAPPS.slice(0, 200) } }), { state: "unknown", reason: "listapps_unavailable" });
  assert.deepEqual(classifyAppContainerRead(generic, bundle, generic, controlId, { ...confirmed, listApps: { status: 0, stdout: "{\n}\n" } }), { state: "unknown", reason: "listapps_no_positive_control" });
  assert.deepEqual(classifyAppContainerRead(generic, bundle, generic, controlId, { ...confirmed, listApps: { status: -1, stdout: LISTAPPS, timedOut: true } }), { state: "unknown", reason: "listapps_unavailable" });
  for (const text of ["Operation timed out", "Connection interrupted", "CoreSimulatorService connection became invalid", "Invalid device: X"]) {
    const same = { status: 2, stdout: "", stderr: text };
    assert.equal(classifyAppContainerRead(same, bundle, same, controlId, confirmed).state, "unknown", text);
  }

  assert.equal(classifyAppContainerRead(shutdown, bundle, control, controlId, confirmed).state, "unknown");
  assert.equal(classifyAppContainerRead({ ...notInstalled(bundle), status: 1 }, bundle, control, controlId, confirmed).state, "unknown");
  assert.equal(classifyAppContainerRead(notInstalled(bundle), bundle, shutdown, controlId, confirmed).state, "unknown", "control must fail the same way");
  assert.equal(classifyAppContainerRead(notInstalled(bundle), bundle, { status: 0, stdout: "/x" }, controlId, confirmed).state, "unknown");
  assert.equal(classifyAppContainerRead(notInstalled(bundle), bundle, { status: 2, stdout: "", stderr: "" }, controlId, confirmed).state, "unknown");
  assert.equal(classifyAppContainerRead({ status: -1, stdout: "", stderr: "", timedOut: true }, bundle, control, controlId, confirmed).state, "unknown");
  assert.equal(classifyAppContainerRead({ status: 0, stdout: "" }, bundle, control, controlId, confirmed).state, "unknown");
});

test("cleanup receipt is clean only for confirmed absence; unknown never reads as clean", () => {
  assert.equal(iosCleanupReceipt({ processState: "absent", uninstallStatus: 0, containerState: "absent" }), "clean");
  assert.equal(iosCleanupReceipt({ processState: "unknown", uninstallStatus: 0, containerState: "absent" }), "unknown");
  assert.equal(iosCleanupReceipt({ processState: "absent", uninstallStatus: 0, containerState: "unknown" }), "unknown");
  assert.equal(iosCleanupReceipt({ processState: "absent", uninstallStatus: 1, containerState: "absent" }), "unknown");
  assert.equal(iosCleanupReceipt({ processState: "present", uninstallStatus: 0, containerState: "absent" }), "failed");
  assert.equal(iosCleanupReceipt({ processState: "absent", uninstallStatus: 0, containerState: "present" }), "failed");
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
  const twin = JSON.stringify({ devices: { a: [{ udid, name: "raft-qa-run-222-1" }], b: [{ udid: FOREIGN, name: "raft-qa-run-222-1" }] } });
  assert.throws(() => requireRunOwnedSimulator(twin, udid, "raft-qa-run-222-1", { ...job, receipt: receiptFor(own) }), /2 simulators are named raft-qa-run-222-1; ownership is ambiguous/);
  const dup = JSON.stringify({ devices: { a: [{ udid, name: "raft-qa-run-222-1" }], b: [{ udid, name: "raft-qa-run-222-1" }] } });
  assert.throws(() => requireRunOwnedSimulator(dup, udid, "raft-qa-run-222-1", { ...job, receipt: receiptFor(own) }), /listed 2 times/);
  assert.throws(() => simulatorOwnershipReceipt({ udid, name: "raft-qa-run-111-1", runId: "222", runAttempt: "1", createdAt: "x" }), /not this job's/);
});

// ---- Full entry point (ios_qa_bootstrap.mjs) with fake xcrun / ps / plutil -------------------------

const harness = fileURLToPath(new URL("./ios_qa_bootstrap.mjs", import.meta.url));
const BUNDLE = "build.raft.app";

/**
 * A scratch PATH with fake xcrun/ps/plutil. `scenario` maps a call key to { status, stdout, stderr }:
 * list, terminate, launchctl, uninstall, container:<data|app>, control, listapps, ps, plutil.
 * Every xcrun call is appended to <root>/calls.
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
fs.appendFileSync(root + "/calls", ${JSON.stringify(path.basename("x"))}.slice(0, 0) + process.argv[1].split("/").pop() + " " + args.join(" ") + "\\n");
const scenario = JSON.parse(fs.readFileSync(root + "/scenario.json", "utf8"));
const key = (${keyExpr})(args);
const r = scenario[key] ?? { status: 97, stderr: "fake: no scenario for " + key };
if (r.stdout) process.stdout.write(r.stdout);
if (r.stderr) process.stderr.write(r.stderr);
process.exitCode = r.status ?? 0;
`;
  fs.writeFileSync(path.join(bin, "xcrun"), fake(`(a) => {
    const c = a[1];
    if (c === "get_app_container") return a[3].startsWith("build.raft.qa.never-installed.") ? "control" : "container:" + a[4];
    if (c === "spawn") return a[3];
    return c;
  }`), { mode: 0o755 });
  fs.writeFileSync(path.join(bin, "ps"), fake(`() => "ps"`), { mode: 0o755 });
  fs.writeFileSync(path.join(bin, "plutil"), fake(`() => "plutil"`), { mode: 0o755 });
  fs.writeFileSync(path.join(bin, "sleep"), "#!/bin/sh\nexit 0\n", { mode: 0o755 });
  const login = buildSmokeLogin({
    applicationId: BUNDLE, signingCertSha256: digest("exe"), bootGeneration: digest("boot"),
    runCaseId: "rc-ios", ciJobRef: "https://github.com/botiverse/mobile/actions/runs/222", requestId: "req-1",
  });
  fs.writeFileSync(path.join(root, "login.json"), JSON.stringify({ request_id: login.request_id, envelope_digest: login.envelope_digest, envelope: login.envelope }));
  fs.writeFileSync(path.join(root, "binding.json"), JSON.stringify({ signing_cert_sha256: digest("exe"), boot_generation: digest("boot") }));
  const runnerTemp = path.join(root, "runner-temp");
  fs.mkdirSync(runnerTemp);
  const env = { ...process.env, PATH: `${bin}:${process.env.PATH}`, RUNNER_TEMP: runnerTemp, GITHUB_RUN_ID: "222", GITHUB_RUN_ATTEMPT: "1" };
  delete env.RAFT_QA_SIMULATOR_OWNERSHIP_RECEIPT;
  const calls = () => (fs.existsSync(path.join(root, "calls")) ? fs.readFileSync(path.join(root, "calls"), "utf8").trim().split("\n") : []);
  const runHarness = (args, extraEnv = {}) => spawnSync(process.execPath, [harness, ...args], { env: { ...env, ...extraEnv }, encoding: "utf8" });
  const cleanupArgs = (deviceUdid, name) => [
    "cleanup", "--udid", deviceUdid, "--simulator-name", name, "--bundle-id", BUNDLE,
    "--response", path.join(root, "login.json"), "--binding", path.join(root, "binding.json"), "--outcome", "aborted",
    "--output", path.join(root, "cleanup.json"), "--run-case-id", "rc-ios", "--ci-job-ref", "https://github.com/botiverse/mobile/actions/runs/222",
  ];
  return { root, runnerTemp, calls, runHarness, cleanupArgs };
}

const listDevices = (devices) => ({ status: 0, stdout: JSON.stringify({ devices: { "com.apple.CoreSimulator.SimRuntime.iOS-27-0": devices } }) });
const ownDevices = [
  { udid, name: "raft-qa-run-222-1", state: "Booted" },
  { udid: FOREIGN, name: "raft-qa-run-111-1", state: "Booted" },
];
const healthyCleanup = {
  list: listDevices(ownDevices),
  terminate: { status: 0 },
  launchctl: { status: 0, stdout: `${LAUNCHCTL_HEADER}\n${springboard}\n` },
  ps: { status: 0, stdout: `  1 /sbin/launchd\n${launchdSimLine}\n` },
  "container:app": { status: 0, stdout: `${DEVICE_DIR}/data/Containers/Bundle/Application/AAAA-BBBB/iosApp.app\n` },
  plutil: { status: 0, stdout: "iosApp\n" },
  uninstall: { status: 0 },
  "container:data": { status: 2, stderr: `An error was encountered processing the command (domain=NSPOSIXErrorDomain, code=2):\nNo such file or directory\n` },
  control: { status: 2, stderr: `An error was encountered processing the command (domain=NSPOSIXErrorDomain, code=2):\nNo such file or directory\n` },
  listapps: { status: 0, stdout: LISTAPPS },
};

test("entry point: record-ownership then cleanup of this job's Simulator ends clean (positive control)", () => {
  const host = fakeHost(healthyCleanup);
  const recorded = host.runHarness(["record-ownership", "--udid", udid, "--simulator-name", "raft-qa-run-222-1"]);
  assert.equal(recorded.status, 0, recorded.stderr);
  const receiptFile = path.join(host.runnerTemp, "raft-qa-simulator-ownership.json");
  assert.equal(fs.statSync(receiptFile).mode & 0o777, 0o400);
  // The receipt is written exactly once.
  assert.notEqual(host.runHarness(["record-ownership", "--udid", udid, "--simulator-name", "raft-qa-run-222-1"]).status, 0);
  const result = host.runHarness(host.cleanupArgs(udid, "raft-qa-run-222-1"));
  assert.equal(result.status, 0, result.stderr);
  const record = JSON.parse(fs.readFileSync(path.join(host.root, "cleanup.json"), "utf8"));
  assert.equal(record.cleanup_receipt, "clean");
  assert.equal(record.app_process.launchctl.state, "absent");
  assert.equal(record.app_process.host_process.state, "absent");
  assert.equal(record.app_data_container.reason, "not_installed_confirmed");
  // Ownership was re-read right before terminate and right before uninstall.
  const calls = host.calls().filter((line) => line.startsWith("xcrun"));
  const terminateAt = calls.findIndex((line) => line.includes(" terminate "));
  const uninstallAt = calls.findIndex((line) => line.includes(" uninstall "));
  assert.match(calls[terminateAt - 1], / list devices -j/);
  assert.match(calls[uninstallAt - 1], / list devices -j/);
});

test("entry point: identical infrastructure errors on both container reads never yield a clean receipt", () => {
  const shutdown = { status: 149, stderr: "An error was encountered processing the command (domain=com.apple.CoreSimulator.SimError, code=405):\nUnable to lookup in current state: Shutdown\n" };
  for (const [name, overrides] of [
    ["shutdown text, everything else healthy", { "container:data": shutdown, control: shutdown }],
    ["generic identical error, listapps failing", {
      "container:data": { status: 1, stderr: "simctl: something went wrong\n" }, control: { status: 1, stderr: "simctl: something went wrong\n" },
      listapps: { status: 1, stderr: "simctl: something went wrong\n" },
    }],
    ["generic identical error, listapps cut", {
      "container:data": { status: 1, stderr: "E\n" }, control: { status: 1, stderr: "E\n" }, listapps: { status: 0, stdout: LISTAPPS.slice(0, 120) },
    }],
    ["container reads time out (fake exit by signal)", { "container:data": { status: 137 }, control: { status: 137 } }],
  ]) {
    const host = fakeHost({ ...healthyCleanup, ...overrides });
    assert.equal(host.runHarness(["record-ownership", "--udid", udid, "--simulator-name", "raft-qa-run-222-1"]).status, 0);
    const result = host.runHarness(host.cleanupArgs(udid, "raft-qa-run-222-1"));
    assert.notEqual(result.status, 0, name);
    const record = JSON.parse(fs.readFileSync(path.join(host.root, "cleanup.json"), "utf8"));
    assert.equal(record.cleanup_receipt, "unknown", name);
    assert.equal(record.app_data_container.state, "unknown", name);
    assert.doesNotMatch(result.stdout, /cleanup_receipt=clean|device cleanup confirmed/, name);
  }
});

test("entry point: launchctl truncation or a failed host process query keeps the receipt unknown", () => {
  for (const [name, overrides, expected] of [
    ["truncated launchctl", { launchctl: { status: 0, stdout: `${LAUNCHCTL_HEADER}\n${springboard}` } }, /launchctl|incomplete_listing/],
    ["ps fails", { ps: { status: 1, stderr: "ps: sysctl failed\n" } }, /exit_1/],
    ["ps without this launchd_sim", { ps: { status: 0, stdout: "  1 /sbin/launchd\n" } }, /no_positive_control/],
    ["host still runs the app", { ps: { status: 0, stdout: `${launchdSimLine}\n${appLine}\n` } }, /app_process_running/],
  ]) {
    const host = fakeHost({ ...healthyCleanup, ...overrides });
    assert.equal(host.runHarness(["record-ownership", "--udid", udid, "--simulator-name", "raft-qa-run-222-1"]).status, 0);
    const result = host.runHarness(host.cleanupArgs(udid, "raft-qa-run-222-1"));
    assert.notEqual(result.status, 0, name);
    const record = JSON.parse(fs.readFileSync(path.join(host.root, "cleanup.json"), "utf8"));
    assert.notEqual(record.cleanup_receipt, "clean", name);
    assert.match(result.stderr, expected, name);
  }
});

test("entry point: run 222 handed run 111's name and matching foreign UDID is refused before terminate", () => {
  const host = fakeHost(healthyCleanup);
  // No receipt at all, foreign name + UDID.
  let result = host.runHarness(host.cleanupArgs(FOREIGN, "raft-qa-run-111-1"));
  assert.equal(result.status, 2);
  assert.match(result.stderr, /not this job's run-owned raft-qa-run-222-1/);
  // This job's receipt exists (for its own device): the foreign device is still refused.
  assert.equal(host.runHarness(["record-ownership", "--udid", udid, "--simulator-name", "raft-qa-run-222-1"]).status, 0);
  result = host.runHarness(host.cleanupArgs(FOREIGN, "raft-qa-run-111-1"));
  assert.equal(result.status, 2);
  // Foreign UDID under this job's name: receipt UDID mismatch.
  result = host.runHarness(host.cleanupArgs(FOREIGN, "raft-qa-run-222-1"));
  assert.equal(result.status, 2);
  assert.match(result.stderr, /receipt records raft-qa-run-222-1/);
  // Smoke is refused the same way.
  result = host.runHarness(["smoke", "--udid", FOREIGN, "--simulator-name", "raft-qa-run-111-1", "--bundle-id", BUNDLE, "--out", path.join(host.root, "smoke")]);
  assert.equal(result.status, 2);
  const destructive = host.calls().filter((line) => / (terminate|uninstall|shutdown|launch) /.test(line));
  assert.deepEqual(destructive, [], "nothing destructive may run on a Simulator this job does not own");
  // Without a receipt this job cannot even clean its own device.
  const fresh = fakeHost(healthyCleanup);
  result = fresh.runHarness(fresh.cleanupArgs(udid, "raft-qa-run-222-1"));
  assert.equal(result.status, 2);
  assert.match(result.stderr, /receipt is missing/);
  assert.deepEqual(fresh.calls().filter((line) => / (terminate|uninstall) /.test(line)), []);
  // record-ownership refuses another run's name, and a device listed under an ambiguous name.
  assert.notEqual(fresh.runHarness(["record-ownership", "--udid", FOREIGN, "--simulator-name", "raft-qa-run-111-1"]).status, 0);
  const ambiguous = fakeHost({ ...healthyCleanup, list: listDevices([...ownDevices, { udid: "22222222-2222-3333-4444-555555555555", name: "raft-qa-run-222-1", state: "Booted" }]) });
  assert.notEqual(ambiguous.runHarness(["record-ownership", "--udid", udid, "--simulator-name", "raft-qa-run-222-1"]).status, 0);
});

test("entry point: ownership verified at the start but lost before uninstall stops before uninstall", () => {
  // The device is renamed between the terminate check and the uninstall check (list returns the
  // run-owned name for the first reads, then a foreign name).
  const host = fakeHost(healthyCleanup);
  assert.equal(host.runHarness(["record-ownership", "--udid", udid, "--simulator-name", "raft-qa-run-222-1"]).status, 0);
  const scenarioFile = path.join(host.root, "scenario.json");
  const xcrun = path.join(host.root, "bin", "xcrun");
  // Swap the scenario after `terminate` is seen: wrap the fake xcrun.
  fs.renameSync(xcrun, `${xcrun}.real`);
  fs.writeFileSync(xcrun, `#!/bin/sh
"${xcrun}.real" "$@"; rc=$?
if [ "$2" = "terminate" ]; then
  ${JSON.stringify(process.execPath)} -e 'const fs=require("fs"),f=process.argv[1],s=JSON.parse(fs.readFileSync(f,"utf8"));s.list={status:0,stdout:JSON.stringify({devices:{r:[{udid:process.argv[2],name:"shared-iPhone",state:"Booted"}]}})};fs.writeFileSync(f,JSON.stringify(s))' "${scenarioFile}" "${udid}"
fi
exit $rc
`, { mode: 0o755 });
  const result = host.runHarness(host.cleanupArgs(udid, "raft-qa-run-222-1"));
  assert.equal(result.status, 2);
  assert.match(result.stderr, /named shared-iPhone/);
  assert.equal(host.calls().some((line) => / uninstall /.test(line)), false);
});
