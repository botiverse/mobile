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
const callbackUris = (entryAbility?.skills ?? [])
  .filter((skill) => skill.actions?.includes('ohos.want.action.viewData'))
  .flatMap((skill) => skill.uris ?? [])
  .filter((uri) => uri.host === 'oauth' && uri.path === '/callback');

if (callbackUris.length !== 1) {
  throw new Error(`Expected exactly one packaged OAuth callback URI, found ${callbackUris.length}`);
}
const [callbackUri] = callbackUris;
if (callbackUri.scheme !== expectedScheme || callbackUri.host !== 'oauth' || callbackUri.path !== '/callback') {
  throw new Error(`Unexpected packaged OAuth callback URI: ${JSON.stringify(callbackUri)}`);
}

console.log(`OHOS packaged OAuth callback contract: PASS (${expectedScheme}://oauth/callback only)`);
