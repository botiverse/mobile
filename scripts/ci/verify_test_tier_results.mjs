#!/usr/bin/env node

const DEVICE_JOB = "android-device";
const ATTACHMENT_JOB = "android-minimum-supported-attachment-runtime";
const CANONICAL_FINAL_JOB = "android-canonical-final-actual-host";

function requireBoolean(value, name) {
  if (value !== "true" && value !== "false") {
    throw new Error(`CI plan output ${name} must be true or false, got ${value}`);
  }
  return value === "true";
}

export function verifyTierResults(needs) {
  if (!needs || typeof needs !== "object" || needs.plan?.result !== "success") {
    throw new Error("CI plan job did not succeed");
  }
  const outputs = needs.plan.outputs || {};
  const runAndroid = requireBoolean(outputs.run_android, "run_android");
  const runIos = requireBoolean(outputs.run_ios, "run_ios");
  const runOhos = requireBoolean(outputs.run_ohos, "run_ohos");
  const runEmulators = requireBoolean(outputs.run_emulators, "run_emulators");
  const runCanonicalFinal = requireBoolean(outputs.run_canonical_final, "run_canonical_final");
  const runAttachment = outputs.run_attachment;
  if (runAttachment !== "none" && runAttachment !== "api31" && runAttachment !== "all") {
    throw new Error(`CI plan output run_attachment must be none, api31 or all, got ${runAttachment}`);
  }
  if (runEmulators && !runAndroid) throw new Error("emulator jobs require Android");
  if (runCanonicalFinal && !runAndroid) throw new Error("canonical-final requires Android");
  if (runAttachment !== "none" && !runAndroid) throw new Error("attachment authority requires Android");

  const expected = new Map([
    ["kuikly-artifacts", runAndroid || runOhos],
    ["fork-deps", runOhos],
    ["android", runAndroid],
    ["ios", runIos],
    ["ohos-receipt-selftest", runOhos],
    ["ohos", runOhos],
    ["ohos-host", runOhos],
    [DEVICE_JOB, runEmulators],
    [CANONICAL_FINAL_JOB, runCanonicalFinal],
    [ATTACHMENT_JOB, runAttachment !== "none"],
  ]);
  for (const [name, shouldRun] of expected) {
    const result = needs[name]?.result;
    const wanted = shouldRun ? "success" : "skipped";
    if (result !== wanted) {
      throw new Error(`${name} result must be ${wanted}, got ${result ?? "missing"}`);
    }
  }

  return {
    run_android: runAndroid,
    run_ios: runIos,
    run_ohos: runOhos,
    run_emulators: runEmulators,
    run_canonical_final: runCanonicalFinal,
    run_attachment: runAttachment,
    reason: outputs.reason || "missing",
  };
}

function fixture({ android, ios, ohos, emulators, canonicalFinal = false, attachment = "none" }) {
  const needs = {
    plan: {
      result: "success",
      outputs: {
        run_android: String(android),
        run_ios: String(ios),
        run_ohos: String(ohos),
        run_emulators: String(emulators),
        run_canonical_final: String(canonicalFinal),
        run_attachment: attachment,
        reason: "fixture",
      },
    },
  };
  needs["kuikly-artifacts"] = { result: android || ohos ? "success" : "skipped" };
  needs["fork-deps"] = { result: ohos ? "success" : "skipped" };
  needs.android = { result: android ? "success" : "skipped" };
  needs.ios = { result: ios ? "success" : "skipped" };
  needs["ohos-receipt-selftest"] = { result: ohos ? "success" : "skipped" };
  needs.ohos = { result: ohos ? "success" : "skipped" };
  needs["ohos-host"] = { result: ohos ? "success" : "skipped" };
  needs[DEVICE_JOB] = { result: emulators ? "success" : "skipped" };
  needs[CANONICAL_FINAL_JOB] = { result: canonicalFinal ? "success" : "skipped" };
  needs[ATTACHMENT_JOB] = { result: attachment !== "none" ? "success" : "skipped" };
  return needs;
}

function expectFailure(needs, message) {
  try {
    verifyTierResults(needs);
  } catch {
    return;
  }
  throw new Error(`negative fixture unexpectedly passed: ${message}`);
}

function selfTest() {
  verifyTierResults(fixture({ android: false, ios: false, ohos: false, emulators: false }));
  verifyTierResults(fixture({ android: true, ios: false, ohos: false, emulators: false }));
  verifyTierResults(fixture({ android: false, ios: true, ohos: false, emulators: false }));
  verifyTierResults(fixture({ android: false, ios: false, ohos: true, emulators: false }));
  verifyTierResults(fixture({ android: true, ios: true, ohos: true, emulators: false }));
  verifyTierResults(fixture({ android: true, ios: true, ohos: true, emulators: true, canonicalFinal: true, attachment: "all" }));
  verifyTierResults(fixture({ android: true, ios: true, ohos: true, emulators: true, canonicalFinal: false, attachment: "api31" }));

  const failedAndroid = fixture({ android: true, ios: false, ohos: false, emulators: false });
  failedAndroid.android.result = "failure";
  expectFailure(failedAndroid, "selected Android failure");

  const missingOhos = fixture({ android: false, ios: false, ohos: true, emulators: false });
  missingOhos.ohos.result = "skipped";
  expectFailure(missingOhos, "selected OHOS job skipped");

  const accidentalEmulator = fixture({ android: true, ios: false, ohos: false, emulators: false });
  accidentalEmulator[DEVICE_JOB].result = "success";
  expectFailure(accidentalEmulator, "unplanned emulator cost");

  const accidentalCanonical = fixture({ android: true, ios: false, ohos: false, emulators: false });
  accidentalCanonical[CANONICAL_FINAL_JOB].result = "success";
  expectFailure(accidentalCanonical, "unplanned canonical-final cost");

  const accidentalAttachment = fixture({ android: true, ios: false, ohos: false, emulators: false });
  accidentalAttachment[ATTACHMENT_JOB].result = "success";
  expectFailure(accidentalAttachment, "unplanned attachment cost");

  const invalidPlan = fixture({ android: false, ios: true, ohos: false, emulators: false });
  invalidPlan.plan.outputs.run_emulators = "true";
  expectFailure(invalidPlan, "emulators without Android");

  const invalidAttachment = fixture({ android: false, ios: true, ohos: false, emulators: false });
  invalidAttachment.plan.outputs.run_attachment = "all";
  expectFailure(invalidAttachment, "attachment without Android");
  console.log("CI tier aggregate self-test: PASS");
}

if (process.argv[2] === "--self-test") {
  selfTest();
} else {
  const needs = JSON.parse(process.env.NEEDS_JSON || "null");
  const result = verifyTierResults(needs);
  console.log(`CI tier aggregate PASS: ${JSON.stringify(result)}`);
}
