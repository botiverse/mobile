#!/usr/bin/env node
// iOS Simulator QA session bootstrap harness: the twin of mobile_login_bootstrap.mjs (Android).
// Same qa-bootstrap-login/v1 envelope, same READY line, same output files; only the device
// channel differs (xcrun simctl instead of adb). Contract: docs/qa-acceptance/session-bootstrap.md.
//
//   binding --udid <udid> --bundle-id <id> --output <binding.json>
//   prepare --udid <udid> --bundle-id <id> --response <login.json> --binding <binding.json>
//           --run-case-id <id> --ci-job-ref <url> --ready-output <ready.json> [--timeout-seconds 120]
//   cleanup --udid <udid> --simulator-name <raft-qa-run-…> --bundle-id <id> --response <login.json>
//           --binding <binding.json> [--ready <ready.json>] --outcome completed|aborted
//           --output <cleanup.json> --run-case-id <id> --ci-job-ref <url>
//           (exit 0 only for cleanup_receipt=clean; the output is written for clean|failed|unknown)
//   smoke   --udid <udid> --simulator-name <raft-qa-run-…> --bundle-id <id> --out <dir>
//           (no server: fake-token envelopes prove delivery, one-shot consumption, digest/binding
//           validation and the replay refusal; then a Simulator reboot must change boot_generation,
//           and the device cleanup must end with a clean receipt)
// cleanup and smoke uninstall the app, so they refuse any Simulator that is not this run's own.
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";
import {
  parseBootstrapDiagnostics,
  parseReadyLine,
  readyPayload,
  sha256Digest,
  validateLoginResponse,
} from "./mobile_login_bootstrap_lib.mjs";
import {
  IOS_LOG_PREDICATE,
  IOS_QA_BOOTSTRAP_MARKER,
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
  parsePsLstartUtc,
  requireBundleId,
  requireRunOwnedSimulator,
  requireSimulatorUdid,
} from "./ios_qa_bootstrap_lib.mjs";

const commandTimeoutMs = 60_000;

function fail(message) {
  console.error(`ios-qa-bootstrap: ${message}`);
  process.exit(2);
}

function argument(name, required = true) {
  const index = process.argv.indexOf(`--${name}`);
  const value = index >= 0 ? process.argv[index + 1] : undefined;
  if (required && (!value || value.startsWith("--"))) fail(`--${name} is required`);
  return value;
}

function run(command, args, { allowFailure = false, timeoutMs = commandTimeoutMs, env } = {}) {
  const result = spawnSync(command, args, {
    encoding: "utf8", maxBuffer: 16 * 1024 * 1024, timeout: timeoutMs, killSignal: "SIGKILL", ...(env ? { env } : {}),
  });
  if (!allowFailure && (result.error || result.status !== 0)) {
    const detail = String(result.stderr ?? "").trim().slice(0, 2_048);
    throw new Error(`${command} ${args.slice(0, 3).join(" ")} failed (status=${result.status ?? "none"})${detail ? `: ${detail}` : ""}`);
  }
  return {
    status: result.status ?? -1,
    stdout: String(result.stdout ?? ""),
    stderr: String(result.stderr ?? ""),
    timedOut: result.error?.code === "ETIMEDOUT" || (result.status === null && result.signal === "SIGKILL"),
    error: result.error && result.error.code !== "ETIMEDOUT" ? String(result.error.code ?? result.error.message) : undefined,
  };
}

const simctl = (args, options) => run("xcrun", ["simctl", ...args], options);

function writePrivateJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  fs.chmodSync(file, 0o600);
}

function appContainer(udid, bundleId, kind) {
  const value = simctl(["get_app_container", udid, bundleId, kind]).stdout.trim();
  if (!path.isAbsolute(value) || !fs.existsSync(value)) throw new Error(`${kind} container of ${bundleId} is unavailable (is it installed on ${udid}?)`);
  return value;
}

function plistValue(plist, key) {
  return run("plutil", ["-extract", key, "raw", "-o", "-", plist]).stdout.trim();
}

/** Refuses any Simulator but the one this run created (name raft-qa-run-<run id>-<attempt>). */
function requireOwned(udid) {
  const name = argument("simulator-name");
  requireRunOwnedSimulator(simctl(["list", "devices", "-j"]).stdout, udid, name);
  return name;
}

