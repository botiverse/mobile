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
const moduleProfile = read('entry/src/main/module.json5');

assertContract(
  moduleProfile.includes('"ohos.want.action.sendData"') &&
    moduleProfile.includes('"entity.system.default"') &&
    moduleProfile.includes('"type": "*/*"') &&
    moduleProfile.includes('"utd": "general.data"'),
  'EntryAbility must declare the sendData action plus MIME and UTD admission'
);

assertContract(
  (entry.match(/this\.admitExternalShareWant\(want\);/g) ?? []).length === 2 &&
    entry.includes('onCreate(want: Want, launchParam: AbilityConstant.LaunchParam)') &&
    entry.includes('onNewWant(want: Want, _launchParam: AbilityConstant.LaunchParam)'),
  'cold and warm Wants must converge on one intake owner'
);

assertContract(
  intake.includes('fs.readSync(sourceFd, buffer)') &&
    intake.includes('fs.writeSync(target.fd, buffer, { offset: written, length: read - written })') &&
    intake.includes('fs.renameSync(temporary, target)') &&
    intake.includes('clearRawWant(want, parameters)') &&
    intake.indexOf('clearRawWant(want, parameters)') < intake.indexOf('copyItems(context, uris,'),
  'provider data must be cleared from retained Want state and privately/atomically copied'
);

assertContract(
  intake.includes("launch: { intent: SLOCK_OHOS_EXTERNAL_SHARE_INTENT, externalShareToken: token }") &&
    !intake.includes('launch: { intent: SLOCK_OHOS_EXTERNAL_SHARE_INTENT, externalShareToken: token,') &&
    index.includes("pageData['intent'] = SLOCK_OHOS_EXTERNAL_SHARE_INTENT;") &&
    index.includes("pageData['externalShareToken'] = externalShare['externalShareToken'];") &&
    index.includes("return { 'externalShareToken': token };") &&
    !index.includes("pageData['text']") &&
    !index.includes("pageData['subject']") &&
    !index.includes("pageData['items']"),
  'share pageData must be reconstructed from intent and opaque token only'
);

console.log('OHOS external-share intake source contract: PASS');
