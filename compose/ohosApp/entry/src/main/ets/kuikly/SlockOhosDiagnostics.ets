import hilog from '@ohos.hilog';
import taskpool from '@ohos.taskpool';
import {
  canSlockOhosDrain,
  createSlockOhosTaskpoolFailureEntry,
  createSlockOhosDiagnosticSnapshotState,
  isSlockOhosWorkGenerationCurrent,
  SlockOhosAsyncDiagnosticWork,
  SlockOhosDiagnosticsQueue,
  SlockOhosPreparedDiagnosticEntry,
  SlockOhosRawAsyncDiagnosticEntry,
} from './SlockOhosDiagnosticsQueue';

export interface SlockOhosDiagnosticEntry {
  timestampMillis: number;
  level: string;
  tag: string;
  event: string;
  message: string;
  thread: string;
  seq: number;
  dropped: number;
  truncated: boolean;
  sourceSeq: number;
}

@Concurrent
function prepareSlockOhosDiagnosticEntry(
  raw: SlockOhosRawAsyncDiagnosticEntry
): SlockOhosPreparedDiagnosticEntry {
  const patterns: RegExp[] = [
    /(\bauthorization\s*[:=]\s*bearer\s+)[^\s,;]+/gi,
    /(\bbearer\s+)[^\s,;]+/gi,
    /(\baccess[_-]?token\s*[:=]\s*)[^\s,;]+/gi,
    /(\brefresh[_-]?token\s*[:=]\s*)[^\s,;]+/gi,
    /(\bpassword\s*[:=]\s*)[^\s,;]+/gi,
    /(\braw\s+(?:response|body)\s*[:=]\s*).+/gi,
    /\b((?:eyJ)[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.)[A-Za-z0-9_-]+\b/g,
    /\b([A-Za-z0-9._%+-]{2})[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\.[A-Za-z]{2,})\b/g,
  ];
  let safeTag = raw.tag;
  let sanitizedMessage = raw.message;
  patterns.forEach((pattern: RegExp): void => {
    safeTag = safeTag.replace(pattern, '$1[redacted]');
    sanitizedMessage = sanitizedMessage.replace(pattern, '$1[redacted]');
  });
  safeTag = safeTag.slice(0, 48);
  const safeMessage = sanitizedMessage.slice(0, 2048);
  const firstSpace = safeMessage.indexOf(' ');
  const event = (firstSpace >= 0 ? safeMessage.slice(0, firstSpace) : safeMessage).slice(0, 96);
  const normalizedLevel = raw.level.trim().toLowerCase();
  return {
    timestampMillis: raw.timestampMillis,
    seq: raw.seq,
    level:
      normalizedLevel === 'debug' || normalizedLevel === 'warn' || normalizedLevel === 'error'
        ? normalizedLevel
        : 'info',
    tag: safeTag,
    event,
    message: safeMessage,
    dropped: raw.dropped,
    truncated: sanitizedMessage.length > safeMessage.length,
    thread: 'taskpool',
    sourceSeq: raw.seq,
  };
}

export class SlockOhosDiagnostics {
  private static readonly MAX_ENTRIES: number = 5000;
  private static readonly ASYNC_QUEUE_CAPACITY: number = 512;
  private static readonly ASYNC_QUEUE_NORMAL_CAPACITY: number = 480;
  private static readonly SENSITIVE_PATTERNS: RegExp[] = [
    /(\bauthorization\s*[:=]\s*bearer\s+)[^\s,;]+/gi,
    /(\bbearer\s+)[^\s,;]+/gi,
    /(\baccess[_-]?token\s*[:=]\s*)[^\s,;]+/gi,
    /(\brefresh[_-]?token\s*[:=]\s*)[^\s,;]+/gi,
    /(\bpassword\s*[:=]\s*)[^\s,;]+/gi,
    /(\braw\s+(?:response|body)\s*[:=]\s*).+/gi,
    /\b((?:eyJ)[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.)[A-Za-z0-9_-]+\b/g,
    /\b([A-Za-z0-9._%+-]{2})[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\.[A-Za-z]{2,})\b/g,
  ];

  private static entries: SlockOhosDiagnosticEntry[] = [];
  private static entryStartIndex: number = 0;
  private static asyncQueue: SlockOhosDiagnosticsQueue = new SlockOhosDiagnosticsQueue(
    SlockOhosDiagnostics.ASYNC_QUEUE_CAPACITY,
    SlockOhosDiagnostics.ASYNC_QUEUE_NORMAL_CAPACITY
  );
  private static nextSequence: number = 1;
  private static generation: number = 1;
  private static asyncDispatchScheduled: boolean = false;
  private static asyncDispatchInFlight: boolean = false;
  private static asyncDispatchGeneration: number = 0;
  private static upstreamPending: boolean = false;
  private static upstreamHealthy: boolean = true;
  private static persistentRecorder: ((entry: SlockOhosDiagnosticEntry) => void) | null = null;

  static setPersistentRecorder(recorder: ((entry: SlockOhosDiagnosticEntry) => void) | null): void {
    SlockOhosDiagnostics.persistentRecorder = recorder;
  }

  static setUpstreamState(pending: boolean, healthy: boolean): void {
    SlockOhosDiagnostics.upstreamPending = pending;
    SlockOhosDiagnostics.upstreamHealthy = healthy && SlockOhosDiagnostics.upstreamHealthy;
  }

  static record(
    tag: string,
    message: string,
    timestampMillis: number = Date.now(),
    level: string = 'info',
    _mirrorToHiLog: boolean = true
  ): void {
    SlockOhosDiagnostics.recordAsync(tag, message, timestampMillis, level);
  }

  /** Fixed, secret-free evidence for a caught fatal path; never accepts raw content. */
  static recordCrashCaptured(timestampMillis: number = Date.now()): void {
    const seq = SlockOhosDiagnostics.allocateSequence();
    const entry: SlockOhosDiagnosticEntry = {
      timestampMillis,
      level: 'error',
      tag: 'SlockDiagnostics',
      event: 'crash_captured',
      message: 'crash_captured',
      thread: 'arkts-crash',
      seq,
      dropped: 0,
      truncated: false,
      sourceSeq: seq,
    };
    SlockOhosDiagnostics.mirrorToHiLog(entry.tag, entry.message, entry.level);
    SlockOhosDiagnostics.appendEntry(entry);
    SlockOhosDiagnostics.persistentRecorder?.(entry);
  }

  /** Fixed-cost render-caller capture; regex/format/routing run in a taskpool consumer. */
  static recordAsync(
    tag: string,
    message: string,
    timestampMillis: number = Date.now(),
    level: string = 'info'
  ): void {
    const normalizedLevel = SlockOhosDiagnostics.normalizeLevel(level);
    if (normalizedLevel === 'error') {
      // Keep one secret-free crash-adjacent breadcrumb even if the process
      // terminates before taskpool returns the fully sanitized entry.
      SlockOhosDiagnostics.mirrorToHiLog(
        'SlockDiagnostics',
        `async_error_captured ts=${timestampMillis}`,
        'error'
      );
    }
    const seq = SlockOhosDiagnostics.allocateSequence();
    const raw: SlockOhosRawAsyncDiagnosticEntry = {
      timestampMillis,
      seq,
      level: normalizedLevel,
      tag: tag.slice(0, 96),
      message: message.slice(0, 8192),
      dropped: 0,
    };
    const accepted = SlockOhosDiagnostics.asyncQueue.offer({
      seq,
      generation: SlockOhosDiagnostics.generation,
      level: normalizedLevel,
      critical: normalizedLevel === 'error',
      raw,
      prepared: null,
    });
    if (!accepted) {
      if (
        normalizedLevel === 'error' &&
        SlockOhosDiagnostics.asyncQueue.size() >= SlockOhosDiagnostics.ASYNC_QUEUE_CAPACITY
      ) {
        // Preserve a bounded, secret-free emergency breadcrumb without running
        // regex or formatting on the render caller.
        SlockOhosDiagnostics.mirrorToHiLog(
          'SlockDiagnostics',
          `async_error_queue_overflow dropped=${SlockOhosDiagnostics.asyncQueue.droppedCount()}`,
          'error'
        );
      }
      return;
    }
    SlockOhosDiagnostics.scheduleAsyncDrain();
  }

  /** Admit already-sanitized shared entries to the same FIFO owner as render logs. */
  static recordPreparedAsync(entry: SlockOhosPreparedDiagnosticEntry): boolean {
    const seq = SlockOhosDiagnostics.allocateSequence();
    const normalizedLevel = SlockOhosDiagnostics.normalizeLevel(entry.level);
    entry.seq = seq;
    const accepted = SlockOhosDiagnostics.asyncQueue.offer({
      seq,
      generation: SlockOhosDiagnostics.generation,
      level: normalizedLevel,
      critical: normalizedLevel === 'error' || entry.critical === true,
      raw: null,
      prepared: entry,
    });
    if (accepted) {
      SlockOhosDiagnostics.scheduleAsyncDrain();
    }
    return accepted;
  }

  /** Commit an entry that was sanitized and shaped on a background consumer. */
  private static recordPrepared(
    entry: SlockOhosPreparedDiagnosticEntry,
    mirrorToHiLog: boolean = true
  ): void {
    const safeTag = entry.tag.slice(0, 48);
    const normalizedLevel = SlockOhosDiagnostics.normalizeLevel(entry.level);
    const safeMessage = entry.message.slice(0, 2048);
    if (mirrorToHiLog) {
      SlockOhosDiagnostics.mirrorToHiLog(safeTag, safeMessage, normalizedLevel);
    }
    const persistentEntry: SlockOhosDiagnosticEntry = {
      timestampMillis: entry.timestampMillis,
      level: normalizedLevel,
      tag: safeTag,
      event: entry.event.slice(0, 96),
      message: safeMessage,
      thread: entry.thread === undefined ? 'arkts' : entry.thread,
      sourceSeq: entry.sourceSeq === undefined ? 0 : entry.sourceSeq,
      seq: entry.seq === undefined ? SlockOhosDiagnostics.allocateSequence() : entry.seq,
      dropped: entry.dropped === undefined ? 0 : entry.dropped,
      truncated: entry.truncated || entry.message.length > safeMessage.length,
    };
    SlockOhosDiagnostics.appendEntry(persistentEntry);
    SlockOhosDiagnostics.persistentRecorder?.(persistentEntry);
  }

  static recent(limit: number = 500): SlockOhosDiagnosticEntry[] {
    const ordered = SlockOhosDiagnostics.orderedEntries();
    const recent = ordered.slice(Math.max(0, ordered.length - limit));
    const pending = SlockOhosDiagnostics.pendingAcceptedCount();
    if (pending === 0) {
      return recent;
    }
    return recent.concat([SlockOhosDiagnostics.incompleteSnapshotEntry(pending)]);
  }

  static exportText(limit: number = 500): string {
    const snapshotState = createSlockOhosDiagnosticSnapshotState(
      SlockOhosDiagnostics.pendingAcceptedCount()
    );
    return [
      'App-owned recent diagnostics',
      'Files: 0',
      `Complete: ${snapshotState.complete}`,
      `Pending accepted entries: ${snapshotState.pendingAccepted}`,
      '',
      '### recent-memory.jsonl',
      SlockOhosDiagnostics.exportJsonl(limit),
    ].join('\n');
  }

  static exportJsonl(limit: number = 500): string {
    return SlockOhosDiagnostics.recent(limit)
      .map((entry: SlockOhosDiagnosticEntry) => JSON.stringify({
        ts: new Date(entry.timestampMillis).toISOString(),
        level: entry.level,
        tag: entry.tag,
        event: entry.event,
        message: entry.message,
        thread: entry.thread,
        seq: entry.seq,
        dropped: entry.dropped,
        truncated: entry.truncated,
        sourceSeq: entry.sourceSeq,
      }))
      .join('\n');
  }

  static clear(): void {
    SlockOhosDiagnostics.generation += 1;
    SlockOhosDiagnostics.asyncQueue.clear();
    SlockOhosDiagnostics.entries = [];
    SlockOhosDiagnostics.entryStartIndex = 0;
  }

  private static pendingAcceptedCount(): number {
    return SlockOhosDiagnostics.asyncQueue.size() +
      (SlockOhosDiagnostics.asyncDispatchInFlight &&
        SlockOhosDiagnostics.asyncDispatchGeneration === SlockOhosDiagnostics.generation ? 1 : 0) +
      (SlockOhosDiagnostics.upstreamPending ? 1 : 0) +
      (SlockOhosDiagnostics.upstreamHealthy ? 0 : 1);
  }

  private static incompleteSnapshotEntry(pending: number): SlockOhosDiagnosticEntry {
    return {
      timestampMillis: Date.now(),
      level: 'warn',
      tag: 'SlockDiagnostics',
      event: 'diagnostics_snapshot_incomplete',
      message: `diagnostics_snapshot_incomplete pending=${pending}`,
      thread: 'arkts',
      seq: -1,
      dropped: 0,
      truncated: false,
      sourceSeq: 0,
    };
  }

  private static allocateSequence(): number {
    const seq = SlockOhosDiagnostics.nextSequence;
    SlockOhosDiagnostics.nextSequence += 1;
    return seq;
  }

  static sanitize(value: string): string {
    let sanitized = value;
    SlockOhosDiagnostics.SENSITIVE_PATTERNS.forEach((pattern: RegExp) => {
      sanitized = sanitized.replace(pattern, '$1[redacted]');
    });
    return sanitized;
  }

  private static normalizeLevel(value: string): string {
    const level = value.trim().toLowerCase();
    if (level === 'debug' || level === 'info' || level === 'warn' || level === 'error') {
      return level;
    }
    return 'info';
  }

  private static mirrorToHiLog(tag: string, message: string, level: string): void {
    try {
      if (level === 'debug') {
        hilog.debug(0x0000, tag, '%{public}s', message);
        return;
      }
      if (level === 'warn') {
        hilog.warn(0x0000, tag, '%{public}s', message);
        return;
      }
      if (level === 'error') {
        hilog.error(0x0000, tag, '%{public}s', message);
        return;
      }
      hilog.info(0x0000, tag, '%{public}s', message);
    } catch (_error) {
    }
  }

  private static eventName(message: string): string {
    const firstSpace = message.indexOf(' ');
    const event = firstSpace >= 0 ? message.slice(0, firstSpace) : message;
    return event.slice(0, 96);
  }

  private static appendEntry(entry: SlockOhosDiagnosticEntry): void {
    if (SlockOhosDiagnostics.entries.length < SlockOhosDiagnostics.MAX_ENTRIES) {
      SlockOhosDiagnostics.entries.push(entry);
      return;
    }
    SlockOhosDiagnostics.entries[SlockOhosDiagnostics.entryStartIndex] = entry;
    SlockOhosDiagnostics.entryStartIndex =
      (SlockOhosDiagnostics.entryStartIndex + 1) % SlockOhosDiagnostics.MAX_ENTRIES;
  }

  private static orderedEntries(): SlockOhosDiagnosticEntry[] {
    let ordered: SlockOhosDiagnosticEntry[];
    if (
      SlockOhosDiagnostics.entries.length < SlockOhosDiagnostics.MAX_ENTRIES ||
      SlockOhosDiagnostics.entryStartIndex === 0
    ) {
      ordered = SlockOhosDiagnostics.entries.slice();
    } else {
      ordered = SlockOhosDiagnostics.entries.slice(SlockOhosDiagnostics.entryStartIndex)
        .concat(SlockOhosDiagnostics.entries.slice(0, SlockOhosDiagnostics.entryStartIndex));
    }
    ordered.sort((left: SlockOhosDiagnosticEntry, right: SlockOhosDiagnosticEntry): number => {
      return left.seq - right.seq;
    });
    return ordered;
  }

  private static scheduleAsyncDrain(): void {
    if (
      SlockOhosDiagnostics.asyncDispatchScheduled ||
      SlockOhosDiagnostics.asyncDispatchInFlight ||
      SlockOhosDiagnostics.asyncQueue.size() === 0 &&
      SlockOhosDiagnostics.asyncQueue.droppedCount() === 0
    ) {
      return;
    }
    SlockOhosDiagnostics.asyncDispatchScheduled = true;
    setTimeout((): void => {
      SlockOhosDiagnostics.asyncDispatchScheduled = false;
      SlockOhosDiagnostics.drainAsyncQueue();
    }, 0);
  }

  private static drainAsyncQueue(): void {
    if (!canSlockOhosDrain(SlockOhosDiagnostics.asyncDispatchInFlight)) {
      return;
    }
    let work = SlockOhosDiagnostics.asyncQueue.poll();
    if (work === null) {
      const dropped = SlockOhosDiagnostics.asyncQueue.consumeTrailingDropped();
      if (dropped === 0) {
        return;
      }
      const seq = SlockOhosDiagnostics.allocateSequence();
      const raw: SlockOhosRawAsyncDiagnosticEntry = {
        timestampMillis: Date.now(),
        seq,
        level: 'warn',
        tag: 'SlockDiagnostics',
        message: `async_queue_dropped count=${dropped} capacity=${SlockOhosDiagnostics.ASYNC_QUEUE_CAPACITY}`,
        dropped,
      };
      work = {
        seq,
        generation: SlockOhosDiagnostics.generation,
        level: 'warn',
        critical: false,
        raw,
        prepared: null,
      };
    }
    const currentWork = work as SlockOhosAsyncDiagnosticWork;
    SlockOhosDiagnostics.asyncDispatchInFlight = true;
    SlockOhosDiagnostics.asyncDispatchGeneration = currentWork.generation;
    if (currentWork.prepared !== null) {
      const prepared = currentWork.prepared;
      setTimeout((): void => {
        if (isSlockOhosWorkGenerationCurrent(currentWork.generation, SlockOhosDiagnostics.generation)) {
          SlockOhosDiagnostics.recordPrepared(prepared);
        }
        SlockOhosDiagnostics.completeAsyncDrain();
      }, 0);
      return;
    }
    const rawEntry = currentWork.raw as SlockOhosRawAsyncDiagnosticEntry;
    const task = new taskpool.Task(prepareSlockOhosDiagnosticEntry, rawEntry);
    taskpool.execute(task, taskpool.Priority.LOW)
      .then((result: Object): void => {
        const prepared = result as SlockOhosPreparedDiagnosticEntry;
        setTimeout((): void => {
          if (isSlockOhosWorkGenerationCurrent(currentWork.generation, SlockOhosDiagnostics.generation)) {
            SlockOhosDiagnostics.recordPrepared(prepared);
          }
          SlockOhosDiagnostics.completeAsyncDrain();
        }, 0);
      })
      .catch((_error: Error): void => {
        const failure = createSlockOhosTaskpoolFailureEntry(rawEntry, Date.now());
        setTimeout((): void => {
          if (isSlockOhosWorkGenerationCurrent(currentWork.generation, SlockOhosDiagnostics.generation)) {
            SlockOhosDiagnostics.recordPrepared(failure);
          }
          SlockOhosDiagnostics.completeAsyncDrain();
        }, 0);
      });
  }

  private static completeAsyncDrain(): void {
    SlockOhosDiagnostics.asyncDispatchInFlight = false;
    SlockOhosDiagnostics.asyncDispatchGeneration = 0;
    SlockOhosDiagnostics.scheduleAsyncDrain();
  }
}