/**
 * Start time (epoch seconds) of this Simulator's launchd_sim: new on every Simulator boot. The app
 * reads the same p_starttime of its launchd_sim ancestor through sysctl(KERN_PROC_PID).
 */
function launchdSimStartSeconds(udid) {
  const pid = findLaunchdSimPid(run("ps", ["-axww", "-o", "pid=,args="]).stdout, udid);
  const env = { ...process.env, LC_ALL: "C", TZ: "UTC" };
  return parsePsLstartUtc(run("ps", ["-o", "lstart=", "-p", String(pid)], { env }).stdout);
}

/** The device binding the app computes for itself, read from the INSTALLED bundle and the Simulator. */
function deviceBinding(udid, bundleId) {
  const app = appContainer(udid, bundleId, "app");
  const plist = path.join(app, "Info.plist");
  const applicationId = plistValue(plist, "CFBundleIdentifier");
  if (applicationId !== bundleId) throw new Error(`installed bundle id ${applicationId} != ${bundleId}`);
  const executablePath = path.join(app, plistValue(plist, "CFBundleExecutable"));
  const images = iosCodeImages(executablePath, fs.existsSync);
  const bytes = images.map((image) => fs.readFileSync(image));
  // Pre-flight (Android checks application-debuggable): only the Debug configuration compiles the
  // bootstrap host in; a Release/TestFlight build would ignore the envelope and time out at READY.
  const marker = Buffer.from(IOS_QA_BOOTSTRAP_MARKER, "utf8");
  if (!bytes.some((image) => image.includes(marker))) {
    throw new Error(`${bundleId} on ${udid} is not a Debug QA build (no ${IOS_QA_BOOTSTRAP_MARKER} in ${images.map((i) => path.basename(i)).join(", ")}); build the Debug configuration`);
  }
  const launchdSimStart = launchdSimStartSeconds(udid);
  return {
    applicationId,
    signingCertSha256: sha256Digest(Buffer.concat(bytes)),
    bootGeneration: iosBootGeneration(udid, launchdSimStart),
    launchdSimStart,
  };
}

function binding() {
  const udid = requireSimulatorUdid(argument("udid"));
  const bundleId = requireBundleId(argument("bundle-id"));
  const device = deviceBinding(udid, bundleId);
  // Same two keys as the Android binding file, so stamp_report.sh bootstrap-login posts it unchanged.
  writePrivateJson(argument("output"), { signing_cert_sha256: device.signingCertSha256, boot_generation: device.bootGeneration });
  console.log(`ios-qa-bootstrap: device binding ready bundle=${bundleId} udid=${udid}`);
}

/** Writes the envelope into the app's private inbox: 0700 dir, 0600 temp file, atomic rename, readback. */
function installPrivateEnvelope(udid, bundleId, requestId, bytes, { mode = 0o600 } = {}) {
  const paths = iosInboxPaths(appContainer(udid, bundleId, "data"), requestId);
  fs.mkdirSync(paths.dir, { recursive: true, mode: 0o700 });
  fs.chmodSync(paths.dir, 0o700);
  fs.rmSync(paths.tmp, { force: true });
  try {
    fs.writeFileSync(paths.tmp, bytes, { mode: 0o600, flag: "wx" });
    fs.chmodSync(paths.tmp, mode);
    fs.renameSync(paths.tmp, paths.file);
  } finally {
    fs.rmSync(paths.tmp, { force: true });
  }
  const stat = fs.lstatSync(paths.file);
  const readback = sha256Digest(fs.readFileSync(paths.file));
  if (!stat.isFile() || (stat.mode & 0o7777) !== mode || stat.size !== bytes.length || readback !== sha256Digest(bytes)) {
    throw new Error(`private envelope readback mismatch (mode=${(stat.mode & 0o7777).toString(8)} size=${stat.size}/${bytes.length})`);
  }
  console.log(`ios-qa-bootstrap: envelope installed path=${paths.file} bytes=${bytes.length}`);
  return paths;
}

