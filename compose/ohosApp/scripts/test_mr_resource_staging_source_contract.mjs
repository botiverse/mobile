#!/usr/bin/env node

// Static build-wiring contract for incident task #44. This intentionally
// inspects Gradle source rather than pretending to be a behavioral unit test:
// a clean DevEco build must stage the output of generateMRohosArm64Main and
// must not require the standalone mergeOhosResources task.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const composeRoot = path.resolve(scriptDirectory, '..', '..');

function assertContract(condition, message) {
  if (!condition) {
    throw new Error(`OHOS MR resource staging contract failed: ${message}`);
  }
}

function taskBlock(source, taskName) {
  const marker = `tasks.register<Sync>("${taskName}")`;
  const markerIndex = source.indexOf(marker);
  assertContract(markerIndex >= 0, `missing ${taskName}`);
  const blockStart = source.indexOf('{', markerIndex + marker.length);
  assertContract(blockStart >= 0, `${taskName} has no body`);

  let depth = 0;
  for (let index = blockStart; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1;
    if (source[index] === '}') depth -= 1;
    if (depth === 0) return source.slice(blockStart + 1, index);
  }
  throw new Error(`OHOS MR resource staging contract failed: unterminated ${taskName}`);
}

export function validateMrResourceStagingSource(source) {
  const resourceTask = taskBlock(source, 'copyMrResourcesForOhosEntry');
  const assetTask = taskBlock(source, 'copyMrKuiklyAssetsForOhosEntry');
  const generatedRoot = 'generated/tmm-res/ohosArm64Main/res';

  for (const [label, block] of [
    ['resource task', resourceTask],
    ['asset task', assetTask],
  ]) {
    assertContract(
      block.includes('dependsOn(":shared:generateMRohosArm64Main")'),
      `${label} must depend directly on generateMRohosArm64Main`
    );
    assertContract(
      !block.includes('mergeOhosResources') && !block.includes('mergeRes'),
      `${label} must not depend on the unavailable merge task/output`
    );
  }

  assertContract(
    resourceTask.includes(`dir("${generatedRoot}")`),
    'resource task must stage the generator res root'
  );
  assertContract(
    assetTask.includes(`dir("${generatedRoot}/rawfile/assets")`),
    'asset task must mirror generated rawfile assets into the Kuikly resfile root'
  );
  assertContract(
    source.includes(
      'project(":shared").tasks.matching { it.name == "generateMRohosArm64Main" }.configureEach {'
    ) && source.includes('finalizedBy(copyMrResourcesForOhosEntry, copyMrKuiklyAssetsForOhosEntry)'),
    'generateMRohosArm64Main must finalize both staging tasks'
  );
}

function runSelfTests() {
  const valid = `
val copyMrResourcesForOhosEntry = tasks.register<Sync>("copyMrResourcesForOhosEntry") {
  dependsOn(":shared:generateMRohosArm64Main")
  from(project(":shared").layout.buildDirectory.dir("generated/tmm-res/ohosArm64Main/res"))
}
val copyMrKuiklyAssetsForOhosEntry = tasks.register<Sync>("copyMrKuiklyAssetsForOhosEntry") {
  dependsOn(":shared:generateMRohosArm64Main")
  from(project(":shared").layout.buildDirectory.dir("generated/tmm-res/ohosArm64Main/res/rawfile/assets"))
}
project(":shared").tasks.matching { it.name == "generateMRohosArm64Main" }.configureEach {
  finalizedBy(copyMrResourcesForOhosEntry, copyMrKuiklyAssetsForOhosEntry)
}`;
  validateMrResourceStagingSource(valid);

  const failures = [
    valid.replace(
      'dependsOn(":shared:generateMRohosArm64Main")',
      'dependsOn(":shared:generateMRohosArm64Main", ":shared:mergeOhosResources")'
    ),
    valid.replace(
      'generated/tmm-res/ohosArm64Main/res"',
      'generated/tmm-res/ohosArm64Main/mergeRes"'
    ),
    valid.replace(
      'it.name == "generateMRohosArm64Main"',
      'it.name == "mergeOhosResources"'
    ),
    valid.replace(
      'finalizedBy(copyMrResourcesForOhosEntry, copyMrKuiklyAssetsForOhosEntry)',
      'finalizedBy(copyMrResourcesForOhosEntry)'
    ),
  ];
  failures.forEach((fixture, index) => {
    let rejected = false;
    try {
      validateMrResourceStagingSource(fixture);
    } catch (_error) {
      rejected = true;
    }
    assertContract(rejected, `negative self-test ${index + 1} unexpectedly passed`);
  });
}

runSelfTests();
validateMrResourceStagingSource(
  fs.readFileSync(path.resolve(composeRoot, 'build.ohos.gradle.kts'), 'utf8').replaceAll('\r\n', '\n')
);
console.log('OHOS MR resource staging source contract: PASS (self-tests=5)');
