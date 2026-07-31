#!/usr/bin/env node

import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, '../../..');

function read(relativePath) {
  return fs.readFileSync(path.resolve(repositoryRoot, relativePath), 'utf8').replaceAll('\r\n', '\n');
}

function assertContract(condition, message) {
  if (!condition) {
    throw new Error(`OHOS image decode policy contract failed: ${message}`);
  }
}

function run(command, args, encoding = 'utf8') {
  const result = spawnSync(command, args, {
    encoding,
    maxBuffer: 64 * 1024 * 1024
  });
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
    process.env.DEVECO_SDK_HOME &&
      path.join(process.env.DEVECO_SDK_HOME, 'default/openharmony/native/llvm/bin', name),
    path.join(
      '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native/llvm/bin',
      name
    )
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

function functionBody(source, startMarker, endMarker) {
  const start = source.indexOf(startMarker);
  const end = source.indexOf(endMarker, start + startMarker.length);
  assertContract(start >= 0 && end > start, `unable to isolate ${startMarker}`);
  return source.slice(start, end);
}

function assertCanvasCompatibleDecode(name, source) {
  assertContract(
    source.includes('OH_DecodingOptions_SetDesiredDynamicRange') &&
      source.includes('IMAGE_DYNAMIC_RANGE_SDR'),
    `${name} must tone-map HDR input to SDR`
  );
  assertContract(
    source.includes('OH_DecodingOptions_SetPixelFormat') &&
      source.includes('PIXEL_FORMAT_RGBA_8888'),
    `${name} must request RGBA_8888 pixels`
  );
  assertContract(
    source.includes('OH_ImageSourceNative_CreatePixelmapUsingAllocator') &&
      source.includes('IMAGE_ALLOCATOR_TYPE_SHARE_MEMORY'),
    `${name} must request CPU-readable shared-memory pixels`
  );
  assertContract(
    !source.includes('IMAGE_DYNAMIC_RANGE_AUTO'),
    `${name} must not preserve HDR AUTO output`
  );
  assertContract(
    source.includes('OH_ImageSourceNative_CreatePixelmapUsingAllocator != nullptr'),
    `${name} must gate the API 15 allocator symbol before calling it`
  );
  assertContract(
    source.includes('OH_ImageSourceNative_CreatePixelmap(source, options, pixelmap)') &&
      source.includes('ValidateCpuReadableRgbaPixelmap(*pixelmap)') &&
      source.includes('OH_PixelmapNative_ReadPixels('),
    `${name} API 12-14 fallback must prove the SDR/RGBA result is CPU-readable`
  );
}

const memoryCacheSource = read(
  'third_party/kuikly-ui/core-render-ohos/src/main/cpp/libohos_render/expand/modules/cache/KRMemoryCacheModule.cpp'
);
const memoryCacheDecode = functionBody(
  memoryCacheSource,
  'static Image_ErrorCode ValidateCpuReadableRgbaPixelmap(',
  'KRAnyValue KRMemoryCacheModule::Get('
);
assertContract(
  /OH_ImageSourceNative_CreatePixelmapUsingAllocator\([\s\S]*?__attribute__\(\(weak\)\)/.test(memoryCacheSource),
  'API 15 allocator decoder must remain weak-linked for Kuikly API 12-14 compatibility'
);

const entryPackage = read('compose/ohosApp/entry/oh-package.json5');
const renderPin = /"@kuikly-open\/render"\s*:\s*['"]file:\.\/libs\/([^'"]+\.har)['"]/.exec(
  entryPackage
);
assertContract(renderPin, 'OHOS entry must consume a checked-in Kuikly render HAR');
const pinnedHarName = renderPin[1];
const pinnedHarRelativePath = `compose/ohosApp/entry/libs/${pinnedHarName}`;
const pinnedHarPath = path.resolve(repositoryRoot, pinnedHarRelativePath);
assertContract(fs.existsSync(pinnedHarPath), `pinned render HAR is missing: ${pinnedHarName}`);

const pinIdentity = /^kuikly-render-staging2-([0-9a-f]{8})\.har$/.exec(pinnedHarName);
assertContract(pinIdentity, 'render HAR filename must name the staging2 source short SHA');
const kuiklySourceExact = run('git', ['-C', 'third_party/kuikly-ui', 'rev-parse', 'HEAD']).trim();
assertContract(
  kuiklySourceExact.startsWith(pinIdentity[1]),
  `render HAR source ${pinIdentity[1]} does not match Kuikly gitlink ${kuiklySourceExact}`
);

const provenanceName = pinnedHarName.replace(/\.har$/, '.provenance.json');
const provenance = JSON.parse(read(`compose/ohosApp/entry/libs/${provenanceName}`));
assertContract(provenance.sourceCommit === kuiklySourceExact, 'render HAR provenance source must match gitlink');
assertContract(provenance.harFile === pinnedHarName, 'render HAR provenance filename mismatch');
const harBytes = fs.readFileSync(pinnedHarPath);
assertContract(sha256(harBytes) === provenance.harSha256, 'render HAR SHA-256 mismatch');

const lockfile = read('compose/ohosApp/entry/oh-package-lock.json5');
assertContract(
  lockfile.includes(`@kuikly-open/render@libs/${pinnedHarName}`) &&
    lockfile.includes(`"resolved": "libs/${pinnedHarName}"`),
  'OHOS lockfile must resolve the same checked-in render HAR'
);

const temporaryDirectory = fs.mkdtempSync(path.join(os.tmpdir(), 'kuikly-render-contract-'));
const extractedLibrary = path.join(temporaryDirectory, 'libkuikly.so');
try {
  const libraryBytes = run('tar', ['-xOzf', pinnedHarPath, provenance.libraryMember], null);
  assertContract(Buffer.isBuffer(libraryBytes), 'render HAR extraction must return binary bytes');
  fs.writeFileSync(extractedLibrary, libraryBytes);
  assertContract(sha256(libraryBytes) === provenance.librarySha256, 'libkuikly.so SHA-256 mismatch');

  const symbolTable = run(resolveLlvmTool('llvm-nm'), ['-D', extractedLibrary]);
  assertContract(
    /^\s*w\s+OH_ImageSourceNative_CreatePixelmapUsingAllocator$/m.test(symbolTable),
    'pinned libkuikly.so must weak-link the API 15 allocator'
  );
  assertContract(
    /^\s*U\s+OH_ImageSourceNative_CreatePixelmap$/m.test(symbolTable) &&
      /^\s*U\s+OH_PixelmapNative_ReadPixels$/m.test(symbolTable),
    'pinned libkuikly.so must retain the API 12 legacy decoder and CPU-read fallback symbols'
  );

  const notes = run(resolveLlvmTool('llvm-readobj'), ['--notes', extractedLibrary]);
  const buildId = /Build ID:\s*([0-9a-f]+)/i.exec(notes)?.[1];
  assertContract(buildId === provenance.libraryBuildId, 'libkuikly.so Build ID mismatch');
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
    editor.includes('SlockAspectFitImagePreviewSurface('),
  'editor bitmap and visible preview must remain on the shared Kuikly image-cache path'
);

const widgetsSource = read('compose/shared/src/commonMain/kotlin/build/raft/app/BasicWidget.kt');
const cachedImage = functionBody(widgetsSource, 'fun SlockCachedImage(', 'internal fun SlockAspectFitImagePreviewSurface(');
assertContract(
  cachedImage.includes('rememberAsyncImagePainter(source)'),
  'editor preview surface must resolve through the Kuikly async painter'
);

const asyncPainterSource = read(
  'third_party/kuikly-ui/compose/src/commonMain/kotlin/com/tencent/kuikly/compose/coil3/AsyncImagePainter.kt'
);
const asyncBitmap = functionBody(asyncPainterSource, 'fun rememberAsyncImageBitmap(', '/**\n * Return an [AsyncImagePainter]');
assertContract(
  asyncBitmap.includes('activity.imageCacheManager.loadImage(source)'),
  'rememberAsyncImageBitmap must resolve through KuiklyImageCacheManager'
);

const kuiklyPainterSource = read(
  'third_party/kuikly-ui/compose/src/commonMain/kotlin/com/tencent/kuikly/compose/ui/KuiklyPainter.kt'
);
assertContract(
  kuiklyPainterSource.includes('context.imageCacheManager.loadImage(src)'),
  'rememberAsyncImagePainter must resolve through the same KuiklyImageCacheManager'
);

assertCanvasCompatibleDecode('Kuikly Canvas memory cache', memoryCacheDecode);

console.log(
  `OHOS image decode policy source + pinned HAR contract: PASS ` +
    `(source=${kuiklySourceExact} harSha256=${provenance.harSha256} ` +
    `libSha256=${provenance.librarySha256} buildId=${provenance.libraryBuildId})`
);
