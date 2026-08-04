#!/usr/bin/env node

const EMULATOR_JOBS = [
  "android-device",
  "android-minimum-supported-attachment-runtime",
  "android-canonical-final-actual-host",
];

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
  if (runEmulators && !runAndroid) throw new Error("emulator jobs require Android");

  const expected = new Map([
    ["kuikly-artifacts", runAndroid || runOhos],
    ["fork-deps", runOhos],
    ["android", runAndroid],
    ["ios", runIos],
    ["ohos-receipt-selftest", runOhos],
    ["ohos", runOhos],
    ["ohos-host", runOhos],
    ...EMULATOR_JOBS.map((name) => [name, runEmulators]),
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
    reason: outputs.reason || "missing",
  };
}

function fixture({ android, ios, ohos, emulators }) {
  const needs = {
    plan: {
      result: "success",
      outputs: {
        run_android: String(android),
        run_ios: String(ios),
        run_ohos: String(ohos),
        run_emulators: String(emulators),
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
  for (const name of EMULATOR_JOBS) needs[name] = { result: emulators ? "success" : "skipped" };
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
  verifyTierResults(fixture({ android: true, ios: true, ohos: true, emulators: true }));

  const failedAndroid = fixture({ android: true, ios: false, ohos: false, emulators: false });
  failedAndroid.android.result = "failure";
  expectFailure(failedAndroid, "selected Android failure");

  const missingOhos = fixture({ android: false, ios: false, ohos: true, emulators: false });
  missingOhos.ohos.result = "skipped";
  expectFailure(missingOhos, "selected OHOS job skipped");

  const accidentalEmulator = fixture({ android: true, ios: false, ohos: false, emulators: false });
  accidentalEmulator["android-device"].result = "success";
  expectFailure(accidentalEmulator, "unplanned emulator cost");

  const invalidPlan = fixture({ android: false, ios: true, ohos: false, emulators: false });
  invalidPlan.plan.outputs.run_emulators = "true";
  expectFailure(invalidPlan, "emulators without Android");
  console.log("CI tier aggregate self-test: PASS");
}

if (process.argv[2] === "--self-test") {
  selfTest();
} else {
  const needs = JSON.parse(process.env.NEEDS_JSON || "null");
  const result = verifyTierResults(needs);
  console.log(`CI tier aggregate PASS: ${JSON.stringify(result)}`);
}
