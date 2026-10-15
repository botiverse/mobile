#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const ohosAppRoot = path.resolve(scriptDirectory, '..');
const repositoryRoot = path.resolve(ohosAppRoot, '../..');

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
const releaseWorkflow = fs.readFileSync(path.resolve(repositoryRoot, '.github/workflows/ohos-release.yml'), 'utf8');
const testWorkflow = fs.readFileSync(path.resolve(repositoryRoot, '.github/workflows/test.yml'), 'utf8');

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
assertContract(
  plugin.includes("runGradle(config, ['--refresh-dependencies', `:${config.moduleName}:link${buildMode}SharedOhosArm64`]);"),
  'the OHOS link must refresh the immutable Raft artifacts instead of accepting stale Gradle state'
);
assertContract(
  plugin.includes("../../scripts/verify_kuikly_ohos_provenance.sh") &&
    plugin.includes('verifyKuiklyForkProvenance(ohosAppPath, destinationSo);'),
  'the copied libshared must pass the Kuikly provenance gate before packaging'
);
assertContract(
  releaseWorkflow.includes('scripts/ci/verify_kuikly_release_manifest.sh'),
  'OHOS release must require the complete immutable Kuikly manifest'
);
assertContract(
  !releaseWorkflow.includes('third_party/kuikly-ui') &&
    !releaseWorkflow.includes('build_kuikly_fork.sh') &&
    !releaseWorkflow.includes('kuikly-fork-mavenlocal'),
  'OHOS release must not hydrate Kuikly from a gitlink, source build, or mavenLocal cache'
);
assertContract(
  releaseWorkflow.includes('needs: [resolve, kuikly-artifacts]'),
  'the OHOS package job must depend on the manifest gate'
);
assertContract(
  releaseWorkflow.includes('--refresh-dependencies \\\n            copyNetworkOhosRuntimeLibs') &&
    releaseWorkflow.includes('../scripts/verify_kuikly_ohos_provenance.sh \\\n            ohosApp/entry/libs/arm64-v8a/libshared.so'),
  'the direct OHOS release link must refresh the fork and verify the staged libshared before HAP packaging'
);
assertContract(
  testWorkflow.includes('scripts/ci/test_kuikly_raft_consumer_contract.py') &&
    testWorkflow.includes('scripts/ci/verify_kuikly_release_manifest.sh') &&
    testWorkflow.includes('scripts/ci/verify_kuikly_ohos_renderer.sh'),
  'required CI must validate the Raft-only source contract and release manifest'
);

console.log('OHOS Kuikly plugin + immutable-Raft source contract: PASS');
