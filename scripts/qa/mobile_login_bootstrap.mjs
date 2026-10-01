#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import {
  adbRunAsShellArgs,
  apksignerVerifyArgs,
  parseBootstrapDiagnostics,
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
    const detail = String(stderr ?? "").trim().slice(0, 2_048);
    const code = result.error && "code" in result.error ? String(result.error.code) : "none";
    throw new Error(
      `${command} failed (status=${result.status ?? "none"}, signal=${result.signal ?? "none"}, code=${code})${detail ? `: ${detail}` : ""}`,
    );
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
  // Pre-flight, before any device work: QaSessionBootstrapActivity refuses with
  // `runtime_gate disabled` unless the package is debuggable AND built with
  // QA_SESSION_BOOTSTRAP_ENABLED (only the `alpha` and `connectedTest` build
  // types set it). A release/Hands-main package therefore can never bootstrap,
  // and every alpha-qa run pinned to one died 120s later as "aborted before
  // READY" (18/18 in production through 2026-07-26). Fail here, by name.
  if (!/application-debuggable/.test(badging)) {
    throw new Error(`auth-bootstrap needs a debuggable QA build (alpha build type, package build.raft.app.alpha, an android-alpha.yml artifact); ${applicationId} is not debuggable`);
  }
  if (!/\.(alpha|connectedtest)$/.test(applicationId)) {
    console.warn(`qa-login-bootstrap: WARNING ${applicationId} is debuggable but not an alpha/connectedTest package; QA_SESSION_BOOTSTRAP_ENABLED is only set for those build types, expect runtime_gate=disabled`);
  }
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
  const expectedDigest = sha256Digest(bytes).slice("sha256:".length);
  const text = (r) => (Buffer.isBuffer(r.stdout) ? r.stdout.toString("utf8") : String(r.stdout ?? ""));
  const errText = (r) => (Buffer.isBuffer(r.stderr) ? r.stderr.toString("utf8") : String(r.stderr ?? ""));
  // Hard precondition: the plain shell channel must carry the remote exit
  // status. It is the only channel measured to do so (exec-in/exec-out drop
  // it: HuaTuo on the cloud phone, canary 96e256d5 on the hosted emulator).
  const statusProbe = run("adb", ["shell", "exit 7"], { allowFailure: true });
  if (statusProbe.status !== 7) {
    throw new Error(`adb shell does not propagate the remote exit status (exit 7 read as ${statusProbe.status}); every status-based check on this host is void (task #188)`);
  }
  // Transport: `adb shell -T` with the envelope on stdin. canary-14 (run
  // 7cf1eef7, CI 34683983775, hosted API 34 emulator, script 49324c024): the
  // same run-as helper over exec-in with NO stdin landed a probe file that the
  // shell channel read back; with the envelope on stdin, exec-in returned in
  // 0.1 s with no file, no sentinel and no stderr. exec-in is the legacy
  // unframed `exec:` service: host stdin EOF closes the socket and the remote
  // shell dies before its `mv`. On the cloud phone the same pipe is sound
  // (HuaTuo), so this is a channel difference, not a script difference. The
  // shell-v2 channel sends EOF as a packet, so nothing enters argv, no
  // staging path, no shared storage; umask 077, .tmp then mv, trap cleanup
  // as before. Stdin-over-shell measurement, same channel, one token:
  const markToken = `probe-${Date.now()}`;
  const markWrite = run("adb", adbRunAsShellArgs("shell", applicationId, "umask 077; mkdir -p files/qa-bootstrap; cat > files/qa-bootstrap/qa-mark.txt; echo WROTE"), { input: Buffer.from(markToken, "utf8"), allowFailure: true });
  const markRead = run("adb", ["shell", `run-as ${applicationId} sh -c 'cat files/qa-bootstrap/qa-mark.txt 2>&1'`], { allowFailure: true });
  console.log(`qa-login-bootstrap: shell -T stdin probe token=${markToken} write_status=${markWrite.status} wrote=${text(markWrite).includes("WROTE")} seen_via_shell=${text(markRead).includes(markToken)} stderr=${JSON.stringify(errText(markWrite).trim()).slice(0, 300)}`);
  const script = [
    "set -eu", "umask 077", "mkdir -p files/qa-bootstrap/inbox",
    `tmp=files/qa-bootstrap/inbox/${requestId}.session.tmp`,
    `dst=files/qa-bootstrap/inbox/${requestId}.session`,
    "trap 'rm -f \"$tmp\"' EXIT", "cat > \"$tmp\"", "chmod 600 \"$tmp\"",
    "mv -f \"$tmp\" \"$dst\"", "trap - EXIT",
    // Success sentinel on stdout; under set -e it prints only if every step
    // succeeded, and on this channel the exit status arrives as well.
    `printf 'INSTALL_OK %s\\n' "$(sha256sum \"$dst\" | cut -d' ' -f1)"`,
  ].join("; ");
  const installed = run("adb", adbRunAsShellArgs("shell", applicationId, script), { input: bytes, allowFailure: true });
  if (installed.status !== 0 || !text(installed).includes(`INSTALL_OK ${expectedDigest}`)) {
    throw new Error(`private envelope install failed on the shell channel (status=${installed.status} expected INSTALL_OK ${expectedDigest.slice(0, 12)}… bytes=${bytes.length} stdout=${JSON.stringify(text(installed).trim()).slice(0, 300)} stderr=${JSON.stringify(errText(installed).trim()).slice(0, 300)})`);
  }
  // Judgement: an independent READBACK on the same channel must return the
  // expected digest. The INSTALL_OK line above is a witness, not the judge.
  const readback = run("adb", ["shell", `run-as ${applicationId} sh -c 'sha256sum files/qa-bootstrap/inbox/${requestId}.session 2>/dev/null | cut -d" " -f1'`], { allowFailure: true });
  const readbackSha = readback.stdout.trim().split(/\s+/)[0] ?? "";
  if (readbackSha !== expectedDigest) {
    throw new Error(`private envelope shell sha readback mismatch: expected ${expectedDigest.slice(0, 12)}… got ${JSON.stringify(readbackSha).slice(0, 80)} (status=${readback.status} bytes=${bytes.length})`);
  }
  // Verify with EXPLICIT sentinels on the same status-bearing channel. Every
  // check appends its name to $fail; the script always ends by printing
  // VERIFY_OK or VERIFY_FAIL <steps>. Three outcomes, none of them silent:
  // OK, FAIL with the failing steps, or neither = the command did not run to
  // its end. The run-as view (cwd, id, ls -laZ) follows for the reader-side
  // comparison. No file contents, no credentials.
  const verifyScript = [
    "set -u",
    `dst=files/qa-bootstrap/inbox/${requestId}.session`,
    "fail=''",
    "[ -f \"$dst\" ] || fail=\"$fail missing\"",
    "[ \"$(stat -c %a \"$dst\" 2>/dev/null)\" = 600 ] || fail=\"$fail mode\"",
    `[ "$(wc -c <\"$dst\" 2>/dev/null)" = ${bytes.length} ] || fail="$fail size"`,
    `[ "$(sha256sum \"$dst\" 2>/dev/null | cut -d' ' -f1)" = ${expectedDigest} ] || fail="$fail sha"`,
    "if [ -z \"$fail\" ]; then printf 'VERIFY_OK\\n'; else printf 'VERIFY_FAIL%s\\n' \"$fail\"; fi",
    "printf 'run-as pwd=%s\\n' \"$(pwd)\"",
    "printf 'run-as id=%s\\n' \"$(id)\"",
    "ls -laZ files/qa-bootstrap/inbox 2>&1 | sed 's/^/inbox: /'",
  ].join("; ");
  const verify = run("adb", adbRunAsShellArgs("shell", applicationId, verifyScript), { allowFailure: true });
  const verifyOut = verify.stdout;
  if (verify.status !== 0 || !verifyOut.includes("VERIFY_OK")) {
    const failLine = verifyOut.match(/VERIFY_FAIL[^\r\n]*/)?.[0];
    // Three branches, each named for what was actually observed (LiBai):
    // a VERIFY_FAIL line = checks failed; VERIFY_OK present but a non-zero
    // status = the script passed its checks and then exited non-zero; neither
    // sentinel = the script did not run to its end.
    const branch = failLine
      ? `failed: ${failLine}`
      : verifyOut.includes("VERIFY_OK")
        ? `printed VERIFY_OK but the shell channel returned status ${verify.status}`
        : "did not run to its end (neither VERIFY_OK nor VERIFY_FAIL)";
    throw new Error(`private envelope verify ${branch}; status=${verify.status} stdout=${JSON.stringify(verifyOut.trim()).slice(0, 1500)} stderr=${JSON.stringify(verify.stderr.trim()).slice(0, 500)}`);
  }
  const facts = run("adb", ["shell", "am get-current-user; getprop ro.build.version.sdk; getprop ro.build.product; id"], { allowFailure: true }).stdout;
  console.log(`qa-login-bootstrap: envelope verified (VERIFY_OK, shell channel status=${verify.status})\n${verifyOut.trim().slice(0, 3000)}\ndevice: ${facts.trim().split("\n").join(" | ")}`);
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
  const diagnosticLog = run("adb", ["logcat", "-d", "-v", "brief", "SlockQaBootstrap:I", "*:S"]).stdout;
  const diagnostics = parseBootstrapDiagnostics(diagnosticLog);
  // The app's reader-side coordinates (QA_BOOTSTRAP_DIAG filesDir/dataDir/uid/user/path,
  // paths and ids only). Printed raw next to the stage summary so the writer's
  // realpath above and the reader's path can be compared in one log.
  const readerDiag = [...String(diagnosticLog).matchAll(/QA_BOOTSTRAP_DIAG [^\r\n]*/g)].map((m) => m[0]);
  throw new Error(
    `exact-bound QA_BOOTSTRAP_READY was not observed before timeout; stages=${diagnostics.join(",") || "none"}; reader=${readerDiag.join(" || ") || "none"}`,
  );
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
