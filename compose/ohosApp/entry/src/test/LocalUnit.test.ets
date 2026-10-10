import { parseSlockOhosAppLoginCallback, prepareSlockOhosAppLogin, SLOCK_OHOS_APP_LOGIN_URI } from '../main/ets/kuikly/SlockOhosAppLogin';
import { describe, beforeAll, beforeEach, afterEach, afterAll, it, expect } from '@ohos/hypium';
import { ApplicationStateChangeCallback } from '@kit.AbilityKit';
import { decodeOhosSocketIoFrame } from '../main/ets/kuikly/OhosSocketIoClient';
import { parseDohJsonAnswers, SlockOhosRealtimeDohPolicySink } from '../main/ets/kuikly/SlockOhosRealtimeDoh';
import {
  admitSlockOhosPreparedBatch,
  canSlockOhosDrain,
  createSlockOhosTaskpoolFailureEntry,
  createSlockOhosDiagnosticSnapshotState,
  isSlockOhosWorkGenerationCurrent,
  SlockOhosAsyncDiagnosticWork,
  SlockOhosDiagnosticsQueue,
  SlockOhosPreparedDiagnosticEntry,
  SlockOhosRawAsyncDiagnosticEntry,
} from '../main/ets/kuikly/SlockOhosDiagnosticsQueue';
import {
  nextSlockOhosForegroundEpoch,
  SlockOhosRealtimeHostContext,
  SlockOhosRealtimeSharedIngress,
} from '../main/ets/kuikly/SlockOhosRealtimeHostContext';
import {
  compareOhosDiagnosticsPath,
  collectOhosDiagnosticsSourceFiles,
  filterOhosHandsFeedbackEnvironmentLines,
  isOhosDiagnosticsLogFileName,
  isOhosDiagnosticsDescendantPath,
  isOhosFeedbackArchiveName,
  isOhosSafeDiagnosticsArchiveComponent,
  OhosDiagnosticsSourceInspector,
  OhosDiagnosticsSourceNodeKind,
  ohosDiagnosticsSourceNodeKind,
  ohosUniqueDiagnosticsStagedName,
  selectOhosDiagnosticsCandidateGroups,
} from '../main/ets/entryability/SlockOhosAppDiagnostics';
import {
  activityUnreadCountOrNull,
  activityUnreadPersistedFallbackCount,
  createActivityUnreadWidgetRefreshError,
} from '../main/ets/forms/SlockWidgetData';
import {
  SlockOhosPlatformFileBackend,
  SlockOhosPlatformFileReader,
  SLOCK_OHOS_PLATFORM_FILE_MAX_STREAM_CHUNK_BYTES,
} from '../main/ets/entryability/SlockOhosPlatformFileReader';
import { SlockOhosProcessNetworkLifecycle } from '../main/ets/entryability/SlockOhosProcessNetworkLifecycle';
import {
  isSlockOhosThreadComposerTemporaryFileRefAuthorityAllowed,
} from '../main/ets/entryability/SlockOhosThreadComposerTemporaryFileRetirement';
import {
  cacheSlockOhosPickedAttachment,
  ensureSlockOhosAttachmentCacheDirectory,
  normalizeSlockOhosImagePickerFileName,
  SlockOhosAttachmentCacheDirectoryBackend,
  SlockOhosPickedAttachmentCacheBackend,
} from '../main/ets/kuikly/SlockOhosPickedAttachmentCache';
import {
  slockOhosHandsFeedbackAttachmentPaths,
  SlockOhosHandsFeedbackAttachmentBackend,
  SlockOhosHandsFeedbackAttachmentStat,
} from '../main/ets/kuikly/SlockOhosHandsFeedbackAttachments';
import {
  promoteSlockOhosFeedbackDraftAttachments,
  slockOhosFeedbackDraftAccountSegmentFromDigest,
  SlockOhosFeedbackDraftAttachmentBackend,
} from '../main/ets/kuikly/SlockOhosHandsFeedbackDraftAttachments';
import {
  cleanupSlockOhosPrivateAttachmentOpenAliases,
  cleanupSlockOhosPrivateAttachmentOrphanParts,
  copySlockOhosPrivateAttachmentToProviderUri,
  isSlockOhosPrivateAttachmentOwnerKey,
  materializeSlockOhosPrivateAttachmentOpenAlias,
  slockOhosPrivateAttachmentDirectory,
  slockOhosPrivateAttachmentFileName,
  slockOhosPrivateAttachmentOpenAliasDirectoryName,
  slockOhosPrivateAttachmentOpenAliasFileName,
  SlockOhosPrivateAttachmentOpenAliasBackend,
  SlockOhosPrivateAttachmentCopyBackend,
  SlockOhosPrivateAttachmentCleanupBackend,
  SlockOhosPrivateAttachmentStat,
} from '../main/ets/kuikly/SlockOhosPrivateAttachmentStore';
import {
  buildSlockOhosAttachmentOpenWants,
  slockOhosAttachmentOpenDiagnostic,
  slockOhosAttachmentOpenPreflightDecision,
  slockOhosAttachmentOpenPreflightDiagnostic,
  slockOhosAttachmentOpenFailureKind,
  SLOCK_OHOS_ACTION_VIEW_DATA,
  SLOCK_OHOS_DEFAULT_ATTACHMENT_MIME_TYPE,
  SlockOhosAttachmentOpenDiagnosticTerminal,
  SlockOhosAttachmentOpenFailureKind,
  SlockOhosAttachmentOpenPreflightDecision,
  SlockOhosAttachmentOpenPreflightTrace,
  SlockOhosAttachmentOpenPreflightStage,
} from '../main/ets/kuikly/SlockOhosAttachmentOpenWant';
import { wantConstant } from '@kit.AbilityKit';
import {
  parseSlockOhosMobileOAuthCallback,
  dispatchSlockOhosMobileOAuthCallback,
  prepareSlockOhosMobileOAuth,
  SLOCK_OHOS_MOBILE_OAUTH_SCHEME,
} from '../main/ets/kuikly/SlockOhosMobileOAuth';
import { SlockOhosMobileOAuthIngress } from '../main/ets/kuikly/SlockOhosMobileOAuthIngress';

interface FakeHandsFeedbackAttachmentInput {
  fileRef: string;
}

class FakePrivateAttachmentHandle {
  readonly kind: string;
  constructor(kind: string) {
    this.kind = kind;
  }
}

class FakePrivateAttachmentCopyBackend implements SlockOhosPrivateAttachmentCopyBackend {
  sourceSize: number = 0;
  readOffset: number = 0;
  writeLimit: number = Number.MAX_SAFE_INTEGER;
  readonly openedTargets: Array<string> = [];
  readonly closedKinds: Array<string> = [];
  writtenBytes: number = 0;
  readFailure: Error | null = null;
  closeTargetFailure: Error | null = null;
  closeSourceFailure: Error | null = null;
  targetOpenFailure: Error | null = null;

  stat(_path: string): SlockOhosPrivateAttachmentStat | null {
    return this.sourceSize > 0 ? { isFile: true, sizeBytes: this.sourceSize, mtime: 1 } : null;
  }

  openSource(_path: string): Object {
    return new FakePrivateAttachmentHandle('source');
  }

  openTarget(uri: string): Object {
    this.openedTargets.push(uri);
    if (this.targetOpenFailure !== null) throw this.targetOpenFailure;
    return new FakePrivateAttachmentHandle('target');
  }

  read(_source: Object, buffer: ArrayBuffer): number {
    if (this.readFailure !== null) throw this.readFailure;
    const remaining = this.sourceSize - this.readOffset;
    const count = Math.min(remaining, buffer.byteLength);
    this.readOffset += count;
    return count;
  }

  write(_target: Object, _buffer: ArrayBuffer, length: number): number {
    const count = Math.min(length, this.writeLimit);
    this.writtenBytes += count;
    return count;
  }

  close(handle: Object): void {
    const kind = (handle as FakePrivateAttachmentHandle).kind;
    this.closedKinds.push(kind);
    if (kind === 'target' && this.closeTargetFailure !== null) throw this.closeTargetFailure;
    if (kind === 'source' && this.closeSourceFailure !== null) throw this.closeSourceFailure;
  }
}

class FakePrivateAttachmentCleanupBackend implements SlockOhosPrivateAttachmentCleanupBackend {
  readonly names: Array<string> = [];
  readonly removedPaths: Array<string> = [];

  list(_directory: string): Array<string> {
    return this.names;
  }

  unlink(path: string): void {
    this.removedPaths.push(path);
  }
}

class FakePrivateAttachmentOpenAliasBackend implements SlockOhosPrivateAttachmentOpenAliasBackend {
  readonly stats: Map<string, SlockOhosPrivateAttachmentStat> = new Map();
  readonly contents: Map<string, string> = new Map();
  readonly directoryEntries: Map<string, Array<string>> = new Map();
  readonly removedPaths: Array<string> = [];
  readonly removedDirectories: Array<string> = [];
  copyCount: number = 0;
  unlinkCount: number = 0;
  copyFailure: Error | null = null;
  copiedSizeOverride: number | null = null;

  list(directory: string): Array<string> {
    return this.directoryEntries.get(directory) ?? [];
  }

  stat(path: string): SlockOhosPrivateAttachmentStat | null {
    return this.stats.get(path) ?? null;
  }

  async copy(sourcePath: string, targetPath: string): Promise<void> {
    const source = this.stats.get(sourcePath);
    if (source === undefined) throw new Error('source missing');
    this.copyCount += 1;
    if (this.copyFailure !== null) throw this.copyFailure;
    this.stats.set(targetPath, {
      isFile: source.isFile,
      sizeBytes: this.copiedSizeOverride ?? source.sizeBytes,
      mtime: source.mtime,
    });
    this.contents.set(targetPath, this.contents.get(sourcePath) ?? '');
  }

  unlink(path: string): void {
    this.unlinkCount += 1;
    this.removedPaths.push(path);
    this.stats.delete(path);
    this.contents.delete(path);
  }

  removeDirectory(path: string): void {
    this.removedDirectories.push(path);
    this.directoryEntries.delete(path);
  }
}

class FakePlatformFileHandle {
  readonly fileRef: string;

  constructor(fileRef: string) {
    this.fileRef = fileRef;
  }
}

class FakeNonErrorReason {
  readonly reason: string = 'offline';
}

class FakeRealtimeSharedIngress implements SlockOhosRealtimeSharedIngress {
  readonly payloads: Array<string>;
  readonly accepted: boolean;
  readonly shouldThrow: boolean;

  constructor(payloads: Array<string>, accepted: boolean, shouldThrow: boolean = false) {
    this.payloads = payloads;
    this.accepted = accepted;
    this.shouldThrow = shouldThrow;
  }

  publishEvent(eventDataJson: string): boolean {
    this.payloads.push(eventDataJson);
    if (this.shouldThrow) throw new Error('expected test failure');
    return this.accepted;
  }
}

class FakePickedAttachmentCacheBackend implements SlockOhosPickedAttachmentCacheBackend {
  readonly copiedSourceFds: Array<number> = [];
  readonly copiedTargetPaths: Array<string> = [];
  readonly removedPaths: Array<string> = [];
  copiedSizeBytes: number = 0;

