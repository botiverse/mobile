#!/usr/bin/env node

const DEVICE_SUITE_JOB = "android-device";
const ATTACHMENT_JOB = "android-minimum-supported-attachment-runtime";
const IOS_DEFAULT_LANE = "github-xcode27.0";
const IOS_XCODE271_LANE = "github-xcode27.1";
const QUEUE_REUSE_JOB = "queue-ci-reuse";

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
  const runIosXcode270 = requireBoolean(outputs.run_ios_xcode270, "run_ios_xcode270");
  const runIosXcode271 = requireBoolean(outputs.run_ios_xcode271, "run_ios_xcode271");
  const runIosTests = requireBoolean(outputs.run_ios_tests, "run_ios_tests");
  const runRoborazzi = requireBoolean(outputs.run_roborazzi, "run_roborazzi");
  const runDeviceSuite = requireBoolean(outputs.run_device_suite, "run_device_suite");
  // Stamp merge queue (2026-11-19). queueCandidate: Stamp's managed draft PR; Stamp accepts the
  // run only when every job succeeded, so the emulator jobs the plan does not select run as
  // no-op successes. queueReuse: a main push proven by stamp/queue-tested; only queue-ci-reuse runs.
  const queueCandidate = requireBoolean(outputs.queue_candidate, "queue_candidate");
  const queueReuse = requireBoolean(outputs.queue_reuse, "queue_reuse");
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
  if (queueCandidate && queueReuse) throw new Error("queue_candidate and queue_reuse are exclusive");
  if (queueReuse && (runAndroid || runIos || runDeviceSuite || runAttachment)) {
    throw new Error("queue_reuse plans no other job");
  }
  if (runDeviceSuite && !runAndroid) throw new Error("device suite requires Android");
  if (runAttachment && !runAndroid) throw new Error("attachment legs require Android");
  if (runIosXcode270 && !runIos) throw new Error("Xcode 27.0 iOS lane requires iOS");
  if (runIosXcode271 && !runIos) throw new Error("Xcode 27.1 iOS lane requires iOS");
  // Every iOS run has at least one lane: 27.0 (default), 27.1 beside it (nightly, release-*),
  // or 27.1 alone (Stamp merge queue batch).
  if (runIos && !runIosXcode270 && !runIosXcode271) throw new Error("iOS run without an iOS lane");
  if (runIosTests && !runIos) throw new Error("iOS tests require iOS");
  if (runRoborazzi && !runAndroid) throw new Error("Roborazzi requires Android");

  // The `ios` job is a matrix over ios_lanes, and needs.ios.result is success only when every
  // leg succeeded. Pin the lane list to the booleans so a planned 27.1 lane cannot be dropped
  // from the matrix while the gate still reports success.
  let iosLanes;
  try {
    iosLanes = JSON.parse(outputs.ios_lanes);
  } catch {
    throw new Error(`ios_lanes must be valid JSON, got ${outputs.ios_lanes}`);
  }
  const expectedLanes = [
    ...(runIosXcode270 ? [IOS_DEFAULT_LANE] : []),
    ...(runIosXcode271 ? [IOS_XCODE271_LANE] : []),
  ];
  if (JSON.stringify(iosLanes) !== JSON.stringify(expectedLanes)) {
    throw new Error(`ios_lanes must be ${JSON.stringify(expectedLanes)}, got ${outputs.ios_lanes}`);
  }

  const expected = new Map([
    ["android", runAndroid],
    ["ios", runIos],
    // On a queue candidate the emulator jobs always run: for real when planned, else as no-ops.
    [DEVICE_SUITE_JOB, runDeviceSuite || queueCandidate],
    [ATTACHMENT_JOB, runAttachment || queueCandidate],
    [QUEUE_REUSE_JOB, queueReuse],
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
    run_ios_xcode270: runIosXcode270,
    run_ios_xcode271: runIosXcode271,
    ios_lanes: iosLanes,
    run_ios_tests: runIosTests,
    run_roborazzi: runRoborazzi,
    run_device_suite: runDeviceSuite,
    attachment_api_levels: attachmentLevels,
    queue_candidate: queueCandidate,
    queue_reuse: queueReuse,
    reason: outputs.reason || "missing",
  };
}

