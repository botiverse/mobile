import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import test from "node:test";
import {
  PROVIDER_CLASS,
  UNSUPPORTED_MESSAGE,
  UnsupportedBuildError,
  apkSignerForDevice,
  apksignerDeviceArgs,
  componentFor,
  contentCallCommand,
  createIdentityReceiptRunner,
  crossCheckReceipt,
  disableFailureAnnotation,
  newReceiptRequest,
  parseContentCallOutput,
  parseReceipt,
  procStartTicks,
  providerDeclared,
  readPairingSaltFile,
  serverRef,
  splitPrivateReceipt,
  userRef,
  writePrivateReceipt,
} from "./app_identity_receipt_lib.mjs";
import os_ from "node:os";
import path from "node:path";
import { runOutcome } from "./release_auto_login_lib.mjs";

// Shared with AppIdentityReceiptVectorTest.kt (fake values only).
const vector = JSON.parse(fs.readFileSync(new URL("./fixtures/app-identity-receipt-vector.json", import.meta.url), "utf8"));
const { fixture, sample_receipt: sample } = vector;
const PKG = "build.raft.app";
const request = { attemptId: sample.attempt_id, nonce: sample.nonce, requestId: sample.request_id };
const sampleJson = JSON.stringify(sample);

test("refs match the shared vector and every equality control differs", () => {
  assert.equal(userRef(fixture.nonce, fixture.subject), fixture.user_ref);
  assert.equal(serverRef(fixture.nonce, fixture.server_id, fixture.server_slug), fixture.server_ref);
  assert.equal(vector.controls.length, 6);
  for (const c of vector.controls) {
    const ref = c.ref === "user" ? userRef(c.nonce, c.subject) : serverRef(c.nonce, c.server_id, c.server_slug);
    assert.notEqual(ref, c.ref === "user" ? fixture.user_ref : fixture.server_ref, c.name);
  }
  // Fake signer of the design sample: sha256 of the fake DER bytes.
  assert.equal(crypto.createHash("sha256").update("FAKE-CERT-DER-NOT-A-REAL-CERT").digest("hex"), sample.signer_sha256);
});

test("the design sample parses as a v1 receipt; anything outside the contract is refused", () => {
  assert.deepEqual(parseReceipt(sampleJson), sample);
  assert.throws(() => parseReceipt(JSON.stringify({ ...sample, email: "x" })), /keys/);
  const { server_ref, ...missing } = sample;
  assert.throws(() => parseReceipt(JSON.stringify(missing)), /keys/);
  assert.throws(() => parseReceipt(JSON.stringify({ ...sample, pid: { nested: 1 } })), /flat/);
  assert.throws(() => parseReceipt(JSON.stringify({ ...sample, contract: "raft-qa-release-readiness/v2" })), /contract/);
  assert.throws(() => parseReceipt(JSON.stringify({ ...sample, version_name: "x".repeat(1100) })), /1024/);
});

const os = {
  packageName: PKG,
  versionCode: "12600",
  signerSha256: sample.signer_sha256,
  debuggable: false,
  pidBefore: "4242",
  pidAfter: "4242",
  startTicksBefore: "987654",
  startTicksAfter: "987654",
};
const expected = { userRef: fixture.user_ref, serverRef: fixture.server_ref };

test("cross-check is ok only when the receipt, the OS and the frozen refs all agree", () => {
  assert.deepEqual(crossCheckReceipt({ receipt: sample, request, os, expected }), { verdict: "ok", problems: [], pending: [] });
  const cases = [
    [{ receipt: { ...sample, user_ref: userRef(fixture.nonce, "qa-user-2") } }, /user_ref differs/],
    [{ receipt: { ...sample, server_ref: serverRef(fixture.nonce, "server-1", "") } }, /server_ref differs/],
    [{ receipt: { ...sample, server_ref: null } }, /no user_ref\/server_ref/],
    [{ receipt: { ...sample, session_origin: "qa_bootstrap" } }, /origin/],
    [{ receipt: { ...sample, debuggable: true } }, /debuggable/],
    [{ os: { ...os, debuggable: true } }, /debuggable/],
    [{ os: { ...os, signerSha256: "0".repeat(64) } }, /signer_sha256 differs/],
    [{ receipt: { ...sample, signer_sha256: null } }, /no single signer/],
    [{ os: { ...os, packageName: "build.raft.app.alpha" } }, /package differs/],
    [{ os: { ...os, versionCode: "12600001" } }, /version_code/],
    [{ os: { ...os, pidAfter: "4243" } }, /pid changed/],
    [{ os: { ...os, startTicksAfter: "1" } }, /start time/],
    [{ receipt: { ...sample, request_id: "ab".repeat(16) } }, /echo/],
    [{ receipt: { ...sample, signed_in: false } }, /not signed in/],
  ];
  for (const [override, problem] of cases) {
    const result = crossCheckReceipt({ receipt: sample, request, os, expected, ...override });
    assert.equal(result.verdict, "unknown", String(problem));
    assert.ok(result.problems.some((p) => problem.test(p)), `${problem}: ${result.problems}`);
  }
});

