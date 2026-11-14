#!/usr/bin/env node
// iOS Simulator QA session bootstrap harness: the twin of mobile_login_bootstrap.mjs (Android).
// Same qa-bootstrap-login/v1 envelope, same READY line, same output files; only the device
// channel differs (xcrun simctl instead of adb). Contract: docs/qa-acceptance/session-bootstrap.md.
//
//   binding --udid <udid> --bundle-id <id> --output <binding.json>
//   prepare --udid <udid> --bundle-id <id> --response <login.json> --binding <binding.json>
//           --run-case-id <id> --ci-job-ref <url> --ready-output <ready.json> [--timeout-seconds 120]
//   cleanup --udid <udid> --bundle-id <id> --response <login.json> --binding <binding.json>
//           [--ready <ready.json>] --outcome completed|aborted --output <cleanup.json>
//           --run-case-id <id> --ci-job-ref <url>
//   smoke   --udid <udid> --bundle-id <id> --out <dir>   (no server: fake-token envelopes prove
//           delivery, one-shot consumption, digest/binding validation and the replay refusal)
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
  hasBootstrapLine,
  iosBootGeneration,
  iosCodeImages,
  iosInboxPaths,
  iosLaunchArgs,
  launchctlAppAlive,
  parseKernBootTime,
  requireBundleId,
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

function run(command, args, { allowFailure = false, timeoutMs = commandTimeoutMs } = {}) {
  const result = spawnSync(command, args, { encoding: "utf8", maxBuffer: 16 * 1024 * 1024, timeout: timeoutMs, killSignal: "SIGKILL" });
  if (!allowFailure && (result.error || result.status !== 0)) {
    const detail = String(result.stderr ?? "").trim().slice(0, 2_048);
    throw new Error(`${command} ${args.slice(0, 3).join(" ")} failed (status=${result.status ?? "none"})${detail ? `: ${detail}` : ""}`);
  }
  return { status: result.status ?? -1, stdout: String(result.stdout ?? ""), stderr: String(result.stderr ?? "") };
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

/** The device binding the app computes for itself, read from the INSTALLED bundle and the host. */
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
  const bootTime = parseKernBootTime(run("sysctl", ["-n", "kern.boottime"]).stdout);
  return {
    applicationId,
    signingCertSha256: sha256Digest(Buffer.concat(bytes)),
    bootGeneration: iosBootGeneration(udid, bootTime),
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

function terminateAndConfirm(udid, bundleId) {
  simctl(["terminate", udid, bundleId], { allowFailure: true });
  for (let attempt = 0; attempt < 10; attempt += 1) {
    const jobs = simctl(["spawn", udid, "launchctl", "list"], { allowFailure: true }).stdout;
    if (!launchctlAppAlive(jobs, bundleId)) return true;
    spawnSync("sleep", ["1"]);
  }
  return false;
}

function cleanup() {
  const udid = requireSimulatorUdid(argument("udid"));
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
  if (!terminateAndConfirm(udid, bundleId)) throw new Error("app process remained alive after terminate");
  // `simctl uninstall` drops the whole data container (MMKV session, inbox); the app keeps no keychain items.
  simctl(["uninstall", udid, bundleId]);
  const left = simctl(["get_app_container", udid, bundleId, "data"], { allowFailure: true });
  if (left.status === 0) throw new Error("app data container still present after uninstall");
  writePrivateJson(argument("output"), {
    application_id: bundleId,
    boot_generation: login.envelope.boot_generation,
    request_id: login.requestId,
    envelope_digest: login.envelopeDigest,
    process_instance_id: runtime.process_instance_id,
    process_generation: runtime.process_generation,
    force_stopped: true,
    package_data_cleared: true,
  });
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

async function smoke() {
  const udid = requireSimulatorUdid(argument("udid"));
  const bundleId = requireBundleId(argument("bundle-id"));
  const out = argument("out");
  fs.mkdirSync(out, { recursive: true });
  const device = deviceBinding(udid, bundleId);
  const base = { ...device, runCaseId: "ios-bootstrap-smoke", ciJobRef: process.env.GITHUB_RUN_ID ? `run-${process.env.GITHUB_RUN_ID}` : "local" };
  const stream = await startLogStream(udid);
  const results = [];
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
  } finally {
    stream.stop();
    fs.writeFileSync(path.join(out, "bootstrap-log.txt"), stream.text());
  }
  const passed = results.every((r) => r.pass);
  fs.writeFileSync(path.join(out, "smoke-report.json"), `${JSON.stringify({ contract: "qa-bootstrap-login/v1", platform: "ios-simulator", udid, bundle_id: bundleId, passed, results }, null, 2)}\n`);
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
