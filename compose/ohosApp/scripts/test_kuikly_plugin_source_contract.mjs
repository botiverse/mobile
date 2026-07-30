#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const ohosAppRoot = path.resolve(scriptDirectory, '..');

function read(relativePath) {
  return fs.readFileSync(path.resolve(ohosAppRoot, relativePath), 'utf8').replaceAll('\r\n', '\n');
}

function assertContract(condition, message) {
  if (!condition) {
    throw new Error(`OHOS Kuikly plugin contract failed: ${message}`);
  }
}

const plugin = read('hvigor/kuiklyPlugin.ts');
const example = read('local.properties.example');

assertContract(
  plugin.includes("const DEFAULT_LIBRARY_NAME = 'shared';"),
  'legacy local.properties must default to the libshared ABI name'
);
assertContract(
  plugin.includes('return config.libraryName || DEFAULT_LIBRARY_NAME;'),
  'an explicit libraryName must override the compatibility default'
);
assertContract(
  plugin.match(/const libraryName = resolveLibraryName\(config\);/g)?.length === 2,
  'build and clean must resolve the same library name'
);
assertContract(
  plugin.includes("libraryName: values.get('kuikly.libraryName')"),
  'local.properties must continue to load an explicit libraryName'
);
assertContract(
  example.includes('kuikly.moduleName=ohosBridge') && example.includes('kuikly.libraryName=shared'),
  'the example must document the Gradle-module and native-library split'
);

console.log('OHOS Kuikly plugin source contract: PASS');