test("without frozen refs everything else is checked and the refs are left to Testbed", () => {
  const result = crossCheckReceipt({ receipt: sample, request, os, expected: {} });
  assert.equal(result.verdict, "pending_testbed");
  assert.deepEqual(result.problems, []);
  assert.match(result.pending[0], /Testbed compares/);
  assert.equal(crossCheckReceipt({ receipt: { ...sample, pid: 1 }, request, os, expected: {} }).verdict, "unknown");
});

// Signer fixtures: verified v3/v3.1 blocks per SDK range, plus apksigner's summary line, which
// must never decide (it is the 2nd certificate of a 3-generation rotation).
const certA = "a1".repeat(32);
const certB = "b2".repeat(32);
const certC = "c3".repeat(32);
const lines = (...l) => l.join("\n");
const twoCertRotation = lines(
  `Signer #1 certificate SHA-256 digest: ${certA}`,
  `V3.1 Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: ${certB}`,
  `V3 Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: ${certA}`,
);
const threeCertRotation = lines(
  `Signer #1 certificate SHA-256 digest: ${certB}`,
  `V3.1 Signer (minSdkVersion=35, maxSdkVersion=2147483647) certificate SHA-256 digest: ${certC}`,
  `V3 Signer (minSdkVersion=24, maxSdkVersion=34) certificate SHA-256 digest: ${certB}`,
);
// rotation-min-sdk-version=35 + the dev-release flag: the v3.0 block reaches up to 35.
const devReleaseRotation = lines(
  `Signer #1 certificate SHA-256 digest: ${certA}`,
  `V3.1 Signer (minSdkVersion=35, maxSdkVersion=2147483647, targetsDevRelease=true) certificate SHA-256 digest: ${certB}`,
  `V3 Signer (minSdkVersion=24, maxSdkVersion=35) certificate SHA-256 digest: ${certA}`,
);

test("2-cert rotation: API 34/35/36 use the rotated certificate, API 30 the original", () => {
  for (const sdk of [34, 35, 36]) assert.equal(apkSignerForDevice(twoCertRotation, { sdk }), certB, `API ${sdk}`);
  assert.equal(apkSignerForDevice(twoCertRotation, { sdk: 30 }), certA);
});

test("3-cert rotation: API 35/36 use the 3rd certificate (not apksigner's summary), API 34 the 2nd", () => {
  assert.equal(apkSignerForDevice(threeCertRotation, { sdk: 34 }), certB);
  assert.equal(apkSignerForDevice(threeCertRotation, { sdk: 35 }), certC);
  assert.equal(apkSignerForDevice(threeCertRotation, { sdk: 36 }), certC);
});

test("dev-release flag moves only the lower bound: release API 35 keeps the old certificate", () => {
  assert.equal(apkSignerForDevice(devReleaseRotation, { sdk: 35 }), certA);
  assert.equal(apkSignerForDevice(devReleaseRotation, { sdk: 36 }), certB);
  assert.equal(apkSignerForDevice(devReleaseRotation, { sdk: 34 }), certA);
  // A preview device on SDK 35 (the codename build of 36) already takes the new certificate.
  assert.equal(apkSignerForDevice(devReleaseRotation, { sdk: 35, preview: true }), certB);
});

