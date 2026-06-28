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
}

export class SlockOhosDiagnostics {
  private static readonly MAX_ENTRIES: number = 5000;
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
  private static persistentRecorder: ((tag: string, message: string) => void) | null = null;

  static setPersistentRecorder(recorder: ((tag: string, message: string) => void) | null): void {
    SlockOhosDiagnostics.persistentRecorder = recorder;
  }

  static record(tag: string, message: string, timestampMillis: number = Date.now(), level: string = 'info'): void {
    const safeTag = SlockOhosDiagnostics.sanitize(tag).slice(0, 48);
    const normalizedLevel = SlockOhosDiagnostics.normalizeLevel(level);
    const sanitizedMessage = SlockOhosDiagnostics.sanitize(message);
    const safeMessage = sanitizedMessage.slice(0, 2048);
    SlockOhosDiagnostics.entries.push({
      timestampMillis,
      level: normalizedLevel,
      tag: safeTag,
      event: SlockOhosDiagnostics.eventName(safeMessage),
      message: safeMessage,
      thread: 'arkts',
      seq: timestampMillis,
      dropped: 0,
      truncated: sanitizedMessage.length > safeMessage.length,
    });
    SlockOhosDiagnostics.persistentRecorder?.(safeTag, safeMessage.slice(0, 280));
    if (SlockOhosDiagnostics.entries.length > SlockOhosDiagnostics.MAX_ENTRIES) {
      SlockOhosDiagnostics.entries.splice(
        0,
        SlockOhosDiagnostics.entries.length - SlockOhosDiagnostics.MAX_ENTRIES,
      );
    }
  }

  static recent(limit: number = 500): SlockOhosDiagnosticEntry[] {
    return SlockOhosDiagnostics.entries.slice(Math.max(0, SlockOhosDiagnostics.entries.length - limit));
  }

  static exportText(limit: number = 500): string {
    return [
      'App-owned recent diagnostics',
      'Files: 0',
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
      }))
      .join('\n');
  }

  static clear(): void {
    SlockOhosDiagnostics.entries = [];
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

  private static eventName(message: string): string {
    const firstSpace = message.indexOf(' ');
    const event = firstSpace >= 0 ? message.slice(0, firstSpace) : message;
    return event.slice(0, 96);
  }
}
