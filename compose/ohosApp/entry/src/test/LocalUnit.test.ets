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
    // Defines a test suite. Two parameters are supported: test suite name and test suite function.
    beforeAll(() => {
      // Presets an action, which is performed only once before all test cases of the test suite start.
      // This API supports only one parameter: preset action function.
    });
    beforeEach(() => {
      // Presets an action, which is performed before each unit test case starts.
      // The number of execution times is the same as the number of test cases defined by **it**.
      // This API supports only one parameter: preset action function.
    });
    afterEach(() => {
      // Presets a clear action, which is performed after each unit test case ends.
      // The number of execution times is the same as the number of test cases defined by **it**.
      // This API supports only one parameter: clear action function.
    });
    afterAll(() => {
      // Presets a clear action, which is performed after all test cases of the test suite end.
      // This API supports only one parameter: clear action function.
    });
    it('assertContain', 0, () => {
      // Defines a test case. This API supports three parameters: test case name, filter parameter, and test case function.
      let a = 'abc';
      let b = 'b';
      // Defines a variety of assertion methods, which are used to declare expected boolean conditions.
      expect(a).assertContain(b);
      expect(a).assertEqual(a);
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