test("the summary certificate is used only without any v3 block; ambiguity is an error", () => {
  assert.equal(apkSignerForDevice(`Signer #1 certificate SHA-256 digest: ${certA}`, { sdk: 34 }), certA);
  assert.throws(() => apkSignerForDevice(lines(`Signer #1 certificate SHA-256 digest: ${certA}`, `Signer #2 certificate SHA-256 digest: ${certB}`), { sdk: 34 }), /2 summary/);
  assert.throws(() => apkSignerForDevice(`V3 Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: ${certA}`, { sdk: 34 }), /0 certificates/);
  assert.deepEqual(apksignerDeviceArgs("app.apk", 35), ["verify", "-v", "--min-sdk-version", "35", "--max-sdk-version", "35", "--print-certs", "app.apk"]);
  // The receipt (apkContentsSigners on the device) is compared with that selection.
  const deviceCert = apkSignerForDevice(threeCertRotation, { sdk: 36 });
  assert.equal(crossCheckReceipt({ receipt: { ...sample, signer_sha256: certC }, request, os: { ...os, signerSha256: deviceCert }, expected }).verdict, "ok");
  assert.equal(crossCheckReceipt({ receipt: { ...sample, signer_sha256: certB }, request, os: { ...os, signerSha256: deviceCert }, expected }).verdict, "unknown");
});

test("request needs Testbed's attempt id and nonce; the request id is fresh each time", () => {
  const a = newReceiptRequest({ attemptId: sample.attempt_id, nonce: sample.nonce });
  const b = newReceiptRequest({ attemptId: sample.attempt_id, nonce: sample.nonce });
  assert.match(a.requestId, /^[0-9a-f]{32}$/);
  assert.notEqual(a.requestId, b.requestId);
  assert.throws(() => newReceiptRequest({ attemptId: "run-1", nonce: sample.nonce }), /attempt id/);
  assert.throws(() => newReceiptRequest({ attemptId: sample.attempt_id, nonce: "AB".repeat(32) }), /nonce/);
  assert.throws(() => newReceiptRequest({ attemptId: sample.attempt_id }), /nonce/);
  assert.equal(
    contentCallCommand(PKG, request),
    `content call --uri content://build.raft.app.identity-receipt --method receipt_v1 --extra attempt_id:s:${request.attemptId} --extra nonce:s:${request.nonce} --extra request_id:s:${request.requestId}`,
  );
});

test("content call output: receipt, refusal code, or no answer", () => {
  assert.deepEqual(parseContentCallOutput(`Result: Bundle[{receipt=${sampleJson}}]\n`), { receipt: sampleJson });
  assert.deepEqual(parseContentCallOutput("Result: Bundle[{error=caller_not_allowed}]"), { error: "caller_not_allowed" });
  assert.equal(parseContentCallOutput("Error while accessing provider:build.raft.app.identity-receipt\njava.lang.SecurityException").error, "no_answer");
  assert.equal(parseContentCallOutput("Result: null").error, "no_answer");
  assert.equal(procStartTicks("4242 (build.raft) app) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 987654 20 21"), "987654");
});

// A fake `adb shell`: records every command, answers from a table.
function fakeDevice({ hasProvider = true, enableOutput, disableOutput, disableStatus = 0 } = {}) {
  const commands = [];
  const providerDump = hasProvider
    ? `  ContentProvider Authorities:\n    [build.raft.app.identity-receipt]:\n      Provider{1 ${PKG}/.core.data.auth.AppIdentityReceiptProvider}\n`
    : "";
  const shell = (command) => {
    commands.push(command);
    if (command === `dumpsys package ${PKG}`) return { status: 0, stdout: `Packages:\n  Package [${PKG}]\n    versionCode=12600002 minSdk=29\n${providerDump}`, stderr: "" };
    if (command.startsWith("pm enable ")) {
      return enableOutput ?? (hasProvider
        ? { status: 0, stdout: `Component {${componentFor(PKG)}} new state: enabled\n`, stderr: "" }
        : { status: 255, stdout: "", stderr: `Error: java.lang.IllegalArgumentException: Component class ${PROVIDER_CLASS} does not exist in ${PKG}` });
    }
    if (command.startsWith("pm disable ")) return { status: disableStatus, stdout: disableOutput ?? `Component {${componentFor(PKG)}} new state: disabled\n`, stderr: "" };
    if (command === `pidof ${PKG}`) return { status: 0, stdout: "4242\n", stderr: "" };
    if (command === "cat /proc/4242/stat") return { status: 0, stdout: "4242 (build.raft.app) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 987654 20\n", stderr: "" };
    if (command.startsWith("content call ")) return { status: 0, stdout: `Result: Bundle[{receipt=${sampleJson}}]\n`, stderr: "" };
    return { status: 1, stdout: "", stderr: `unexpected ${command}` };
  };
  return { shell, commands };
}

