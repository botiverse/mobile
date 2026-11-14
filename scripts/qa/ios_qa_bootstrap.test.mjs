import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { validateLoginResponse } from "./mobile_login_bootstrap_lib.mjs";
import {
  buildSmokeLogin,
  hasBootstrapLine,
  iosBootGeneration,
  iosCodeImages,
  iosInboxPaths,
  iosLaunchArgs,
  launchctlAppAlive,
  parseKernBootTime,
  requireSimulatorUdid,
} from "./ios_qa_bootstrap_lib.mjs";

const udid = "0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0";
const digest = (text) => `sha256:${crypto.createHash("sha256").update(text).digest("hex")}`;

test("kern.boottime parses the macOS sysctl rendering", () => {
  assert.equal(parseKernBootTime("{ sec = 1731500000, usec = 123456 } Wed Nov 13 12:13:20 2024\n"), 1731500000);
  assert.throws(() => parseKernBootTime(""), /kern.boottime/);
  assert.throws(() => parseKernBootTime("{ sec = 0, usec = 1 }"), /kern.boottime/);
});

test("boot generation matches the app formula (uppercased UDID)", () => {
  // Same vector as compose/iosApp/scripts/test_qa_session_bootstrap_contract.sh.
  assert.equal(
    iosBootGeneration(udid.toLowerCase(), 1731500000),
    digest(`ios-simulator-boot/v1|${udid}|1731500000`),
  );
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
