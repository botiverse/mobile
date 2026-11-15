#!/usr/bin/env node
// iOS Simulator QA session bootstrap harness: the twin of mobile_login_bootstrap.mjs (Android).
// Same qa-bootstrap-login/v1 envelope, same READY line, same output files; only the device
// channel differs (xcrun simctl instead of adb). Contract: docs/qa-acceptance/session-bootstrap.md.
//
//   binding --udid <udid> --bundle-id <id> --output <binding.json>
//   prepare --udid <udid> --simulator-name <raft-qa-run-…> --bundle-id <id> --response <login.json>
//           --binding <binding.json> --run-case-id <id> --ci-job-ref <url> --ready-output <ready.json>
//           [--timeout-seconds 120]   (ownership verified before the inbox write AND before launch)
//   record-ownership --udid <udid> --simulator-name raft-qa-run-<run id>-<attempt> [--ownership-receipt <file>]
//           (run by the job right after `simctl create`: writes the protected ownership receipt once)
//   verify-ownership --udid <udid> --simulator-name <raft-qa-run-…> [--ownership-receipt <file>]
//   cleanup --udid <udid> --simulator-name <raft-qa-run-…> --bundle-id <id> --response <login.json>
//           --binding <binding.json> [--ready <ready.json>] --outcome completed|aborted
//           --output <cleanup.json> --run-case-id <id> --ci-job-ref <url>
//           (run only after ALL evidence collection: deletes the job-owned Simulator. Exit 0 only for
//           cleanup_receipt=simulator_deleted, i.e. simulator=deleted_and_absent; app_process is always
//           unknown and server_session not_proven_by_device_cleanup; the output is always written)
//   delete-simulator --udid <udid> --simulator-name <raft-qa-run-…> [--bundle-id <id>] [--output <file>]
//           (the workflow's final step: the same verified deletion; a no-op if this job's device is
//           already gone)
//   smoke   --udid <udid> --simulator-name <raft-qa-run-…> --bundle-id <id> --out <dir>
//           (no server: fake-token envelopes prove delivery, one-shot consumption, digest/binding
//           validation and the replay refusal; then a Simulator reboot must change boot_generation.
//           The device is deleted afterwards by the workflow's delete-simulator step)
// Every command that writes into, launches on, stops or deletes the Simulator refuses any device that
// is not this job's own: the ownership receipt (default $RUNNER_TEMP/raft-qa-simulator-ownership.json)
// must bind the UDID to this GITHUB_RUN_ID/GITHUB_RUN_ATTEMPT, and it is re-verified (with a fresh
// device list) immediately before every such action.
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
  classifyHostProcessRead,
  classifyLaunchctlRead,
  combineProcessStates,
  findLaunchdSimPid,
  hasBootstrapLine,
  iosBootGeneration,
  iosCodeImages,
  iosInboxPaths,
  iosLaunchArgs,
  parsePsLstartUtc,
  requireBundleId,
  requireRunOwnedSimulator,
  requireSimulatorUdid,
  runOwnedSimulatorName,
  parseSimctlDeviceList,
  simulatorCleanupFacts,
  simulatorDeletionReceipt,
  verifySimulatorDeletionReceipt,
  simulatorOwnershipReceipt,
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