test("order: probe, enable before sign-in, call after it, disable last", () => {
  const device = fakeDevice();
  const runner = createIdentityReceiptRunner({ shell: device.shell, pkg: PKG });
  runner.probe();
  runner.enable();
  runner.markSignInStarted();
  const answer = runner.call(request);
  assert.deepEqual(answer.receipt, sample);
  assert.deepEqual(answer.os, { pidBefore: "4242", pidAfter: "4242", startTicksBefore: "987654", startTicksAfter: "987654" });
  assert.deepEqual(runner.disable(), { ok: true, status: "disabled" });
  const kinds = device.commands.map((c) => c.split(" ").slice(0, 2).join(" "));
  assert.deepEqual(kinds, ["dumpsys package", "pm enable", "pidof build.raft.app", "cat /proc/4242/stat", "content call", "pidof build.raft.app", "cat /proc/4242/stat", "pm disable"]);
  assert.ok(device.commands.includes(`pm enable ${PKG}/${PROVIDER_CLASS}`));
  assert.ok(device.commands.includes(`pm disable ${PKG}/${PROVIDER_CLASS}`));
});

test("enabling after sign-in started, or without the probe, is refused (it would kill the signed-in app)", () => {
  const late = createIdentityReceiptRunner({ shell: fakeDevice().shell, pkg: PKG });
  late.probe();
  late.markSignInStarted();
  assert.throws(() => late.enable(), /before sign-in/);
  assert.throws(() => createIdentityReceiptRunner({ shell: fakeDevice().shell, pkg: PKG }).enable(), /probe/);
  const notEnabled = createIdentityReceiptRunner({ shell: fakeDevice().shell, pkg: PKG });
  assert.throws(() => notEnabled.call(request), /not enabled/);
  assert.deepEqual(notEnabled.disable(), { ok: true, status: "not_enabled" });
});

test("a build without the provider (1.26.x) fails clearly before any account is used, with no fallback", () => {
  const device = fakeDevice({ hasProvider: false });
  const runner = createIdentityReceiptRunner({ shell: device.shell, pkg: PKG });
  assert.throws(() => runner.probe(), (error) => error instanceof UnsupportedBuildError && error.message.startsWith(UNSUPPORTED_MESSAGE) && /versionCode=12600002/.test(error.message));
  assert.deepEqual(device.commands, [`dumpsys package ${PKG}`]);
  assert.equal(providerDeclared("", PKG), false);
  // Even if the listing were missed, pm enable on a missing component is the same verdict.
  const sneaky = createIdentityReceiptRunner({ shell: fakeDevice({ hasProvider: false }).shell, pkg: PKG });
  sneaky.state.probed = true;
  assert.throws(() => sneaky.enable(), (error) => error instanceof UnsupportedBuildError);
  // The runner wires this before reading credentials (source order).
  const source = fs.readFileSync(new URL("./release_auto_login.mjs", import.meta.url), "utf8");
  const main = source.slice(source.indexOf("async function main()"));
  assert.ok(main.indexOf("identity.probe()") < main.indexOf("account ??= credentials()"), "probe before the account");
  assert.ok(main.indexOf("identity.enable()") < main.indexOf("identity?.markSignInStarted()"), "enable before sign-in");
  assert.ok(main.indexOf("identity?.markSignInStarted()") < main.indexOf("launchApp()"), "sign-in starts after enable");
  assert.ok(main.indexOf("await cleanupDevice()") < main.indexOf("identity.disable()"), "disable last in cleanup");
  assert.match(main, /let account = IDENTITY_RECEIPT \? null : credentials\(\);/);
});

