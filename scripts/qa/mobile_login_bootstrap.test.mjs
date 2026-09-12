import assert from "node:assert/strict";
import fs from "node:fs";
import { createHash } from "node:crypto";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import {
  adbRunAsShellArgs,
  apksignerVerifyArgs,
  canonicalJson,
  parseBootstrapDiagnostics,
  parseReadyLine,
  readyPayload,
  selectApkSigningCertificateSha256,
  sha256Digest,
  validateLoginResponse,
} from "./mobile_login_bootstrap_lib.mjs";

test("pre-READY subprocesses are bounded and phase-observable", () => {
  const source = fs.readFileSync(path.resolve("scripts/qa/mobile_login_bootstrap.mjs"), "utf8");
  const agentSource = fs.readFileSync(path.resolve("scripts/qa/acceptance_agent.mjs"), "utf8");
  assert.match(source, /timeoutMs = commandTimeoutMs/);
  assert.match(source, /timeout: timeoutMs/);
  assert.match(source, /killSignal: "SIGKILL"/);
  assert.match(source, /signal=\$\{result\.signal/);
  assert.match(source, /result\.error\.code/);
  assert.match(source, /installing private envelope/);
  assert.match(source, /private envelope installed/);
  assert.match(source, /launching bootstrap activity/);
  assert.match(source, /bootstrap activity launched; polling READY/);
  assert.match(source, /adbRunAsShellArgs\("shell", applicationId, script\)/);
  assert.match(agentSource, /adbRunAsShellArgs\("shell", opts\.appPackage, installScript\)/);
  assert.doesNotMatch(agentSource, /"exec-out", "run-as", opts\.appPackage/);
});

test("run-as keeps the complete remote script in one shell-quoted ADB command", () => {
  assert.deepEqual(
    adbRunAsShellArgs(
      "exec-in",
      "build.raft.app.alpha",
      "set -eu; tmp='files/request.session.tmp'; cat >\"$tmp\"; mv \"$tmp\" files/request.session",
    ),
    [
      "exec-in",
      "run-as 'build.raft.app.alpha' sh -c 'set -eu; tmp='\"'\"'files/request.session.tmp'\"'\"'; cat >\"$tmp\"; mv \"$tmp\" files/request.session'",
    ],
  );
  assert.throws(
    () => adbRunAsShellArgs("exec-in", "build.raft.app;id", "true"),
    /application id is invalid/,
  );
});

test("a hung pre-READY ADB command is killed before the outer fuse", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "qa-bootstrap-timeout-"));
  const bin = path.join(root, "bin");
  const tools = path.join(root, "android-sdk", "build-tools", "37.0.0");
  fs.mkdirSync(bin, { recursive: true });
  fs.mkdirSync(tools, { recursive: true });
  const apk = path.join(root, "app.apk");
  fs.writeFileSync(apk, "apk");
  // The binding pre-flight (#2462) refuses a non-debuggable APK; these fake
  // badgings stand in for the alpha build type, so they carry the flag.
  writeExecutable(path.join(tools, "aapt"), "printf \"package: name='build.raft.app.alpha'\\napplication-debuggable\\n\"");
  writeExecutable(path.join(bin, "adb"), "sleep 5");
  const started = Date.now();
  const result = spawnSync(process.execPath, [
    path.resolve("scripts/qa/mobile_login_bootstrap.mjs"), "binding",
    "--apk", apk, "--output", path.join(root, "binding.json"),
  ], {
    cwd: path.resolve("."), encoding: "utf8",
    env: {
      ...process.env,
      NODE_ENV: "test",
      QA_BOOTSTRAP_COMMAND_TIMEOUT_MS: "50",
      ANDROID_HOME: path.join(root, "android-sdk"),
      PATH: `${bin}:${process.env.PATH ?? ""}`,
    },
  });
  try {
    assert.notEqual(result.status, 0);
    assert.ok(Date.now() - started < 2_000, "hung adb exceeded the bounded test timeout");
    assert.match(result.stderr, /adb failed \(status=none, signal=SIGKILL, code=ETIMEDOUT\)/);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("private envelope rides adb shell -T stdin (shell v2), is judged by shell readback, and never stages on shared storage", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "qa-bootstrap-shell-stdin-"));
  const bin = path.join(root, "bin");
  fs.mkdirSync(bin, { recursive: true });
  const responseFile = path.join(root, "response.json");
  const bindingFile = path.join(root, "binding.json");
  const readyFile = path.join(root, "ready.json");
  const capturedBytes = path.join(root, "captured-envelope.json");
  const capturedArgs = path.join(root, "captured-args.txt");
  const capturedVerifyArgs = path.join(root, "captured-verify-args.txt");
  const response = loginResponse();
  fs.writeFileSync(responseFile, JSON.stringify(response));
  fs.writeFileSync(bindingFile, JSON.stringify({ signing_cert_sha256: digestA, boot_generation: digestB }));
  const readyLine = [
    "I/SlockQaBootstrap: QA_BOOTSTRAP_READY",
    `envelopeDigest=${response.envelope_digest}`,
    "runCaseId=case-1", "attempt=2", `ciJobRef=${expected.ciJobRef}`,
    "principalId=user-1", "serverId=server-1", "sessionGeneration=session-1",
    "route=compose_slock_app_shell", "applicationId=build.raft.app.alpha",
    `bootGeneration=${digestB}`, "pid=42", "processInstanceId=process-1", "processGeneration=100",
    "deviceReadyElapsedRealtimeNanos=200", `readyReceiptDigest=${response.envelope_digest}`,
    `runtimeEnvelopeDigest=${digestA}`, `routeReadyProofDigest=${digestB}`,
  ].join(" ");
  writeExecutable(path.join(bin, "adb"), [
    "case \"$*\" in",
    "  'shell exit 7') exit 7 ;;",
    "  'shell -T run-as '*'qa-mark.txt; echo WROTE'*) cat > /dev/null; echo WROTE; exit 0 ;;",
    "  'shell -T run-as '*'cat > \"$tmp\"'*)",
    "    [ \"$#\" -eq 3 ] || exit 8",
    "    printf '%s\\n' \"$*\" > \"$CAPTURED_ARGS\"",
    "    cat > \"$CAPTURED_BYTES\"",
    "    printf 'INSTALL_OK %s\\n' \"$(sha256sum \"$CAPTURED_BYTES\" | cut -d' ' -f1)\"",
    "    exit 0 ;;",
    "  'shell -T run-as '*'stat -c %a'*)",
    "    [ \"$#\" -eq 3 ] || exit 8",
    "    printf '%s\\n' \"$*\" > \"$CAPTURED_VERIFY_ARGS\"",
    "    case \"${FAKE_MODE:-}\" in",
    "      verify_fail) printf 'VERIFY_FAIL size sha\\n' ;;",
    "      verify_silent) : ;;",
    "      *) printf 'VERIFY_OK\\n' ;;",
    "    esac",
    "    exit 0 ;;",
    "  'shell run-as '*'sha256sum'*) if [ \"${FAKE_MODE:-}\" = install_bad_sha ]; then printf 'x' | sha256sum | cut -d' ' -f1; else sha256sum \"$CAPTURED_BYTES\" | cut -d' ' -f1; fi; exit 0 ;;",
    "  'logcat -c') exit 0 ;;",
    "  'shell am start -W '*) exit 0 ;;",
    "  'logcat -d -v brief SlockQaBootstrap:I *:S') printf '%s\\n' \"$READY_LINE\"; exit 0 ;;",
    "esac",
    "exit 9",
  ].join("\n"));
  const result = spawnSync(process.execPath, [
    path.resolve("scripts/qa/mobile_login_bootstrap.mjs"), "prepare",
    "--response", responseFile, "--binding", bindingFile,
    "--run-case-id", "case-1", "--ci-job-ref", expected.ciJobRef,
    "--ready-output", readyFile, "--timeout-seconds", "1",
  ], {
    cwd: path.resolve("."), encoding: "utf8",
    env: {
      ...process.env,
      PATH: `${bin}:${process.env.PATH ?? ""}`,
      CAPTURED_ARGS: capturedArgs,
      CAPTURED_BYTES: capturedBytes,
      CAPTURED_VERIFY_ARGS: capturedVerifyArgs,
      READY_LINE: readyLine,
    },
  });
  try {
    assert.equal(result.status, 0, result.stderr);
    assert.equal(fs.readFileSync(capturedBytes, "utf8"), canonicalJson(response.envelope));
    const args = fs.readFileSync(capturedArgs, "utf8");
    assert.match(args, /^shell -T run-as 'build\.raft\.app\.alpha' sh -c '/);
    assert.match(args, /cat > "\$tmp"/);
    assert.match(args, /files\/qa-bootstrap\/inbox\/request-1\.session\.tmp/);
    assert.doesNotMatch(args, /sdcard|storage\/emulated|data\/local\/tmp/i);
    const verifyArgs = fs.readFileSync(capturedVerifyArgs, "utf8");
    assert.match(verifyArgs, /^shell -T run-as 'build\.raft\.app\.alpha' sh -c '/);
    assert.match(verifyArgs, /stat -c %a/);
    assert.match(verifyArgs, /wc -c/);
    assert.match(verifyArgs, /sha256sum/);
    assert.equal(fs.statSync(readyFile).mode & 0o777, 0o600);
    assert.equal(fs.readFileSync(readyFile, "utf8").includes("access-secret"), false);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("install/verify sentinels fail closed: wrong install digest, VERIFY_FAIL, and a silent verify each abort prepare by name", () => {
  // 2026-09-12: adb exec-in/exec-out never return the remote exit status, so a
  // gate that is only ever exercised in the passing direction proves nothing
  // (the previous set -e verify passed unconditionally for seven weeks). Each
  // case reuses the same fake adb with FAKE_MODE and must exit non-zero with
  // the matching branch named in stderr.
  const cases = [
    { mode: "install_bad_sha", expect: /shell sha readback mismatch/ },
    { mode: "verify_fail", expect: /verify failed: VERIFY_FAIL size sha/ },
    { mode: "verify_silent", expect: /did not run to its end/ },
  ];
  for (const { mode, expect } of cases) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), `qa-bootstrap-neg-${mode}-`));
    const bin = path.join(root, "bin");
    fs.mkdirSync(bin, { recursive: true });
    const responseFile = path.join(root, "response.json");
    const bindingFile = path.join(root, "binding.json");
    const readyFile = path.join(root, "ready.json");
    const response = loginResponse();
    fs.writeFileSync(responseFile, JSON.stringify(response));
    fs.writeFileSync(bindingFile, JSON.stringify({ signing_cert_sha256: digestA, boot_generation: digestB }));
    writeExecutable(path.join(bin, "adb"), [
      "case \"$*\" in",
      "  'shell exit 7') exit 7 ;;",
      "  'shell -T run-as '*'qa-mark.txt; echo WROTE'*) cat > /dev/null; echo WROTE; exit 0 ;;",
      "  'shell -T run-as '*'cat > \"$tmp\"'*) cat > /dev/null; printf 'INSTALL_OK %s\\n' \"$EXPECTED_SHA\"; exit 0 ;;",
      "  'shell -T run-as '*'stat -c %a'*)",
      "    case \"${FAKE_MODE:-}\" in",
      "      verify_fail) printf 'VERIFY_FAIL size sha\\n' ;;",
      "      verify_silent) : ;;",
      "      *) printf 'VERIFY_OK\\n' ;;",
      "    esac",
      "    exit 0 ;;",
      "  'shell run-as '*'sha256sum'*) if [ \"${FAKE_MODE:-}\" = install_bad_sha ]; then printf 'x' | sha256sum | cut -d' ' -f1; else printf '%s\\n' \"$EXPECTED_SHA\"; fi; exit 0 ;;",
      "  'logcat -c') exit 0 ;;",
      "esac",
      "exit 0",
    ].join("\n"));
    const expectedSha = createHash("sha256").update(canonicalJson(response.envelope)).digest("hex");
    const result = spawnSync(process.execPath, [
      path.resolve("scripts/qa/mobile_login_bootstrap.mjs"), "prepare",
      "--response", responseFile, "--binding", bindingFile,
      "--run-case-id", "case-1", "--ci-job-ref", expected.ciJobRef,
      "--ready-output", readyFile, "--timeout-seconds", "1",
    ], { cwd: path.resolve("."), encoding: "utf8", env: { ...process.env, PATH: `${bin}:${process.env.PATH ?? ""}`, FAKE_MODE: mode, EXPECTED_SHA: expectedSha } });
    try {
      assert.notEqual(result.status, 0, `${mode}: prepare must fail`);
      assert.match(result.stderr, expect, `${mode}: stderr must name the branch; got ${result.stderr.slice(0, 300)}`);
      assert.equal(fs.existsSync(readyFile), false, `${mode}: no READY file may be written`);
    } finally {
      fs.rmSync(root, { recursive: true, force: true });
    }
  }
});

