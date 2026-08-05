#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, '../../..');
const hostPath = path.resolve(
  repositoryRoot,
  'compose/iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m'
);
const projectPath = path.resolve(repositoryRoot, 'compose/iosApp/iosApp.xcodeproj/project.pbxproj');
const requestedHost = process.argv[2];
const hostSource = (
  requestedHost === '-'
    ? fs.readFileSync(0, 'utf8')
    : fs.readFileSync(requestedHost ? path.resolve(requestedHost) : hostPath, 'utf8')
).replaceAll('\r\n', '\n');
const projectSource = fs.readFileSync(projectPath, 'utf8').replaceAll('\r\n', '\n');

function fail(message) {
  throw new Error(`iOS avatar-cache unsupported bridge contract failed: ${message}`);
}

function assertContract(condition, message) {
  if (!condition) fail(message);
}

function runGit(args, workingDirectory) {
  const result = spawnSync('git', args, {
    cwd: workingDirectory,
    encoding: 'utf8',
    maxBuffer: 8 * 1024 * 1024
  });
  assertContract(!result.error, `git failed to start: ${result.error?.message}`);
  assertContract(result.status === 0, `git ${args.join(' ')} failed: ${result.stderr.trim()}`);
  return result.stdout;
}

function section(body, startMarker, endMarker, name) {
  const start = body.indexOf(startMarker);
  const end = body.indexOf(endMarker, start + startMarker.length);
  assertContract(start >= 0 && end > start, `unable to isolate ${name}`);
  return body.slice(start, end);
}

function occurrenceCount(body, marker) {
  return body.split(marker).length - 1;
}

const kuiklyGitlink = runGit(['rev-parse', 'HEAD:third_party/kuikly-ui'], repositoryRoot).trim();
assertContract(/^[0-9a-f]{40}$/.test(kuiklyGitlink), 'Kuikly gitlink is not an exact commit');
const kuiklyRepository = process.env.SLOCK_KUIKLY_IOS_SOURCE_ROOT ||
  path.resolve(repositoryRoot, 'third_party/kuikly-ui');
const baseModuleSource = runGit([
  'show',
  `${kuiklyGitlink}:core-render-ios/Extension/Modules/KRBaseModule.m`
], kuiklyRepository).replaceAll('\r\n', '\n');

const baseUnknownBranch = section(
  baseModuleSource,
  '    } else {',
  '    }\n    return nil;',
  'pinned KRBaseModule unknown-selector branch'
);
const assertionIndex = baseUnknownBranch.indexOf('NSAssert(false, reason);');
const callbackIndex = baseUnknownBranch.indexOf('callback(');
assertContract(
  assertionIndex >= 0 && callbackIndex > assertionIndex,
  'pinned unknown-selector branch must prove Debug asserts before its fallback callback'
);

const debugConfiguration = /\/\* Debug \*\/ = \{[\s\S]*?name = Debug;\n\s*\};/.exec(projectSource)?.[0];
const releaseConfiguration = /\/\* Release \*\/ = \{[\s\S]*?name = Release;\n\s*\};/.exec(projectSource)?.[0];
assertContract(debugConfiguration !== undefined, 'unable to isolate the Debug build configuration');
assertContract(releaseConfiguration !== undefined, 'unable to isolate the Release build configuration');
assertContract(
  !debugConfiguration.includes('ENABLE_NS_ASSERTIONS = NO;'),
  'Debug must remain an assertion-enabled proof of the inherited crash path'
);
assertContract(
  releaseConfiguration.includes('ENABLE_NS_ASSERTIONS = NO;'),
  'Release assertion policy changed; re-audit the inherited fallback premise'
);

const methodStart = '- (void)avatarCacheCapability:(NSDictionary *)args {';
const methodEnd = '\n- (void)settingsCapability:(NSDictionary *)args {';

function validateHost(candidateSource) {
  assertContract(
    occurrenceCount(candidateSource, methodStart) === 1,
    'app-owned avatarCacheCapability selector must exist exactly once'
  );
  const methodBody = section(
    candidateSource,
    methodStart,
    methodEnd,
    'HRBridgeModule avatarCacheCapability'
  );
  assertContract(
    occurrenceCount(methodBody, 'KuiklyRenderCallback callback = args[KR_CALLBACK_KEY];') === 1,
    'fallback must read the supplied callback exactly once'
  );
  assertContract(methodBody.includes('if (!callback) return;'), 'nil callback must fail closed');
  assertContract(
    occurrenceCount(methodBody, 'callback(@{') === 1,
    'fallback must invoke the supplied callback exactly once'
  );
  assertContract(
    /@"code"\s*:\s*@\(-1\)/.test(methodBody),
    'fallback must report code=-1'
  );
  assertContract(
    !/(NSAssert|NSCAssert|@throw|raiseException|performSelector)/.test(methodBody),
    'app-owned fallback must not throw or re-enter the inherited selector path'
  );
  return methodBody;
}

function assertMutationRejected(candidateSource, label) {
  try {
    validateHost(candidateSource);
  } catch (_error) {
    return;
  }
  fail(`self-test accepted ${label}`);
}

const methodBody = validateHost(hostSource);
const callbackStart = methodBody.indexOf('    callback(@{');
const callbackEndMarker = '    });';
const callbackEnd = methodBody.indexOf(callbackEndMarker, callbackStart) + callbackEndMarker.length;
assertContract(callbackStart >= 0 && callbackEnd >= callbackEndMarker.length, 'unable to isolate callback');
const callbackInvocation = methodBody.slice(callbackStart, callbackEnd);

assertMutationRejected(hostSource.replace(methodBody, ''), 'missing app-owned selector');
assertMutationRejected(
  hostSource.replace(methodBody, methodBody.replace(callbackInvocation, `${callbackInvocation}\n${callbackInvocation}`)),
  'double callback'
);
assertMutationRejected(
  hostSource.replace(methodBody, methodBody.replace('@"code" : @(-1)', '@"code" : @(0)')),
  'non-fail-closed status code'
);
assertMutationRejected(
  hostSource.replace(methodBody, methodBody.replace('    callback(@{', '    NSAssert(false, @"mutation");\n    callback(@{')),
  'throwing Debug fallback'
);

console.log(`iOS avatar-cache unsupported bridge contract passed (${kuiklyGitlink.slice(0, 12)})`);
