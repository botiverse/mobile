#!/usr/bin/env node

const BASIC_JOBS = ["kuikly-artifacts", "android", "ohos-receipt-selftest"];
const FULL_JOBS = ["fork-deps", "ios", "ohos", "ohos-host"];
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
  const runBasic = requireBoolean(outputs.run_basic, "run_basic");
  const runFull = requireBoolean(outputs.run_full, "run_full");
  const runEmulators = requireBoolean(outputs.run_emulators, "run_emulators");
  if (runFull && !runBasic) throw new Error("full tier cannot run without basic tier");
  if (runEmulators && !runFull) throw new Error("emulator tier cannot run without full tier");

  const expected = new Map([
    ...BASIC_JOBS.map((name) => [name, runBasic]),
    ...FULL_JOBS.map((name) => [name, runFull]),
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
    run_basic: runBasic,
    run_full: runFull,
    run_emulators: runEmulators,
    reason: outputs.reason || "missing",
  };
}

function fixture({ basic, full, emulators }) {
  const needs = {
    plan: {
      result: "success",
      outputs: {
        run_basic: String(basic),
        run_full: String(full),
        run_emulators: String(emulators),
        reason: "fixture",
      },
    },
  };
  for (const name of BASIC_JOBS) needs[name] = { result: basic ? "success" : "skipped" };
  for (const name of FULL_JOBS) needs[name] = { result: full ? "success" : "skipped" };
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
  verifyTierResults(fixture({ basic: false, full: false, emulators: false }));
  verifyTierResults(fixture({ basic: true, full: false, emulators: false }));
  verifyTierResults(fixture({ basic: true, full: true, emulators: false }));
  verifyTierResults(fixture({ basic: true, full: true, emulators: true }));

  const failedBasic = fixture({ basic: true, full: false, emulators: false });
  failedBasic.android.result = "failure";
  expectFailure(failedBasic, "required basic failure");

  const missingFull = fixture({ basic: true, full: true, emulators: false });
  missingFull.ohos.result = "skipped";
  expectFailure(missingFull, "required full job skipped");

  const accidentalEmulator = fixture({ basic: true, full: true, emulators: false });
  accidentalEmulator["android-device"].result = "success";
  expectFailure(accidentalEmulator, "unplanned emulator cost");

  const invalidPlan = fixture({ basic: true, full: false, emulators: false });
  invalidPlan.plan.outputs.run_emulators = "true";
  expectFailure(invalidPlan, "emulator tier without full tier");
  console.log("CI tier aggregate self-test: PASS");
}

if (process.argv[2] === "--self-test") {
  selfTest();
} else {
  const needs = JSON.parse(process.env.NEEDS_JSON || "null");
  const result = verifyTierResults(needs);
  console.log(`CI tier aggregate PASS: ${JSON.stringify(result)}`);
}
