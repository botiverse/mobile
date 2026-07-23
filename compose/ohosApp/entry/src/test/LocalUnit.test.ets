import { describe, beforeAll, beforeEach, afterEach, afterAll, it, expect } from '@ohos/hypium';
import { decodeOhosSocketIoFrame } from '../main/ets/kuikly/OhosSocketIoClient';
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
import { nextSlockOhosForegroundEpoch } from '../main/ets/kuikly/SlockOhosRealtimeHostContext';
import {
  compareOhosDiagnosticsPath,
  collectOhosDiagnosticsSourceFiles,
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

class FakePlatformFileHandle {
  readonly fileRef: string;

  constructor(fileRef: string) {
    this.fileRef = fileRef;
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
      const nonError = createActivityUnreadWidgetRefreshError({ reason: 'offline' });

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
