export interface SlockOhosDiagnosticEntry {
  timestampMillis: number;
  tag: string;
  message: string;
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

  static record(tag: string, message: string, timestampMillis: number = Date.now()): void {
    const safeTag = SlockOhosDiagnostics.sanitize(tag).slice(0, 48);
    const safeMessage = SlockOhosDiagnostics.sanitize(message).slice(0, 280);
    SlockOhosDiagnostics.entries.push({
      timestampMillis,
      tag: safeTag,
      message: safeMessage,
    });
    SlockOhosDiagnostics.persistentRecorder?.(safeTag, safeMessage);
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
    return SlockOhosDiagnostics.recent(limit)
      .map((entry: SlockOhosDiagnosticEntry) => `${entry.timestampMillis} ${entry.tag}: ${entry.message}`)
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
}