test("a failed or unconfirmed disable is loud: ::error:: annotation and a non-zero exit even without strict cleanup", () => {
  for (const options of [{ disableStatus: 255, disableOutput: "Error: java.lang.SecurityException: Shell cannot change component state" }, { disableOutput: "" }]) {
    const runner = createIdentityReceiptRunner({ shell: fakeDevice(options).shell, pkg: PKG });
    runner.probe();
    runner.enable();
    const result = runner.disable();
    assert.equal(result.ok, false);
    assert.equal(result.status, "failed");
    const annotation = disableFailureAnnotation(PKG, result.detail);
    assert.match(annotation, /^::error title=identity receipt provider left enabled::pm disable build\.raft\.app\/build\.raft\.app\.core\.data\.auth\.AppIdentityReceiptProvider failed/);
    assert.doesNotMatch(annotation, /\n/);
  }
  const clean = { capture_file: "deleted", browser: "restored", capture_browser: "uninstalled", web_session: "signed_out", app_session: "app_reported_server_logout_2xx" };
  for (const strict of [true, false]) {
    const outcome = runOutcome({ signedIn: true, cleanup: { ...clean, identity_receipt_provider: "failed" }, strict });
    assert.deepEqual([outcome.result, outcome.exitCode], ["cleanup_failed", 1]);
    assert.ok(outcome.cleanupFailures.some((f) => f.step === "identity_receipt_provider"));
  }
  assert.equal(runOutcome({ signedIn: true, cleanup: { ...clean, identity_receipt_provider: "disabled" }, strict: true }).exitCode, 0);
  assert.equal(runOutcome({ signedIn: true, cleanup: { ...clean, identity_receipt_provider: "not_enabled" }, strict: true }).exitCode, 0);
  // Runs without --identity-receipt have no such step and are unaffected.
  assert.equal(runOutcome({ signedIn: true, cleanup: clean, strict: true }).exitCode, 0);
});

// ---------- pairing salt (second shared vector) ----------
const pairing = JSON.parse(fs.readFileSync(new URL("./fixtures/app-identity-receipt-pairing-vector.json", import.meta.url), "utf8"));
const salt = pairing.pairing_salt;
const pairedRequest = { ...request, pairingSalt: salt };

test("pair refs reproduce the pairing vector: 806 bytes, stable across nonces, changed by the salt", () => {
  const { input } = pairing;
  assert.equal(userRef(salt, input.subject), pairing.receipt.user_pair_ref);
  assert.equal(serverRef(salt, input.server_id, input.server_slug), pairing.receipt.server_pair_ref);
  assert.equal(Buffer.byteLength(JSON.stringify(pairing.receipt)), pairing.extended_bytes);
  assert.equal(Buffer.byteLength(sampleJson), pairing.original_bytes);
  assert.deepEqual(parseReceipt(JSON.stringify(pairing.receipt), { paired: true }), pairing.receipt);
  assert.throws(() => parseReceipt(sampleJson, { paired: true }), /keys/);
  const other = pairing.different_attempt_same_secret_version;
  assert.equal(userRef(other.nonce, input.subject), other.user_ref);
  assert.equal(serverRef(other.nonce, input.server_id, input.server_slug), other.server_ref);
  assert.equal(other.user_pair_ref, pairing.receipt.user_pair_ref);
  assert.equal(other.server_pair_ref, pairing.receipt.server_pair_ref);
  const rotated = pairing.same_target_after_rotation;
  assert.equal(userRef(rotated.pairing_salt, input.subject), rotated.user_pair_ref);
  assert.equal(serverRef(rotated.pairing_salt, input.server_id, input.server_slug), rotated.server_pair_ref);
  assert.notEqual(rotated.user_pair_ref, pairing.receipt.user_pair_ref);
});

test("pairing salt: 64 hex, never the nonce, never quoted in an error", () => {
  assert.equal(newReceiptRequest({ attemptId: sample.attempt_id, nonce: sample.nonce, pairingSalt: salt }).pairingSalt, salt);
  for (const bad of [sample.nonce, "CD".repeat(32), "cd".repeat(31)]) {
    assert.throws(() => newReceiptRequest({ attemptId: sample.attempt_id, nonce: sample.nonce, pairingSalt: bad }), (e) => /pairing salt/.test(e.message) && !e.message.includes(bad));
  }
  assert.equal("pairingSalt" in newReceiptRequest({ attemptId: sample.attempt_id, nonce: sample.nonce }), false);
  const dir = fs.mkdtempSync(path.join(os_.tmpdir(), "pairing-salt-"));
  fs.writeFileSync(path.join(dir, "salt"), `${salt}\n`, { mode: 0o600 });
  assert.equal(readPairingSaltFile(path.join(dir, "salt")), salt);
  assert.equal(readPairingSaltFile(""), null);
  fs.rmSync(dir, { recursive: true });
});

