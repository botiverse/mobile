#!/usr/bin/env node

import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, '../../..');
const release = '2.28.0-raft.8';
const version = `${release}-2.0.21-ohos`;
const coordinate = `com.tencent.kuikly-open:core-render-ohos:${version}`;
const expectedHarSpec = `file:../../build/kuikly/core-render-ohos-${version}.har`;

function read(relativePath) {
  return fs.readFileSync(path.resolve(repositoryRoot, relativePath), 'utf8').replaceAll('\r\n', '\n');
}

function assertContract(condition, message) {
  if (!condition) throw new Error(`OHOS image decode policy contract failed: ${message}`);
}

function run(command, args, encoding = 'utf8') {
  const result = spawnSync(command, args, { encoding, maxBuffer: 64 * 1024 * 1024 });
  assertContract(!result.error, `${command} failed to start: ${result.error?.message}`);
  assertContract(
    result.status === 0,
    `${command} ${args.join(' ')} failed (${result.status}): ${String(result.stderr).trim()}`
  );
  return result.stdout;
}

function resolveLlvmTool(name) {
  const candidates = [
    process.env.OHOS_SDK_HOME && path.join(process.env.OHOS_SDK_HOME, 'native/llvm/bin', name),
    process.env.OHOS_SDK_HOME && path.join(process.env.OHOS_SDK_HOME, 'openharmony/native/llvm/bin', name),
    process.env.OHOS_SDK_HOME && path.join(process.env.OHOS_SDK_HOME, 'default/openharmony/native/llvm/bin', name),
    process.env.DEVECO_SDK_HOME && path.join(process.env.DEVECO_SDK_HOME, 'default/openharmony/native/llvm/bin', name),
    path.join('/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native/llvm/bin', name)
  ].filter(Boolean);
  const exact = candidates.find((candidate) => fs.existsSync(candidate));
  if (exact) return exact;
  const discovered = spawnSync('which', [name], { encoding: 'utf8' });
  assertContract(discovered.status === 0, `${name} is unavailable in the OpenHarmony SDK`);
  return discovered.stdout.trim();
}

function sha256(bytes) {
  return crypto.createHash('sha256').update(bytes).digest('hex');
}

