#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import {
  apksignerVerifyArgs,
  parseReadyLine,
  readyPayload,
  selectApkSigningCertificateSha256,
  sha256Digest,
  validateLoginResponse,
} from "./mobile_login_bootstrap_lib.mjs";

const commandTimeoutMs = process.env.NODE_ENV === "test"
  ? Number(process.env.QA_BOOTSTRAP_COMMAND_TIMEOUT_MS ?? "15000")
  : 15_000;
if (!Number.isInteger(commandTimeoutMs) || commandTimeoutMs < 10 || commandTimeoutMs > 15_000) {
  fail("QA bootstrap command timeout is invalid");
}

function fail(message) {
  console.error(`qa-login-bootstrap: ${message}`);
  process.exit(2);
}

function argument(name, required = true) {
  const index = process.argv.indexOf(`--${name}`);
  const value = index >= 0 ? process.argv[index + 1] : undefined;
  if (required && (!value || value.startsWith("--"))) fail(`--${name} is required`);
  return value;
}

function run(command, args, { input, allowFailure = false, timeoutMs = commandTimeoutMs } = {}) {
  const result = spawnSync(command, args, {
    input,
    encoding: input ? undefined : "utf8",
    maxBuffer: 16 * 1024 * 1024,
    timeout: timeoutMs,
    killSignal: "SIGKILL",
  });
  if (!allowFailure && (result.error || result.status !== 0)) {
    const stderr = Buffer.isBuffer(result.stderr) ? result.stderr.toString("utf8") : result.stderr;
    throw new Error(`${command} failed (${result.status ?? "spawn"}): ${String(stderr ?? "").trim()}`);
  }
  return {
    status: result.status ?? -1,
    stdout: Buffer.isBuffer(result.stdout) ? result.stdout.toString("utf8") : String(result.stdout ?? ""),
    stderr: Buffer.isBuffer(result.stderr) ? result.stderr.toString("utf8") : String(result.stderr ?? ""),
  };
}

function writePrivateJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  fs.chmodSync(file, 0o600);
}

function buildTool(name) {
  const root = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (!root) throw new Error("ANDROID_HOME/ANDROID_SDK_ROOT is required");
  const versions = fs.readdirSync(path.join(root, "build-tools"))
    .sort((left, right) => left.localeCompare(right, undefined, { numeric: true }));
  const tool = path.join(root, "build-tools", versions.at(-1), name);
  if (!fs.existsSync(tool)) throw new Error(`${name} was not found in Android build-tools`);
  return tool;
}

function binding() {
  const apk = argument("apk");
  const output = argument("output");
  const badging = run(buildTool("aapt"), ["dump", "badging", apk]).stdout;
  const applicationId = badging.match(/package: name='([^']+)'/)?.[1];
  if (!applicationId) throw new Error("APK package id is unavailable");
  const deviceSdkText = run("adb", ["shell", "getprop", "ro.build.version.sdk"]).stdout.trim();
  if (!/^[1-9][0-9]*$/.test(deviceSdkText)) throw new Error("Android device SDK is unavailable");
  const deviceSdk = Number(deviceSdkText);
  const signer = run(buildTool("apksigner"), apksignerVerifyArgs(apk, deviceSdk)).stdout;
  const signingCertSha256 = selectApkSigningCertificateSha256(signer, deviceSdk);
  const bootId = run("adb", ["shell", "cat", "/proc/sys/kernel/random/boot_id"]).stdout.trim();
  if (!/^[a-f0-9-]{36}$/i.test(bootId)) throw new Error("device boot id is unavailable");
  writePrivateJson(output, {
    signing_cert_sha256: signingCertSha256,
    boot_generation: sha256Digest(Buffer.from(bootId, "utf8")),
  });
  console.log(`qa-login-bootstrap: device binding ready package=${applicationId}`);
}

function installPrivateEnvelope(applicationId, requestId, bytes) {
  const script = [
    "set -eu", "umask 077", "mkdir -p files/qa-bootstrap/inbox",
    `tmp=files/qa-bootstrap/inbox/${requestId}.session.tmp`,
    `dst=files/qa-bootstrap/inbox/${requestId}.session`,
    "trap 'rm -f \"$tmp\"' EXIT", "cat > \"$tmp\"", "chmod 600 \"$tmp\"",
    "mv -f \"$tmp\" \"$dst\"", "trap - EXIT",
  ].join("; ");
  run("adb", ["exec-out", "run-as", applicationId, "sh", "-c", script], { input: bytes });
}