function run(command, args, { allowFailure = false, timeoutMs = commandTimeoutMs, env, input } = {}) {
  const result = spawnSync(command, args, {
    encoding: "utf8", maxBuffer: 16 * 1024 * 1024, timeout: timeoutMs, killSignal: "SIGKILL", ...(env ? { env } : {}),
    ...(input !== undefined ? { input } : {}),
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

/** Atomic replace (0600 temp file + rename): a reader never sees a stale or half-written record. */
function writePrivateJsonAtomic(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = `${file}.tmp-${process.pid}`;
  fs.writeFileSync(tmp, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600, flag: "w" });
  fs.chmodSync(tmp, 0o600);
  fs.renameSync(tmp, file);
}

function appContainer(udid, bundleId, kind) {
  const value = simctl(["get_app_container", udid, bundleId, kind]).stdout.trim();
  if (!path.isAbsolute(value) || !fs.existsSync(value)) throw new Error(`${kind} container of ${bundleId} is unavailable (is it installed on ${udid}?)`);
  return value;
}

function plistValue(plist, key) {
  return run("plutil", ["-extract", key, "raw", "-o", "-", plist]).stdout.trim();
}

function ownershipReceiptPath() {
  const explicit = argument("ownership-receipt", false) ?? process.env.RAFT_QA_SIMULATOR_OWNERSHIP_RECEIPT;
  if (explicit) return path.resolve(explicit);
  if (!process.env.RUNNER_TEMP) throw new Error("no ownership receipt location (--ownership-receipt or RUNNER_TEMP)");
  return path.join(process.env.RUNNER_TEMP, "raft-qa-simulator-ownership.json");
}

/** This job's deletion receipt lives next to its ownership receipt. */
function deletionReceiptPath() {
  return ownershipReceiptPath().replace(/(\.json)?$/, ".deletion.json");
}

const currentUid = () => (typeof process.getuid === "function" ? process.getuid() : undefined);

function readOwnershipReceipt(file) {
  let stat;
  try {
    stat = fs.lstatSync(file);
  } catch (error) {
    if (error.code === "ENOENT") return null;
    throw error;
  }
  const text = stat.isFile() && !stat.isSymbolicLink() ? fs.readFileSync(file, "utf8") : "";
  return { text, stat: { isFile: stat.isFile(), isSymbolicLink: stat.isSymbolicLink(), mode: stat.mode, uid: stat.uid } };
}

/**
 * Refuses any Simulator but the one THIS job created: the protected ownership receipt must bind the
 * UDID and name to this GITHUB_RUN_ID/GITHUB_RUN_ATTEMPT, and a fresh `simctl list` must show that
 * UDID exactly once under that (unique) name. Called again immediately before each destructive action.
 */
function requireOwned(udid, { allowUnlisted = false } = {}) {
  const name = argument("simulator-name");
  const device = requireRunOwnedSimulator(simctl(["list", "devices", "-j"]).stdout, udid, name, {
    receipt: readOwnershipReceipt(ownershipReceiptPath()),
    runId: process.env.GITHUB_RUN_ID,
    runAttempt: process.env.GITHUB_RUN_ATTEMPT,
    uid: currentUid(),
    allowUnlisted,
  });
  return allowUnlisted ? { name, listed: device !== null } : name;
}

/**
 * Written once, by the job, right after it created the Simulator: 0400 file in RUNNER_TEMP, created
 * exclusively (an existing receipt is never overwritten). The device must be listed under exactly
 * this job's name, once.
 */
function recordOwnership() {
  const udid = requireSimulatorUdid(argument("udid"));
  const name = argument("simulator-name");
  const runId = process.env.GITHUB_RUN_ID;
  const runAttempt = process.env.GITHUB_RUN_ATTEMPT;
  if (name !== runOwnedSimulatorName(runId, runAttempt)) throw new Error(`simulator name ${name} is not this job's run-owned name`);
  const devices = parseSimctlDeviceList(simctl(["list", "devices", "-j"]).stdout);
  const byUdid = devices.filter((d) => d.udid.toUpperCase() === udid);
  const byName = devices.filter((d) => d.name === name);
  if (byUdid.length !== 1 || byUdid[0].name !== name || byName.length !== 1) {
    throw new Error(`simulator ${udid} is not listed exactly once under the unique name ${name}; not recording ownership`);
  }
  const receipt = simulatorOwnershipReceipt({ udid, name, runId, runAttempt, createdAt: new Date().toISOString() });
  const file = ownershipReceiptPath();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, `${JSON.stringify(receipt, null, 2)}\n`, { mode: 0o400, flag: "wx" });
  fs.chmodSync(file, 0o400);
  console.log(`ios-qa-bootstrap: recorded ownership of ${name} (${udid}) for run ${runId} attempt ${runAttempt}`);
}

function verifyOwnership() {
  const udid = requireSimulatorUdid(argument("udid"));
  const name = requireOwned(udid);
  console.log(`ios-qa-bootstrap: ownership of ${name} (${udid}) verified for this job`);
}

/** CFBundleExecutable of the installed app (for the host process query), or undefined. */
function installedExecutableName(udid, bundleId) {
  const app = simctl(["get_app_container", udid, bundleId, "app"], { allowFailure: true, timeoutMs: 30_000 });
  const appPath = app.status === 0 ? app.stdout.trim() : "";
  if (!path.isAbsolute(appPath)) return undefined;
  const value = run("plutil", ["-extract", "CFBundleExecutable", "raw", "-o", "-", path.join(appPath, "Info.plist")], { allowFailure: true });
  return value.status === 0 && value.stdout.trim() ? value.stdout.trim() : undefined;
}

/**
 * Start time (epoch seconds) of this Simulator's launchd_sim: new on every Simulator boot, to
 * one-second resolution (two boots within one UTC second collide; see iosBootGeneration). The app
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
    // Ownership BEFORE the first container write (refused → nothing is written) …
    try {
      requireOwned(udid);
    } catch (error) {
      login.bytes.fill(0);
      throw error;
    }
    paths = installPrivateEnvelope(udid, bundleId, login.requestId, login.bytes);
  } finally {
    login.bytes.fill(0);
  }
  const stream = await startLogStream(udid);
  try {
    console.log("ios-qa-bootstrap: launching app with the bootstrap request; polling READY");
    // … and again immediately before launch --terminate-running-process.
    requireOwned(udid);
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
 * Diagnostic only (never part of the verdict): one launchctl read inside the Simulator and one host
 * process read, before the device is shut down. Process listings cannot be proven complete, and
 * deleting the device does not prove a process exited, so the record's app_process stays "unknown".
 */
function processObservations(udid, bundleId) {
  const executableName = installedExecutableName(udid, bundleId);
  const describe = (check) => ({ state: check.state, reason: check.reason, ...(check.positive_control ? { positive_control: check.positive_control } : {}), ...(check.evidence_ceiling ? { evidence_ceiling: check.evidence_ceiling } : {}) });
  const launchctl = classifyLaunchctlRead(simctl(["spawn", udid, "launchctl", "list"], { allowFailure: true, timeoutMs: 30_000 }), bundleId);
  const host = classifyHostProcessRead(run("ps", ["-axww", "-o", "pid=,args="], { allowFailure: true, timeoutMs: 30_000 }), udid, executableName);
  return { note: "diagnostic observations before shutdown; not proof of process exit", launchctl: describe(launchctl), host_process: describe(host), combined: combineProcessStates(launchctl, host) };
}

/**
 * The end-of-run device cleanup of the job-owned Simulator (run only after ALL evidence collection:
 * screenshots, logs, crash reports). Each run creates its own Simulator, so cleanup removes the whole
 * device instead of inferring that an app and its data are gone:
 *   verify ownership → simctl shutdown → verify ownership again → simctl delete <udid> →
 *   a fresh simctl list devices -j must no longer list the UDID.
 * Returns separate facts (simulatorCleanupFacts): simulator deleted_and_absent|unknown,
 * app_process unknown, server_session not proven. Shared by `cleanup` and `delete-simulator`.
 */
function deleteOwnedSimulator(udid, bundleId) {
  requireOwned(udid);
  const observations = bundleId ? processObservations(udid, bundleId) : undefined;
  requireOwned(udid);
  const shutdown = simctl(["shutdown", udid], { allowFailure: true, timeoutMs: 120_000 });
  requireOwned(udid);
  const deleteResult = simctl(["delete", udid], { allowFailure: true, timeoutMs: 120_000 });
  const listAfter = simctl(["list", "devices", "-j"], { allowFailure: true, timeoutMs: 30_000 });
  const facts = simulatorCleanupFacts({ deleteResult, listAfter, udid });
  if (facts.simulator === "deleted_and_absent") {
    // Proven delete + strict readback: record it once (0400, exclusive) for this job only.
    const receipt = simulatorDeletionReceipt({
      udid, name: argument("simulator-name"), runId: process.env.GITHUB_RUN_ID, runAttempt: process.env.GITHUB_RUN_ATTEMPT,
      deletedAt: new Date().toISOString(),
    });
    fs.writeFileSync(deletionReceiptPath(), `${JSON.stringify(receipt, null, 2)}\n`, { mode: 0o400, flag: "wx" });
    fs.chmodSync(deletionReceiptPath(), 0o400);
  }
  return {
    ...facts,
    simulator_deletion: {
      method: "ownership-verified xcrun simctl shutdown + xcrun simctl delete <udid>, then a fresh xcrun simctl list devices -j must not list the UDID",
      shutdown_exit_status: shutdown.status,
      delete_exit_status: deleteResult.status,
      delete_timed_out: deleteResult.timedOut,
      listed_after_delete: facts.listed_after_delete,
      reason: facts.simulator_reason,
    },
    ...(observations ? { app_process_observations: observations } : {}),
  };
}

function cleanupFailureMessage(result) {
  return `device cleanup is ${result.cleanup_receipt} (simulator=${result.simulator}/${result.simulator_reason}, ` +
    `delete exit=${result.simulator_deletion.delete_exit_status}, listed after delete=${result.listed_after_delete}); the simulator is not proven deleted`;
}

/**
 * Final step of qa-acceptance-ios.yml (after all evidence collection): the same verified deletion
 * as `cleanup`. A record is ALWAYS written to --output. If the device is not listed, it is called
 * deleted only when a strictly parsed list omits the UDID AND this job holds its own deletion receipt
 * from an earlier proven delete (e.g. by `cleanup`); otherwise — malformed list, ownership refused,
 * absent without receipt — the result is unknown and non-zero.
 */
function deleteSimulator() {
  const output = argument("output", false);
  // Bound to THIS run (reporter checks run id/attempt + UDID/name against the ownership receipt).
  const base = {
    platform: "ios-simulator", github_run_id: process.env.GITHUB_RUN_ID ?? null, github_run_attempt: process.env.GITHUB_RUN_ATTEMPT ?? null,
    simulator_udid: String(argument("udid", false) ?? "").toUpperCase(), simulator_name: argument("simulator-name", false) ?? null,
  };
  const unknownRecord = (reason, message) => ({
    ...base, cleanup_receipt: "unknown", simulator: "unknown", simulator_reason: reason,
    app_process: "unknown", server_session: "not_proven_by_device_cleanup", ...(message ? { error: message } : {}),
  });
  // FIRST action, before any check: replace whatever record is there (e.g. another run's leftover)
  // with this run's "unknown". Only a final verified result below replaces it again.
  if (output) writePrivateJsonAtomic(output, unknownRecord("not_completed"));
  const udid = requireSimulatorUdid(argument("udid"));
  const name = argument("simulator-name");
  const unknown = (reason, message) => {
    if (output) {
      writePrivateJsonAtomic(output, {
        ...unknownRecord(reason, message),
      });
    }
    throw new Error(message);
  };
  let ownership;
  try {
    ownership = requireOwned(udid, { allowUnlisted: true });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    unknown(/device list/.test(message) ? "list_unexpected_shape" : "ownership_not_verified", `simulator deletion not attempted: ${message}`);
  }
  if (!ownership.listed) {
    const verdict = verifySimulatorDeletionReceipt(readOwnershipReceipt(deletionReceiptPath()), {
      udid, name, runId: process.env.GITHUB_RUN_ID, runAttempt: process.env.GITHUB_RUN_ATTEMPT, uid: currentUid(),
    });
    if (verdict !== true) {
      unknown(verdict, `${name} (${udid}) is not listed, but this job holds no valid deletion receipt (${verdict}); absence alone is not proof of deletion`);
    }
    if (output) {
      writePrivateJsonAtomic(output, {
        ...base, cleanup_receipt: "simulator_deleted", simulator: "deleted_and_absent", simulator_reason: "deleted_earlier_in_this_job",
        listed_after_delete: "absent", app_process: "unknown", server_session: "not_proven_by_device_cleanup",
      });
    }
    console.log(`ios-qa-bootstrap: ${name} (${udid}) is already deleted (this job's deletion receipt + not listed); nothing to delete`);
    return;
  }
  const result = deleteOwnedSimulator(udid, argument("bundle-id", false));
  if (output) writePrivateJsonAtomic(output, { ...base, ...result });
  if (result.cleanup_receipt !== "simulator_deleted") throw new Error(cleanupFailureMessage(result));
  console.log(`ios-qa-bootstrap: deleted ${name} (${udid}); simulator=deleted_and_absent app_process=unknown server_session=not_proven_by_device_cleanup`);
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
  const result = deleteOwnedSimulator(udid, bundleId);
  // iOS-specific record: what was actually checked on the Simulator (no Android force-stop/pm clear).
  writePrivateJson(argument("output"), {
    platform: "ios-simulator",
    application_id: bundleId,
    github_run_id: process.env.GITHUB_RUN_ID ?? null,
    github_run_attempt: process.env.GITHUB_RUN_ATTEMPT ?? null,
    simulator_udid: udid,
    simulator_name: simulatorName,
    boot_generation: login.envelope.boot_generation,
    request_id: login.requestId,
    envelope_digest: login.envelopeDigest,
    process_instance_id: runtime.process_instance_id,
    process_generation: runtime.process_generation,
    ...result,
  });
  if (result.cleanup_receipt !== "simulator_deleted") throw new Error(cleanupFailureMessage(result));
  console.log(`ios-qa-bootstrap: simulator deleted and absent (app_process=unknown, server_session=not_proven_by_device_cleanup) bundle=${bundleId}`);
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
    requireOwned(udid); // launch --terminate-running-process and the terminate below stop the app
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
    requireOwned(udid);
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

    // No inline device cleanup: the job-owned Simulator is deleted by the workflow's final
    // `delete-simulator` step (the same verified deletion as `cleanup`), after all evidence
    // collection; its record is qa-evidence/simulator-cleanup.json.
  } finally {
    stream.stop();
    fs.writeFileSync(path.join(out, "bootstrap-log.txt"), earlierLog + stream.text());
  }
  const passed = results.every((r) => r.pass);
  fs.writeFileSync(path.join(out, "smoke-report.json"), `${JSON.stringify({ contract: "qa-bootstrap-login/v1", platform: "ios-simulator", udid, bundle_id: bundleId, passed, reboot, results, device_cleanup: "performed after evidence collection by the workflow's delete-simulator step; see qa-evidence/simulator-cleanup.json" }, null, 2)}\n`);
  if (!passed) throw new Error("iOS QA session bootstrap smoke failed; see smoke-report.json and bootstrap-log.txt");
  console.log("ios-qa-bootstrap smoke: all scenarios passed");
}

try {
  const command = process.argv[2];
  if (command === "binding") binding();
  else if (command === "prepare") await prepare();
  else if (command === "cleanup") cleanup();
  else if (command === "record-ownership") recordOwnership();
  else if (command === "verify-ownership") verifyOwnership();
  else if (command === "delete-simulator") deleteSimulator();
  else if (command === "smoke") await smoke();
  else fail("command must be binding|prepare|cleanup|smoke|record-ownership|verify-ownership|delete-simulator");
} catch (error) {
  fail(error instanceof Error ? error.message : String(error));
}
