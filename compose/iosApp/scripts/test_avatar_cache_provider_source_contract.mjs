#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(scriptDirectory, '../../..');
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8').replaceAll('\r\n', '\n');
const provider = read('compose/iosApp/iosApp/RaftIosAvatarCacheProvider.swift');
const app = read('compose/iosApp/iosApp/iOSApp.swift');
const hostHooks = read('compose/shared/src/iosMain/kotlin/build/raft/app/RaftIosHostHooks.kt');
const bridge = read('compose/iosApp/iosApp/Host/HRBridgeModule.m');
const project = read('compose/iosApp/iosApp.xcodeproj/project.pbxproj');

function fail(message) {
  throw new Error(`iOS avatar-cache provider source contract failed: ${message}`);
}

function check(condition, message) {
  if (!condition) fail(message);
}

function count(body, marker) {
  return body.split(marker).length - 1;
}

function validateProvider(candidate) {
  check(count(candidate, 'final class RaftIosAvatarDurableStore') === 1, 'durable store must exist once');
  check(candidate.includes('RaftAvatarMountedNativeProvider'), 'provider must implement shared mounted contract');
  check(candidate.includes('RaftAvatarMountedNativeProviderRegistry.shared.install(next: store)'), 'provider must install through shared registry');
  check(candidate.includes('slock-avatar-render-v1:'), 'managed render protocol must be recognized');
  check(candidate.includes('raftIosAvatarMaxEntryBytes: Int64 = 5 * 1024 * 1024'), '5 MiB entry limit drifted');
  check(candidate.includes('raftIosAvatarMaxManagedBytes: Int64 = 64 * 1024 * 1024'), '64 MiB budget drifted');
  for (const marker of [
    'persistIndex()',
    'atomicWrite(',
    'recoverStartup()',
    'retireOwner(',
    'retireSubject(',
    'clearAll(',
    'retireEntry(at: index)',
    'readBlocked(slot:',
    'recordMovedCandidateCleanup(',
  ]) {
    check(candidate.includes(marker), `missing durable/terminal marker ${marker}`);
  }
  check(
    count(candidate, 'if operation.phase == .finalized') === 2,
    'finalize idempotence and post-finalize rollback teeth must both remain'
  );
  check(
    count(candidate, 'guard !readBlocked(slot:') === 2,
    'inspect and render must both honor read-admission hold'
  );
  check(!candidate.includes('remoteUrl'), 'native provider must not become a second downloader');
}

validateProvider(provider);

const install = app.indexOf('RaftIosAvatarCacheHost.shared.install()');
const firstPageOwner = app.indexOf('RaftIosAppScopeMMKV.install()');
check(install >= 0 && firstPageOwner > install, 'provider must install before shared app scope starts');

// CMP image loader: managed descriptors are read from the durable store, never downloaded.
check(provider.includes('extension RaftIosAvatarCacheHost: RaftIosManagedImageStore'), 'host must serve managed reads to the CMP image loader');
check(provider.includes('store.renderData('), 'managed read must resolve exact committed native bytes');
check(hostHooks.includes('RaftImageHost.managedImageReader'), 'iOS host hooks must install the managed image reader');
const hooksInstall = app.search(/RaftIosHostHooks\.shared\.install\(\s*managedImages: RaftIosAvatarCacheHost\.shared\b/);
check(hooksInstall > install, 'managed image reader must be installed after the provider');

const bridgeStart = bridge.indexOf('- (void)avatarCacheCapability:(NSDictionary *)args {');
const bridgeEnd = bridge.indexOf('\n- (void)settingsCapability:', bridgeStart);
const bridgeBody = bridge.slice(bridgeStart, bridgeEnd);
check(bridgeStart >= 0 && bridgeEnd > bridgeStart, 'unable to isolate bridge method');
check(bridgeBody.includes('handleLegacyCommand:params'), 'legacy bridge must delegate terminal cleanup to provider');
check(!bridgeBody.includes('Avatar cache capability unavailable'), 'unsupported fallback must be removed');

check(project.includes('A42400010000000000000001 /* RaftIosAvatarCacheProvider.swift in Sources */,'), 'provider missing from app target');
check(project.includes('A42400010000000000000002 /* RaftIosAvatarCacheProvider.swift in Sources */,'), 'provider missing from unit-test target');
check(project.includes('A42400010000000000000003 /* RaftIosAvatarCacheProviderTests.swift in Sources */,'), 'provider tests missing from unit-test target');

function assertProviderMutationRejected(mutation, label) {
  check(mutation !== provider, 'source-contract mutation fixture did not change source');
  try {
    validateProvider(mutation);
  } catch (_error) {
    return;
  }
  fail(`self-test accepted ${label}`);
}

assertProviderMutationRejected(
  provider.replace('RaftAvatarMountedNativeProviderRegistry.shared.install(next: store)', '/* removed */'),
  'missing registry install'
);
assertProviderMutationRejected(
  provider.replace('raftIosAvatarMaxEntryBytes: Int64 = 5 * 1024 * 1024', 'raftIosAvatarMaxEntryBytes: Int64 = 6 * 1024 * 1024'),
  'entry-limit drift'
);
assertProviderMutationRejected(
  provider.replace('raftIosAvatarMaxManagedBytes: Int64 = 64 * 1024 * 1024', 'raftIosAvatarMaxManagedBytes: Int64 = 65 * 1024 * 1024'),
  'budget drift'
);
assertProviderMutationRejected(
  provider.replaceAll('if operation.phase == .finalized', 'if false'),
  'missing post-finalize rollback tooth'
);
assertProviderMutationRejected(
  provider.replaceAll('guard !readBlocked(slot:', 'guard true || !readBlocked(slot:'),
  'missing read-admission hold'
);
assertProviderMutationRejected(
  provider.replaceAll('recordMovedCandidateCleanup(', 'recordDiscardedCandidateCleanup('),
  'missing post-move candidate cleanup debt'
);

console.log('iOS avatar-cache provider source contract passed');
