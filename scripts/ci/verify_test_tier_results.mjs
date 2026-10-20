#!/usr/bin/env node

const DEVICE_SUITE_JOB = "android-device";
const CANONICAL_FINAL_JOB = "android-canonical-final-actual-host";
const ATTACHMENT_JOB = "android-minimum-supported-attachment-runtime";

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
  const runDeviceSuite = requireBoolean(outputs.run_device_suite, "run_device_suite");
  const runCanonicalFinal = requireBoolean(outputs.run_canonical_final, "run_canonical_final");
  const attachmentRaw = outputs.attachment_api_levels;
  if (typeof attachmentRaw !== "string") {
    throw new Error("CI plan output attachment_api_levels must be a string");
  }
  let attachmentLevels;
  try {
    attachmentLevels = JSON.parse(attachmentRaw);
  } catch {
    throw new Error(`attachment_api_levels must be valid JSON, got ${attachmentRaw}`);
  }
  if (!Array.isArray(attachmentLevels) || !attachmentLevels.every((n) => Number.isInteger(n))) {
    throw new Error(`attachment_api_levels must be an integer array, got ${attachmentRaw}`);
  }
  const runAttachment = attachmentLevels.length > 0;
  if (runDeviceSuite && !runAndroid) throw new Error("device suite requires Android");
  if (runCanonicalFinal && !runAndroid) throw new Error("canonical-final requires Android");
  if (runAttachment && !runAndroid) throw new Error("attachment legs require Android");

  const expected = new Map([
    // iOS included deliberately: test.yml runs kuikly-artifacts for iOS too (since #2095),
    // and the ios job `needs:` it — dropping iOS here would cascade-skip the iOS compile.
    // The producer is correct; this expectation had been left behind at #2090.
    //
    // PREMISE this line hangs on (@LiBai): `ios` still declares
    //   test.yml  ios:  needs: [plan, kuikly-artifacts]
    // If that dependency is ever removed, the correct fix flips sides — the PRODUCER should
    // drop run_ios from the kuikly-artifacts `if:`, and this expectation should go back to
    // runAndroid. Re-derive before editing either side; do not read this line as
    // "iOS always needs kuikly artifacts".
    ["kuikly-artifacts", runAndroid || runIos],
    ["android", runAndroid],
    ["ios", runIos],
    [DEVICE_SUITE_JOB, runDeviceSuite],
    [CANONICAL_FINAL_JOB, runCanonicalFinal],
    [ATTACHMENT_JOB, runAttachment],
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
    run_device_suite: runDeviceSuite,
    run_canonical_final: runCanonicalFinal,
    attachment_api_levels: attachmentLevels,
    reason: outputs.reason || "missing",
  };
}

function fixture({ android, ios, deviceSuite, canonicalFinal, attachmentLevels }) {
  const levels = attachmentLevels ?? [];
  const needs = {
    plan: {
      result: "success",
      outputs: {
        run_android: String(android),
        run_ios: String(ios),
        run_device_suite: String(deviceSuite),
        run_canonical_final: String(canonicalFinal),
        attachment_api_levels: JSON.stringify(levels),
        reason: "fixture",
      },
    },
  };
  needs["kuikly-artifacts"] = { result: android || ios ? "success" : "skipped" };
  needs.android = { result: android ? "success" : "skipped" };
  needs.ios = { result: ios ? "success" : "skipped" };
  needs[DEVICE_SUITE_JOB] = { result: deviceSuite ? "success" : "skipped" };
  needs[CANONICAL_FINAL_JOB] = { result: canonicalFinal ? "success" : "skipped" };
  needs[ATTACHMENT_JOB] = { result: levels.length > 0 ? "success" : "skipped" };
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
  const none = { android: false, ios: false, deviceSuite: false, canonicalFinal: false, attachmentLevels: [] };
  verifyTierResults(fixture(none));
  verifyTierResults(fixture({ ...none, android: true }));
  verifyTierResults(fixture({ ...none, ios: true }));
  verifyTierResults(fixture({ ...none, android: true, ios: true }));
  // full-ci PR: device suite + API31 only, no canonical-final
  verifyTierResults(fixture({ android: true, ios: true, deviceSuite: true, canonicalFinal: false, attachmentLevels: [31] }));
  // nightly/manual: full device suite + canonical-final + all attachment legs
  verifyTierResults(fixture({ android: true, ios: true, deviceSuite: true, canonicalFinal: true, attachmentLevels: [29, 31, 34] }));

  // iOS-only broke for 18 days, and WHY it survived is the part worth pinning: fixture() and
  // the expectation table were built from the SAME formula, so they agreed with each other
  // while both disagreed with test.yml. A self-test whose fixture is generated by the rule
  // under test cannot fail on a wrong rule. So assert the value LITERALLY, not via fixture():
  const iosOnly = fixture({ ...none, ios: true });
  if (iosOnly["kuikly-artifacts"].result !== "success") {
    throw new Error("fixture drift: iOS-only must run kuikly-artifacts (test.yml `if:` includes run_ios)");
  }
  verifyTierResults(iosOnly);

  // the negative is the assertion with teeth — it does not route through fixture()
  const iosOnlySkipped = fixture({ ...none, ios: true });
  iosOnlySkipped["kuikly-artifacts"].result = "skipped";
  expectFailure(iosOnlySkipped, "iOS-only must not skip kuikly-artifacts");

  const failedAndroid = fixture({ ...none, android: true });
  failedAndroid.android.result = "failure";
  expectFailure(failedAndroid, "selected Android failure");

  const missingIos = fixture({ ...none, ios: true });
  missingIos.ios.result = "skipped";
  expectFailure(missingIos, "selected iOS job skipped");

  const accidentalDevice = fixture({ ...none, android: true });
  accidentalDevice[DEVICE_SUITE_JOB].result = "success";
  expectFailure(accidentalDevice, "unplanned device suite cost");

  const accidentalCanonical = fixture({ ...none, android: true });
  accidentalCanonical[CANONICAL_FINAL_JOB].result = "success";
  expectFailure(accidentalCanonical, "unplanned canonical-final cost");

  const accidentalAttachment = fixture({ ...none, android: true });
  accidentalAttachment[ATTACHMENT_JOB].result = "success";
  expectFailure(accidentalAttachment, "unplanned attachment cost");

  const deviceWithoutAndroid = fixture({ ...none, ios: true });
  deviceWithoutAndroid.plan.outputs.run_device_suite = "true";
  expectFailure(deviceWithoutAndroid, "device suite without Android");

  const canonicalWithoutAndroid = fixture({ ...none, ios: true });
  canonicalWithoutAndroid.plan.outputs.run_canonical_final = "true";
  expectFailure(canonicalWithoutAndroid, "canonical-final without Android");

  const attachmentWithoutAndroid = fixture({ ...none, ios: true });
  attachmentWithoutAndroid.plan.outputs.attachment_api_levels = "[31]";
  expectFailure(attachmentWithoutAndroid, "attachment legs without Android");

  const badLevels = fixture({ ...none, android: true, attachmentLevels: [31] });
  badLevels.plan.outputs.attachment_api_levels = "not-json";
  expectFailure(badLevels, "invalid attachment_api_levels");
  console.log("CI tier aggregate self-test: PASS");
}

if (process.argv[2] === "--self-test") {
  selfTest();
} else {
  const needs = JSON.parse(process.env.NEEDS_JSON || "null");
  const result = verifyTierResults(needs);
  console.log(`CI tier aggregate PASS: ${JSON.stringify(result)}`);
}