test("missing pair refs (signed out / no selected server or slug) fail the identity check", () => {
  const paired = { ...pairing.receipt };
  assert.equal(crossCheckReceipt({ receipt: paired, request: pairedRequest, os, expected }).verdict, "ok");
  for (const key of ["user_pair_ref", "server_pair_ref"]) {
    const result = crossCheckReceipt({ receipt: { ...paired, [key]: null }, request: pairedRequest, os, expected });
    assert.equal(result.verdict, "unknown");
    assert.ok(result.problems.some((p) => /pair_ref/.test(p)));
  }
  const noServer = crossCheckReceipt({ receipt: { ...paired, server_ref: null, server_pair_ref: null }, request: pairedRequest, os, expected: {} });
  assert.equal(noServer.verdict, "unknown");
});

test("the salt goes over the private channel only; pair refs go only to the 0600 private file", () => {
  const argvCommands = [];
  const privateCommands = [];
  const device = fakeDevice();
  const pairedJson = JSON.stringify(pairing.receipt);
  const runner = createIdentityReceiptRunner({
    pkg: PKG,
    shell: (command) => { argvCommands.push(command); return device.shell(command); },
    shellPrivate: (command) => {
      privateCommands.push(command);
      return { status: 0, stdout: `Result: Bundle[{receipt=${pairedJson}}]\n`, stderr: "" };
    },
  });
  runner.probe();
  runner.enable();
  runner.markSignInStarted();
  const answer = runner.call(pairedRequest);
  runner.disable();
  assert.ok(argvCommands.every((c) => !c.includes(salt)), "salt never on an adb argv");
  assert.equal(privateCommands.length, 1);
  assert.match(privateCommands[0], new RegExp(`--extra pairing_salt:s:${salt}$`));
  assert.ok(!pairedJson.includes(salt), "the receipt does not echo the salt");

  const { shareable, private: secret } = splitPrivateReceipt(answer.receipt);
  assert.deepEqual(Object.keys(shareable), Object.keys(sample));
  assert.ok(!JSON.stringify(shareable).includes(pairing.receipt.user_pair_ref));
  assert.ok(!JSON.stringify(shareable).includes(pairing.receipt.server_pair_ref));
  assert.deepEqual(secret, { attempt_id: sample.attempt_id, request_id: sample.request_id, user_pair_ref: pairing.receipt.user_pair_ref, server_pair_ref: pairing.receipt.server_pair_ref });
  assert.equal(splitPrivateReceipt(sample).private, null);

  const dir = fs.mkdtempSync(path.join(os_.tmpdir(), "identity-private-"));
  const file = path.join(dir, "private.json");
  writePrivateReceipt(file, secret);
  assert.equal(fs.statSync(file).mode & 0o777, 0o600);
  assert.deepEqual(JSON.parse(fs.readFileSync(file, "utf8")), secret);
  fs.rmSync(dir, { recursive: true });

  // Without a salt the ordinary channel is used and no pair extra is sent.
  assert.ok(!contentCallCommand(PKG, request).includes("pairing_salt"));

  // The runner: summary gets the shareable part only, the private part goes to its own file,
  // both pair refs and the salt are redacted from every log/summary line, and the salt is read from a file.
  const source = fs.readFileSync(new URL("./release_auto_login.mjs", import.meta.url), "utf8");
  assert.match(source, /summary\.identity_receipt = \{[^}]*receipt: shareable/);
  assert.doesNotMatch(source, /summary\.identity_receipt = \{[^}]*receipt: answer\.receipt/);
  assert.match(source, /writePrivateReceipt\(process\.env\.QA_IDENTITY_PRIVATE_OUT, pair\)/);
  assert.match(source, /redact\.add\(pairingSalt\)/);
  assert.match(source, /redact\.add\(pair\.user_pair_ref/);
  assert.match(source, /redact\.add\(pair\.server_pair_ref/);
  assert.match(source, /readPairingSaltFile\(process\.env\.QA_PAIRING_SALT_FILE/);
  assert.match(source, /spawnSync\("adb", \["shell", "-T"\], \{[^}]*input: `\$\{command\}; exit\\n`/);
});
