#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Task #51 / QA review 3dce640d: executable unit tests own trace behavior. This narrow source
// contract locks only the final production callsites, injected persistent sinks, and causal order.
const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const ohosAppRoot = path.resolve(scriptDirectory, '..');
const composeRoot = path.resolve(ohosAppRoot, '..');

const sharedPath = 'shared/src/commonMain/kotlin/build/raft/app/SlockFilePreview.kt';
const hostPath = 'ohosApp/entry/src/main/ets/kuikly/modules/KRBridgeModule.ets';

function read(relativePath) {
  return fs.readFileSync(path.resolve(composeRoot, relativePath), 'utf8').replaceAll('\r\n', '\n');
}

function fail(message) {
  throw new Error(`OHOS attachment-open diagnostic contract failed: ${message}`);
}

function assertContract(condition, message) {
  if (!condition) fail(message);
}

function section(source, startMarker, endMarker, name) {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start + startMarker.length);
  assertContract(start >= 0 && end > start, `unable to isolate ${name}`);
  return source.slice(start, end);
}

function count(haystack, needle) {
  return haystack.split(needle).length - 1;
}

function assertExactlyOnce(body, marker, name) {
  assertContract(count(body, marker) === 1, `${name} must appear exactly once in its production flow`);
}

function assertOrdered(body, markers, name) {
  let cursor = -1;
  for (const marker of markers) {
    const next = body.indexOf(marker, cursor + 1);
    assertContract(next > cursor, `${name} missing or out of order: ${marker}`);
    cursor = next;
  }
}

const sharedRequestMarkers = [
  'val policy = slockFilePreviewExternalOpenPolicy(',
  'val rejectionReason =',
  'traceSlockFilePreviewExternalOpenAdmission(rejectionReason, policy, bridge::log)',
  'val owner = attachmentOwner ?: return',
  'val admittedPolicy = policy ?: return',
];

const sharedHostMarkers = [
  'runSlockFilePreviewExternalOpenTraceFlow(',
  'resolveUrl = {',
  'onUrlResolved = { externalUrl ->',
  'openHost = { externalUrl ->',
  'bridge.openAttachment(',
  'statusOf = { result -> result?.optString("status") },',
  'trace = bridge::log',
  'SlockFilePreviewExternalOpenTraceResult.UrlResolutionFailed -> return unableToDownloadAttachment',
];

const hostMarkers = [
  'const preflightTrace = new SlockOhosAttachmentOpenPreflightTrace(',
  'SlockOhosDiagnostics.record("SlockAttachmentOpenPreflight", message)',
  'preflightTrace.requested();',
  'const preflightDecision = slockOhosAttachmentOpenPreflightDecision(',
  'preflightTrace.publicDownloadMissing(privateCommitted);',
  'preflightTrace.requestRejected();',
  'preflightTrace.cacheChecked(privateCommitted, cached);',
  'preflightTrace.downloadStarted(privateCommitted);',
  'slockOhosAttachmentDownloadSink.download(',
  'preflightTrace.downloadTerminal(privateCommitted, downloadResult["status"] as string | undefined);',
  'if (downloadResult["status"] !== "complete") {',
  'preflightTrace.wantHandoff(privateCommitted, !cached);',
  'buildSlockOhosAttachmentOpenWants(',
  'SlockOhosAttachmentOpenDiagnosticTerminal.REQUESTED',
  'await context.startAbility(openWants.view);',
  'SlockOhosAttachmentOpenDiagnosticTerminal.LAUNCH_SUCCEEDED',
];

const forbiddenHostOpenMarkers = [
  'ohos.want.action.select',
  '.chooser',
  'ability.want.params.INTENT',
];

function validateShared(source) {
  const requestBody = section(
    source,
    '    fun requestExternalOpen() {',
    '    fun requestDownloadControl(',
    'shared requestExternalOpen'
  );
  const hostBody = section(
    source,
    '    suspend fun openPayloadExternally(',
    '    suspend fun savePayloadExternally(',
    'shared openPayloadExternally'
  );

  assertExactlyOnce(
    requestBody,
    'traceSlockFilePreviewExternalOpenAdmission(rejectionReason, policy, bridge::log)',
    'shared admission trace owner call'
  );
  assertExactlyOnce(hostBody, 'runSlockFilePreviewExternalOpenTraceFlow(', 'shared host trace owner call');
  assertOrdered(requestBody, sharedRequestMarkers, 'shared final admission wiring');
  assertOrdered(hostBody, sharedHostMarkers, 'shared final URL/host wiring');
}