  copyFile(sourceFd: number, targetPath: string): void {
    this.copiedSourceFds.push(sourceFd);
    this.copiedTargetPaths.push(targetPath);
  }

  fileSize(_path: string): number {
    return this.copiedSizeBytes;
  }

  fileUri(path: string): string {
    return `file://build.raft.mobile${path}`;
  }

  removeFile(path: string): void {
    this.removedPaths.push(path);
  }
}

class FakeAttachmentCacheDirectoryBackend implements SlockOhosAttachmentCacheDirectoryBackend {
  directoryExists: boolean = false;
  directoryExistsAfterCreateAttempt: boolean = false;
  createCount: number = 0;
  createError: Error | null = null;

  createDirectory(_path: string): void {
    this.createCount += 1;
    if (this.directoryExistsAfterCreateAttempt) {
      this.directoryExists = true;
    }
    if (this.createError != null) {
      throw this.createError;
    }
    this.directoryExists = true;
  }

  isDirectory(_path: string): boolean {
    return this.directoryExists;
  }
}

class FakeHandsFeedbackAttachmentBackend implements SlockOhosHandsFeedbackAttachmentBackend {
  readonly resolvedRefs: Array<string> = [];
  readonly statPaths: Array<string> = [];
  readonly uriPaths: Map<string, string> = new Map();
  readonly stats: Map<string, SlockOhosHandsFeedbackAttachmentStat> = new Map();
  throwingRef: string = '';

  localPathForFileUri(ref: string): string {
    this.resolvedRefs.push(ref);
    if (ref === this.throwingRef) {
      throw new Error('invalid file uri');
    }
    return this.uriPaths.get(ref) ?? '';
  }

  stat(path: string): SlockOhosHandsFeedbackAttachmentStat {
    this.statPaths.push(path);
    return this.stats.get(path) ?? { isFile: false, sizeBytes: 0 };
  }
}

class FakeFeedbackDraftAttachmentBackend implements SlockOhosFeedbackDraftAttachmentBackend {
  readonly directories: Set<string> = new Set();
  readonly files: Map<string, number> = new Map();
  readonly refs: Map<string, string> = new Map();

  ensureDirectory(path: string): void {
    this.directories.add(path);
  }

  resolveFileRef(ref: string): string {
    if (ref.startsWith('/')) return ref;
    return this.refs.get(ref) ?? '';
  }

  isDirectory(path: string): boolean {
    return this.directories.has(path);
  }

  isFile(path: string): boolean {
    return this.files.has(path);
  }

  fileSize(path: string): number {
    return this.files.get(path) ?? 0;
  }

  copyFile(sourcePath: string, targetPath: string): void {
    const size = this.files.get(sourcePath);
    if (size == null) throw new Error('missing source');
    this.files.set(targetPath, size);
  }

  rename(sourcePath: string, targetPath: string): void {
    const moved: Array<[string, number]> = [];
    this.files.forEach((size: number, path: string): void => {
      if (path.startsWith(`${sourcePath}/`)) {
        moved.push([`${targetPath}${path.slice(sourcePath.length)}`, size]);
        this.files.delete(path);
      }
    });
    moved.forEach((entry: [string, number]): void => {
      this.files.set(entry[0], entry[1]);
    });
    this.directories.delete(sourcePath);
    this.directories.add(targetPath);
  }

  removeRecursively(path: string): void {
    Array.from(this.files.keys())
      .filter((filePath: string): boolean => filePath === path || filePath.startsWith(`${path}/`))
      .forEach((filePath: string): void => {
        this.files.delete(filePath);
      });
    Array.from(this.directories)
      .filter((directory: string): boolean => directory === path || directory.startsWith(`${path}/`))
      .forEach((directory: string): void => {
        this.directories.delete(directory);
      });
  }

  removeFile(path: string): void {
    this.files.delete(path);
  }

  list(path: string): Array<string> {
    const prefix = `${path}/`;
    return Array.from(this.files.keys())
      .filter((filePath: string): boolean => filePath.startsWith(prefix))
      .map((filePath: string): string => filePath.slice(prefix.length).split('/')[0]);
  }

  fileUri(path: string): string {
    const ref = `file://build.raft.mobile${path}`;
    this.refs.set(ref, path);
    return ref;
  }
}

class FakePlatformFileBackend implements SlockOhosPlatformFileBackend {
  private readonly data: Map<string, Array<number>> = new Map();
  private readonly offsets: Map<FakePlatformFileHandle, number> = new Map();
  readonly requestedMaxBytes: Array<number> = [];
  closeCount: number = 0;

  setData(fileRef: string, bytes: Array<number>): void {
    this.data.set(fileRef, bytes);
  }

  open(fileRef: string): Object {
    const handle = new FakePlatformFileHandle(fileRef);
    this.offsets.set(handle, 0);
    return handle;
  }

  statSize(handle: Object): number {
    return this.data.get((handle as FakePlatformFileHandle).fileRef)?.length ?? 0;
  }

  read(handle: Object, maxBytes: number): ArrayBuffer {
    this.requestedMaxBytes.push(maxBytes);
    const typed = handle as FakePlatformFileHandle;
    const bytes = this.data.get(typed.fileRef) ?? [];
    const offset = this.offsets.get(typed) ?? 0;
    const count = Math.min(maxBytes, Math.max(0, bytes.length - offset));
    const result = new ArrayBuffer(count);
    const view = new Uint8Array(result);
    for (let index = 0; index < count; index++) {
      view[index] = bytes[offset + index];
    }
    this.offsets.set(typed, offset + count);
    return result;
  }

  close(handle: Object): void {
    this.offsets.delete(handle as FakePlatformFileHandle);
    this.closeCount += 1;
  }
}

class FakeOhosDiagnosticsSourceInspector implements OhosDiagnosticsSourceInspector {
  private readonly kinds: Map<string, OhosDiagnosticsSourceNodeKind>;
  private readonly children: Map<string, Array<string>>;

  constructor(
    kinds: Map<string, OhosDiagnosticsSourceNodeKind>,
    children: Map<string, Array<string>>
  ) {
    this.kinds = kinds;
    this.children = children;
  }

  kind(path: string): OhosDiagnosticsSourceNodeKind {
    return this.kinds.get(path) ?? OhosDiagnosticsSourceNodeKind.Missing;
  }

  childNames(path: string): Array<string> {
    return this.children.get(path) ?? [];
  }
}

function diagnosticEntry(seq: number, level: string = 'info'): SlockOhosRawAsyncDiagnosticEntry {
  return {
    timestampMillis: seq,
    seq,
    level,
    tag: `tag-${seq}`,
    message: `event-${seq}`,
    dropped: 0,
  };
}

function diagnosticWork(
  seq: number,
  level: string = 'info',
  critical: boolean = level === 'error'
): SlockOhosAsyncDiagnosticWork {
  return {
    seq,
    generation: 1,
    level,
    critical,
    raw: diagnosticEntry(seq, level),
    prepared: null,
  };
}

