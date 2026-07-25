#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const ohosRoot = path.resolve(scriptDirectory, '..');

function read(relativePath) {
  return fs.readFileSync(path.resolve(ohosRoot, relativePath), 'utf8').replaceAll('\r\n', '\n');
}

function assertContract(condition, message) {
  if (!condition) throw new Error(`OHOS external-share intake contract failed: ${message}`);
}

const entry = read('entry/src/main/ets/entryability/EntryAbility.ets');
const intake = read('entry/src/main/ets/entryability/SlockOhosExternalShareIntake.ets');
const index = read('entry/src/main/ets/pages/Index.ets');
const launchBridge = read('entry/src/main/ets/kuikly/SlockOhosExternalShareLaunchBridge.ets');
const moduleProfile = read('entry/src/main/module.json5');

assertContract(
  moduleProfile.includes('"ohos.want.action.sendData"') &&
    moduleProfile.includes('"entity.system.default"') &&
    moduleProfile.includes('"type": "*/*"') &&
    moduleProfile.includes('"utd": "general.data"'),
  'EntryAbility must declare the sendData action plus MIME and UTD admission'
);

assertContract(
  (entry.match(/externalShareAdmissionOwner[^\n]*\.submit\(want\);/g) ?? []).length === 2 &&
    entry.includes('onCreate(want: Want, launchParam: AbilityConstant.LaunchParam)') &&
    entry.includes('onNewWant(want: Want, _launchParam: AbilityConstant.LaunchParam)'),
  'cold and warm Wants must converge on one intake owner'
);

assertContract(
  intake.includes('@Concurrent\nexport function persistSlockOhosExternalShare') &&
    intake.includes("import taskpool from '@ohos.taskpool';") &&
    intake.includes('new taskpool.Task(') &&
    intake.includes('private tail: Promise<void> = Promise.resolve();'),
  'all filesystem work must run in one serial taskpool owner'
);

assertContract(
  intake.includes('fs.readSync(sourceFd, buffer)') &&
    intake.includes('fs.writeSync(target.fd, buffer, { offset: offset, length: length })') &&
    intake.includes('fs.renameSync(temporary, target)') &&
    intake.includes('clearRawWant(want, parameters)') &&
    intake.indexOf('clearRawWant(want, parameters)') < intake.indexOf('new taskpool.Task(') &&
    intake.includes('fs.statSync(file.fd).size !== bytes.byteLength'),
  'provider data must be cleared from retained Want state and privately/atomically copied'
);

assertContract(
  intake.includes("normalized.startsWith('file://') || normalized.startsWith('content://')") &&
    intake.includes('const canonical = fs.realPathSync(uri);') &&
    intake.includes('canonical.startsWith(`${privateRoot}/`)') &&
    !intake.includes('fingerprint('),
  'OHOS input must be grant-shaped, reject canonical private paths, and avoid weak fingerprint decisions'
);

assertContract(
  intake.includes("launch: { intent: SLOCK_OHOS_EXTERNAL_SHARE_INTENT, externalShareToken: token }") &&
    !intake.includes('launch: { intent: SLOCK_OHOS_EXTERNAL_SHARE_INTENT, externalShareToken: token,') &&
    launchBridge.includes('intent: launch.intent,') &&
    launchBridge.includes('externalShareToken: launch.externalShareToken') &&
    !index.includes("pageData['text']") &&
    !index.includes("pageData['subject']") &&
    !index.includes("pageData['items']"),
  'share pageData must be reconstructed from intent and opaque token only'
);

assertContract(
  entry.includes('slockOhosExternalShareLaunchBridge.publish(launch);') &&
    index.includes('slockOhosExternalShareLaunchBridge.setController(controller);') &&
    launchBridge.includes("this.controller.sendEvent(EXTERNAL_SHARE_LAUNCH_EVENT") &&
    launchBridge.includes('this.deliveredToken = launch.externalShareToken;'),
  'warm launches must cross an explicit mounted-controller exactly-once bridge'
);

console.log('OHOS external-share intake source contract: PASS');
