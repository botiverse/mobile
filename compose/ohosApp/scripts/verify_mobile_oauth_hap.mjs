#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';

const [hapPath, expectedScheme] = process.argv.slice(2);
if (!hapPath || !expectedScheme) {
  throw new Error('usage: verify_mobile_oauth_hap.mjs <hap> <raft|raft-debug>');
}
if (!fs.statSync(hapPath).isFile()) {
  throw new Error(`HAP does not exist: ${hapPath}`);
}
if (!['raft', 'raft-debug'].includes(expectedScheme)) {
  throw new Error(`Unsupported expected OHOS callback scheme: ${expectedScheme}`);
}

const moduleJson = JSON.parse(execFileSync('unzip', ['-p', hapPath, 'module.json'], { encoding: 'utf8' }));
const entryAbility = moduleJson.module?.abilities?.find((ability) => ability.name === 'EntryAbility');
if (entryAbility?.exported !== true) {
  throw new Error('EntryAbility must be exported for browser callbacks');
}
const callbackUris = (entryAbility?.skills ?? [])
  .filter((skill) => skill.actions?.includes('ohos.want.action.viewData') &&
    skill.entities?.includes('entity.system.browsable'))
  .flatMap((skill) => skill.uris ?? [])
  .filter((uri) => ['oauth', 'login'].includes(uri.host) && uri.path === 'callback');

if (callbackUris.length !== 2) {
  throw new Error(`Expected one OAuth and one login callback URI, found ${callbackUris.length}`);
}
for (const host of ['oauth', 'login']) {
  const matches = callbackUris.filter((uri) => uri.host === host);
  if (matches.length !== 1 || matches[0].scheme !== expectedScheme) {
    throw new Error(`Unexpected packaged ${host} callback URI: ${JSON.stringify(matches)}`);
  }
}
console.log(`OHOS packaged auth callback contract: PASS (${expectedScheme}, oauth + login only)`);