export default function localUnitTest() {
  describe('localUnitTest',() => {
    it('realtimeDohParsesOnlyIpv4AnswersAndKeepsTheClosedProviderTable', 0, () => {
      const body = '{"Status":0,"Answer":[{"type":5,"data":"api.example.cdn."},{"type":1,"data":"104.18.26.14"},{"type":1,"data":"not-an-ip"},{"type":28,"data":"2606:4700::6812:1a0e"}]}';
      const ips = parseDohJsonAnswers(body);
      expect(ips.length).assertEqual(1);
      expect(ips[0]).assertEqual('104.18.26.14');
      expect(parseDohJsonAnswers('{"Status":3,"Answer":[{"type":1,"data":"1.2.3.4"}]}').length).assertEqual(0);
      expect(parseDohJsonAnswers('not json').length).assertEqual(0);
      // Unknown provider names are dropped; empty turns it off.
      expect(new SlockOhosRealtimeDohPolicySink().setDohProviders('alidns,evil,cloudflare')).assertTrue();
      expect(new SlockOhosRealtimeDohPolicySink().setDohProviders('')).assertTrue();
    });
    it('appLoginIsSeparateFromOAuthAndRejectsAmbiguousCallbacks', 0, () => {
      const preparation = prepareSlockOhosAppLogin();
      expect(preparation.returnUri).assertEqual(SLOCK_OHOS_APP_LOGIN_URI);
      expect(/^[A-Za-z0-9_-]{43,128}$/.test(preparation.codeVerifier)).assertTrue();
      expect(parseSlockOhosAppLoginCallback(`${SLOCK_OHOS_APP_LOGIN_URI}?requestId=req&code=one`)?.code).assertEqual('one');
      expect(parseSlockOhosAppLoginCallback(`${SLOCK_OHOS_APP_LOGIN_URI}?requestId=req&error=access_denied`)?.error).assertEqual('access_denied');
      for (const suffix of ['?code=one', '?requestId=req&code=a&error=expired', '?requestId=req&requestId=other&code=a', '?requestId=&code=a', '?code=a&code=b', '?code=a&error=expired', '?provider=web&code=a', '?code=%GG', '?code=a#fragment', '?code=']) {
        expect(parseSlockOhosAppLoginCallback(`${SLOCK_OHOS_APP_LOGIN_URI}${suffix}`) === undefined).assertTrue();
      }
      expect(parseSlockOhosAppLoginCallback('raft://oauth/callback?code=a') === undefined).assertTrue();
    });
    it('mobileOAuthPreparationCreatesPkceS256Material', 0, () => {
      const preparation = prepareSlockOhosMobileOAuth('google', 'link');
      expect(preparation.generation.length > 0).assertTrue();
      expect(preparation.codeVerifier.length >= 43).assertTrue();
      expect(preparation.codeChallenge.length >= 43).assertTrue();
      expect(preparation.codeVerifier === preparation.codeChallenge).assertFalse();
      expect(preparation.returnUri).assertEqual(`${SLOCK_OHOS_MOBILE_OAUTH_SCHEME}://oauth/callback`);
    });
    it('mobileOAuthCallbackAdmitsOnlyCanonicalRaftRoute', 0, () => {
      const callback = parseSlockOhosMobileOAuthCallback(
        `${SLOCK_OHOS_MOBILE_OAUTH_SCHEME}://oauth/callback?provider=github&mode=link&code=handoff%2Dcode`
      );
      expect(callback?.provider).assertEqual('github');
      expect(callback?.mode).assertEqual('link');
      expect(callback?.code).assertEqual('handoff-code');
      expect(callback?.error).assertEqual('');
      expect(parseSlockOhosMobileOAuthCallback('raft-dev://oauth/callback?code=x') === undefined).assertTrue();
      const wrongVariantScheme = SLOCK_OHOS_MOBILE_OAUTH_SCHEME === 'raft' ? 'raft-debug' : 'raft';
      expect(parseSlockOhosMobileOAuthCallback(`${wrongVariantScheme}://oauth/callback?code=x`) === undefined).assertTrue();
      expect(parseSlockOhosMobileOAuthCallback('raft://oauth/wrong?code=x') === undefined).assertTrue();
      expect(parseSlockOhosMobileOAuthCallback('raft://oauth/callback') === undefined).assertTrue();
    });
    it('mobileOAuthCallbackRejectsDuplicateOrUnknownQueries', 0, () => {
      const prefix = `${SLOCK_OHOS_MOBILE_OAUTH_SCHEME}://oauth/callback`;
      expect(parseSlockOhosMobileOAuthCallback(`${prefix}?code=one&code=two`) === undefined).assertTrue();
      expect(parseSlockOhosMobileOAuthCallback(`${prefix}?code=one&state=forged`) === undefined).assertTrue();
      expect(parseSlockOhosMobileOAuthCallback(`${prefix}?error=x&error_description=y`) === undefined).assertTrue();
    });
    it('mobileOAuthIngressDeliversColdAndWarmCallbacksExactlyOnceAfterPageReady', 0, () => {
      const consumed: string[] = [];
      const ingress = new SlockOhosMobileOAuthIngress((uri) => {
        consumed.push(uri ?? '');
        return true;
      });
      ingress.captureColdStart(`${SLOCK_OHOS_MOBILE_OAUTH_SCHEME}://oauth/callback?code=cold`);
      expect(consumed.length).assertEqual(0);
      ingress.markPageContentLoaded();
      ingress.markPageContentLoaded();
      expect(consumed.length).assertEqual(1);
      expect(consumed[0].includes('code=cold')).assertTrue();
      ingress.captureWarmStart(`${SLOCK_OHOS_MOBILE_OAUTH_SCHEME}://oauth/callback?code=warm`);
      expect(consumed.length).assertEqual(2);
      ingress.markPageContentLoaded();
      expect(consumed.length).assertEqual(2);
    });
    it('mobileOAuthDispatchRejectsWrongVariantBeforeKnoiInvocation', 0, () => {
      let invocations = 0;
      const wrongVariantScheme = SLOCK_OHOS_MOBILE_OAUTH_SCHEME === 'raft' ? 'raft-debug' : 'raft';
      const wrongAccepted = dispatchSlockOhosMobileOAuthCallback(
        `${wrongVariantScheme}://oauth/callback?code=wrong`,
        () => {
          invocations += 1;
          return true;
        }
      );
      expect(wrongAccepted).assertFalse();
      expect(invocations).assertEqual(0);
      const activeAccepted = dispatchSlockOhosMobileOAuthCallback(
        `${SLOCK_OHOS_MOBILE_OAUTH_SCHEME}://oauth/callback?code=right`,
        () => {
          invocations += 1;
          return true;
        }
      );
      expect(activeAccepted).assertTrue();
      expect(invocations).assertEqual(1);
    });
    it('attachmentOpenBuildsReadAuthorizedViewWantForPrivateUri', 0, () => {
      const uri = 'file://build.raft.mobile/data/storage/el2/base/files/slock-attachments/archive.zip';
      const wants = buildSlockOhosAttachmentOpenWants(uri, 'application/zip');

      expect(wants.view.action).assertEqual(SLOCK_OHOS_ACTION_VIEW_DATA);
      expect(wants.view.uri).assertEqual(uri);
      expect(wants.view.type).assertEqual('application/zip');
      expect(wants.view.flags).assertEqual(wantConstant.Flags.FLAG_AUTH_READ_URI_PERMISSION);
      expect((wants.view.parameters?.[wantConstant.Params.PARAMS_STREAM] as Array<string>).length).assertEqual(1);
      expect((wants.view.parameters?.[wantConstant.Params.PARAMS_STREAM] as Array<string>)[0]).assertEqual(uri);

    });
    it('attachmentOpenUsesBinaryFallbackAndRejectsBlankUri', 0, () => {
      const wants = buildSlockOhosAttachmentOpenWants('file://build.raft.mobile/private/file', '  ');
      expect(wants.view.type).assertEqual(SLOCK_OHOS_DEFAULT_ATTACHMENT_MIME_TYPE);

      let rejected = false;
      try {
        buildSlockOhosAttachmentOpenWants('   ', 'application/zip');
      } catch (_error) {
        rejected = true;
      }
      expect(rejected).assertTrue();
    });
    it('attachmentOpenFailureSeparatesNoHandlerFromLaunchFailure', 0, () => {
      expect(slockOhosAttachmentOpenFailureKind(16000001)).assertEqual(SlockOhosAttachmentOpenFailureKind.NO_APP);
      expect(slockOhosAttachmentOpenFailureKind(16000019)).assertEqual(SlockOhosAttachmentOpenFailureKind.NO_APP);
      expect(slockOhosAttachmentOpenFailureKind(16000005)).assertEqual(SlockOhosAttachmentOpenFailureKind.FAILED);
      expect(slockOhosAttachmentOpenFailureKind(undefined)).assertEqual(SlockOhosAttachmentOpenFailureKind.FAILED);
    });
    it('attachmentOpenDiagnosticAllowsOnlySanitizedLaunchFacts', 0, () => {
      const privatePath = 'file://build.raft.mobile/data/storage/el2/base/files/private-secret/archive-secret.zip';
      const privateFileName = 'customer-private-secret.zip';
      const privateMimeType = 'application/x-private-secret;name=customer-private-secret.zip';
      const wants = buildSlockOhosAttachmentOpenWants(privatePath, privateMimeType);

      const requested = slockOhosAttachmentOpenDiagnostic(
        wants,
        SlockOhosAttachmentOpenDiagnosticTerminal.REQUESTED
      );
      expect(requested.includes('outer=view_data')).assertTrue();
      expect(requested.includes('child=none')).assertTrue();
      expect(requested.includes('mime=other')).assertTrue();
      expect(requested.includes('uriScheme=file')).assertTrue();
      expect(requested.includes('outerRead=true childRead=false')).assertTrue();
      expect(requested.includes('outerStream=true childStream=false')).assertTrue();
      expect(requested.endsWith('terminal=requested code=none')).assertTrue();
      expect(requested.includes('private-secret')).assertFalse();
      expect(requested.includes(privateFileName)).assertFalse();
      expect(requested.includes(privateMimeType)).assertFalse();
      expect(requested.includes('/data/storage')).assertFalse();

      const unknownSchemeWants = buildSlockOhosAttachmentOpenWants(
        'customer-private-secret://private-host/private-file.zip',
        'application/zip'
      );
      const unknownScheme = slockOhosAttachmentOpenDiagnostic(
        unknownSchemeWants,
        SlockOhosAttachmentOpenDiagnosticTerminal.REQUESTED
      );
      expect(unknownScheme.includes('mime=application/zip')).assertTrue();
      expect(unknownScheme.includes('uriScheme=other')).assertTrue();
      expect(unknownScheme.includes('customer-private-secret')).assertFalse();

      const failed = slockOhosAttachmentOpenDiagnostic(
        wants,
        SlockOhosAttachmentOpenDiagnosticTerminal.LAUNCH_FAILED,
        16000019
      );
      expect(failed.endsWith('terminal=launch_failed code=16000019')).assertTrue();
      expect(failed.includes('archive-secret.zip')).assertFalse();

      const notBuilt = slockOhosAttachmentOpenDiagnostic(
        undefined,
        SlockOhosAttachmentOpenDiagnosticTerminal.PREPARE_FAILED,
        Number.NaN
      );
      expect(notBuilt.includes('outer=none child=none mime=none uriScheme=none')).assertTrue();
      expect(notBuilt.includes('outerRead=false childRead=false')).assertTrue();
      expect(notBuilt.includes('outerStream=false childStream=false')).assertTrue();
      expect(notBuilt.endsWith('terminal=prepare_failed code=none')).assertTrue();
    });
    it('attachmentOpenPreflightDiagnosticCoversEarlyReturnsWithoutPrivateMaterial', 0, () => {
      const requested = slockOhosAttachmentOpenPreflightDiagnostic(
        SlockOhosAttachmentOpenPreflightStage.REQUESTED,
        'application/x-private-secret;name=customer-private-secret.zip',
        true,
        false,
        true
      );
      expect(requested).assertEqual(
        'stage=requested mime=other urlPresent=true committedRequired=false ownerPrivate=true ' +
          'privateCommitted=unknown cacheUsable=unknown downloadStatus=none'
      );
      expect(requested.includes('customer-private-secret')).assertFalse();

      const downloadFailed = slockOhosAttachmentOpenPreflightDiagnostic(
        SlockOhosAttachmentOpenPreflightStage.DOWNLOAD_TERMINAL,
        'application/zip',
        true,
        false,
        true,
        false,
        false,
        'private-upstream-error'
      );
      expect(downloadFailed).assertEqual(
        'stage=download_terminal mime=application/zip urlPresent=true committedRequired=false ownerPrivate=true ' +
          'privateCommitted=false cacheUsable=false downloadStatus=other'
      );
      expect(downloadFailed.includes('private-upstream-error')).assertFalse();

      const missing = slockOhosAttachmentOpenPreflightDiagnostic(
        SlockOhosAttachmentOpenPreflightStage.PUBLIC_DOWNLOAD_MISSING,
        undefined,
        true,
        true,
        false,
        false,
        false,
        'missing'
      );
      expect(missing).assertEqual(
        'stage=public_download_missing mime=none urlPresent=true committedRequired=true ownerPrivate=false ' +
          'privateCommitted=false cacheUsable=false downloadStatus=missing'
      );
    });
    it('attachmentOpenPreflightTraceExecutesRejectedRequestInExactOrder', 0, () => {
      const events: Array<string> = [];
      const trace = new SlockOhosAttachmentOpenPreflightTrace(
        'application/x-private-secret;name=customer-private-secret.zip',
        false,
        false,
        true,
        (message: string): void => { events.push(message); }
      );

      trace.requested();
      trace.requestRejected();

      expect(events.length).assertEqual(2);
      expect(events[0]).assertEqual(
        'stage=requested mime=other urlPresent=false committedRequired=false ownerPrivate=true ' +
          'privateCommitted=unknown cacheUsable=unknown downloadStatus=none'
      );
      expect(events[1]).assertEqual(
        'stage=request_rejected mime=other urlPresent=false committedRequired=false ownerPrivate=true ' +
          'privateCommitted=unknown cacheUsable=unknown downloadStatus=failed'
      );
      expect(events.join('|').includes('customer-private-secret')).assertFalse();
    });
    it('attachmentOpenPreflightDecisionAllowsCommittedOfflineOpenWithoutUrl', 0, () => {
      expect(slockOhosAttachmentOpenPreflightDecision(true, true, false))
        .assertEqual(SlockOhosAttachmentOpenPreflightDecision.PROCEED);
      expect(slockOhosAttachmentOpenPreflightDecision(false, true, false))
        .assertEqual(SlockOhosAttachmentOpenPreflightDecision.PROCEED);
      expect(slockOhosAttachmentOpenPreflightDecision(true, false, false))
        .assertEqual(SlockOhosAttachmentOpenPreflightDecision.PUBLIC_DOWNLOAD_MISSING);
      expect(slockOhosAttachmentOpenPreflightDecision(true, false, true))
        .assertEqual(SlockOhosAttachmentOpenPreflightDecision.PUBLIC_DOWNLOAD_MISSING);
      expect(slockOhosAttachmentOpenPreflightDecision(false, false, false))
        .assertEqual(SlockOhosAttachmentOpenPreflightDecision.URL_MISSING);
      expect(slockOhosAttachmentOpenPreflightDecision(false, false, true))
        .assertEqual(SlockOhosAttachmentOpenPreflightDecision.PROCEED);
    });
    it('attachmentOpenPreflightTraceExecutesPublicMissingInExactOrder', 0, () => {
      const events: Array<string> = [];
      const trace = new SlockOhosAttachmentOpenPreflightTrace(
        undefined,
        true,
        true,
        false,
        (message: string): void => { events.push(message); }
      );

      trace.requested();
      trace.publicDownloadMissing(false);

      expect(events.length).assertEqual(2);
      expect(events[0]).assertEqual(
        'stage=requested mime=none urlPresent=true committedRequired=true ownerPrivate=false ' +
          'privateCommitted=unknown cacheUsable=unknown downloadStatus=none'
      );
      expect(events[1]).assertEqual(
        'stage=public_download_missing mime=none urlPresent=true committedRequired=true ownerPrivate=false ' +
          'privateCommitted=false cacheUsable=false downloadStatus=missing'
      );
    });
    it('attachmentOpenPreflightTraceExecutesCacheHitInExactOrder', 0, () => {
      const events: Array<string> = [];
      const trace = new SlockOhosAttachmentOpenPreflightTrace(
        'application/zip',
        true,
        false,
        true,
        (message: string): void => { events.push(message); }
      );

      trace.requested();
      trace.cacheChecked(true, true);
      trace.wantHandoff(true, false);

      expect(events.length).assertEqual(3);
      expect(events[0]).assertEqual(
        'stage=requested mime=application/zip urlPresent=true committedRequired=false ownerPrivate=true ' +
          'privateCommitted=unknown cacheUsable=unknown downloadStatus=none'
      );
      expect(events[1]).assertEqual(
        'stage=cache_checked mime=application/zip urlPresent=true committedRequired=false ownerPrivate=true ' +
          'privateCommitted=true cacheUsable=true downloadStatus=none'
      );
      expect(events[2]).assertEqual(
        'stage=want_handoff mime=application/zip urlPresent=true committedRequired=false ownerPrivate=true ' +
          'privateCommitted=true cacheUsable=true downloadStatus=none'
      );
    });
    it('attachmentOpenPreflightTraceExecutesDownloadCompleteInExactOrder', 0, () => {
      const events: Array<string> = [];
      const trace = new SlockOhosAttachmentOpenPreflightTrace(
        'application/zip',
        true,
        false,
        true,
        (message: string): void => { events.push(message); }
      );

      trace.requested();
      trace.cacheChecked(false, false);
      trace.downloadStarted(false);
      trace.downloadTerminal(false, 'complete');
      trace.wantHandoff(false, true);

      expect(events.length).assertEqual(5);
      expect(events[0]).assertEqual(
        'stage=requested mime=application/zip urlPresent=true committedRequired=false ownerPrivate=true ' +
          'privateCommitted=unknown cacheUsable=unknown downloadStatus=none'
      );
      expect(events[1]).assertEqual(
        'stage=cache_checked mime=application/zip urlPresent=true committedRequired=false ownerPrivate=true ' +
          'privateCommitted=false cacheUsable=false downloadStatus=none'
      );
      expect(events[2]).assertEqual(
        'stage=download_started mime=application/zip urlPresent=true committedRequired=false ownerPrivate=true ' +
          'privateCommitted=false cacheUsable=false downloadStatus=none'
      );
      expect(events[3]).assertEqual(
        'stage=download_terminal mime=application/zip urlPresent=true committedRequired=false ownerPrivate=true ' +
          'privateCommitted=false cacheUsable=false downloadStatus=complete'
      );
      expect(events[4]).assertEqual(
        'stage=want_handoff mime=application/zip urlPresent=true committedRequired=false ownerPrivate=true ' +
          'privateCommitted=false cacheUsable=true downloadStatus=complete'
      );
    });
    it('attachmentOpenPreflightTraceStopsAfterSanitizedDownloadFailure', 0, () => {
      const events: Array<string> = [];
      const trace = new SlockOhosAttachmentOpenPreflightTrace(
        'application/zip',
        true,
        false,
        true,
        (message: string): void => { events.push(message); }
      );

      trace.requested();
      trace.cacheChecked(false, false);
      trace.downloadStarted(false);
      trace.downloadTerminal(false, 'private-upstream-error');

      expect(events.length).assertEqual(4);
      expect(events[0]).assertEqual(
        'stage=requested mime=application/zip urlPresent=true committedRequired=false ownerPrivate=true ' +
          'privateCommitted=unknown cacheUsable=unknown downloadStatus=none'
      );
      expect(events[1]).assertEqual(
        'stage=cache_checked mime=application/zip urlPresent=true committedRequired=false ownerPrivate=true ' +
          'privateCommitted=false cacheUsable=false downloadStatus=none'
      );
      expect(events[2]).assertEqual(
        'stage=download_started mime=application/zip urlPresent=true committedRequired=false ownerPrivate=true ' +
          'privateCommitted=false cacheUsable=false downloadStatus=none'
      );
      expect(events[3]).assertEqual(
        'stage=download_terminal mime=application/zip urlPresent=true committedRequired=false ownerPrivate=true ' +
          'privateCommitted=false cacheUsable=false downloadStatus=other'
      );
      expect(events.join('|').includes('private-upstream-error')).assertFalse();
      expect(events.join('|').includes('stage=want_handoff')).assertFalse();
    });
    it('privateAttachmentPathBindsOwnerAndAttachmentWithoutDisplayNameAuthority', 0, () => {
      const owner = 'a'.repeat(64);
      expect(isSlockOhosPrivateAttachmentOwnerKey(owner)).assertTrue();
      expect(isSlockOhosPrivateAttachmentOwnerKey('A'.repeat(64))).assertFalse();
      expect(isSlockOhosPrivateAttachmentOwnerKey(`${'a'.repeat(63)}/`)).assertFalse();
      expect(isSlockOhosPrivateAttachmentOwnerKey('g'.repeat(64))).assertFalse();
      expect(slockOhosPrivateAttachmentDirectory('/data/storage/el2/base/files'))
        .assertEqual('/data/storage/el2/base/files/slock-attachment-downloads');
      expect(slockOhosPrivateAttachmentFileName(owner, '../attachment-id', '../report:2026', '.pdf'))
        .assertEqual(`${owner}-_attachment-id`);
      expect(slockOhosPrivateAttachmentFileName(owner, '../attachment-id', 'renamed.png', '.png'))
        .assertEqual(`${owner}-_attachment-id`);
      expect(slockOhosPrivateAttachmentOpenAliasDirectoryName(owner, '../attachment-id'))
        .assertEqual(`open-${owner}-_attachment-id`);
      expect(
        slockOhosPrivateAttachmentOpenAliasDirectoryName(owner, 'other-attachment') !==
          slockOhosPrivateAttachmentOpenAliasDirectoryName(owner, '../attachment-id')
      ).assertTrue();
      expect(slockOhosPrivateAttachmentOpenAliasFileName('../项目资料 终版.zip', '.ZIP'))
        .assertEqual('项目资料 终版.zip');
      expect(slockOhosPrivateAttachmentOpenAliasFileName('..\\danger:\u0000name?', '.zip'))
        .assertEqual('danger__name_.zip');
      expect(slockOhosPrivateAttachmentOpenAliasFileName('../..', '.zip'))
        .assertEqual('attachment.zip');
      expect(slockOhosPrivateAttachmentOpenAliasFileName('原始报告.docx', ''))
        .assertEqual('原始报告.docx');
      expect(slockOhosPrivateAttachmentOpenAliasFileName('', ''))
        .assertEqual('attachment.bin');
    });

    it('privateAttachmentOpenAliasCopiesOnceAndReusesOnlyFreshExactSize', 0, async () => {
      const backend = new FakePrivateAttachmentOpenAliasBackend();
      backend.stats.set('/private/source', { isFile: true, sizeBytes: 128, mtime: 20 });
      backend.contents.set('/private/source', 'PK\u0003\u0004exact-zip-bytes');
      backend.directoryEntries.set('/cache', []);

      expect(await materializeSlockOhosPrivateAttachmentOpenAlias('/private/source', '/cache/open.zip', backend))
        .assertEqual('/cache/open.zip');
      expect(backend.copyCount).assertEqual(1);
      expect(backend.contents.get('/cache/open.zip')).assertEqual('PK\u0003\u0004exact-zip-bytes');
      expect(await materializeSlockOhosPrivateAttachmentOpenAlias('/private/source', '/cache/open.zip', backend))
        .assertEqual('/cache/open.zip');
      expect(backend.copyCount).assertEqual(1);

      backend.stats.set('/private/source', { isFile: true, sizeBytes: 256, mtime: 40 });
      await materializeSlockOhosPrivateAttachmentOpenAlias('/private/source', '/cache/open.zip', backend);
      expect(backend.copyCount).assertEqual(2);
      expect(backend.unlinkCount).assertEqual(1);
    });

    it('privateAttachmentOpenAliasRenameKeepsOnlyCurrentLeafAndCanonicalSource', 0, async () => {
      const backend = new FakePrivateAttachmentOpenAliasBackend();
      backend.stats.set('/private/source', { isFile: true, sizeBytes: 128, mtime: 20 });
      backend.contents.set('/private/source', 'PK\u0003\u0004canonical');
      backend.stats.set('/cache/old.zip', { isFile: true, sizeBytes: 128, mtime: 20 });
      backend.contents.set('/cache/old.zip', 'PK\u0003\u0004canonical');
      backend.directoryEntries.set('/cache', ['old.zip']);

      await materializeSlockOhosPrivateAttachmentOpenAlias('/private/source', '/cache/新名字.zip', backend);

      expect(backend.removedPaths.join(',')).assertEqual('/cache/old.zip');
      expect(backend.contents.get('/cache/新名字.zip')).assertEqual('PK\u0003\u0004canonical');
      expect(backend.contents.get('/private/source')).assertEqual('PK\u0003\u0004canonical');
      expect(backend.stats.get('/private/source')?.sizeBytes).assertEqual(128);
    });

    it('privateAttachmentOpenAliasCopyThrowAndWrongSizeRemovePartialOnly', 0, async () => {
      const throwing = new FakePrivateAttachmentOpenAliasBackend();
      throwing.stats.set('/private/source', { isFile: true, sizeBytes: 128, mtime: 20 });
      throwing.contents.set('/private/source', 'canonical');
      throwing.directoryEntries.set('/cache', []);
      throwing.copyFailure = new Error('copy failed');
      let copyFailed = false;
      try {
        await materializeSlockOhosPrivateAttachmentOpenAlias('/private/source', '/cache/open.zip', throwing);
      } catch (_) {
        copyFailed = true;
      }
      expect(copyFailed).assertTrue();
      expect(throwing.removedPaths.join(',')).assertEqual('/cache/open.zip');
      expect(throwing.contents.get('/private/source')).assertEqual('canonical');

      const truncated = new FakePrivateAttachmentOpenAliasBackend();
      truncated.stats.set('/private/source', { isFile: true, sizeBytes: 128, mtime: 20 });
      truncated.contents.set('/private/source', 'canonical');
      truncated.directoryEntries.set('/cache', []);
      truncated.copiedSizeOverride = 64;
      let sizeFailed = false;
      try {
        await materializeSlockOhosPrivateAttachmentOpenAlias('/private/source', '/cache/open.zip', truncated);
      } catch (_) {
        sizeFailed = true;
      }
      expect(sizeFailed).assertTrue();
      expect(truncated.removedPaths.join(',')).assertEqual('/cache/open.zip');
      expect(truncated.stats.get('/private/source')?.sizeBytes).assertEqual(128);
    });

    it('privateAttachmentOpenAliasCleanupExpiresOldAndEvictsEightyFirstDirectory', 0, () => {
      const backend = new FakePrivateAttachmentOpenAliasBackend();
      const cacheEntries: Array<string> = [];
      for (let index = 0; index < 81; index += 1) {
        const directoryName = `open-${index}`;
        const directory = `/cache/${directoryName}`;
        cacheEntries.push(directoryName);
        backend.directoryEntries.set(directory, ['alias.zip']);
        backend.stats.set(`${directory}/alias.zip`, { isFile: true, sizeBytes: 10, mtime: 1000 + index });
      }
      cacheEntries.push('unrelated');
      backend.directoryEntries.set('/cache', cacheEntries);
      backend.stats.set('/private/source', { isFile: true, sizeBytes: 128, mtime: 1 });

      const evicted = cleanupSlockOhosPrivateAttachmentOpenAliases(
        '/cache',
        24 * 60 * 60 * 1000,
        80,
        1080 * 1000,
        '/cache/open-0',
        backend
      );
      expect(evicted).assertEqual(1);
      expect(backend.removedDirectories.join(',')).assertEqual('/cache/open-1');
      expect(backend.removedPaths.join(',')).assertEqual('/cache/open-1/alias.zip');
      expect(backend.stats.get('/cache/open-0/alias.zip')?.sizeBytes).assertEqual(10);
      expect(backend.stats.get('/private/source')?.sizeBytes).assertEqual(128);

      const expiryBackend = new FakePrivateAttachmentOpenAliasBackend();
      expiryBackend.directoryEntries.set('/cache', ['open-expired', 'unrelated']);
      expiryBackend.directoryEntries.set('/cache/open-expired', ['alias.zip']);
      expiryBackend.stats.set('/cache/open-expired/alias.zip', { isFile: true, sizeBytes: 10, mtime: 1000 });
      expiryBackend.stats.set('/private/source', { isFile: true, sizeBytes: 128, mtime: 1 });
      const expired = cleanupSlockOhosPrivateAttachmentOpenAliases(
        '/cache',
        24 * 60 * 60 * 1000,
        80,
        (1000 + 86401) * 1000,
        '',
        expiryBackend
      );
      expect(expired).assertEqual(1);
      expect(expiryBackend.removedDirectories.join(',')).assertEqual('/cache/open-expired');
      expect(expiryBackend.removedPaths.join(',')).assertEqual('/cache/open-expired/alias.zip');
      expect(expiryBackend.stats.get('/private/source')?.sizeBytes).assertEqual(128);
    });

    it('privateAttachmentProviderCopyStreamsExactBytesAndClosesBothFds', 0, () => {
      const backend = new FakePrivateAttachmentCopyBackend();
      backend.sourceSize = 140000;

      const copied = copySlockOhosPrivateAttachmentToProviderUri(
        '/data/storage/el2/base/files/source.pdf',
        'file://docs-provider/target.pdf',
        backend
      );

      expect(copied).assertEqual(140000);
      expect(backend.writtenBytes).assertEqual(140000);
      expect(backend.openedTargets[0]).assertEqual('file://docs-provider/target.pdf');
      expect(backend.closedKinds.join(',')).assertEqual('target,source');
    });

    it('privateAttachmentProviderCopyRejectsTruncatedWriteAndStillClosesFds', 0, () => {
      const backend = new FakePrivateAttachmentCopyBackend();
      backend.sourceSize = 80;
      backend.writeLimit = 0;
      let failed = false;

      try {
        copySlockOhosPrivateAttachmentToProviderUri('/private/source', 'file://provider/target', backend);
      } catch (_) {
        failed = true;
      }

      expect(failed).assertTrue();
      expect(backend.closedKinds.join(',')).assertEqual('target,source');
    });

    it('privateAttachmentProviderCopyCompletesPartialProviderWrites', 0, () => {
      const backend = new FakePrivateAttachmentCopyBackend();
      backend.sourceSize = 80;
      backend.writeLimit = 13;

      const copied = copySlockOhosPrivateAttachmentToProviderUri(
        '/private/source',
        'file://provider/target',
        backend
      );

      expect(copied).assertEqual(80);
      expect(backend.writtenBytes).assertEqual(80);
      expect(backend.closedKinds.join(',')).assertEqual('target,source');
    });

    it('privateAttachmentProviderCopyClosesBothHandlesAndPreservesPrimaryFailure', 0, () => {
      const backend = new FakePrivateAttachmentCopyBackend();
      backend.sourceSize = 80;
      backend.readFailure = new Error('primary read failure');
      backend.closeTargetFailure = new Error('target close failure');
      backend.closeSourceFailure = new Error('source close failure');
      let message = '';

      try {
        copySlockOhosPrivateAttachmentToProviderUri('/private/source', 'file://provider/target', backend);
      } catch (error) {
        message = (error as Error).message;
      }

      expect(message).assertEqual('primary read failure');
      expect(backend.closedKinds.join(',')).assertEqual('target,source');
    });

    it('privateAttachmentProviderCopyStillClosesSourceWhenTargetCloseFails', 0, () => {
      const backend = new FakePrivateAttachmentCopyBackend();
      backend.sourceSize = 80;
      backend.closeTargetFailure = new Error('target close failure');
      let message = '';

      try {
        copySlockOhosPrivateAttachmentToProviderUri('/private/source', 'file://provider/target', backend);
      } catch (error) {
        message = (error as Error).message;
      }

      expect(message).assertEqual('target close failure');
      expect(backend.closedKinds.join(',')).assertEqual('target,source');
    });

    it('privateAttachmentProviderCopyClosesSourceWhenTargetOpenFails', 0, () => {
      const backend = new FakePrivateAttachmentCopyBackend();
      backend.sourceSize = 80;
      backend.targetOpenFailure = new Error('target open failure');
      let message = '';

      try {
        copySlockOhosPrivateAttachmentToProviderUri('/private/source', 'file://provider/target', backend);
      } catch (error) {
        message = (error as Error).message;
      }

      expect(message).assertEqual('target open failure');
      expect(backend.closedKinds.join(',')).assertEqual('source');
    });

    it('privateAttachmentProviderCopyReportsSourceCloseFailureAfterSuccessfulCopy', 0, () => {
      const backend = new FakePrivateAttachmentCopyBackend();
      backend.sourceSize = 80;
      backend.closeSourceFailure = new Error('source close failure');
      let message = '';

      try {
        copySlockOhosPrivateAttachmentToProviderUri('/private/source', 'file://provider/target', backend);
      } catch (error) {
        message = (error as Error).message;
      }

      expect(message).assertEqual('source close failure');
      expect(backend.closedKinds.join(',')).assertEqual('target,source');
    });

    it('privateAttachmentOrphanCleanupIsExactAndNeverDeletesCommittedFinal', 0, () => {
      const backend = new FakePrivateAttachmentCleanupBackend();
      backend.names.push('owner-attachment');
      backend.names.push('owner-attachment.part-old');
      backend.names.push('owner-attachment.part-');
      backend.names.push('owner-other.part-old');

      const removed = cleanupSlockOhosPrivateAttachmentOrphanParts(
        '/private/owner-attachment',
        backend
      );

      expect(removed).assertEqual(1);
      expect(backend.removedPaths.join(','))
        .assertEqual('/private/owner-attachment.part-old');
    });

    it('privateAttachmentProviderCopyFailsBeforePickerTargetWhenPrivateCopyMissing', 0, () => {
      const backend = new FakePrivateAttachmentCopyBackend();
      let failed = false;

      try {
        copySlockOhosPrivateAttachmentToProviderUri('/private/missing', 'file://provider/target', backend);
      } catch (_) {
        failed = true;
      }

      expect(failed).assertTrue();
      expect(backend.openedTargets.length).assertEqual(0);
      expect(backend.closedKinds.length).assertEqual(0);
    });
    it('attachmentCacheDirectoryReusesExistingDirectoryWithoutCreating', 0, () => {
      const backend = new FakeAttachmentCacheDirectoryBackend();
      backend.directoryExists = true;

      ensureSlockOhosAttachmentCacheDirectory('/data/cache/slock-attachments', backend);

      expect(backend.createCount).assertEqual(0);
    });

    it('attachmentCacheDirectoryAcceptsConcurrentExistsAfterCreateError', 0, () => {
      const backend = new FakeAttachmentCacheDirectoryBackend();
      backend.createError = new Error('13900015 File exists');
      backend.directoryExistsAfterCreateAttempt = true;

      ensureSlockOhosAttachmentCacheDirectory('/data/cache/slock-attachments', backend);

      expect(backend.createCount).assertEqual(1);
      expect(backend.directoryExists).assertTrue();
    });

    it('attachmentCacheDirectoryPreservesCreateFailureWhenPathIsNotDirectory', 0, () => {
      const backend = new FakeAttachmentCacheDirectoryBackend();
      backend.createError = new Error('permission denied');
      let failed = false;
      let failureMessage = '';
      try {
        ensureSlockOhosAttachmentCacheDirectory('/data/cache/slock-attachments', backend);
      } catch (error) {
        failed = true;
        failureMessage = (error as Error).message;
      }

      expect(failed).assertTrue();
      expect(failureMessage).assertEqual('permission denied');
      expect(backend.createCount).assertEqual(1);
    });

    it('pickedAttachmentCacheSeparatesRendererPathUriFromPlatformFileRef', 0, () => {
      const backend = new FakePickedAttachmentCacheBackend();
      backend.copiedSizeBytes = 4;
      const targetPath = '/data/storage/el2/base/cache/slock-attachments/picked-1-photo.jpg';
      const cached = cacheSlockOhosPickedAttachment(
        42,
        targetPath,
        'photo.jpg',
        'image/jpeg',
        4,
        backend
      );

      expect(backend.copiedSourceFds[0]).assertEqual(42);
      expect(backend.copiedTargetPaths[0]).assertEqual(targetPath);
      expect(cached.previewUri).assertEqual(`file://${targetPath}`);
      expect(cached.fileRef).assertEqual(`file://build.raft.mobile${targetPath}`);
      expect(cached.fileRef === cached.previewUri).assertFalse();
      expect(cached.fileName).assertEqual('photo.jpg');
      expect(cached.mimeType).assertEqual('image/jpeg');
      expect(cached.sizeBytes).assertEqual(4);
      expect(backend.removedPaths.length).assertEqual(0);
    });

    it('temporaryFileRetirementAllowsOnlyHostlessOrOwnBundleAuthority', 0, () => {
      const ownBundle = 'build.raft.mobile';
      expect(isSlockOhosThreadComposerTemporaryFileRefAuthorityAllowed(
        'file:///data/storage/el2/base/cache/picked.jpg', ownBundle
      )).assertTrue();
      expect(isSlockOhosThreadComposerTemporaryFileRefAuthorityAllowed(
        'file://build.raft.mobile/data/storage/el2/base/cache/picked.jpg', ownBundle
      )).assertTrue();
      expect(isSlockOhosThreadComposerTemporaryFileRefAuthorityAllowed(
        'file://BUILD.RAFT.MOBILE/data/storage/el2/base/cache/picked.jpg', ownBundle
      )).assertTrue();
      expect(isSlockOhosThreadComposerTemporaryFileRefAuthorityAllowed(
        'file://foreign.bundle/data/storage/el2/base/cache/picked.jpg', ownBundle
      )).assertFalse();
      expect(isSlockOhosThreadComposerTemporaryFileRefAuthorityAllowed(
        'content://foreign.bundle/data/storage/el2/base/cache/picked.jpg', ownBundle
      )).assertFalse();
    });

    it('pickedAttachmentCacheFailsClosedAndRemovesPartialCopyOnSizeMismatch', 0, () => {
      const backend = new FakePickedAttachmentCacheBackend();
      backend.copiedSizeBytes = 3;
      const targetPath = '/data/storage/el2/base/cache/slock-attachments/picked-1-photo.jpg';
      let failed = false;
      try {
        cacheSlockOhosPickedAttachment(
          42,
          targetPath,
          'photo.jpg',
          'image/jpeg',
          4,
          backend
        );
      } catch (_) {
        failed = true;
      }

      expect(failed).assertTrue();
      expect(backend.removedPaths[0]).assertEqual(targetPath);
    });

    it('imageOnlyPickerNormalizesOpaquePhotoNameButKeepsUntrustedFilesStrict', 0, () => {
      expect(normalizeSlockOhosImagePickerFileName('opaque-id', 'image/jpeg', true))
        .assertEqual('opaque-id.jpg');
      expect(normalizeSlockOhosImagePickerFileName('clip', 'video/mp4', true))
        .assertEqual('clip');
      expect(normalizeSlockOhosImagePickerFileName('opaque-id', 'image/jpeg', false))
        .assertEqual('opaque-id');
    });

    it('feedbackDraftPromotionCommitsActualStatToFilesDirAsOneGroup', 0, () => {
      const backend = new FakeFeedbackDraftAttachmentBackend();
      backend.files.set('/temp/editor-output.png', 7);
      const promoted = promoteSlockOhosFeedbackDraftAttachments(
        '/data/storage/el2/base/files/settings-hands-feedback-drafts',
        'account-a',
        'group-a',
        [{ id: 'edited', fileName: 'edited.jpg', mimeType: 'image/png', fileRef: '/temp/editor-output.png' }],
        10,
        backend,
        (_: string): string => 'account-test',
      );

      expect(promoted.length).assertEqual(1);
      expect(promoted[0].sizeBytes).assertEqual(7);
      expect(promoted[0].source).assertEqual('settings_feedback_draft');
      expect(promoted[0].fileName).assertEqual('edited.png');
      expect(promoted[0].mimeType).assertEqual('image/png');
      expect(promoted[0].previewUri.startsWith('file:///')).assertTrue();
      expect(promoted[0].previewUri.endsWith('.png')).assertTrue();
      expect(backend.resolveFileRef(promoted[0].fileRef).includes('/files/settings-hands-feedback-drafts/')).assertTrue();
      expect(backend.isFile(backend.resolveFileRef(promoted[0].fileRef))).assertTrue();

      backend.files.delete('/temp/editor-output.png');
      const reopened = promoteSlockOhosFeedbackDraftAttachments(
        '/data/storage/el2/base/files/settings-hands-feedback-drafts',
        'account-a',
        'group-a',
        [{ id: 'edited', fileName: 'edited.jpg', mimeType: 'image/png', fileRef: '/temp/editor-output.png' }],
        10,
        backend,
        (_: string): string => 'account-test',
      );
      expect(reopened[0].fileRef).assertEqual(promoted[0].fileRef);
      expect(reopened[0].sizeBytes).assertEqual(7);
    });

    it('feedbackDraftAccountScopeUses128BitDigestSegment', 0, () => {
      const digest = new Uint8Array([
        0, 1, 2, 3, 4, 5, 6, 7,
        8, 9, 10, 11, 12, 13, 14, 15,
        16, 17, 18, 19, 20, 21, 22, 23,
        24, 25, 26, 27, 28, 29, 30, 31,
      ]);
      expect(slockOhosFeedbackDraftAccountSegmentFromDigest(digest))
        .assertEqual('account-000102030405060708090a0b0c0d0e0f');
    });

    it('feedbackDraftPromotionFailureLeavesNoCommittedOrStagedFiles', 0, () => {
      const backend = new FakeFeedbackDraftAttachmentBackend();
      backend.files.set('/temp/small.png', 2);
      backend.files.set('/temp/large.png', 11);
      let failed = false;
      try {
        promoteSlockOhosFeedbackDraftAttachments(
          '/data/storage/el2/base/files/settings-hands-feedback-drafts',
          'account-a',
          'group-failure',
          [
            { id: 'small', fileName: 'small.png', mimeType: 'image/png', fileRef: '/temp/small.png' },
            { id: 'large', fileName: 'large.png', mimeType: 'image/png', fileRef: '/temp/large.png' },
          ],
          10,
          backend,
          (_: string): string => 'account-test',
        );
      } catch (_) {
        failed = true;
      }

      expect(failed).assertTrue();
      expect(Array.from(backend.files.keys()).filter((path: string): boolean =>
        path.includes('settings-hands-feedback-drafts')
      ).length).assertEqual(0);
    });

    it('handsFeedbackAttachmentsNormalizeFileUrisAndPreserveAdmissionGates', 0, () => {
      const backend = new FakeHandsFeedbackAttachmentBackend();
      const authorityRef = 'file://build.raft.mobile/data/storage/el2/base/cache/picked.jpg';
      const authorityPath = '/data/storage/el2/base/cache/picked.jpg';
      const absolutePath = '/data/storage/el2/base/files/raft-feedback.zip';
      const oversizedPath = '/data/storage/el2/base/cache/large.mov';
      backend.uriPaths.set(authorityRef, authorityPath);
      backend.stats.set(authorityPath, { isFile: true, sizeBytes: 4 });
      backend.stats.set(absolutePath, { isFile: true, sizeBytes: 8 });
      backend.stats.set(oversizedPath, { isFile: true, sizeBytes: 11 });
      backend.throwingRef = 'file://build.raft.mobile/broken';

      const inputs: Array<FakeHandsFeedbackAttachmentInput> = [
        { fileRef: authorityRef },
        { fileRef: 'content://media/photo/1' },
        { fileRef: 'relative.txt' },
        { fileRef: 'file://build.raft.mobile/broken' },
        { fileRef: absolutePath },
        { fileRef: oversizedPath },
      ];
      const paths = slockOhosHandsFeedbackAttachmentPaths(inputs, 3, 10, backend);

      expect(paths.length).assertEqual(2);
      expect(paths[0]).assertEqual(authorityPath);
      expect(paths[1]).assertEqual(absolutePath);
      expect(backend.resolvedRefs.length).assertEqual(2);
      expect(backend.statPaths.includes(authorityRef)).assertFalse();
      expect(backend.statPaths.includes(authorityPath)).assertTrue();
      expect(backend.statPaths.includes(absolutePath)).assertTrue();
      expect(backend.statPaths.includes(oversizedPath)).assertTrue();
    });

    it('platformFileReaderCapsChunksAdvancesOffsetAndClosesOnce', 0, () => {
      const backend = new FakePlatformFileBackend();
      backend.setData('large', new Array<number>(SLOCK_OHOS_PLATFORM_FILE_MAX_STREAM_CHUNK_BYTES * 2 + 3).fill(7));
      const reader = new SlockOhosPlatformFileReader(backend);
      const stat = reader.statFile('large');
      expect(stat['sizeBytes'] as number).assertEqual(SLOCK_OHOS_PLATFORM_FILE_MAX_STREAM_CHUNK_BYTES * 2 + 3);
      const opened = reader.openFileStream('large', stat['sizeBytes'] as number);
      const streamId = opened['streamId'] as string;

      const first = reader.readFileChunk(streamId, SLOCK_OHOS_PLATFORM_FILE_MAX_STREAM_CHUNK_BYTES * 4);
      const second = reader.readFileChunk(streamId, SLOCK_OHOS_PLATFORM_FILE_MAX_STREAM_CHUNK_BYTES * 4);
      const third = reader.readFileChunk(streamId, SLOCK_OHOS_PLATFORM_FILE_MAX_STREAM_CHUNK_BYTES * 4);
      const eof = reader.readFileChunk(streamId, 1);
      reader.closeFileStream(streamId);
      reader.closeFileStream(streamId);

      expect(first['readBytes'] as number).assertEqual(SLOCK_OHOS_PLATFORM_FILE_MAX_STREAM_CHUNK_BYTES);
      expect((first['bodyBase64'] as string).length <=
        Math.ceil(SLOCK_OHOS_PLATFORM_FILE_MAX_STREAM_CHUNK_BYTES / 3) * 4
      ).assertTrue();
      expect(second['readBytes'] as number).assertEqual(SLOCK_OHOS_PLATFORM_FILE_MAX_STREAM_CHUNK_BYTES);
      expect(third['readBytes'] as number).assertEqual(3);
      expect(eof['eof'] as boolean).assertTrue();
      expect(backend.requestedMaxBytes.every((value: number) =>
        value <= SLOCK_OHOS_PLATFORM_FILE_MAX_STREAM_CHUNK_BYTES
      )).assertTrue();
      expect(backend.closeCount).assertEqual(2); // stat probe + one stream close
    });

    it('platformFileReaderExposesShortAndGrowingReadsForKmpExactLengthFence', 0, () => {
      const shortBackend = new FakePlatformFileBackend();
      shortBackend.setData('short', [1, 2, 3, 4]);
      const shortReader = new SlockOhosPlatformFileReader(shortBackend);
      const shortId = shortReader.openFileStream('short', 4)['streamId'] as string;
      shortBackend.setData('short', [1, 2]);
      expect(shortReader.readFileChunk(shortId, 4)['readBytes'] as number).assertEqual(2);
      expect(shortReader.readFileChunk(shortId, 2)['eof'] as boolean).assertTrue();
      shortReader.closeFileStream(shortId);

      const growBackend = new FakePlatformFileBackend();
      growBackend.setData('grow', [1, 2]);
      const growReader = new SlockOhosPlatformFileReader(growBackend);
      const growId = growReader.openFileStream('grow', 2)['streamId'] as string;
      growBackend.setData('grow', [1, 2, 3]);
      expect(growReader.readFileChunk(growId, 2)['readBytes'] as number).assertEqual(2);
      expect(growReader.readFileChunk(growId, 1)['readBytes'] as number).assertEqual(1);
      growReader.closeFileStream(growId);

      expect(shortBackend.closeCount).assertEqual(1);
      expect(growBackend.closeCount).assertEqual(1);
    });

    it('diagnosticsArchiveUsesOnlyRaftNames', 0, () => {
      expect(isOhosDiagnosticsLogFileName('raft-diagnostics.jsonl')).assertTrue();
      expect(isOhosDiagnosticsLogFileName('old-brand-diagnostics.jsonl.1')).assertFalse();
      expect(isOhosFeedbackArchiveName('raft-feedback-1.zip')).assertTrue();
      expect(isOhosFeedbackArchiveName('old-brand-feedback-1.zip')).assertFalse();
      expect(isOhosSafeDiagnosticsArchiveComponent('launch-1-raft-diagnostics.jsonl')).assertTrue();
      expect(isOhosSafeDiagnosticsArchiveComponent('..')).assertFalse();
      expect(isOhosSafeDiagnosticsArchiveComponent('launch\\raft-diagnostics.jsonl')).assertFalse();
      expect(isOhosSafeDiagnosticsArchiveComponent('slock-diagnostics')).assertFalse();
      expect(isOhosDiagnosticsDescendantPath('/root-a', '/root-a/launch-1/file')).assertTrue();
      expect(isOhosDiagnosticsDescendantPath('/root-a', '/root-ab/launch-1/file')).assertFalse();
      expect(isOhosDiagnosticsDescendantPath('/root-a', '/outside/file')).assertFalse();
      expect(ohosDiagnosticsSourceNodeKind(false, true, false))
        .assertEqual(OhosDiagnosticsSourceNodeKind.RegularFile);
      expect(ohosDiagnosticsSourceNodeKind(true, true, false))
        .assertEqual(OhosDiagnosticsSourceNodeKind.Rejected);
      expect(ohosDiagnosticsSourceNodeKind(false, false, false))
        .assertEqual(OhosDiagnosticsSourceNodeKind.Rejected);
      expect(ohosDiagnosticsSourceNodeKind(false, false, true))
        .assertEqual(OhosDiagnosticsSourceNodeKind.Directory);

      const sourceKinds = new Map<string, OhosDiagnosticsSourceNodeKind>([
        ['/root-a', OhosDiagnosticsSourceNodeKind.Directory],
        ['/root-a/launch-safe', OhosDiagnosticsSourceNodeKind.Directory],
        ['/root-a/launch-safe/raft-diagnostics.jsonl', OhosDiagnosticsSourceNodeKind.RegularFile],
        ['/root-a/launch-link', ohosDiagnosticsSourceNodeKind(true, false, true)],
        ['/root-a/real', OhosDiagnosticsSourceNodeKind.Directory],
        ['/root-a/real/subdir-link', ohosDiagnosticsSourceNodeKind(true, false, true)],
        ['/root-ab/launch-outside/raft-diagnostics.jsonl', OhosDiagnosticsSourceNodeKind.RegularFile],
      ]);
      const sourceChildren = new Map<string, Array<string>>([
        ['/root-a', ['launch-safe', 'launch-link', 'real']],
        ['/root-a/launch-safe', ['raft-diagnostics.jsonl']],
        ['/root-a/real', ['subdir-link']],
      ]);
      const inspector = new FakeOhosDiagnosticsSourceInspector(sourceKinds, sourceChildren);
      const admitted = collectOhosDiagnosticsSourceFiles('/root-a', '/root-a', inspector);
      expect(admitted.length).assertEqual(1);
      expect(admitted[0]).assertEqual('/root-a/launch-safe/raft-diagnostics.jsonl');
      expect(collectOhosDiagnosticsSourceFiles(
        '/root-a',
        '/root-ab/launch-outside/raft-diagnostics.jsonl',
        inspector
      ).length).assertEqual(0);
      const reserved = new Set<string>();
      expect(ohosUniqueDiagnosticsStagedName('raft-diagnostics.jsonl', reserved))
        .assertEqual('raft-diagnostics.jsonl');
      expect(ohosUniqueDiagnosticsStagedName('raft-diagnostics.jsonl', reserved))
        .assertEqual('raft-diagnostics-2.jsonl');
      expect(ohosUniqueDiagnosticsStagedName('raft-diagnostics.jsonl', reserved))
        .assertEqual('raft-diagnostics-3.jsonl');
      expect(compareOhosDiagnosticsPath('/b', '/a', 100, 100)).assertEqual(1);
      expect(compareOhosDiagnosticsPath('/new', '/old', 200, 100)).assertEqual(-100);
      const selectedAfterEmptyGroups = selectOhosDiagnosticsCandidateGroups([
        { entryPrefix: 'current-empty-1-', files: [] },
        { entryPrefix: 'current-empty-2-', files: [] },
        { entryPrefix: 'current-empty-3-', files: [] },
        { entryPrefix: 'current-empty-4-', files: [] },
        { entryPrefix: 'current-empty-5-', files: [] },
        { entryPrefix: 'current-launch-', files: ['/current/raft-diagnostics.jsonl'] },
      ], 5);
      expect(selectedAfterEmptyGroups.length).assertEqual(1);
      expect(selectedAfterEmptyGroups[0].entryPrefix).assertEqual('current-launch-');

      const selectedCurrentFirst = selectOhosDiagnosticsCandidateGroups([
        { entryPrefix: 'current-launch-', files: ['/current/raft-diagnostics.jsonl'] },
        { entryPrefix: 'current-1-', files: ['/current/1.jsonl'] },
        { entryPrefix: 'current-2-', files: ['/current/2.jsonl'] },
        { entryPrefix: 'current-3-', files: ['/current/3.jsonl'] },
        { entryPrefix: 'current-4-', files: ['/current/4.jsonl'] },
        { entryPrefix: 'current-5-', files: ['/current/5.jsonl'] },
      ], 5);
      expect(selectedCurrentFirst.length).assertEqual(5);
      expect(selectedCurrentFirst[0].entryPrefix).assertEqual('current-launch-');
      expect(selectedCurrentFirst[4].entryPrefix).assertEqual('current-4-');
    });
    it('handsFeedbackEnvironmentOmitsUnusedRowsWithoutChangingDiagnosticsRows', 0, () => {
      const fullEnvironment = [
        'App: Raft',
        'Version: 1.0.0',
        'Version code: 1000000',
        'Build: 1000000',
        'Source: 593a5c542',
        'Package: build.raft.mobile',
        'Platform: OpenHarmony / API 24',
        'SDK: 24',
        'Device: HUAWEI HOP-AL10',
        'Model: HOP-AL10',
        'Install time: --',
        'Screen: --',
        'Density: --',
        'Font scale: --',
        'Session: --',
        'Signed in user: --',
        'Selected server: --',
        'Socket: Shared shell',
      ];

      const feedbackEnvironment = filterOhosHandsFeedbackEnvironmentLines(fullEnvironment);
      const feedbackText = feedbackEnvironment.join('\n');

      expect(feedbackEnvironment.length).assertEqual(10);
      expect(feedbackEnvironment[0]).assertEqual('App: Raft');
      expect(feedbackEnvironment[5]).assertEqual('Package: build.raft.mobile');
      expect(feedbackEnvironment[9]).assertEqual('Model: HOP-AL10');
      expect(feedbackText).assertEqual([
        'App: Raft',
        'Version: 1.0.0',
        'Version code: 1000000',
        'Build: 1000000',
        'Source: 593a5c542',
        'Package: build.raft.mobile',
        'Platform: OpenHarmony / API 24',
        'SDK: 24',
        'Device: HUAWEI HOP-AL10',
        'Model: HOP-AL10',
      ].join('\n'));
      expect(feedbackText.includes('Install time:')).assertFalse();
      expect(feedbackText.includes('Screen:')).assertFalse();
      expect(feedbackText.includes('Density:')).assertFalse();
      expect(feedbackText.includes('Font scale:')).assertFalse();
      expect(feedbackText.includes('Session:')).assertFalse();
      expect(feedbackText.includes('Signed in user:')).assertFalse();
      expect(feedbackText.includes('Selected server:')).assertFalse();
      expect(feedbackText.includes('Socket:')).assertFalse();
      expect(fullEnvironment.length).assertEqual(18);
      expect(fullEnvironment[10]).assertEqual('Install time: --');
      expect(fullEnvironment[17]).assertEqual('Socket: Shared shell');
    });
    it('foregroundEpochAdvancesOnlyOnHiddenToVisibleLeadingEdge', 0, () => {
      const first = nextSlockOhosForegroundEpoch(0, false, true);
      const duplicateShow = nextSlockOhosForegroundEpoch(first, true, true);
      const hidden = nextSlockOhosForegroundEpoch(duplicateShow, true, false);
      const duplicateHidden = nextSlockOhosForegroundEpoch(hidden, false, false);
      const second = nextSlockOhosForegroundEpoch(duplicateHidden, false, true);

      expect(first).assertEqual(1);
      expect(duplicateShow).assertEqual(1);
      expect(hidden).assertEqual(1);
      expect(duplicateHidden).assertEqual(1);
      expect(second).assertEqual(2);
    });
    it('realtimeHostPublishesNativeFanOutToSharedIngressExactlyOnceWithoutRetry', 0, () => {
      const acceptedPayloads: Array<string> = [];
      const rejectingPayloads: Array<string> = [];
      const throwingPayloads: Array<string> = [];
      const acceptedIngress = new FakeRealtimeSharedIngress(acceptedPayloads, true);
      const rejectingIngress = new FakeRealtimeSharedIngress(rejectingPayloads, false);
      const throwingIngress = new FakeRealtimeSharedIngress(throwingPayloads, false, true);

      new SlockOhosRealtimeHostContext(acceptedIngress).onPageShow();
      new SlockOhosRealtimeHostContext(rejectingIngress).onPageShow();
      new SlockOhosRealtimeHostContext(throwingIngress).onPageShow();

      expect(acceptedPayloads.length).assertEqual(1);
      expect(rejectingPayloads.length).assertEqual(1);
      expect(throwingPayloads.length).assertEqual(1);
      const accepted = JSON.parse(acceptedPayloads[0]) as Record<string, Object>;
      expect(accepted['action']).assertEqual('appLifecycle');
      expect(accepted['event']).assertEqual('show');
    });
    it('processNetworkLifecycleRegistersOnceAndPublishesOnlyApplicationEdges', 0, () => {
      const transitions: Array<boolean> = [];
      let registrationCount = 0;
      let callback: ApplicationStateChangeCallback | undefined;
      const lifecycle = new SlockOhosProcessNetworkLifecycle((foreground: boolean): number => {
        transitions.push(foreground);
        return 0;
      });
      const register = (candidate: ApplicationStateChangeCallback): void => {
        registrationCount += 1;
        callback = candidate;
      };

      expect(lifecycle.installWithRegistrar(register)).assertTrue();
      expect(lifecycle.installWithRegistrar(register)).assertFalse();
      expect(registrationCount).assertEqual(1);

      const applicationCallback = callback as ApplicationStateChangeCallback;
      applicationCallback.onApplicationForeground();
      applicationCallback.onApplicationForeground();
      applicationCallback.onApplicationBackground();
      applicationCallback.onApplicationBackground();
      applicationCallback.onApplicationForeground();

      expect(transitions.length).assertEqual(3);
      expect(transitions[0]).assertTrue();
      expect(transitions[1]).assertFalse();
      expect(transitions[2]).assertTrue();
    });
    it('activityUnreadWidgetAcceptsOnlyFiniteLiveCounts', 0, () => {
      expect(activityUnreadCountOrNull(4)).assertEqual(4);
      expect(activityUnreadCountOrNull(-2)).assertEqual(0);
      expect(activityUnreadCountOrNull(undefined)).assertNull();
      expect(activityUnreadCountOrNull(Number.NaN)).assertNull();
      expect(activityUnreadCountOrNull(Number.POSITIVE_INFINITY)).assertNull();
      expect(activityUnreadCountOrNull(Number.NEGATIVE_INFINITY)).assertNull();
    });
    it('activityUnreadWidgetUsesOnlyValidPersistedProjectionAsStaleFallback', 0, () => {
      expect(activityUnreadPersistedFallbackCount({ totalUnreadCount: 4 })).assertEqual(4);
      expect(activityUnreadPersistedFallbackCount({ totalUnreadCount: -2 })).assertEqual(0);
      expect(activityUnreadPersistedFallbackCount({})).assertNull();
      expect(activityUnreadPersistedFallbackCount({ totalUnreadCount: Number.NaN })).assertNull();
      expect(activityUnreadPersistedFallbackCount({ totalUnreadCount: Number.POSITIVE_INFINITY })).assertNull();
      expect(activityUnreadPersistedFallbackCount({ totalUnreadCount: Number.NEGATIVE_INFINITY })).assertNull();
      expect(activityUnreadPersistedFallbackCount(null)).assertNull();
    });
    it('activityUnreadWidgetCreatesTypedErrorWhenNoFallbackExists', 0, () => {
      const original = new Error('offline');
      const normalized = createActivityUnreadWidgetRefreshError(original);
      const nonError = createActivityUnreadWidgetRefreshError(new FakeNonErrorReason());

      expect(normalized instanceof Error).assertTrue();
      expect(normalized.message).assertContain('offline');
      expect(nonError instanceof Error).assertTrue();
      expect(nonError.message).assertEqual('activity_unread_widget_refresh_failed');
    });
    it('decodesSocketIoFrames', 0, () => {
      const open = decodeOhosSocketIoFrame('0{"sid":"sid-1","pingInterval":25000,"pingTimeout":20000}');
      expect(open.kind).assertEqual('engine_open');
      expect(open.pingInterval).assertEqual(25000);
      expect(open.pingTimeout).assertEqual(20000);

      expect(decodeOhosSocketIoFrame('2').kind).assertEqual('engine_ping');
      expect(decodeOhosSocketIoFrame('1').kind).assertEqual('engine_close');
      expect(decodeOhosSocketIoFrame('40{"sid":"socket-1"}').kind).assertEqual('socket_connect');
      expect(decodeOhosSocketIoFrame('41').kind).assertEqual('socket_disconnect');

      const connectError = decodeOhosSocketIoFrame('44{"message":"Unauthorized"}');
      expect(connectError.kind).assertEqual('socket_connect_error');
      expect(connectError.message).assertContain('Unauthorized');

      const event = decodeOhosSocketIoFrame('42["message:new",{"id":"msg-1","content":"hello"}]');
      expect(event.kind).assertEqual('socket_event');
      expect(event.eventName).assertEqual('message:new');
      expect((event.payload as Record<string, string>)['id']).assertEqual('msg-1');
    });
    it('diagnosticsQueueReservesErrorCapacityAndPreservesOrder', 0, () => {
      const queue = new SlockOhosDiagnosticsQueue(4, 2);
      expect(queue.offer(diagnosticWork(1))).assertTrue();
      expect(queue.offer(diagnosticWork(2))).assertTrue();
      expect(queue.offer(diagnosticWork(3))).assertFalse();
      expect(queue.offer(diagnosticWork(4, 'error'))).assertTrue();
      expect(queue.offer(diagnosticWork(5, 'error'))).assertTrue();
      expect(queue.offer(diagnosticWork(6, 'error'))).assertFalse();

      const first = queue.poll();
      const second = queue.poll();
      const third = queue.poll();
      const fourth = queue.poll();
      expect(first?.seq).assertEqual(1);
      expect(second?.seq).assertEqual(2);
      expect(third?.seq).assertEqual(4);
      expect(third?.raw?.dropped).assertEqual(1);
      expect(fourth?.seq).assertEqual(5);
      expect(queue.consumeTrailingDropped()).assertEqual(1);
    });
    it('diagnosticsQueueCarriesDropsOnTheNextAcceptedEntry', 0, () => {
      const queue = new SlockOhosDiagnosticsQueue(2, 1);
      expect(queue.offer(diagnosticWork(10))).assertTrue();
      expect(queue.offer(diagnosticWork(11))).assertFalse();
      expect(queue.poll()?.seq).assertEqual(10);
      expect(queue.offer(diagnosticWork(12))).assertTrue();
      const resumed = queue.poll();
      expect(resumed?.seq).assertEqual(12);
      expect(resumed?.raw?.dropped).assertEqual(1);
      expect(queue.consumeTrailingDropped()).assertEqual(0);
    });
    it('diagnosticsQueueUnifiesRawAndPreparedAdmissionOrder', 0, () => {
      const queue = new SlockOhosDiagnosticsQueue(4, 3);
      const raw = diagnosticWork(30);
      const prepared: SlockOhosAsyncDiagnosticWork = {
        seq: 31,
        generation: 1,
        level: 'info',
        critical: false,
        raw: null,
        prepared: {
          timestampMillis: 31,
          seq: 31,
          level: 'info',
          tag: 'shared',
          event: 'shared',
          message: 'shared',
          dropped: 0,
          truncated: false,
          thread: 'kmp-worker',
          sourceSeq: 9,
        },
      };
      expect(queue.offer(raw)).assertTrue();
      expect(queue.poll()?.seq).assertEqual(30);
      // seq=30 is now taskpool in-flight; shared seq=31 must wait behind it.
      expect(queue.offer(prepared)).assertTrue();
      expect(canSlockOhosDrain(true)).assertFalse();
      expect(canSlockOhosDrain(false)).assertTrue();
      expect(queue.poll()?.seq).assertEqual(31);
    });
    it('diagnosticsQueueReservesHostCapacityForInfoAuthCritical', 0, () => {
      const queue = new SlockOhosDiagnosticsQueue(4, 2);
      expect(queue.offer(diagnosticWork(32))).assertTrue();
      expect(queue.offer(diagnosticWork(33))).assertTrue();
      expect(queue.offer(diagnosticWork(34))).assertFalse();
      expect(queue.offer(diagnosticWork(35, 'info', true))).assertTrue();
      expect(queue.offer(diagnosticWork(36, 'warn', true))).assertTrue();
      expect(queue.poll()?.seq).assertEqual(32);
      expect(queue.poll()?.seq).assertEqual(33);
      expect(queue.poll()?.seq).assertEqual(35);
      expect(queue.poll()?.seq).assertEqual(36);
    });
    it('diagnosticsPreparedBatchRejectsInvalidPayload', 0, () => {
      let admittedCount = 0;
      const invalidPayloads = ['{invalid', '{}', 'null'];
      for (let index = 0; index < invalidPayloads.length; index += 1) {
        const admitted = admitSlockOhosPreparedBatch(invalidPayloads[index], () => {
          admittedCount += 1;
          return true;
        });

        expect(admitted).assertFalse();
      }
      expect(admittedCount).assertEqual(0);
    });
    it('diagnosticsPreparedBatchAcceptsEmptyArrayAsNoOp', 0, () => {
      let admittedCount = 0;
      const admitted = admitSlockOhosPreparedBatch('[]', () => {
        admittedCount += 1;
        return true;
      });

      expect(admitted).assertTrue();
      expect(admittedCount).assertEqual(0);
    });
    it('diagnosticsPreparedBatchFailsWhenHostQueueSaturates', 0, () => {
      const queue = new SlockOhosDiagnosticsQueue(2, 1);
      let seq = 100;
      const payload = JSON.stringify([
        preparedBatchEntry(1),
        preparedBatchEntry(2),
      ]);
      const admitted = admitSlockOhosPreparedBatch(payload, (entry: SlockOhosPreparedDiagnosticEntry) => {
        const work: SlockOhosAsyncDiagnosticWork = {
          seq,
          generation: 1,
          level: entry.level,
          critical: entry.critical === true,
          raw: null,
          prepared: entry,
        };
        seq += 1;
        return queue.offer(work);
      });

      expect(admitted).assertFalse();
      expect(queue.size()).assertEqual(1);
      expect(queue.poll()?.prepared?.sourceSeq).assertEqual(1);
    });
    it('diagnosticsQueueClearDropsQueuedGeneration', 0, () => {
      const queue = new SlockOhosDiagnosticsQueue(4, 3);
      expect(queue.offer(diagnosticWork(40))).assertTrue();
      expect(queue.offer(diagnosticWork(41))).assertTrue();
      queue.clear();
      expect(queue.size()).assertEqual(0);
      expect(queue.droppedCount()).assertEqual(0);
      expect(queue.poll()).assertNull();
    });
    it('diagnosticsGenerationFenceRejectsPreClearInflightResult', 0, () => {
      expect(isSlockOhosWorkGenerationCurrent(7, 7)).assertTrue();
      expect(isSlockOhosWorkGenerationCurrent(7, 8)).assertFalse();
    });
    it('diagnosticsSnapshotExplicitlyReportsPendingAcceptedEntries', 0, () => {
      const complete = createSlockOhosDiagnosticSnapshotState(0);
      expect(complete.complete).assertTrue();
      expect(complete.pendingAccepted).assertEqual(0);
      const incomplete = createSlockOhosDiagnosticSnapshotState(2);
      expect(incomplete.complete).assertFalse();
      expect(incomplete.pendingAccepted).assertEqual(2);
    });
    it('diagnosticsTaskpoolFailureDropsRawContentAndKeepsSequence', 0, () => {
      const raw = diagnosticEntry(21, 'error');
      raw.tag = 'Bearer secret-tag';
      raw.message = 'password=secret-value';
      raw.dropped = 3;
      const failure = createSlockOhosTaskpoolFailureEntry(raw, 1000);

      expect(failure.seq).assertEqual(21);
      expect(failure.timestampMillis).assertEqual(1000);
      expect(failure.dropped).assertEqual(4);
      expect(failure.event).assertEqual('async_taskpool_failed');
      expect(failure.thread).assertEqual('taskpool');
      expect(failure.sourceSeq).assertEqual(21);
      expect(failure.message).assertEqual('async_taskpool_failed dropped=4');
      expect(failure.message.includes('secret')).assertFalse();
      expect(failure.tag.includes('Bearer')).assertFalse();
    });
  });
}

function preparedBatchEntry(sourceSeq: number): SlockOhosPreparedDiagnosticEntry {
  return {
    timestampMillis: sourceSeq,
    level: 'info',
    tag: 'shared',
    event: `shared-${sourceSeq}`,
    message: `shared-${sourceSeq}`,
    dropped: 0,
    truncated: false,
    thread: 'kmp-worker',
    sourceSeq,
    critical: false,
  };
}
