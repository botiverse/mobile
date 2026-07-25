#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const ohosAppRoot = path.resolve(scriptDirectory, '..');
const composeRoot = path.resolve(ohosAppRoot, '..');

function read(relativePath) {
  return fs.readFileSync(path.resolve(composeRoot, relativePath), 'utf8').replaceAll('\r\n', '\n');
}

function assertContract(condition, message) {
  if (!condition) {
    throw new Error(`OHOS process-network lifecycle contract failed: ${message}`);
  }
}

function methodBody(source, startMarker, endMarker) {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start + startMarker.length);
  assertContract(start >= 0 && end > start, `unable to isolate ${startMarker}`);
  return source.slice(start, end);
}

const entryAbility = read('ohosApp/entry/src/main/ets/entryability/EntryAbility.ets');
const lifecycle = read('ohosApp/entry/src/main/ets/entryability/SlockOhosProcessNetworkLifecycle.ets');
const nativeBridge = read('ohosBridge/src/ohosArm64Main/kotlin/OhosNativeBridge.kt');
const napi = read('ohosApp/entry/src/main/cpp/napi_init.cpp');
const napiTypes = read('ohosApp/entry/src/main/cpp/types/libentry/index.d.ts');

const setupIndex = entryAbility.indexOf('this.setupKnoi();');
const installIndex = entryAbility.indexOf(
  'slockOhosProcessNetworkLifecycle.install(this.context.getApplicationContext());'
);
assertContract(setupIndex >= 0 && installIndex > setupIndex, 'process listener must install after KNOI setup');

const abilityForeground = methodBody(entryAbility, '  onForeground(): void {', '  onBackground(): void {');
const abilityBackground = methodBody(entryAbility, '  onBackground(): void {', '\n}\n');
for (const [name, body] of [['onForeground', abilityForeground], ['onBackground', abilityBackground]]) {
  assertContract(
    !body.includes('updateProcessNetworkVisibility') &&
      !body.includes('slockOhosProcessNetworkLifecycle'),
    `${name} must not drive process request retirement`
  );
}

assertContract(
  lifecycle.includes("applicationContext.on('applicationStateChange', callback)"),
  'ApplicationContext.applicationStateChange must be the lifecycle authority'
);
assertContract(
  lifecycle.includes('onApplicationForeground()') && lifecycle.includes('onApplicationBackground()'),
  'both application foreground/background callbacks are required'
);
assertContract(
  !lifecycle.includes("'abilityLifecycle'") && !lifecycle.includes('onWindowStage'),
  'adapter must not depend on ability/window lifecycle events'
);

assertContract(
  nativeBridge.includes('fun raftUpdateProcessNetworkVisibility(foreground: Boolean): Int') &&
    nativeBridge.includes('updateSlockProcessNetworkVisibility(foreground)'),
  'minimal Kotlin/Native root bridge must delegate to the shared coordinator'
);
assertContract(
  napi.includes('api->kotlin.root.raftUpdateProcessNetworkVisibility(foreground)') &&
    napi.includes('{"updateProcessNetworkVisibility", nullptr, UpdateProcessNetworkVisibility'),
  'NAPI must expose and invoke the root visibility primitive'
);
assertContract(
  napi.includes('size_t argc = 2;') && napi.includes('argc == 1'),
  'NAPI must detect and reject missing or extra visibility arguments'
);
assertContract(
  napiTypes.includes('updateProcessNetworkVisibility: (foreground: boolean) => number'),
  'ArkTS declaration must expose Boolean-to-number visibility bridge'
);

console.log('OHOS process-network lifecycle source contract: PASS');