function fixture({ android, ios, iosXcode270 = ios, iosXcode271 = false, iosTests = false, roborazzi = false, deviceSuite, attachmentLevels, queueCandidate = false, queueReuse = false }) {
  const levels = attachmentLevels ?? [];
  const lanes = [...(iosXcode270 ? [IOS_DEFAULT_LANE] : []), ...(iosXcode271 ? [IOS_XCODE271_LANE] : [])];
  const needs = {
    plan: {
      result: "success",
      outputs: {
        run_android: String(android),
        run_ios: String(ios),
        run_ios_xcode270: String(iosXcode270),
        run_ios_xcode271: String(iosXcode271),
        ios_lanes: JSON.stringify(lanes),
        run_ios_tests: String(iosTests),
        run_roborazzi: String(roborazzi),
        run_device_suite: String(deviceSuite),
        attachment_api_levels: JSON.stringify(levels),
        queue_candidate: String(queueCandidate),
        queue_reuse: String(queueReuse),
        reason: "fixture",
      },
    },
  };
  needs.android = { result: android ? "success" : "skipped" };
  needs.ios = { result: ios ? "success" : "skipped" };
  needs[DEVICE_SUITE_JOB] = { result: deviceSuite || queueCandidate ? "success" : "skipped" };
  needs[ATTACHMENT_JOB] = { result: levels.length > 0 || queueCandidate ? "success" : "skipped" };
  needs[QUEUE_REUSE_JOB] = { result: queueReuse ? "success" : "skipped" };
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
  const none = { android: false, ios: false, deviceSuite: false, attachmentLevels: [] };
  verifyTierResults(fixture(none));
  // ordinary PR Android tier (no Roborazzi) and post-merge main push (with Roborazzi)
  verifyTierResults(fixture({ ...none, android: true, roborazzi: false }));
  verifyTierResults(fixture({ ...none, android: true, roborazzi: true }));
  verifyTierResults(fixture({ ...none, ios: true }));
  verifyTierResults(fixture({ ...none, android: true, ios: true, roborazzi: true }));
  // release-* push: both iOS lanes, Roborazzi on, no device suite
  verifyTierResults(fixture({ ...none, android: true, ios: true, iosXcode271: true, iosTests: true, roborazzi: true }));
  // Stamp merge queue candidate: Android + Roborazzi + the Xcode 27.1 lane alone, emulator jobs as no-ops
  const queue = { ...none, android: true, ios: true, iosXcode270: false, iosXcode271: true, roborazzi: true, queueCandidate: true };
  verifyTierResults(fixture(queue));
  // queue candidate touching iOS test sources: + iOS tests
  verifyTierResults(fixture({ ...queue, iosTests: true }));
  // main push reusing a queue-tested run: only queue-ci-reuse
  verifyTierResults(fixture({ ...none, queueReuse: true }));
  // main push touching iOS test sources: iOS compile + tests
  verifyTierResults(fixture({ ...none, ios: true, iosTests: true }));
  // full-ci PR: Roborazzi + device suite + API31 only, Xcode 27.0 iOS lane only
  verifyTierResults(fixture({ android: true, ios: true, roborazzi: true, deviceSuite: true, attachmentLevels: [31], iosTests: true }));
  // scope=full dispatch: Roborazzi + full device suite + all attachment legs, Xcode 27.0 iOS lane only
  verifyTierResults(fixture({ android: true, ios: true, iosTests: true, roborazzi: true, deviceSuite: true, attachmentLevels: [29, 31, 34] }));
  // nightly: Roborazzi + full device suite + all attachment legs, both iOS lanes
  verifyTierResults(fixture({ android: true, ios: true, iosXcode271: true, iosTests: true, roborazzi: true, deviceSuite: true, attachmentLevels: [29, 31, 34] }));

  // fixture() and the expectation table must not share one formula: a self-test whose fixture
  // is generated by the rule under test cannot fail on a wrong rule (#2294). Pin the iOS-only
  // tier (scope=ios dispatch) literally.
  const iosOnly = fixture({ ...none, ios: true });
  if (iosOnly.ios.result !== "success" || iosOnly.android.result !== "skipped") {
    throw new Error("fixture drift: iOS-only must run ios and skip android");
  }
  verifyTierResults(iosOnly);

  const failedAndroid = fixture({ ...none, android: true });
  failedAndroid.android.result = "failure";
  expectFailure(failedAndroid, "selected Android failure");

  const missingIos = fixture({ ...none, ios: true });
  missingIos.ios.result = "skipped";
  expectFailure(missingIos, "selected iOS job skipped");

  const accidentalDevice = fixture({ ...none, android: true });
  accidentalDevice[DEVICE_SUITE_JOB].result = "success";
  expectFailure(accidentalDevice, "unplanned device suite cost");

  const accidentalAttachment = fixture({ ...none, android: true });
  accidentalAttachment[ATTACHMENT_JOB].result = "success";
  expectFailure(accidentalAttachment, "unplanned attachment cost");

  const deviceWithoutAndroid = fixture({ ...none, ios: true });
  deviceWithoutAndroid.plan.outputs.run_device_suite = "true";
  expectFailure(deviceWithoutAndroid, "device suite without Android");

  const attachmentWithoutAndroid = fixture({ ...none, ios: true });
  attachmentWithoutAndroid.plan.outputs.attachment_api_levels = "[31]";
  expectFailure(attachmentWithoutAndroid, "attachment legs without Android");

  const xcode271WithoutIos = fixture({ ...none, android: true });
  xcode271WithoutIos.plan.outputs.run_ios_xcode271 = "true";
  expectFailure(xcode271WithoutIos, "Xcode 27.1 lane without iOS");

  const droppedXcode271Lane = fixture({ ...none, ios: true, iosXcode271: true });
  droppedXcode271Lane.plan.outputs.ios_lanes = JSON.stringify([IOS_DEFAULT_LANE]);
  expectFailure(droppedXcode271Lane, "planned Xcode 27.1 lane missing from the matrix");

  const unplannedXcode271Lane = fixture({ ...none, ios: true });
  unplannedXcode271Lane.plan.outputs.ios_lanes = JSON.stringify([IOS_DEFAULT_LANE, IOS_XCODE271_LANE]);
  expectFailure(unplannedXcode271Lane, "unplanned GitHub-hosted Xcode 27.1 cost");

  const missingXcode271Flag = fixture({ ...none, ios: true });
  delete missingXcode271Flag.plan.outputs.run_ios_xcode271;
  expectFailure(missingXcode271Flag, "missing run_ios_xcode271");

  // Literal pins (not from fixture()): the candidate's no-op emulator jobs and the reuse job.
  const queueLiteral = fixture(queue);
  if (queueLiteral[DEVICE_SUITE_JOB].result !== "success" || queueLiteral[QUEUE_REUSE_JOB].result !== "skipped") {
    throw new Error("fixture drift: a queue candidate runs the emulator jobs as no-ops and skips queue-ci-reuse");
  }

  const queueSkippedEmulator = fixture(queue);
  queueSkippedEmulator[ATTACHMENT_JOB].result = "skipped";
  expectFailure(queueSkippedEmulator, "queue candidate with a skipped job (Stamp would refuse the batch)");

  const queueReuseRan = fixture(queue);
  queueReuseRan[QUEUE_REUSE_JOB].result = "success";
  expectFailure(queueReuseRan, "queue-ci-reuse ran on a candidate");

  const reuseSkipped = fixture({ ...none, queueReuse: true });
  reuseSkipped[QUEUE_REUSE_JOB].result = "skipped";
  expectFailure(reuseSkipped, "planned queue-ci-reuse skipped");

  const reuseWithAndroid = fixture({ ...none, android: true, queueReuse: true });
  expectFailure(reuseWithAndroid, "queue reuse that still runs Android");

  const reuseOnOrdinary = fixture({ ...none, android: true });
  reuseOnOrdinary[QUEUE_REUSE_JOB].result = "success";
  expectFailure(reuseOnOrdinary, "unplanned queue-ci-reuse");

  const bothQueueFlags = fixture({ ...queue, queueReuse: true });
  expectFailure(bothQueueFlags, "queue_candidate and queue_reuse together");

  const missingQueueFlag = fixture({ ...none, android: true });
  delete missingQueueFlag.plan.outputs.queue_reuse;
  expectFailure(missingQueueFlag, "missing queue_reuse");

  const queueLaneLiteral = fixture({ ...none, android: true, ios: true, iosXcode270: false, iosXcode271: true });
  if (queueLaneLiteral.plan.outputs.ios_lanes !== '["github-xcode27.1"]') {
    throw new Error("fixture drift: the merge-queue tier must plan the Xcode 27.1 lane alone");
  }

  const xcode270WithoutIos = fixture({ ...none, android: true });
  xcode270WithoutIos.plan.outputs.run_ios_xcode270 = "true";
  expectFailure(xcode270WithoutIos, "Xcode 27.0 lane without iOS");

  const iosWithoutLane = fixture({ ...none, ios: true, iosXcode270: false });
  expectFailure(iosWithoutLane, "iOS planned with no lane");

  const queueWithDefaultLane = fixture({ ...none, android: true, ios: true, iosXcode270: false, iosXcode271: true });
  queueWithDefaultLane.plan.outputs.ios_lanes = JSON.stringify([IOS_DEFAULT_LANE, IOS_XCODE271_LANE]);
  expectFailure(queueWithDefaultLane, "unplanned Xcode 27.0 lane on the merge queue");

  const queueDroppedLane = fixture({ ...none, android: true, ios: true, iosXcode270: false, iosXcode271: true });
  queueDroppedLane.plan.outputs.ios_lanes = JSON.stringify([IOS_DEFAULT_LANE]);
  expectFailure(queueDroppedLane, "merge-queue Xcode 27.1 lane swapped for 27.0");

  const missingXcode270Flag = fixture({ ...none, ios: true });
  delete missingXcode270Flag.plan.outputs.run_ios_xcode270;
  expectFailure(missingXcode270Flag, "missing run_ios_xcode270");

  const iosTestsWithoutIos = fixture({ ...none, android: true });
  iosTestsWithoutIos.plan.outputs.run_ios_tests = "true";
  expectFailure(iosTestsWithoutIos, "iOS tests without iOS");

  const missingIosTestsFlag = fixture({ ...none, ios: true });
  delete missingIosTestsFlag.plan.outputs.run_ios_tests;
  expectFailure(missingIosTestsFlag, "missing run_ios_tests");

  const badIosTestsFlag = fixture({ ...none, ios: true });
  badIosTestsFlag.plan.outputs.run_ios_tests = "yes";
  expectFailure(badIosTestsFlag, "non-boolean run_ios_tests");

  const roborazziWithoutAndroid = fixture({ ...none, ios: true });
  roborazziWithoutAndroid.plan.outputs.run_roborazzi = "true";
  expectFailure(roborazziWithoutAndroid, "Roborazzi without Android");

  const missingRoborazziFlag = fixture({ ...none, android: true });
  delete missingRoborazziFlag.plan.outputs.run_roborazzi;
  expectFailure(missingRoborazziFlag, "missing run_roborazzi");

  const badRoborazziFlag = fixture({ ...none, android: true });
  badRoborazziFlag.plan.outputs.run_roborazzi = "yes";
  expectFailure(badRoborazziFlag, "non-boolean run_roborazzi");

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
