export interface SlockOhosRawAsyncDiagnosticEntry {
  timestampMillis: number;
  seq: number;
  level: string;
  tag: string;
  message: string;
  dropped: number;
}

export interface SlockOhosPreparedDiagnosticEntry {
  timestampMillis: number;
  seq?: number;
  level: string;
  tag: string;
  event: string;
  message: string;
  dropped?: number;
  truncated: boolean;
  thread?: string;
  sourceSeq?: number;
  critical?: boolean;
}

export interface SlockOhosAsyncDiagnosticWork {
  seq: number;
  generation: number;
  level: string;
  critical: boolean;
  raw: SlockOhosRawAsyncDiagnosticEntry | null;
  prepared: SlockOhosPreparedDiagnosticEntry | null;
}

export interface SlockOhosDiagnosticSnapshotState {
  complete: boolean;
  pendingAccepted: number;
}

export function admitSlockOhosPreparedBatch(
  payload: string,
  admit: (entry: SlockOhosPreparedDiagnosticEntry) => boolean
): boolean {
  try {
    const parsed: Object = JSON.parse(payload) as Object;
    if (!Array.isArray(parsed)) {
      return false;
    }
    const entries = parsed as Array<SlockOhosPreparedDiagnosticEntry>;
    for (let index = 0; index < entries.length; index += 1) {
      if (!admit(entries[index])) {
        return false;
      }
    }
    return true;
  } catch (_error) {
    return false;
  }
}

export function isSlockOhosWorkGenerationCurrent(
  workGeneration: number,
  currentGeneration: number
): boolean {
  return workGeneration === currentGeneration;
}

export function canSlockOhosDrain(inFlight: boolean): boolean {
  return !inFlight;
}

export function createSlockOhosDiagnosticSnapshotState(
  pendingAccepted: number
): SlockOhosDiagnosticSnapshotState {
  return {
    complete: pendingAccepted === 0,
    pendingAccepted,
  };
}

export function createSlockOhosTaskpoolFailureEntry(
  raw: SlockOhosRawAsyncDiagnosticEntry,
  timestampMillis: number
): SlockOhosPreparedDiagnosticEntry {
  const dropped = raw.dropped + 1;
  return {
    timestampMillis,
    seq: raw.seq,
    level: 'error',
    tag: 'SlockDiagnostics',
    event: 'async_taskpool_failed',
    message: `async_taskpool_failed dropped=${dropped}`,
    dropped,
    truncated: false,
    thread: 'taskpool',
    sourceSeq: raw.seq,
  };
}

export class SlockOhosDiagnosticsQueue {
  private readonly slots: Array<SlockOhosAsyncDiagnosticWork | null> = [];
  private head: number = 0;
  private tail: number = 0;
  private count: number = 0;
  private pendingDropped: number = 0;
  private readonly capacity: number;
  private readonly normalCapacity: number;

  constructor(capacity: number, normalCapacity: number) {
    this.capacity = capacity;
    this.normalCapacity = normalCapacity;
    for (let index = 0; index < capacity; index += 1) {
      this.slots.push(null);
    }
  }

  offer(work: SlockOhosAsyncDiagnosticWork): boolean {
    const normalQueueFull = !work.critical && this.count >= this.normalCapacity;
    if (normalQueueFull || this.count >= this.capacity) {
      this.pendingDropped += 1;
      return false;
    }
    if (work.raw !== null) {
      work.raw.dropped = this.pendingDropped;
    } else if (work.prepared !== null) {
      work.prepared.dropped = (work.prepared.dropped === undefined ? 0 : work.prepared.dropped) +
        this.pendingDropped;
    }
    this.pendingDropped = 0;
    this.slots[this.tail] = work;
    this.tail = (this.tail + 1) % this.capacity;
    this.count += 1;
    return true;
  }

  poll(): SlockOhosAsyncDiagnosticWork | null {
    if (this.count === 0) {
      return null;
    }
    const entry = this.slots[this.head];
    this.slots[this.head] = null;
    this.head = (this.head + 1) % this.capacity;
    this.count -= 1;
    return entry;
  }

  size(): number {
    return this.count;
  }

  droppedCount(): number {
    return this.pendingDropped;
  }

  consumeTrailingDropped(): number {
    if (this.count !== 0) {
      return 0;
    }
    const dropped = this.pendingDropped;
    this.pendingDropped = 0;
    return dropped;
  }

  clear(): void {
    for (let index = 0; index < this.capacity; index += 1) {
      this.slots[index] = null;
    }
    this.head = 0;
    this.tail = 0;
    this.count = 0;
    this.pendingDropped = 0;
  }
}
