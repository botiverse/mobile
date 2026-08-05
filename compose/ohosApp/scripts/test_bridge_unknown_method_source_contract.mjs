#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const defaultSourcePath = path.resolve(
  scriptDirectory,
  '../entry/src/main/ets/kuikly/modules/KRBridgeModule.ets'
);
const requestedSource = process.argv[2];
const source = (
  requestedSource === '-'
    ? fs.readFileSync(0, 'utf8')
    : fs.readFileSync(requestedSource ? path.resolve(requestedSource) : defaultSourcePath, 'utf8')
).replaceAll('\r\n', '\n');

function fail(message) {
  throw new Error(`OHOS bridge unknown-method contract failed: ${message}`);
}

function assertContract(condition, message) {
  if (!condition) fail(message);
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

function validateContract(candidateSource) {
  const callBody = section(
    candidateSource,
    '  call(method: string, params: KRAny, callback: KuiklyRenderCallback): KRAny {',
    '  private showAlert(',
    'KRBridgeModule.call'
  );
  const defaultBody = section(
    callBody,
    '      default: {',
    '    }\n    return null;',
    'KRBridgeModule.call default branch'
  );

  assertContract(
    occurrenceCount(defaultBody, 'callback({') === 1,
    'unknown methods must invoke the supplied callback exactly once'
  );
  assertContract(
    /["']code["']\s*:\s*-1(?:\s*[,}])/.test(defaultBody),
    'unknown-method callback must report code=-1'
  );
  assertContract(
    defaultBody.includes('"message": "Method not implemented"'),
    'unknown-method callback must identify the unsupported method'
  );
  assertContract(
    occurrenceCount(defaultBody, 'break;') === 1,
    'unknown-method callback branch must terminate exactly once'
  );
  return defaultBody;
}

function assertMutationRejected(candidateSource, label) {
  try {
    validateContract(candidateSource);
  } catch (_error) {
    return;
  }
  fail(`self-test accepted ${label}`);
}

const defaultBody = validateContract(source);
const callbackStart = defaultBody.indexOf('        callback({');
const callbackEndMarker = '        });';
const callbackEnd = defaultBody.indexOf(callbackEndMarker, callbackStart) + callbackEndMarker.length;
assertContract(callbackStart >= 0 && callbackEnd >= callbackEndMarker.length, 'unable to isolate callback');
const callbackInvocation = defaultBody.slice(callbackStart, callbackEnd);

assertMutationRejected(
  source.replace(defaultBody, defaultBody.replace(callbackInvocation, '')),
  'silent default branch'
);
assertMutationRejected(
  source.replace(defaultBody, defaultBody.replace(callbackInvocation, `${callbackInvocation}\n${callbackInvocation}`)),
  'double callback branch'
);
assertMutationRejected(
  source.replace(defaultBody, defaultBody.replace('"code": -1', '"code": 0')),
  'non-fail-closed status code'
);

console.log('OHOS bridge unknown-method contract passed');
