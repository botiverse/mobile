#!/usr/bin/env node
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const compose = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const read = (file) => fs.readFileSync(path.join(compose, file), 'utf8');
const entry = read('ohosApp/entry/src/main/ets/entryability/EntryAbility.ets');
const bridge = read('shared/src/ohosArm64Main/kotlin/build/raft/app/data/SlockOhosFontPreferenceKnoiBridge.kt');
const store = read('shared/src/ohosArm64Main/kotlin/build/raft/app/data/SlockOhosMMKVKeyValueStore.kt');
const shared = read('shared/src/commonMain/kotlin/build/raft/app/data/KeyValueStore.kt');

const onCreate = entry.slice(entry.indexOf('  onCreate('), entry.indexOf('  onNewWant('));
assert.ok(onCreate.includes('this.setupKnoi();'), 'restore must run in ability creation, before window-stage content');
const setup = entry.slice(entry.indexOf('  private setupKnoi()'), entry.indexOf('  private updateSafeAreaStorage('));
const ordered = [
  'initializeSlockOhosAppScopeMMKV(this.context);',
  "setup('libshared.so', BuildProfile.DEBUG);",
  'init();',
  "invoke<string>('slockOhosRestoreStoredFontPreferenceOnColdStart')",
];
let previous = -1;
for (const marker of ordered) {
  const index = setup.indexOf(marker);
  assert.ok(index > previous, `startup order missing or reversed: ${marker}`);
  previous = index;
}
assert.ok(!setup.includes('await ') && !setup.includes('setTimeout('), 'restore must finish synchronously before setup returns');
assert.match(bridge, /@KNExport\s+fun slockOhosRestoreStoredFontPreferenceOnColdStart\(\): String\s*\{\s*slockRestoreStoredFontPreferenceOnColdStart\(\)\s+return slockReadStoredFontPreferenceForDiagnostics\(\)/);
assert.match(setup, /hilog\.info\(0x0000, SLOCK_KNOI_LOG_TAG, '%\{public\}s', fontDiagnostic\)/,
  'raw/parsed store diagnostic must reach the native cold-start log');
assert.match(shared, /fun slockRestoreStoredFontPreferenceOnColdStart\(\)[\s\S]*?readFontPreferenceSync\(\)/,
  'cold-start restore must force a synchronous read, rather than leave lazy MMKV untouched');
assert.match(store, /override fun getString\(key: String\): String\?[\s\S]*?mmkv_c_contains_key\(mmkvHandle,/,
  'reading a stored preference must force the lazy native handle');
console.log('OHOS shared font cold-start source contract: PASS');