function validateHost(source) {
  const body = section(
    source,
    '  private async openAttachment(',
    '  private async saveAttachment(',
    'OHOS host openAttachment'
  );

  for (const marker of hostMarkers.filter((item) => item.includes('preflightTrace.'))) {
    assertExactlyOnce(body, marker, `host ${marker}`);
  }
  assertExactlyOnce(body, 'const preflightTrace = new SlockOhosAttachmentOpenPreflightTrace(', 'host trace owner');
  assertExactlyOnce(
    body,
    'SlockOhosDiagnostics.record("SlockAttachmentOpenPreflight", message)',
    'host persistent trace sink'
  );
  assertOrdered(body, hostMarkers, 'OHOS final request/cache/download/Want wiring');
  assertExactlyOnce(
    body,
    'await context.startAbility(openWants.view);',
    'host direct viewData launch call'
  );
  for (const marker of forbiddenHostOpenMarkers) {
    assertContract(!body.includes(marker), `host openAttachment must not contain ${marker}`);
  }

  assertOrdered(body, [
    'const preflightDecision = slockOhosAttachmentOpenPreflightDecision(',
    'if (preflightDecision === SlockOhosAttachmentOpenPreflightDecision.PUBLIC_DOWNLOAD_MISSING) {',
    'preflightTrace.publicDownloadMissing(privateCommitted);',
    'callback({ "status": "missing", "message": "Downloaded attachment is unavailable" });',
    'return;',
  ], 'public-download-missing terminal');
  assertOrdered(body, [
    'if (preflightDecision === SlockOhosAttachmentOpenPreflightDecision.URL_MISSING) {',
    'preflightTrace.requestRejected();',
    'callback({ "status": "failed", "message": "Unable to download attachment" });',
    'return;',
  ], 'URL-missing terminal');
}

function expectMutationFailure(name, sharedSource, hostSource) {
  let rejected = false;
  try {
    validateShared(sharedSource);
    validateHost(hostSource);
  } catch (_error) {
    rejected = true;
  }
  assertContract(rejected, `self-test mutation survived: ${name}`);
}

function removeFirst(source, marker) {
  assertContract(source.includes(marker), `self-test fixture missing marker: ${marker}`);
  return source.replace(marker, '/* diagnostic mutation removed */');
}

function swapFirst(source, first, second) {
  assertContract(source.includes(first) && source.includes(second), 'self-test order fixture missing');
  return source
    .replace(first, '__SLOCK_DIAGNOSTIC_ORDER_SWAP__')
    .replace(second, first)
    .replace('__SLOCK_DIAGNOSTIC_ORDER_SWAP__', second);
}

const shared = read(sharedPath);
const host = read(hostPath);
validateShared(shared);
validateHost(host);

for (const marker of [
  'traceSlockFilePreviewExternalOpenAdmission(rejectionReason, policy, bridge::log)',
  'runSlockFilePreviewExternalOpenTraceFlow(',
]) {
  expectMutationFailure(`delete shared ${marker}`, removeFirst(shared, marker), host);
}
for (const marker of hostMarkers.filter((item) => item.includes('preflightTrace.'))) {
  expectMutationFailure(`delete host ${marker}`, shared, removeFirst(host, marker));
}
expectMutationFailure(
  'delete host committed-file/URL decision',
  shared,
  removeFirst(host, 'const preflightDecision = slockOhosAttachmentOpenPreflightDecision(')
);
expectMutationFailure(
  'swap shared host call/status projection',
  swapFirst(
    shared,
    'bridge.openAttachment(',
    'statusOf = { result -> result?.optString("status") },'
  ),
  host
);
expectMutationFailure(
  'swap host cache/download-start',
  shared,
  swapFirst(
    host,
    'preflightTrace.cacheChecked(privateCommitted, cached);',
    'preflightTrace.downloadStarted(privateCommitted);'
  )
);
expectMutationFailure(
  'replace direct viewData launch with select action',
  shared,
  host.replace(
    'await context.startAbility(openWants.view);',
    "await context.startAbility({ action: 'ohos.want.action.select' });"
  )
);

console.log('OHOS attachment-open diagnostic production contract + mutation self-test: PASS');
