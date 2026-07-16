import fs from '@ohos.file.fs';
import { invoke } from '@kuiklybase/knoi';
import { KuiklyRenderCallback } from '@kuikly-open/render';

export const SLOCK_OHOS_ATTACHMENT_DOWNLOAD_SINK_SERVICE = 'slockOhosAttachmentDownloadSink';

interface OhosDownloadFileSession {
  targetPath: string;
  stagingPath: string;
  file: fs.File;
}

interface OhosDownloadPendingSession {
  progressCallback?: KuiklyRenderCallback;
  resolve: (result: Record<string, Object>) => void;
}

/**
 * Filesystem-only OHOS host boundary for the common AttachmentDownloadCoordinator.
 * HTTP transport must remain in NetworkKMM; this service only stages, fsyncs and renames bytes.
 */
export class SlockOhosAttachmentDownloadSink {
  private readonly files: Map<string, OhosDownloadFileSession> = new Map();
  private readonly pending: Map<string, OhosDownloadPendingSession> = new Map();
  private nextHostRequestId: number = 0;

  async download(
    url: string,
    targetPath: string,
    downloadId: string,
    targetKey: string,
    progressCallback?: KuiklyRenderCallback
  ): Promise<Record<string, Object>> {
    this.nextHostRequestId += 1;
    const hostRequestKey = `${downloadId}:${this.nextHostRequestId}`;
    const resultPromise = new Promise<Record<string, Object>>((resolve) => {
      this.pending.set(hostRequestKey, { progressCallback: progressCallback, resolve: resolve });
    });
    const attemptToken = invoke<string>(
      'slockAttachmentDownloadStart',
      hostRequestKey,
      downloadId,
      targetKey,
      url,
      targetPath
    );
    if (attemptToken.length === 0 && this.pending.has(hostRequestKey)) {
      const session = this.pending.get(hostRequestKey);
      this.pending.delete(hostRequestKey);
      session?.resolve({ "status": "failed", "message": "Unable to start attachment download" });
    }
    return resultPromise;
  }

  control(downloadId: string, action: string): string {
    return invoke<string>('slockAttachmentDownloadControl', downloadId, action);
  }

  begin(targetPath: string, attemptToken: string): boolean {
    try {
      this.abort(attemptToken);
      const parentEnd = targetPath.lastIndexOf('/');
      if (parentEnd > 0) {
        fs.mkdirSync(targetPath.substring(0, parentEnd), true);
      }
      const stagingPath = `${targetPath}.part-${this.safeToken(attemptToken)}`;
      this.safeUnlink(stagingPath);
      const file = fs.openSync(stagingPath, fs.OpenMode.CREATE | fs.OpenMode.TRUNC | fs.OpenMode.WRITE_ONLY);
      this.files.set(attemptToken, { targetPath: targetPath, stagingPath: stagingPath, file: file });
      return true;
    } catch (_) {
      return false;
    }
  }

  write(attemptToken: string, bytes: Uint8Array): boolean {
    const session = this.files.get(attemptToken);
    if (session === undefined) {
      return false;
    }
    try {
      fs.writeSync(session.file.fd, bytes);
      return true;
    } catch (_) {
      return false;
    }
  }

  commit(attemptToken: string): boolean {
    const session = this.files.get(attemptToken);
    if (session === undefined) {
      return false;
    }
    try {
      fs.fsyncSync(session.file.fd);
      fs.closeSync(session.file);
      this.files.delete(attemptToken);
      // POSIX rename is the atomic publication boundary and replaces an existing target.
      fs.renameSync(session.stagingPath, session.targetPath);
      return fs.statSync(session.targetPath).isFile();
    } catch (_) {
      this.files.delete(attemptToken);
      this.safeUnlink(session.stagingPath);
      return false;
    }
  }

  abort(attemptToken: string): void {
    const session = this.files.get(attemptToken);
    if (session === undefined) {
      return;
    }
    this.files.delete(attemptToken);
    try {
      fs.closeSync(session.file);
    } catch (_) {
    }
    this.safeUnlink(session.stagingPath);
  }

  onProgress(payload: Record<string, Object>): void {
    const key = payload["hostRequestKey"] as string | undefined;
    if (key === undefined) {
      return;
    }
    this.pending.get(key)?.progressCallback?.(payload);
  }

  onComplete(payload: Record<string, Object>): void {
    const key = payload["hostRequestKey"] as string | undefined;
    if (key === undefined) {
      return;
    }
    const session = this.pending.get(key);
    this.pending.delete(key);
    session?.resolve(payload);
  }

  private safeUnlink(path: string): void {
    try {
      fs.unlinkSync(path);
    } catch (_) {
    }
  }

  private safeToken(value: string): string {
    let result = '';
    for (let index = 0; index < value.length && result.length < 80; index += 1) {
      const character = value.charAt(index);
      const accepted =
        (character >= 'a' && character <= 'z') ||
        (character >= 'A' && character <= 'Z') ||
        (character >= '0' && character <= '9') ||
        character === '-' || character === '_';
      if (accepted) result += character;
    }
    return result.length > 0 ? result : 'attempt';
  }
}

export const slockOhosAttachmentDownloadSink = new SlockOhosAttachmentDownloadSink();