/** Background `log stream` of the app's QA_BOOTSTRAP_* lines; text() returns everything seen so far. */
async function startLogStream(udid) {
  const child = spawn("xcrun", ["simctl", "spawn", udid, "log", "stream", "--style", "compact", "--predicate", IOS_LOG_PREDICATE], {
    stdio: ["ignore", "pipe", "pipe"],
  });
  let buffer = "";
  child.stdout.setEncoding("utf8");
  child.stdout.on("data", (chunk) => { buffer += chunk; });
  child.stderr.on("data", () => {});
  // `log stream` prints its "Filtering the log data…" header once attached.
  const deadline = Date.now() + 10_000;
  while (!buffer && Date.now() < deadline && child.exitCode === null) await sleep(200);
  await sleep(500);
  return { text: () => buffer, stop: () => { child.kill("SIGTERM"); } };
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function launch(udid, bundleId, requestId, envelopeDigest) {
  run("xcrun", iosLaunchArgs(udid, bundleId, requestId, envelopeDigest));
}

function expectedLogin(responseFile, bindingFile, bundleId, runCaseId, ciJobRef) {
  const response = JSON.parse(fs.readFileSync(responseFile, "utf8"));
  const device = JSON.parse(fs.readFileSync(bindingFile, "utf8"));
  return validateLoginResponse(response, {
    runCaseId, ciJobRef, applicationId: bundleId,
    signingCertSha256: device.signing_cert_sha256,
    bootGeneration: device.boot_generation,
  });
}

async function prepare() {
  const udid = requireSimulatorUdid(argument("udid"));
  const bundleId = requireBundleId(argument("bundle-id"));
  const readyOutput = argument("ready-output");
  const timeoutSeconds = Number(argument("timeout-seconds", false) ?? "120");
  if (!Number.isInteger(timeoutSeconds) || timeoutSeconds < 1 || timeoutSeconds > 180) throw new Error("READY timeout is invalid");
  const login = expectedLogin(argument("response"), argument("binding"), bundleId, argument("run-case-id"), argument("ci-job-ref"));
  const envelope = login.envelope;
  let paths;
  try {
    paths = installPrivateEnvelope(udid, bundleId, login.requestId, login.bytes);
  } finally {
    login.bytes.fill(0);
  }
  const stream = await startLogStream(udid);
  try {
    console.log("ios-qa-bootstrap: launching app with the bootstrap request; polling READY");
    launch(udid, bundleId, login.requestId, login.envelopeDigest);
    const expected = {
      envelopeDigest: login.envelopeDigest,
      runCaseId: envelope.run_case_id,
      attempt: envelope.attempt,
      ciJobRef: envelope.ci_job_ref,
      principalId: envelope.principal_id,
      serverId: envelope.server_id,
      targetRoute: envelope.target_route,
      applicationId: envelope.application_id,
      bootGeneration: envelope.boot_generation,
    };
    const deadline = Date.now() + timeoutSeconds * 1000;
    while (Date.now() < deadline) {
      const ready = parseReadyLine(stream.text(), expected);
      if (ready) {
        writePrivateJson(readyOutput, readyPayload(login, ready));
        console.log(`ios-qa-bootstrap: READY accepted digest=${login.envelopeDigest}`);
        return;
      }
      await sleep(1000);
    }
    const log = stream.text();
    const diagnostics = parseBootstrapDiagnostics(log);
    const reader = [...log.matchAll(/QA_BOOTSTRAP_DIAG [^\r\n]*/g)].map((m) => m[0]);
    throw new Error(
      `exact-bound QA_BOOTSTRAP_READY was not observed before timeout; stages=${diagnostics.join(",") || "none"}; ` +
        `inboxFileLeft=${fs.existsSync(paths.file)}; reader=${reader.join(" || ") || "none"}`,
    );
  } finally {
    stream.stop();
    fs.rmSync(paths.file, { force: true });
  }
}

/**
 * Terminates the app and reads `launchctl list` inside the Simulator until a read classifies the app
 * as absent (with a positive control) or attempts run out. Returns the last classification:
 * present / absent / unknown (see classifyLaunchctlRead).
 */
function terminateAndConfirm(udid, bundleId) {
  simctl(["terminate", udid, bundleId], { allowFailure: true });
  let last = { state: "unknown", reason: "not_read" };
  for (let attempt = 0; attempt < 10; attempt += 1) {
    last = classifyLaunchctlRead(simctl(["spawn", udid, "launchctl", "list"], { allowFailure: true, timeoutMs: 30_000 }), bundleId);
    if (last.state === "absent") return last;
    spawnSync("sleep", ["1"]);
  }
  return last;
}

/**
 * Stop, uninstall and prove the data container is gone. `simctl uninstall` drops the whole data
 * container (MMKV session, inbox); the app keeps no keychain items. Every read is classified
 * present / absent / unknown, and only absent+absent with a successful uninstall is "clean".
 */
function deviceCleanup(udid, bundleId) {
  const processCheck = terminateAndConfirm(udid, bundleId);
  const uninstall = simctl(["uninstall", udid, bundleId], { allowFailure: true });
  const controlBundleId = `build.raft.qa.never-installed.${crypto.randomBytes(6).toString("hex")}`;
  const control = simctl(["get_app_container", udid, controlBundleId, "data"], { allowFailure: true, timeoutMs: 30_000 });
  const read = simctl(["get_app_container", udid, bundleId, "data"], { allowFailure: true, timeoutMs: 30_000 });
  const container = classifyAppContainerRead(read, bundleId, control, controlBundleId);
  const receipt = iosCleanupReceipt({ processState: processCheck.state, uninstallStatus: uninstall.timedOut ? -1 : uninstall.status, containerState: container.state });
  return {
    cleanup_receipt: receipt,
    app_process: {
      method: "xcrun simctl terminate, then xcrun simctl spawn <udid> launchctl list",
      state: processCheck.state,
      reason: processCheck.reason,
      ...(processCheck.positive_control ? { positive_control: processCheck.positive_control } : {}),
    },
    app_uninstall: { method: "xcrun simctl uninstall", exit_status: uninstall.status, timed_out: uninstall.timedOut },
    app_data_container: {
      method: "xcrun simctl get_app_container <udid> <bundle> data, against a never-installed control bundle id",
      state: container.state,
      reason: container.reason,
    },
  };
}

function cleanup() {
  const udid = requireSimulatorUdid(argument("udid"));
  const simulatorName = requireOwned(udid);
  const bundleId = requireBundleId(argument("bundle-id"));
  const outcome = argument("outcome");
  if (outcome !== "completed" && outcome !== "aborted") throw new Error("cleanup outcome is invalid");
  const login = expectedLogin(argument("response"), argument("binding"), bundleId, argument("run-case-id"), argument("ci-job-ref"));
  login.bytes.fill(0);
  const readyFile = argument("ready", false);
  const runtime = outcome === "completed"
    ? JSON.parse(fs.readFileSync(readyFile, "utf8")).runtime_envelope
    : { process_instance_id: "not-ready", process_generation: "not-ready" };
  if (outcome === "completed" && (!runtime?.process_instance_id || !runtime?.process_generation)) {
    throw new Error("completed cleanup requires the exact READY process binding");
  }
  const result = deviceCleanup(udid, bundleId);
  // iOS-specific record: what was actually checked on the Simulator (no Android force-stop/pm clear).
  writePrivateJson(argument("output"), {
    platform: "ios-simulator",
    application_id: bundleId,
    simulator_udid: udid,
    simulator_name: simulatorName,
    boot_generation: login.envelope.boot_generation,
    request_id: login.requestId,
    envelope_digest: login.envelopeDigest,
    process_instance_id: runtime.process_instance_id,
    process_generation: runtime.process_generation,
    ...result,
  });
  if (result.cleanup_receipt !== "clean") {
    throw new Error(`device cleanup receipt is ${result.cleanup_receipt} (process=${result.app_process.state}/${result.app_process.reason}, ` +
      `uninstall=${result.app_uninstall.exit_status}, container=${result.app_data_container.state}/${result.app_data_container.reason}); not clean`);
  }
  console.log(`ios-qa-bootstrap: device cleanup confirmed bundle=${bundleId}`);
}

async function waitFor(stream, from, predicate, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate(stream.text().slice(from))) return true;
    await sleep(500);
  }
  return false;
}

