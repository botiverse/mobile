#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const appRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const rules = [
  ['entry/src/main/ets/entryability/EntryAbility.ets', /new SlockOhosMobileOAuthIngress\(\(uri\) => completeSlockOhosAppLoginCallback\(uri\) \|\| completeSlockOhosMobileOAuthCallback\(uri\)\)/, 'production ingress owns the real callback consumer'],
  ['entry/src/main/ets/entryability/EntryAbility.ets', /mobileOAuthIngress\.captureColdStart\(want\.uri\)/, 'cold Want is queued'],
  ['entry/src/main/ets/entryability/EntryAbility.ets', /mobileOAuthIngress\.captureWarmStart\(want\.uri\)/, 'warm Want is delivered'],
  ['entry/src/main/ets/entryability/EntryAbility.ets', /mobileOAuthIngress\.markPageContentLoaded\(\)/, 'page-ready fence is released'],
  ['entry/src/main/ets/kuikly/SlockOhosMobileOAuth.ets', /dispatchSlockOhosMobileOAuthCallback\(uri, \(callback\) => invoke<boolean>\(/, 'admission precedes KNOI completion'],
  ['../shared/src/ohosArm64Main/kotlin/build/raft/app/data/SlockOhosMobileOAuthKnoiBridge.kt', /eventName = SLOCK_MOBILE_OAUTH_COMPLETION_EVENT/, 'shared completion posts the refresh event'],
  ['../shared/src/commonMain/kotlin/build/raft/app/SettingsAccountPage.kt', /observe\(SLOCK_MOBILE_OAUTH_COMPLETION_EVENT\)/, 'Settings observes the completion event'],
  ['../shared/src/commonMain/kotlin/build/raft/app/SettingsAccountPage.kt', /if \(shouldReloadSettingsAccountAfterMobileOAuth\(event\)\) \{[\s\S]{0,240}LoadPageContent\(SharedSettingsDestination\.Account\)/, 'successful completion reloads canonical account state'],
  ['entry/hvigorfile.ts', /slockOhosOAuthCallbackPlugin\(\)/, 'HAP build installs the callback skill plugin'],
];

const sources = new Map();
for (const [relativePath] of rules) {
  if (!sources.has(relativePath)) {
    sources.set(relativePath, fs.readFileSync(path.resolve(appRoot, relativePath), 'utf8'));
  }
}

function verify(candidateSources) {
  for (const [relativePath, pattern, label] of rules) {
    if (!pattern.test(candidateSources.get(relativePath) ?? '')) {
      throw new Error(`OHOS mobile OAuth source contract missing: ${label} (${relativePath})`);
    }
  }
}

verify(sources);

// Each production rule is mutation-tested in memory. Removing any one real
// callsite must make the same verifier fail closed.
for (const [relativePath, pattern, label] of rules) {
  const mutated = new Map(sources);
  mutated.set(relativePath, (sources.get(relativePath) ?? '').replace(pattern, ''));
  let rejected = false;
  try {
    verify(mutated);
  } catch {
    rejected = true;
  }
  if (!rejected) {
    throw new Error(`OHOS mobile OAuth mutation survived: ${label}`);
  }
}

console.log(`OHOS mobile OAuth production contract: PASS (${rules.length}/${rules.length} mutation teeth)`);
