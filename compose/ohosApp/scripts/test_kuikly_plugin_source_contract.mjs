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
const forkPublisher = fs.readFileSync(path.resolve(repositoryRoot, 'scripts/build_kuikly_fork.sh'), 'utf8');
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
  'the OHOS link must re-resolve same-coordinate fork artifacts instead of accepting an older Gradle cache entry'
);
assertContract(
  plugin.includes("../../scripts/verify_kuikly_ohos_provenance.sh") &&
    plugin.includes('verifyKuiklyForkProvenance(ohosAppPath, destinationSo);'),
  'the copied libshared must pass the fork provenance gate before packaging'
);
assertContract(
  forkPublisher.includes(':core-annotations:publishJvmPublicationToMavenLocal'),
  'OHOS fork publication must include the JVM annotations variant required by core-ksp'
);
const releaseCacheKeys = releaseWorkflow.match(/key: kuikly-fork-mavenlocal-ohos-.+/g) ?? [];
const releaseProducerCacheKey =
  "key: kuikly-fork-mavenlocal-ohos-${{ hashFiles('scripts/build_kuikly_fork.sh') }}-${{ steps.pins.outputs.kuikly }}";
const releaseConsumerCacheKey =
  "key: kuikly-fork-mavenlocal-ohos-${{ hashFiles('scripts/build_kuikly_fork.sh') }}-${{ needs.kuikly-artifacts.outputs.kuikly-sha }}";
assertContract(
  releaseCacheKeys.length === 3 &&
    releaseWorkflow.split(releaseProducerCacheKey).length - 1 === 2 &&
    releaseWorkflow.split(releaseConsumerCacheKey).length - 1 === 1,
  'every OHOS release producer/consumer cache key must bind both the publisher script and Kuikly gitlink'
);
assertContract(
  releaseWorkflow.includes('test -d "$root/core-annotations-jvm"') &&
    releaseWorkflow.includes('test -d "$root/core-ksp"') &&
    releaseWorkflow.includes('test -d "$dst/com/tencent/kuikly-open/core-annotations-jvm"') &&
    releaseWorkflow.includes('test -d "$dst/com/tencent/kuikly-open/core-ksp"'),
  'the OHOS release producer and consumer must fail closed when JVM/KSP variants are absent'
);
assertContract(
  releaseWorkflow.includes('--refresh-dependencies \\\n            copyNetworkOhosRuntimeLibs') &&
    releaseWorkflow.includes('../scripts/verify_kuikly_ohos_provenance.sh \\\n            ohosApp/entry/libs/arm64-v8a/libshared.so'),
  'the direct OHOS release link must refresh the fork and verify the staged libshared before HAP packaging'
);
assertContract(
  testWorkflow.includes('scripts/test_verify_kuikly_ohos_provenance.sh'),
  'required CI must execute the stale/missing provenance negative self-test'
);

console.log('OHOS Kuikly plugin source contract: PASS');