/** Evidence only: kern.boottime as seen from inside the Simulator (expected to be the host's). */
function readSimulatorKernBootTime(udid) {
  const result = simctl(["spawn", udid, "sysctl", "-n", "kern.boottime"], { allowFailure: true, timeoutMs: 30_000 });
  return result.status === 0 ? result.stdout.trim() : `unavailable (exit ${result.status}: ${result.stderr.trim().slice(0, 200)})`;
}

async function smoke() {
  const udid = requireSimulatorUdid(argument("udid"));
  const bundleId = requireBundleId(argument("bundle-id"));
  requireOwned(udid);
  const out = argument("out");
  fs.mkdirSync(out, { recursive: true });
  const device = deviceBinding(udid, bundleId);
  const runCase = { runCaseId: "ios-bootstrap-smoke", ciJobRef: process.env.GITHUB_RUN_ID ? `run-${process.env.GITHUB_RUN_ID}` : "local" };
  const base = { ...device, ...runCase };
  let stream = await startLogStream(udid);
  let earlierLog = "";
  const results = [];
  let reboot;
  const scenario = async (name, { login, digest, mode, expect, fileLeft }) => {
    const from = stream.text().length;
    const paths = iosInboxPaths(appContainer(udid, bundleId, "data"), login.request_id);
    if (login.bytes) installPrivateEnvelope(udid, bundleId, login.request_id, login.bytes, { mode });
    launch(udid, bundleId, login.request_id, digest ?? login.envelope_digest);
    const seen = await waitFor(stream, from, (text) => expect.every(([kind, stage, code]) => hasBootstrapLine(text, kind, stage, code)), 90_000);
    const left = fs.existsSync(paths.file);
    const pass = seen && left === fileLeft;
    if (!pass) {
      // Host view of the Simulator app process: did the launch arguments reach it?
      const ps = run("ps", ["-axww", "-o", "pid=,args="], { allowFailure: true }).stdout
        .split("\n").filter((line) => line.includes(`/${bundleId}`) || line.includes(".app/")).filter((line) => line.includes("qaBootstrap") || /iosApp|Raft/.test(line));
      console.log(`ios-qa-bootstrap smoke: process view ${JSON.stringify(ps.slice(0, 5))}`);
      console.log(`ios-qa-bootstrap smoke: app log ${JSON.stringify(stream.text().slice(from).slice(-1500))}`);
    }
    const stages = parseBootstrapDiagnostics(stream.text().slice(from));
    results.push({ name, pass, expected: expect.map((e) => e.filter(Boolean).join(" ")), stages, inbox_file_left: left });
    console.log(`ios-qa-bootstrap smoke: ${pass ? "PASS" : "FAIL"} ${name} stages=${stages.join(",") || "none"} inboxFileLeft=${left}`);
    fs.rmSync(paths.file, { force: true });
    simctl(["terminate", udid, bundleId], { allowFailure: true });
  };
  try {
    const good = buildSmokeLogin({ ...base, requestId: "smoke-delivery" });
    // Delivery + one-shot + validation: everything up to the shared consumer, which then fails on the
    // fake token (any login_consume code but a runtime gate; reaching it proves the binding matched what
    // the host computed and that the Kotlin bridge is live in this build).
    await scenario("delivered envelope is consumed once and validated", {
      login: good,
      expect: [["STAGE", "inbox_consumed"], ["STAGE", "envelope_validated"], ["FAILURE", "login_consume", "(?!runtime_gate)[a-z0-9_]+"]],
      fileLeft: false,
    });
    run("xcrun", ["simctl", "io", udid, "screenshot", path.join(out, "after-delivery.png")], { allowFailure: true });
    await scenario("replayed request id finds nothing (one-shot)", {
      login: { request_id: good.request_id, envelope_digest: good.envelope_digest },
      expect: [["FAILURE", "inbox_consume", "missing"]],
      fileLeft: false,
    });
    const tampered = buildSmokeLogin({ ...base, requestId: "smoke-digest" });
    await scenario("digest mismatch is refused after consumption", {
      login: tampered,
      digest: sha256Digest(Buffer.from("other", "utf8")),
      expect: [["STAGE", "inbox_consumed"], ["FAILURE", "envelope_digest", "mismatch"]],
      fileLeft: false,
    });
    const loose = buildSmokeLogin({ ...base, requestId: "smoke-mode" });
    await scenario("world-readable inbox file is refused and left untouched", {
      login: loose,
      mode: 0o644,
      expect: [["FAILURE", "inbox_consume", "mode"]],
      fileLeft: true,
    });
    const foreign = buildSmokeLogin({
      ...base,
      requestId: "smoke-binding",
      edit: (envelope) => { envelope.boot_generation = sha256Digest(Buffer.from("another-simulator", "utf8")); },
    });
    await scenario("envelope bound to another simulator boot is refused", {
      login: foreign,
      expect: [["STAGE", "inbox_consumed"], ["FAILURE", "envelope_binding", "boot_generation"]],
      fileLeft: false,
    });

    // boot_generation must change when (and because) the Simulator reboots: compare the harness value,
    // the value the app logged, and, as evidence only, kern.boottime read inside the Simulator.
    const observe = (binding, log) => ({
      launchd_sim_start_utc: new Date(binding.launchdSimStart * 1000).toISOString(),
      boot_generation: binding.bootGeneration,
      app_boot_generation: [...log.matchAll(/QA_BOOTSTRAP_DIAG [^\r\n]*bootGeneration=(\S+)/g)].map((m) => m[1]).at(-1) ?? null,
      kern_boottime_inside_simulator: readSimulatorKernBootTime(udid),
      kern_boottime_host: run("sysctl", ["-n", "kern.boottime"], { allowFailure: true }).stdout.trim() || null,
    });
    const before = observe(device, stream.text());
    stream.stop();
    earlierLog = stream.text();
    simctl(["shutdown", udid]);
    simctl(["boot", udid]);
    simctl(["bootstatus", udid, "-b"], { timeoutMs: 300_000 });
    stream = await startLogStream(udid);
    const rebooted = deviceBinding(udid, bundleId);
    const stale = buildSmokeLogin({ ...base, requestId: "smoke-stale-boot" });
    await scenario("after a Simulator reboot, an envelope bound to the previous boot is refused", {
      login: stale,
      expect: [["STAGE", "inbox_consumed"], ["FAILURE", "envelope_binding", "boot_generation"]],
      fileLeft: false,
    });
    const fresh = buildSmokeLogin({ ...rebooted, ...runCase, requestId: "smoke-new-boot" });
    await scenario("after a Simulator reboot, an envelope bound to the new boot is validated", {
      login: fresh,
      expect: [["STAGE", "inbox_consumed"], ["STAGE", "envelope_validated"], ["FAILURE", "login_consume", "(?!runtime_gate)[a-z0-9_]+"]],
      fileLeft: false,
    });
    const after = observe(rebooted, stream.text());
    const changed = before.boot_generation !== after.boot_generation && before.launchd_sim_start_utc !== after.launchd_sim_start_utc;
    const agrees = before.app_boot_generation === before.boot_generation && after.app_boot_generation === after.boot_generation;
    reboot = { before, after, boot_generation_changed: changed, app_agrees_with_harness: agrees };
    results.push({ name: "Simulator reboot changes boot_generation (harness and app agree)", pass: changed && agrees, ...reboot });
    console.log(`ios-qa-bootstrap smoke: ${changed && agrees ? "PASS" : "FAIL"} reboot ${JSON.stringify(reboot)}`);

    // Teardown on this run-owned Simulator: stop + uninstall must end with a clean receipt.
    const teardown = deviceCleanup(udid, bundleId);
    results.push({ name: "device cleanup ends with a clean receipt", pass: teardown.cleanup_receipt === "clean", ...teardown });
    console.log(`ios-qa-bootstrap smoke: ${teardown.cleanup_receipt === "clean" ? "PASS" : "FAIL"} cleanup ${JSON.stringify(teardown)}`);
  } finally {
    stream.stop();
    fs.writeFileSync(path.join(out, "bootstrap-log.txt"), earlierLog + stream.text());
  }
  const passed = results.every((r) => r.pass);
  fs.writeFileSync(path.join(out, "smoke-report.json"), `${JSON.stringify({ contract: "qa-bootstrap-login/v1", platform: "ios-simulator", udid, bundle_id: bundleId, passed, reboot, results }, null, 2)}\n`);
  if (!passed) throw new Error("iOS QA session bootstrap smoke failed; see smoke-report.json and bootstrap-log.txt");
  console.log("ios-qa-bootstrap smoke: all scenarios passed");
}

try {
  const command = process.argv[2];
  if (command === "binding") binding();
  else if (command === "prepare") await prepare();
  else if (command === "cleanup") cleanup();
  else if (command === "smoke") await smoke();
  else fail("command must be binding|prepare|cleanup|smoke");
} catch (error) {
  fail(error instanceof Error ? error.message : String(error));
}
