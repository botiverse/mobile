import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import test from "node:test";
import {
  PROVIDER_CLASS,
  UNSUPPORTED_MESSAGE,
  UnsupportedBuildError,
  APKSIG_BUILD_TOOLS,
  MISMATCH_CATEGORIES,
  PUBLIC_IDENTITY_FIELDS,
  apkSignerInspectorArgs,
  componentFor,
  contentCallCommand,
  createIdentityReceiptRunner,
  mismatchCategories,
  probeCallCommand,
  providerOverride,
  providerStartState,
  PROVIDER_CLEANUP_STATUSES,
  crossCheckReceipt,
  disableFailureAnnotation,
  newReceiptRequest,
  parseContentCallOutput,
  parseApkSignerInspector,
  parseReceipt,
  privateOutPath,
  procStartTicks,
  publicIdentitySummary,
  providerDeclared,
  afterSignIn,
  createCaptureGate,
  guardedCapture,
  parseTestbedDecision,
  readPairingSaltFile,
  runIdentityGate,
  serverRef,
  userRef,
  verificationStatus,
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
  deviceSdk: 35,
  deviceCodename: "REL",
  devicePreview: false,
  installedApkSha256: "d4".repeat(32),
  splitApks: 0,
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
    // An unread pkgFlags is unknown, never "not debuggable".
    [{ os: { ...os, debuggable: null } }, /debuggable flag could not be read/],
    [{ os: { ...os, deviceCodename: "Baklava", devicePreview: true } }, /released SDK/],
    [{ os: { ...os, deviceCodename: null, devicePreview: null } }, /released SDK/],
    [{ os: { ...os, splitApks: 1 } }, /single APK/],
    [{ os: { ...os, splitApks: null } }, /single APK/],
    [{ os: { ...os, installedApkSha256: null } }, /APK digest/],
    [{ os: { ...os, signerSha256: null } }, /signer_sha256 differs/],
    [{ os: { ...os, signerSha256: "0".repeat(64) } }, /signer_sha256 differs/],
    [{ receipt: { ...sample, signer_sha256: null } }, /no single signer/],
    [{ os: { ...os, packageName: "build.raft.app.alpha" } }, /package differs/],
    [{ os: { ...os, versionCode: "12600001" } }, /version_code/],
    // Testbed's observation takes the original digits as a string; a number is refused.
    [{ os: { ...os, versionCode: 12600 } }, /version_code/],
    [{ os: { ...os, versionCode: "012600" } }, /version_code/],
    [{ os: { ...os, versionCode: null } }, /version_code/],
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

// The signer comes from AppIdentityApkSigner.java (apksig's structured result; real signed
// fixtures in test_app_identity_apk_signer.sh). Only its exact success line for the device SDK
// counts; anything else is unverified.
const certB = "b2".repeat(32);
test("the signer inspector's answer counts only as its exact success line for this SDK", () => {
  assert.equal(APKSIG_BUILD_TOOLS, "36.0.0");
  assert.deepEqual(apkSignerInspectorArgs({ apksignerJar: "/sdk/build-tools/36.0.0/lib/apksigner.jar", script: "AppIdentityApkSigner.java", apk: "base.apk", sdk: 35 }),
    ["-cp", "/sdk/build-tools/36.0.0/lib/apksigner.jar", "AppIdentityApkSigner.java", "base.apk", "35"]);
  assert.throws(() => apkSignerInspectorArgs({ apksignerJar: "j", script: "s", apk: "a", sdk: null }), /SDK/);
  const ok = `{"result":"SIGNER_SELECTED","android_sdk":35,"signer_sha256":"${certB}"}\n`;
  assert.equal(parseApkSignerInspector(ok, { status: 0, sdk: 35 }), certB);
  for (const [output, status, sdk] of [
    [ok, 1, 35],
    [ok, 0, 36],
    ['{"result":"APK_SIGNER_UNVERIFIED"}', 1, 35],
    [`Signer (minSdkVersion=35 (dev release=true), maxSdkVersion=2147483647) certificate SHA-256 digest: ${certB}`, 0, 35],
    ["", 0, 35],
  ]) {
    assert.throws(() => parseApkSignerInspector(output, { status, sdk }), /^Error: APK_SIGNER_UNVERIFIED$/);
  }
  const source = fs.readFileSync(new URL("./release_auto_login.mjs", import.meta.url), "utf8");
  assert.doesNotMatch(source, /apksigner"\), \[?"verify"|buildTool\("apksigner"\)/, "no apksigner text parsing in the runner");
  assert.match(source, /"AppIdentityApkSigner\.java"/);
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

// A fake `adb shell`: records every command, answers from a table. `shipsEnabled`: a new package
// (manifest enabled="true", signed: the shell may not change component state). Otherwise an older
// package (manifest enabled="false"), with `override` = a left-over `pm enable` / `pm disable`.
const SHELL_DENIED = `Exception occurred while executing 'enable':\njava.lang.SecurityException: Shell cannot change component state for ComponentInfo{${PKG}/${PROVIDER_CLASS}}`;
function fakeDevice({ hasProvider = true, shipsEnabled = false, override = null, probeOutput, enableOutput, disableOutput, disableStatus = 0 } = {}) {
  const commands = [];
  const providerDump = hasProvider
    ? `  ContentProvider Authorities:\n    [build.raft.app.identity-receipt]:\n      Provider{1 ${PKG}/.core.data.auth.AppIdentityReceiptProvider}\n`
    : "";
  const userState = `    User 0: ceDataInode=1 installed=true hidden=false suspended=false stopped=false enabled=0 instant=false\n      runtime permissions:\n${override ? `      ${override}Components:\n        ${PROVIDER_CLASS}\n` : ""}`;
  let enabled = hasProvider && (shipsEnabled || override === "enabled");
  const shell = (command) => {
    commands.push(command);
    if (command === `dumpsys package ${PKG}`) return { status: 0, stdout: `${providerDump}Packages:\n  Package [${PKG}]\n    versionCode=12600002 minSdk=29\n${userState}`, stderr: "" };
    if (command === probeCallCommand(PKG)) {
      if (probeOutput !== undefined) return { status: 0, stdout: probeOutput, stderr: "" };
      return enabled
        ? { status: 0, stdout: "Result: Bundle[{error=unknown_method}]\n", stderr: "" }
        : { status: 0, stdout: "", stderr: `Error while accessing provider:${PKG}.identity-receipt\njava.lang.IllegalStateException: Could not find provider: ${PKG}.identity-receipt\n` };
    }
    if (command.startsWith("pm enable ")) {
      if (enableOutput) return enableOutput;
      if (!hasProvider) return { status: 255, stdout: "", stderr: `Error: java.lang.IllegalArgumentException: Component class ${PROVIDER_CLASS} does not exist in ${PKG}` };
      if (shipsEnabled) return { status: 255, stdout: "", stderr: SHELL_DENIED };
      enabled = true;
      return { status: 0, stdout: `Component {${componentFor(PKG)}} new state: enabled\n`, stderr: "" };
    }
    if (command.startsWith("pm disable ")) {
      if (shipsEnabled) return { status: 255, stdout: "", stderr: SHELL_DENIED };
      return { status: disableStatus, stdout: disableOutput ?? `Component {${componentFor(PKG)}} new state: disabled\n`, stderr: "" };
    }
    if (command === `pidof ${PKG}`) return { status: 0, stdout: "4242\n", stderr: "" };
    if (command === "cat /proc/4242/stat") return { status: 0, stdout: "4242 (build.raft.app) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 987654 20\n", stderr: "" };
    if (command.startsWith("content call ")) return { status: 0, stdout: `Result: Bundle[{receipt=${sampleJson}}]\n`, stderr: "" };
    return { status: 1, stdout: "", stderr: `unexpected ${command}` };
  };
  return { shell, commands };
}
const kindsOf = (commands) => commands.map((c) => c.split(" ").slice(0, 2).join(" "));

test("a package that ships the provider enabled: no pm enable, no pm disable, cleanup enabled_by_package", () => {
  const device = fakeDevice({ shipsEnabled: true });
  const runner = createIdentityReceiptRunner({ shell: device.shell, pkg: PKG });
  runner.probe();
  assert.equal(runner.state.startState, "enabled_by_package");
  runner.enable();
  runner.markSignInStarted();
  // No state change is made, so the "before sign-in" order does not apply.
  runner.enable();
  assert.deepEqual(runner.call(request).receipt, sample);
  assert.deepEqual(runner.disable(), { ok: true, status: "enabled_by_package" });
  assert.deepEqual(kindsOf(device.commands), ["dumpsys package", "content call", "pidof build.raft.app", "cat /proc/4242/stat", "content call", "pidof build.raft.app", "cat /proc/4242/stat"]);
  assert.ok(!device.commands.some((c) => /^pm (enable|disable)\b/.test(c)), "no component state change");
  // The probe carries nothing of the attempt and is not the receipt method.
  assert.equal(device.commands[1], `content call --uri content://${PKG}.identity-receipt --method probe`);
  // Cleanup without a call (the run failed in between) is the same: nothing to undo, nothing annotated.
  const early = createIdentityReceiptRunner({ shell: fakeDevice({ shipsEnabled: true }).shell, pkg: PKG });
  early.probe();
  assert.deepEqual(early.disable(), { ok: true, status: "enabled_by_package" });
});

test("start state: the probe call decides, dumpsys only tells a left-over pm override apart", () => {
  const dump = (override) => `    User 0: installed=true enabled=0\n      disabledComponents:\n        build.raft.app.Other\n${override ? `      ${override}Components:\n        build.raft.app.Another\n        ${PROVIDER_CLASS}\n` : ""}      lastDisabledCaller: shell\n        ${PROVIDER_CLASS}\n`;
  assert.equal(providerOverride(dump(null)), null);
  assert.equal(providerOverride(dump("enabled")), "enabled");
  assert.equal(providerOverride(dump("disabled")), "disabled");
  assert.equal(providerOverride(""), null);
  const refused = "Result: Bundle[{error=unknown_method}]\n";
  const notFound = `Error while accessing provider:${PKG}.identity-receipt\njava.lang.IllegalStateException: Could not find provider: ${PKG}.identity-receipt`;
  assert.equal(providerStartState(refused, dump(null)), "enabled_by_package");
  // Answers only because an earlier run left `pm enable` behind: the package ships it disabled.
  assert.equal(providerStartState(refused, dump("enabled")), "disabled");
  assert.equal(providerStartState(notFound, dump(null)), "disabled");
  assert.equal(providerStartState(notFound, dump("disabled")), "disabled");
  // Only the agreed refusal, as the whole reply, proves the provider answered. Another code, a
  // receipt or a malformed reply is unknown, with or without an override.
  const notTheAgreedReply = [
    "", "Result: null", "error: closed", "java.lang.SecurityException: Permission Denial",
    "Result: Bundle[{error=unavailable}]", "Result: Bundle[{error=caller_not_allowed}]", "Result: Bundle[{error=rate_limited}]",
    "Result: Bundle[{error=bad_request}]", `Result: Bundle[{receipt=${sampleJson}}]`,
    "Result: Bundle[{error=unknown_method", "Result: Bundle[{error=unknown_method, receipt={}}]", "Result: Bundle[{error=unknown_methods}]",
    "Result: Bundle[{error=UNKNOWN_METHOD}]", "error=unknown_method", "warning: something\nResult: Bundle[{error=unknown_method}]",
    "Result: Bundle[{error=unknown_method}]\nResult: Bundle[{error=unavailable}]",
  ];
  for (const other of notTheAgreedReply) {
    for (const override of [null, "enabled"]) assert.equal(providerStartState(other, dump(override)), "unknown", other);
  }
  assert.equal(providerStartState("  Result: Bundle[{error=unknown_method}]\r\n", dump(null)), "enabled_by_package", "surrounding whitespace only");
  for (const reply of ["Result: Bundle[{error=unavailable}]\n", "Result: Bundle[{error=caller_not_allowed}]\n", "Result: Bundle[{error=rate_limited}]\n", "Result: Bundle[{error=unknown_method\n"]) {
    const runner = createIdentityReceiptRunner({ shell: fakeDevice({ shipsEnabled: true, probeOutput: reply }).shell, pkg: PKG });
    runner.probe();
    assert.equal(runner.state.startState, "unknown", reply);
    assert.equal(runner.disable().status, "unknown", reply);
  }
  assert.deepEqual(PROVIDER_CLEANUP_STATUSES, ["disabled", "enabled_by_package", "unknown", "failed", "not_enabled"]);

  // A left-over override on an older package takes the pm path and ends disabled.
  const leftOver = fakeDevice({ override: "enabled" });
  const runner = createIdentityReceiptRunner({ shell: leftOver.shell, pkg: PKG });
  runner.probe();
  assert.equal(runner.state.startState, "disabled");
  runner.enable();
  assert.deepEqual(runner.disable(), { ok: true, status: "disabled" });
});

test("start state unknown is final: enable + disable succeeding never makes it disabled; a failed disable is failed; a failed enable sends no pm disable", () => {
  const clean = { capture_file: "deleted", browser: "restored", capture_browser: "uninstalled", web_session: "signed_out", app_session: "app_reported_server_logout_2xx" };
  const exits = (status) => [true, false].map((strict) => {
    const outcome = runOutcome({ signedIn: true, cleanup: { ...clean, identity_receipt_provider: status }, strict });
    assert.deepEqual(outcome.cleanupFailures, [{ step: "identity_receipt_provider", status }]);
    return [outcome.result, outcome.exitCode];
  });

  // unknown -> pm enable ok -> pm disable ok: restored, but the terminal value stays unknown, exit 1.
  const older = fakeDevice({ probeOutput: "" });
  const restored = createIdentityReceiptRunner({ shell: older.shell, pkg: PKG });
  restored.probe();
  assert.equal(restored.state.startState, "unknown");
  restored.enable();
  restored.markSignInStarted();
  assert.deepEqual(restored.call(request).receipt, sample);
  assert.deepEqual(restored.disable(), { ok: true, status: "unknown" });
  assert.deepEqual(older.commands.filter((c) => c.startsWith("pm ")), [`pm enable ${PKG}/${PROVIDER_CLASS}`, `pm disable ${PKG}/${PROVIDER_CLASS}`], "what the run enabled is disabled again");
  assert.equal(restored.state.disabled, true);
  assert.deepEqual(exits("unknown"), [["cleanup_failed", 1], ["cleanup_failed", 1]]);

  // unknown -> pm enable ok -> pm disable fails: failed (wins over unknown), with the loud annotation.
  for (const options of [{ disableStatus: 255, disableOutput: "Error: could not disable" }, { disableOutput: "" }]) {
    const runner = createIdentityReceiptRunner({ shell: fakeDevice({ probeOutput: "", ...options }).shell, pkg: PKG });
    runner.probe();
    runner.enable();
    const result = runner.disable();
    assert.deepEqual([result.ok, result.status], [false, "failed"]);
    assert.match(disableFailureAnnotation(PKG, result.detail), /^::error title=identity receipt provider left enabled::/);
  }
  assert.deepEqual(exits("failed"), [["cleanup_failed", 1], ["cleanup_failed", 1]]);

  // unknown -> pm enable fails (a signed package the probe could not read): unknown, no pm disable.
  const signed = fakeDevice({ shipsEnabled: true, probeOutput: "error: closed" });
  const runner = createIdentityReceiptRunner({ shell: signed.shell, pkg: PKG });
  runner.probe();
  assert.throws(() => runner.enable(), /pm enable failed \(the provider did not answer the probe either\): .*Shell cannot change component state/s);
  assert.throws(() => runner.call(request), /not enabled/);
  assert.deepEqual(runner.disable(), { ok: true, status: "unknown" });
  assert.ok(!signed.commands.some((c) => c.startsWith("pm disable")), "no pm disable on an unread state");

  // Unknown before enable was ever tried is unknown too, and sends nothing.
  const untouchedDevice = fakeDevice({ probeOutput: "" });
  const untouched = createIdentityReceiptRunner({ shell: untouchedDevice.shell, pkg: PKG });
  untouched.probe();
  assert.deepEqual(untouched.disable(), { ok: true, status: "unknown" });
  assert.ok(!untouchedDevice.commands.some((c) => c.startsWith("pm ")));
});

test("older package (ships the provider disabled): probe, enable before sign-in, call after it, disable last", () => {
  const device = fakeDevice();
  const runner = createIdentityReceiptRunner({ shell: device.shell, pkg: PKG });
  runner.probe();
  assert.equal(runner.state.startState, "disabled");
  runner.enable();
  runner.markSignInStarted();
  const answer = runner.call(request);
  assert.deepEqual(answer.receipt, sample);
  assert.deepEqual(answer.os, { pidBefore: "4242", pidAfter: "4242", startTicksBefore: "987654", startTicksAfter: "987654" });
  assert.deepEqual(runner.disable(), { ok: true, status: "disabled" });
  const kinds = kindsOf(device.commands);
  assert.deepEqual(kinds, ["dumpsys package", "content call", "pm enable", "pidof build.raft.app", "cat /proc/4242/stat", "content call", "pidof build.raft.app", "cat /proc/4242/stat", "pm disable"]);
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
  assert.equal(runOutcome({ signedIn: true, cleanup: { ...clean, identity_receipt_provider: "enabled_by_package" }, strict: true }).exitCode, 0);
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

  assert.ok(argvCommands.every((c) => !c.includes(sample.nonce)), "nonce never on an adb argv");

  // Without a salt no pair extra is sent (the call still goes over the private channel).
  assert.ok(!contentCallCommand(PKG, request).includes("pairing_salt"));

  const source = fs.readFileSync(new URL("./release_auto_login.mjs", import.meta.url), "utf8");
  assert.match(source, /readPairingSaltFile\(process\.env\.QA_PAIRING_SALT_FILE/);
  assert.match(source, /JSON\.parse\(fs\.readFileSync\(challengePath, "utf8"\)\)/);
  assert.match(source, /spawnSync\("adb", \["shell", "-T"\], \{[^}]*input: `\$\{command\}; exit\\n`/);
  assert.match(source, /for \(const secret of \[pairingSalt, nonce, process\.env\.QA_EXPECTED_USER_REF, process\.env\.QA_EXPECTED_SERVER_REF\]\)/);
  assert.match(source, /publish: \(result\) => \{ summary\.identity_receipt = publicIdentitySummary\(result\); \}/);
});

// The five values that are Testbed's comparison baseline (synthetic pairing vector).
const FIVE = ["nonce", "user_ref", "server_ref", "user_pair_ref", "server_pair_ref"];

const decisions = JSON.parse(fs.readFileSync(new URL("./fixtures/app-identity-testbed-decisions.json", import.meta.url), "utf8")).replies;

function gateFixture({ answer = { receipt: pairing.receipt }, osFacts = { ...os }, testbed = decisions.matched, observed = { state: "recorded" }, expectedRefs = {} } = {}) {
  const published = [];
  const privateWrites = [];
  const observations = [];
  const counts = { screenshots: 0, uiFiles: 0, scenarios: 0, testbedAsked: 0, observed: 0 };
  const gate = createCaptureGate({ identityMode: true });
  const evidence = guardedCapture(gate, () => { counts.screenshots += 1; counts.uiFiles += 1; });
  const fakeRunner = { call: () => ({ ...answer, ...(answer.receipt ? { receipt: JSON.parse(JSON.stringify(answer.receipt)) } : {}), os: { pidBefore: "4242", pidAfter: "4242", startTicksBefore: "987654", startTicksAfter: "987654" } }) };
  const run = () =>
    afterSignIn({
      identityGate: async () => {
        // Same order as release_auto_login.mjs: verify-signed-in's default capture comes first.
        evidence("verify-signed-in");
        return runIdentityGate({
          runner: fakeRunner,
          request: pairedRequest,
          gate,
          expected: expectedRefs,
          readOsFacts: () => {
            if (osFacts instanceof Error) throw osFacts;
            return osFacts;
          },
          writePrivate: (secret) => privateWrites.push(secret),
          publish: (result) => published.push(result),
          testbed: {
            observe: async (observation) => {
              counts.observed += 1;
              observations.push(observation);
              if (observed instanceof Error) throw observed;
              return observed;
            },
            decide: async () => {
              counts.testbedAsked += 1;
              if (testbed instanceof Error) throw testbed;
              return testbed;
            },
          },
        });
      },
      runScenario: async () => { counts.scenarios += 1; evidence("scenario-step"); },
    });
  return { run, counts, published, privateWrites, observations };
}

test("public output is only the agreed check statuses and state; the private output has the complete receipt and OS facts", async () => {
  const fixture = gateFixture();
  const outcome = await fixture.run();
  assert.equal(outcome.matched, true);
  const last = fixture.published.at(-1);
  assert.deepEqual(last, { receipt_check: "pass", os_check: "pass", user_check: "pending", server_check: "pending", pair_check: "pending", state: "matched" });
  for (const result of fixture.published) {
    assert.ok(Object.keys(result).every((key) => PUBLIC_IDENTITY_FIELDS.includes(key)), JSON.stringify(result));
  }
  const publicText = JSON.stringify(fixture.published);
  for (const value of [...FIVE.map((k) => pairing.receipt[k]), pairing.pairing_salt, pairedRequest.attemptId, pairedRequest.requestId, os.signerSha256, os.installedApkSha256, "4242", "987654", "REL"]) {
    assert.ok(!publicText.includes(String(value)), `public output must not contain ${value}`);
  }
  // The projection drops every other key and any free-text value.
  assert.deepEqual(publicIdentitySummary({ user_check: "pass", state: "matched", problems: ["x"], receipt: { nonce: "n" }, os: {}, attempt_id: "pla_x", server_check: "because" }), { user_check: "pass", state: "matched" });
  assert.deepEqual(publicIdentitySummary({ state: "free text" }), { state: "unknown" });
  assert.deepEqual(publicIdentitySummary({ state: "unsupported_build" }), { state: "unsupported_build" });
  // Status with frozen refs: pass / fail, never the value.
  assert.deepEqual(verificationStatus({ receipt: pairing.receipt, request: pairedRequest, expected }), { user_check: "pass", server_check: "pass", pair_check: "pending" });
  assert.equal(verificationStatus({ receipt: pairing.receipt, request, expected: { userRef: "0".repeat(64), serverRef: expected.serverRef } }).user_check, "fail");
  assert.equal(verificationStatus({ receipt: { ...sample, server_ref: null }, request, expected: {} }).server_check, "fail");

  // Private: once, the complete receipt + every OS fact, bound to this attempt and request.
  assert.equal(fixture.privateWrites.length, 1);
  const secret = fixture.privateWrites[0];
  assert.deepEqual(Object.keys(secret), ["attempt_id", "request_id", "receipt", "os", "problems"]);
  assert.deepEqual(secret.receipt, pairing.receipt);
  for (const key of ["packageName", "versionCode", "debuggable", "deviceSdk", "deviceCodename", "devicePreview", "signerSha256", "installedApkSha256", "splitApks", "pidBefore", "pidAfter", "startTicksBefore", "startTicksAfter"]) {
    assert.ok(key in secret.os, key);
  }
  // Types as Testbed #318's observation schema takes them: OS versionCode/pids/ticks are decimal
  // strings, the receipt's version_code a number.
  assert.equal(typeof secret.os.versionCode, "string");
  assert.match(secret.os.versionCode, /^[1-9]\d{0,19}$/);
  assert.equal(typeof secret.receipt.version_code, "number");
  for (const key of ["pidBefore", "pidAfter", "startTicksBefore", "startTicksAfter"]) assert.equal(typeof secret.os[key], "string", key);
  // The observation Testbed gets privately is the same complete receipt + OS facts.
  assert.deepEqual(fixture.observations, [{ request_id: pairedRequest.requestId, receipt: pairing.receipt, os: secret.os }]);

  const dir = fs.mkdtempSync(path.join(os_.tmpdir(), "identity-private-"));
  const evidenceDir = path.join(dir, "evidence");
  fs.mkdirSync(evidenceDir);
  fs.mkdirSync(path.join(dir, "private"));
  fs.chmodSync(path.join(dir, "private"), 0o700);
  const file = privateOutPath(path.join(dir, "private", "private.json"), evidenceDir);
  // The directory that holds the evidence directory is not "outside" it either.
  assert.throws(() => privateOutPath(path.join(dir, "private.json"), evidenceDir), /outside the evidence directory/);
  writePrivateReceipt(file, secret);
  assert.equal(fs.statSync(file).mode & 0o777, 0o600);
  const raw = fs.readFileSync(file, "utf8");
  for (const key of FIVE) assert.ok(raw.includes(pairing.receipt[key]), key);
  // Fresh only, and never inside the uploaded evidence directory.
  assert.throws(() => privateOutPath(file, evidenceDir), /must not exist/);
  assert.throws(() => writePrivateReceipt(file, secret), /EEXIST/);
  assert.throws(() => privateOutPath(path.join(evidenceDir, "private.json"), evidenceDir), /outside the evidence directory/);
  fs.mkdirSync(path.join(evidenceDir, "sub"));
  fs.chmodSync(path.join(evidenceDir, "sub"), 0o700);
  assert.throws(() => privateOutPath(path.join(evidenceDir, "sub", "p.json"), evidenceDir), /outside the evidence directory/);
  assert.throws(() => privateOutPath("", evidenceDir), /required/);
  // Real filesystem boundary: a symlinked directory or a ".." spelling that lands inside the
  // evidence directory is inside; a sibling whose name merely starts with "evidence" is outside.
  fs.mkdirSync(path.join(evidenceDir, "inner"));
  fs.symlinkSync(path.join(evidenceDir, "inner"), path.join(dir, "link"));
  assert.throws(() => privateOutPath(path.join(dir, "link", "p.json"), evidenceDir), /outside the evidence directory/);
  assert.throws(() => privateOutPath(path.join(dir, "other", "..", "evidence", "p.json"), evidenceDir), /outside the evidence directory/);
  fs.symlinkSync(evidenceDir, path.join(dir, "evidence-alias"));
  assert.throws(() => privateOutPath(path.join(dir, "out.json"), path.join(dir, "evidence-alias", "inner")), /outside the evidence directory/, "a parent of the evidence dir is refused too");
  fs.mkdirSync(path.join(dir, "evidence-private"));
  fs.chmodSync(path.join(dir, "evidence-private"), 0o700);
  assert.equal(privateOutPath(path.join(dir, "evidence-private", "p.json"), evidenceDir), path.join(fs.realpathSync(dir), "evidence-private", "p.json"));
  // A dangling symlink at the target counts as existing; exclusive creation never follows it.
  fs.symlinkSync(path.join(evidenceDir, "planted.json"), path.join(dir, "evidence-private", "s.json"));
  assert.throws(() => privateOutPath(path.join(dir, "evidence-private", "s.json"), evidenceDir), /must not exist/);
  // A directory others can write, or a missing one, is refused.
  fs.mkdirSync(path.join(dir, "shared"));
  fs.chmodSync(path.join(dir, "shared"), 0o777);
  assert.throws(() => privateOutPath(path.join(dir, "shared", "p.json"), evidenceDir), /runner-owned/);
  assert.throws(() => privateOutPath(path.join(dir, "missing", "p.json"), evidenceDir), /must exist/);
  fs.rmSync(dir, { recursive: true });
});

test("nothing is captured and no scenario runs unless Testbed's reply is matched for this attempt and request", async () => {
  const cases = {
    "awaiting_confirmation (first sample)": gateFixture({ testbed: decisions.awaiting_confirmation }),
    "identity_mismatch": gateFixture({ testbed: decisions.identity_mismatch }),
    "unknown": gateFixture({ testbed: decisions.unknown }),
    "matched for another attempt": gateFixture({ testbed: decisions.matched_other_attempt }),
    "matched for another request": gateFixture({ testbed: decisions.matched_other_request }),
    "unrecognised state": gateFixture({ testbed: decisions.unrecognised_state }),
    "no reply": gateFixture({ testbed: null }),
    "transport failure": gateFixture({ testbed: new Error("ECONNRESET") }),
    "observation not recorded": gateFixture({ observed: { state: "refused" } }),
    "observation transport failure": gateFixture({ observed: new Error("ETIMEDOUT") }),
    "runner-side mismatch": gateFixture({ osFacts: { ...os, signerSha256: "0".repeat(64) } }),
    "pkgFlags unread": gateFixture({ osFacts: { ...os, debuggable: null } }),
    "codename unread": gateFixture({ osFacts: { ...os, deviceCodename: null, devicePreview: null } }),
    "OS read failed": gateFixture({ osFacts: new Error("adb: device offline") }),
    "unclear receipt": gateFixture({ answer: { error: "no_answer", detail: "Error while accessing provider" } }),
    "missing pair refs": gateFixture({ answer: { receipt: { ...pairing.receipt, server_pair_ref: null } } }),
  };
  for (const [name, fixture] of Object.entries(cases)) {
    const outcome = await fixture.run();
    assert.equal(outcome.matched, false, name);
    assert.equal(outcome.scenarioRan, false, name);
    assert.deepEqual([fixture.counts.screenshots, fixture.counts.uiFiles, fixture.counts.scenarios], [0, 0, 0], name);
    assert.notEqual(fixture.published.at(-1).state, "matched", name);
  }
  // A runner-side problem (including an unknown OS value) stops before anything is submitted.
  for (const name of ["runner-side mismatch", "pkgFlags unread", "codename unread", "OS read failed", "unclear receipt", "missing pair refs"]) {
    assert.deepEqual([cases[name].counts.observed, cases[name].counts.testbedAsked], [0, 0], name);
  }
  assert.equal(cases["pkgFlags unread"].published.at(-1).os_check, "fail");
  assert.deepEqual(cases["OS read failed"].privateWrites[0].problems.slice(0, 1), ["os_facts_unavailable"]);
  assert.deepEqual(cases["unclear receipt"].published.at(-1), { receipt_check: "fail", state: "unknown" });
  assert.equal(cases["observation not recorded"].counts.testbedAsked, 0, "no decision without a recorded observation");
  assert.equal(cases["matched for another request"].published.at(-1).state, "unknown");
  assert.equal(cases["identity_mismatch"].published.at(-1).state, "identity_mismatch");

  const matched = gateFixture({ testbed: decisions.matched });
  const outcome = await matched.run();
  assert.deepEqual(outcome, { matched: true, scenarioRan: true });
  // Captures only after matched: the scenario's capture, not verify-signed-in's.
  assert.deepEqual([matched.counts.screenshots, matched.counts.uiFiles, matched.counts.scenarios], [1, 1, 1]);
  assert.equal(matched.published.at(-1).state, "matched");
});

test("Testbed decisions bind to this attempt and request; release mode accepts no local decision", () => {
  assert.equal(parseTestbedDecision(decisions.matched, pairedRequest), "matched");
  assert.equal(parseTestbedDecision(decisions.matched_other_attempt, pairedRequest), "unknown");
  assert.equal(parseTestbedDecision(decisions.matched_other_request, pairedRequest), "unknown");
  assert.equal(parseTestbedDecision(decisions.unrecognised_state, pairedRequest), "unknown");
  assert.equal(parseTestbedDecision(null, pairedRequest), "unknown");
  assert.equal(parseTestbedDecision("matched", pairedRequest), "unknown");
  let captured = 0;
  assert.equal(guardedCapture(createCaptureGate({ identityMode: false }), () => { captured += 1; })("x"), true);
  assert.equal(captured, 1);
  const source = fs.readFileSync(new URL("./release_auto_login.mjs", import.meta.url), "utf8");
  // Outside the Testbed release lane observe/decide return nothing; inside it the only source is
  // the Testbed client (HTTP + OIDC + claim credential). No file/env/flag decision is read.
  const stub = source.slice(source.indexOf("const noTestbed = {"), source.indexOf("function testbedDecisionSource()"));
  assert.equal((stub.match(/return null;/g) ?? []).length, 2);
  assert.doesNotMatch(stub, /process\.env|readFileSync|option\(|flag\(/);
  const chooser = source.slice(source.indexOf("function testbedDecisionSource()"), source.indexOf("function identityGate(request)"));
  assert.match(chooser, /return createTestbedReleaseClient\(\{ env: process\.env, claimToken \}\);/);
  assert.doesNotMatch(chooser, /DECISION|VERDICT|state/);
  assert.doesNotMatch(source, /VERDICT_FILE|DECISION_FILE/);
  // Every capture (step screenshots, failure shots) goes through the gate; the scenario only
  // through afterSignIn after the identity gate.
  assert.match(source, /const evidence = guardedCapture\(captureGate, captureEvidence\);/);
  assert.equal((source.match(/captureEvidence\(/g) ?? []).length, 1, "captureEvidence is only reached through the gate");
  assert.doesNotMatch(source, /if \(SCENARIO\) await runScenario/);
  assert.match(source, /runScenario: SCENARIO \? \(\) => runScenario\(webSession\) : null/);
});

test("#227 mismatchCategories maps every runner-side cross-check problem to a closed allowlist without leaking values", async () => {
  const cases = [
    [["os_facts_unavailable"], ["os_facts_unavailable"]],
    [["receipt does not echo this request"], ["request_binding"]],
    [["package differs from the installed package"], ["package"]],
    [["version_code differs from dumpsys package"], ["version_code"]],
    [["receipt has no single signer", "signer_sha256 differs from apksigner (device SDK certificate)"], ["signer"]],
    [["build is debuggable", "debuggable flag could not be read"], ["debuggable"]],
    [["device is not on a released SDK (codename is not REL or unread)"], ["device_sdk"]],
    [["installed package is not a single APK", "installed APK digest is unknown", "installed base.apk differs from --apk"], ["apk_integrity"]],
    [["pid changed around the call", "process start time changed around the call"], ["process"]],
    [["app is not signed in"], ["session_state"]],
    [["session origin is qa_bootstrap"], ["session_origin"]],
    [["receipt has no user_ref/server_ref (no selected server, or it has no slug)"], ["user_or_server_ref"]],
    [["receipt has no user_pair_ref/server_pair_ref"], ["pair_ref"]],
    [["user_ref differs from the frozen value", "server_ref differs from the frozen value"], ["expected_ref"]],
    [["unexpected free text nonce=" + fixture.nonce + " secret=fake"], ["unknown"]],
  ];
  for (const [problems, expectedCategories] of cases) {
    const categories = mismatchCategories(problems);
    assert.deepEqual(categories, expectedCategories, JSON.stringify(problems));
    for (const c of categories) assert.ok(MISMATCH_CATEGORIES.includes(c), c);
    assert.ok(!categories.join(",").includes(fixture.nonce));
    assert.ok(!categories.join(",").includes("qa_bootstrap"));
  }

  // runIdentityGate exposes allowlisted categories on runner-side mismatch while keeping published
  // output strictly inside PUBLIC_IDENTITY_FIELDS and keeping the evidence gate closed.
  const noServer = gateFixture({ answer: { receipt: { ...pairing.receipt, server_ref: null, server_pair_ref: null } } });
  const noServerOutcome = await runIdentityGate({
    runner: { call: () => ({ receipt: { ...pairing.receipt, server_ref: null, server_pair_ref: null }, os: { pidBefore: "4242", pidAfter: "4242", startTicksBefore: "987654", startTicksAfter: "987654" } }) },
    request: pairedRequest,
    gate: createCaptureGate({ identityMode: true }),
    readOsFacts: () => ({ ...os }),
    writePrivate: () => {},
    publish: (result) => {
      assert.ok(Object.keys(result).every((key) => PUBLIC_IDENTITY_FIELDS.includes(key)), JSON.stringify(result));
      assert.equal("categories" in result, false);
    },
    testbed: { observe: async () => { throw new Error("must not observe on runner mismatch"); }, decide: async () => { throw new Error("must not decide on runner mismatch"); } },
  });
  assert.equal(noServerOutcome.matched, false);
  assert.equal(noServerOutcome.reason, "mismatch");
  assert.deepEqual(noServerOutcome.categories, ["user_or_server_ref", "pair_ref"]);
  const noServerRun = await noServer.run();
  assert.equal(noServerRun.matched, false);
  assert.equal(noServer.counts.observed, 0);

  // release_auto_login.mjs logs only the sanitized categories and public check statuses, never raw problems.
  const source = fs.readFileSync(new URL("./release_auto_login.mjs", import.meta.url), "utf8");
  assert.match(source, /identity receipt: mismatch categories=\$\{categories\}/);
  assert.doesNotMatch(source, /outcome\.problems/);
  assert.match(source, /log\(`verify-signed-in: screen=\$\{screen\}`\)/);
});