// Markers are matched with the Kotlin visibility keyword made optional. Pinning
// the literal `private fun ...` made this contract fail whenever a declaration was
// legitimately widened to `internal` - which is exactly what the visual-fixture
// split does when a visual host in another file needs to call it (task #193). The
// contract is about the image-decode path inside the function, not about who may
// call it, so visibility must not be part of the anchor.
function markerPattern(marker) {
  const withoutVisibility = marker.replace(/^(?:private|internal|public) /, '');
  const escaped = withoutVisibility.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(?:private |internal |public )?${escaped}`);
}

function functionBody(source, startMarker, endMarker) {
  const startMatch = markerPattern(startMarker).exec(source);
  const start = startMatch ? startMatch.index : -1;
  const endMatch =
    start >= 0
      ? markerPattern(endMarker).exec(source.slice(start + startMatch[0].length))
      : null;
  const end = endMatch ? start + startMatch[0].length + endMatch.index : -1;
  assertContract(start >= 0 && end > start, `unable to isolate ${startMarker}`);
  return source.slice(start, end);
}

const entryPackage = read('compose/ohosApp/entry/oh-package.json5');
assertContract(
  entryPackage.includes(`"@kuikly-open/render": "${expectedHarSpec}"`),
  'OHOS entry must consume the manifest-generated renderer HAR'
);
assertContract(!entryPackage.includes('kuikly-render-staging2'), 'local staging renderer fallback remains');

const harPath = process.env.KUIKLY_OHOS_HAR;
const provenancePath = process.env.KUIKLY_OHOS_PROVENANCE;
assertContract(harPath && fs.existsSync(harPath), 'KUIKLY_OHOS_HAR is missing');
assertContract(provenancePath && fs.existsSync(provenancePath), 'KUIKLY_OHOS_PROVENANCE is missing');
const harBytes = fs.readFileSync(harPath);
const provenance = JSON.parse(fs.readFileSync(provenancePath, 'utf8'));
assertContract(provenance.schema === 'kuikly-host-renderer-provenance/v1', 'provenance schema drifted');
assertContract(provenance.coordinate === coordinate, 'provenance coordinate drifted');
assertContract(provenance.binarySize === harBytes.length, 'HAR size does not match provenance');
assertContract(provenance.binarySha256 === sha256(harBytes), 'HAR SHA-256 does not match provenance');
assertContract(provenance.source?.repository === 'botiverse/KuiklyUI', 'source repository drifted');
assertContract(/^[0-9a-f]{40}$/.test(provenance.source?.commit ?? ''), 'source commit is invalid');
assertContract(/^[0-9a-f]{40}$/.test(provenance.source?.tree ?? ''), 'source tree is invalid');

const members = run('tar', ['-tzf', harPath]).trim().split('\n');
assertContract(
  members.every((member) => member && !member.startsWith('/') && !member.split('/').includes('..')),
  'HAR contains an unsafe member path'
);
const libraryMember = 'package/libs/arm64-v8a/libkuikly.so';
assertContract(members.filter((member) => member === libraryMember).length === 1, 'HAR must contain one arm64 libkuikly.so');

const temporaryDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'kuikly-render-contract-'));
const extractedLibrary = path.join(temporaryDirectory, 'libkuikly.so');
try {
  const libraryBytes = run('tar', ['-xOzf', harPath, libraryMember], null);
  fs.writeFileSync(extractedLibrary, libraryBytes);

  const symbolTable = run(resolveLlvmTool('llvm-nm'), ['-D', extractedLibrary]);
  assertContract(
    /^\s*w\s+OH_ImageSourceNative_CreatePixelmapUsingAllocator$/m.test(symbolTable) &&
      /^\s*w\s+OH_PixelmapNative_Destroy$/m.test(symbolTable),
    'renderer must weak-link the API 15 allocator and API 18 destroy symbols'
  );
  assertContract(
    /^\s*U\s+OH_ImageSourceNative_CreatePixelmap$/m.test(symbolTable) &&
      /^\s*U\s+OH_PixelmapNative_ReadPixels$/m.test(symbolTable) &&
      /^\s*U\s+OH_PixelmapNative_Release$/m.test(symbolTable) &&
      /^\s*U\s+OH_Drawing_PixelMapGetFromOhPixelMapNative$/m.test(symbolTable) &&
      /^\s*U\s+OH_Drawing_PixelMapDissolve$/m.test(symbolTable),
    'renderer must retain the legacy decode/read/release and Canvas conversion symbols'
  );
  const notes = run(resolveLlvmTool('llvm-readobj'), ['--notes', extractedLibrary]);
  assertContract(/Build ID:\s*[0-9a-f]+/i.test(notes), 'renderer has no ELF Build ID');
  const dynamicTable = run(resolveLlvmTool('llvm-readelf'), ['-d', extractedLibrary]);
  assertContract(dynamicTable.includes('BIND_NOW'), 'renderer must retain BIND_NOW');
} finally {
  fs.rmSync(temporaryDirectory, { recursive: true, force: true });
}

const editorSource = read('compose/shared/src/commonMain/kotlin/build/raft/app/SlockFilePreview.kt');
const editor = functionBody(
  editorSource,
  'private fun SlockFilePreviewComposerImageEditor(',
  'private fun SlockFilePreviewBorderlessIconButton('
);
assertContract(
  editor.includes('rememberAsyncImageBitmap(payload.imageUrl)') &&
    (editor.includes('drawSlockComposerBackgroundImage(') || editor.includes('SlockAspectFitImagePreviewSurface(')),
  'editor image path must remain on the shared Kuikly image-cache surface'
);
const widgetsSource = read('compose/shared/src/commonMain/kotlin/build/raft/app/BasicWidget.kt');
const cachedImage = functionBody(
  widgetsSource,
  'fun SlockCachedImage(',
  'internal fun SlockAspectFitImagePreviewSurface('
);
assertContract(cachedImage.includes('rememberAsyncImagePainter(source)'), 'preview must use the async painter');

console.log(
  `OHOS image decode policy + manifest-bound HAR contract: PASS ` +
    `(source=${provenance.source.commit} harSha256=${provenance.binarySha256})`
);