function expectedLogin(responseFile, bindingFile, runCaseId, ciJobRef) {
  const response = JSON.parse(fs.readFileSync(responseFile, "utf8"));
  const device = JSON.parse(fs.readFileSync(bindingFile, "utf8"));
  const applicationId = response.envelope?.application_id;
  return validateLoginResponse(response, {
    runCaseId, ciJobRef, applicationId,
    signingCertSha256: device.signing_cert_sha256,
    bootGeneration: device.boot_generation,
  });
}

async function prepare() {
  const responseFile = argument("response");
  const bindingFile = argument("binding");
  const runCaseId = argument("run-case-id");
  const ciJobRef = argument("ci-job-ref");
  const readyOutput = argument("ready-output");
  const timeoutSeconds = Number(argument("timeout-seconds", false) ?? "120");
  if (!Number.isInteger(timeoutSeconds) || timeoutSeconds < 1 || timeoutSeconds > 180) throw new Error("READY timeout is invalid");
  const login = expectedLogin(responseFile, bindingFile, runCaseId, ciJobRef);
  const envelope = login.envelope;
  try {
    console.log("qa-login-bootstrap: installing private envelope");
    installPrivateEnvelope(envelope.application_id, login.requestId, login.bytes);
    console.log("qa-login-bootstrap: private envelope installed");
  } finally {
    login.bytes.fill(0);
  }
  run("adb", ["logcat", "-c"]);
  console.log("qa-login-bootstrap: launching bootstrap activity");
  run("adb", [
    "shell", "am", "start", "-W",
    "-n", `${envelope.application_id}/build.raft.app.core.data.auth.QaSessionBootstrapActivity`,
    "--es", "qaBootstrapRequestId", login.requestId,
    "--es", "qaBootstrapEnvelopeDigest", login.envelopeDigest,
  ]);
  console.log("qa-login-bootstrap: bootstrap activity launched; polling READY");
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
    const log = run("adb", ["logcat", "-d", "-v", "brief", "SlockQaBootstrap:I", "*:S"]).stdout;
    const ready = parseReadyLine(log, expected);
    if (ready) {
      writePrivateJson(readyOutput, readyPayload(login, ready));
      console.log(`qa-login-bootstrap: READY accepted digest=${login.envelopeDigest}`);
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error("exact-bound QA_BOOTSTRAP_READY was not observed before timeout");
}

function cleanup() {
  const responseFile = argument("response");
  const bindingFile = argument("binding");
  const readyFile = argument("ready", false);
  const outcome = argument("outcome");
  if (outcome !== "completed" && outcome !== "aborted") throw new Error("cleanup outcome is invalid");
  const output = argument("output");
  const runCaseId = argument("run-case-id");
  const ciJobRef = argument("ci-job-ref");
  const login = expectedLogin(responseFile, bindingFile, runCaseId, ciJobRef);
  login.bytes.fill(0);
  const runtime = outcome === "completed"
    ? JSON.parse(fs.readFileSync(readyFile, "utf8")).runtime_envelope
    : { process_instance_id: "not-ready", process_generation: "not-ready" };
  if (outcome === "completed" && (!runtime?.process_instance_id || !runtime?.process_generation)) {
    throw new Error("completed cleanup requires the exact READY process binding");
  }
  const applicationId = login.envelope.application_id;
  run("adb", ["shell", "am", "force-stop", applicationId]);
  const pid = run("adb", ["shell", "pidof", applicationId], { allowFailure: true }).stdout.trim();
  if (pid) throw new Error("app process remained alive after force-stop");
  run("adb", ["exec-out", "run-as", applicationId, "rm", "-rf", "files/qa-bootstrap"], { allowFailure: true });
  const cleared = run("adb", ["shell", "pm", "clear", applicationId]);
  if (!cleared.stdout.includes("Success")) throw new Error("package data clear was not confirmed");
  writePrivateJson(output, {
    application_id: applicationId,
    boot_generation: login.envelope.boot_generation,
    request_id: login.requestId,
    envelope_digest: login.envelopeDigest,
    process_instance_id: runtime.process_instance_id,
    process_generation: runtime.process_generation,
    force_stopped: true,
    package_data_cleared: true,
  });
  console.log(`qa-login-bootstrap: device cleanup confirmed package=${applicationId}`);
}

try {
  const command = process.argv[2];
  if (command === "binding") binding();
  else if (command === "prepare") await prepare();
  else if (command === "cleanup") cleanup();
  else fail("command must be binding|prepare|cleanup");
} catch (error) {
  fail(error instanceof Error ? error.message : String(error));
}