const digestA = `sha256:${"a".repeat(64)}`;
const digestB = `sha256:${"b".repeat(64)}`;
const rotatedLegacyDigest = "sha256:b4919381d8f9cede4f8eb8f6d132f6dfe0d6d15bf6d1f63057d5eba045161a45";
const rotatedCurrentDigest = "sha256:5f08bbe82a27ccdad26c13523a60b5670d3aa7633e8ed2fd71fa6b5751f9b0b1";

const rotatedSignerOutput = [
  `V3.1 Signer: (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: ${rotatedCurrentDigest.slice(7)}`,
  `V3.0 Signer: (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: ${rotatedLegacyDigest.slice(7)}`,
].join("\n");

function writeExecutable(file, body) {
  fs.writeFileSync(file, `#!/bin/sh\nset -eu\n${body}\n`);
  fs.chmodSync(file, 0o755);
}

function runBindingWithBuildTools37(deviceSdk, signerOutput) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "qa-bootstrap-binding-"));
  const buildTools = path.join(root, "android-sdk", "build-tools", "37.0.0");
  const bin = path.join(root, "bin");
  fs.mkdirSync(buildTools, { recursive: true });
  fs.mkdirSync(bin, { recursive: true });
  const apk = path.join(root, "app.apk");
  const output = path.join(root, "binding.json");
  fs.writeFileSync(apk, "fixture");
  writeExecutable(
    path.join(buildTools, "aapt"),
    "printf \"package: name='build.raft.app' versionCode='1' versionName='1.0'\\napplication-debuggable\\n\"",
  );
  writeExecutable(
    path.join(buildTools, "apksigner"),
    [
      `test \"$*\" = \"verify --min-sdk-version ${deviceSdk} --max-sdk-version ${deviceSdk} --print-certs ${apk}\"`,
      `printf '%b\\n' ${JSON.stringify(signerOutput)}`,
    ].join("\n"),
  );
  writeExecutable(
    path.join(bin, "adb"),
    [
      `if [ \"$*\" = \"shell getprop ro.build.version.sdk\" ]; then printf '%s\\n' ${deviceSdk}; exit 0; fi`,
      "if [ \"$*\" = \"shell cat /proc/sys/kernel/random/boot_id\" ]; then printf '%s\\n' 12345678-1234-1234-1234-123456789abc; exit 0; fi",
      "exit 9",
    ].join("\n"),
  );
  const result = spawnSync(
    process.execPath,
    [
      path.resolve("scripts/qa/mobile_login_bootstrap.mjs"),
      "binding", "--apk", apk, "--output", output,
    ],
    {
      cwd: path.resolve("."),
      encoding: "utf8",
      env: {
        ...process.env,
        ANDROID_HOME: path.join(root, "android-sdk"),
        PATH: `${bin}:${process.env.PATH ?? ""}`,
      },
    },
  );
  try {
    assert.equal(result.status, 0, result.stderr);
    assert.equal(fs.statSync(output).mode & 0o777, 0o600);
    return JSON.parse(fs.readFileSync(output, "utf8"));
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

function loginResponse() {
  const envelope = {
    version: "qa-bootstrap-login/v1",
    run_case_id: "case-1",
    attempt: 2,
    ci_job_ref: "https://github.com/botiverse/mobile/actions/runs/1",
    principal_id: "user-1",
    server_id: "server-1",
    target_route: "compose_slock_app_shell",
    application_id: "build.raft.app.alpha",
    signing_cert_sha256: digestA,
    boot_generation: digestB,
    user: { id: "user-1", email: "qa@example.test" },
    access_token: "access-secret",
    refresh_token: "refresh-secret",
  };
  return { request_id: "request-1", envelope, envelope_digest: sha256Digest(Buffer.from(canonicalJson(envelope))), expires_at: "soon" };
}

const expected = {
  runCaseId: "case-1",
  ciJobRef: "https://github.com/botiverse/mobile/actions/runs/1",
  applicationId: "build.raft.app.alpha",
  signingCertSha256: digestA,
  bootGeneration: digestB,
};

test("APK signer selection matches the platform signer for each rotated SDK range", () => {
  assert.equal(selectApkSigningCertificateSha256(rotatedSignerOutput, 24), rotatedLegacyDigest);
  assert.equal(selectApkSigningCertificateSha256(rotatedSignerOutput, 32), rotatedLegacyDigest);
  assert.equal(selectApkSigningCertificateSha256(rotatedSignerOutput, 33), rotatedCurrentDigest);
  assert.equal(selectApkSigningCertificateSha256(rotatedSignerOutput, 36), rotatedCurrentDigest);
  assert.deepEqual(
    apksignerVerifyArgs("app.apk", 34),
    ["verify", "--min-sdk-version", "34", "--max-sdk-version", "34", "--print-certs", "app.apk"],
  );
  assert.equal(
    selectApkSigningCertificateSha256(
      `Signer #1 certificate SHA-256 digest: ${"a".repeat(64)}`,
      34,
    ),
    digestA,
  );
  assert.equal(
    selectApkSigningCertificateSha256(
      `V3.0 Signer: certificate SHA-256 digest: ${rotatedLegacyDigest.slice(7)}`,
      31,
    ),
    rotatedLegacyDigest,
  );
});

test("binding command matches build-tools 37 targeted signer output on API 31 and 34", () => {
  const api31 = runBindingWithBuildTools37(
    31,
    `V3.0 Signer: certificate SHA-256 digest: ${rotatedLegacyDigest.slice(7)}`,
  );
  assert.equal(api31.signing_cert_sha256, rotatedLegacyDigest);

  const api34 = runBindingWithBuildTools37(34, rotatedSignerOutput);
  assert.equal(api34.signing_cert_sha256, rotatedCurrentDigest);
});

test("APK signer selection fails closed for no match, overlap, and extra signers", () => {
  assert.throws(
    () => selectApkSigningCertificateSha256(rotatedSignerOutput, 23),
    /exactly one signing certificate for the Android device SDK/,
  );
  assert.throws(
    () => selectApkSigningCertificateSha256([
      rotatedSignerOutput,
      `Signer (minSdkVersion=30, maxSdkVersion=40) certificate SHA-256 digest: ${"c".repeat(64)}`,
    ].join("\n"), 34),
    /exactly one signing certificate for the Android device SDK/,
  );
  assert.throws(
    () => selectApkSigningCertificateSha256([
      `Signer #1 certificate SHA-256 digest: ${"a".repeat(64)}`,
      `Signer #2 certificate SHA-256 digest: ${"b".repeat(64)}`,
    ].join("\n"), 34),
    /exactly one signing certificate for the Android device SDK/,
  );
  assert.throws(
    () => selectApkSigningCertificateSha256([
      `V3.1 Signer: (minSdkVersion=33) certificate SHA-256 digest: ${"a".repeat(64)}`,
      `Signer #1 certificate SHA-256 digest: ${"b".repeat(64)}`,
    ].join("\n"), 34),
    /signer certificate record is invalid/,
  );
  assert.throws(
    () => selectApkSigningCertificateSha256([
      `Signer certificate SHA-256 digest: ${"a".repeat(63)}`,
      `Signer #1 certificate SHA-256 digest: ${"b".repeat(64)}`,
    ].join("\n"), 34),
    /signer certificate record is invalid/,
  );
  assert.throws(
    () => selectApkSigningCertificateSha256([
      `V3.1 Signer certificate SHA-256 digest: ${"a".repeat(63)}`,
      `V3.0 Signer: certificate SHA-256 digest: ${"b".repeat(64)}`,
    ].join("\n"), 34),
    /signer certificate record is invalid/,
  );
});

test("canonical login envelope validates exact keys, digest and frozen binding", () => {
  const result = validateLoginResponse(loginResponse(), expected);
  assert.equal(result.envelope.access_token, "access-secret");
  result.bytes.fill(0);
  assert.throws(() => validateLoginResponse({ ...loginResponse(), envelope_digest: digestA }, expected), /digest mismatch/);
  assert.throws(() => validateLoginResponse({ ...loginResponse(), envelope: { ...loginResponse().envelope, extra: true } }, expected), /keys/);
  assert.throws(
    () => validateLoginResponse(loginResponse(), { ...expected, signingCertSha256: digestB }),
    /binding is invalid/,
  );
});

test("READY parser rejects another attempt and emits token-free Stamp payload", () => {
  const line = [
    "I/SlockQaBootstrap: QA_BOOTSTRAP_READY",
    `envelopeDigest=${loginResponse().envelope_digest}`,
    "runCaseId=case-1", "attempt=2", `ciJobRef=${expected.ciJobRef}`,
    "principalId=user-1", "serverId=server-1", "sessionGeneration=session-1",
    "route=compose_slock_app_shell", "applicationId=build.raft.app.alpha",
    `bootGeneration=${digestB}`, "pid=42", "processInstanceId=process-1", "processGeneration=100",
    "deviceReadyElapsedRealtimeNanos=200", `readyReceiptDigest=${loginResponse().envelope_digest}`,
    `runtimeEnvelopeDigest=${digestA}`, `routeReadyProofDigest=${digestB}`,
  ].join(" ");
  const login = validateLoginResponse(loginResponse(), expected);
  login.bytes.fill(0);
  const ready = parseReadyLine(line, {
    envelopeDigest: login.envelopeDigest, runCaseId: "case-1", attempt: 2, ciJobRef: expected.ciJobRef,
    principalId: "user-1", serverId: "server-1", targetRoute: "compose_slock_app_shell",
    applicationId: "build.raft.app.alpha", bootGeneration: digestB,
  });
  assert.ok(ready);
  const payload = readyPayload(login, ready);
  assert.equal(payload.runtime_envelope.pid, 42);
  assert.equal(JSON.stringify(payload).includes("access-secret"), false);
  assert.equal(parseReadyLine(line.replace("attempt=2", "attempt=3"), { ...expected, envelopeDigest: login.envelopeDigest, attempt: 2 }), null);
});

test("bootstrap diagnostics expose only fixed stage and code tokens", () => {
  const text = [
    "I/SlockQaBootstrap: QA_BOOTSTRAP_STAGE stage=inbox_consumed",
    "I/SlockQaBootstrap: QA_BOOTSTRAP_FAILURE stage=login_consume code=network access_token=secret",
    "I/SlockQaBootstrap: QA_BOOTSTRAP_STAGE stage=inbox_consumed request_id=private",
    "I/SlockQaBootstrap: QA_BOOTSTRAP_READY envelopeDigest=sha256:secret",
  ].join("\n");
  assert.deepEqual(parseBootstrapDiagnostics(text), [
    "QA_BOOTSTRAP_STAGE stage=inbox_consumed",
    "QA_BOOTSTRAP_FAILURE stage=login_consume code=network",
  ]);
  const rendered = parseBootstrapDiagnostics(text).join(",");
  assert.doesNotMatch(rendered, /secret|access_token|request_id|envelopeDigest/);
});
