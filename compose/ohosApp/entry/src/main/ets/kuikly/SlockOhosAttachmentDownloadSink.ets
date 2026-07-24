import { invoke } from '@kuiklybase/knoi';
import { KuiklyRenderCallback } from '@kuikly-open/render';

export const SLOCK_OHOS_ATTACHMENT_DOWNLOAD_OBSERVER_SERVICE = 'slockOhosAttachmentDownloadObserver';

interface OhosDownloadPendingSession {
  progressCallback?: KuiklyRenderCallback;
  resolve: (result: Record<string, Object>) => void;
}

/**
 * Low-frequency KNOI control/observer bridge. Download chunks never cross ArkTS: NetworkKMM calls
 * the ohosArm64 POSIX atomic sink directly for write-full/fsync/close/rename.
 */
export class SlockOhosAttachmentDownloadObserver {
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
    try {
      const attemptToken = invoke<string>(
        'slockAttachmentDownloadStart',
        hostRequestKey,
        downloadId,
        targetKey,
        url,
        targetPath
      );
      if (attemptToken.length === 0) {
        this.finishStartFailure(hostRequestKey, 'Unable to start attachment download');
      }
    } catch (error) {
      this.finishStartFailure(hostRequestKey, `Unable to start attachment download: ${String(error)}`);
    }
    return resultPromise;
  }

  control(downloadId: string, expectedAttemptToken: string, action: string): string {
    try {
      return invoke<string>(
        'slockAttachmentDownloadControl',
        downloadId,
        expectedAttemptToken,
        action
      );
    } catch (_) {
      return 'failed';
    }
  }

  controlExisting(downloadId: string, expectedAttemptToken: string, action: string): string {
    try {
      return invoke<string>(
        'slockAttachmentDownloadControlExisting',
        downloadId,
        expectedAttemptToken,
        action
      );
    } catch (_) {
      return 'failed';
    }
  }

  onProgress(payload: Record<string, Object>): void {
    const key = payload["hostRequestKey"] as string | undefined;
    if (key === undefined) return;
    this.pending.get(key)?.progressCallback?.(payload);
  }

  onComplete(payload: Record<string, Object>): void {
    const key = payload["hostRequestKey"] as string | undefined;
    if (key === undefined) return;
    const session = this.pending.get(key);
    this.pending.delete(key);
    session?.resolve(payload);
  }

  private finishStartFailure(hostRequestKey: string, message: string): void {
    const session = this.pending.get(hostRequestKey);
    this.pending.delete(hostRequestKey);
    session?.resolve({ "status": "failed", "message": message });
  }
}

export const slockOhosAttachmentDownloadSink = new SlockOhosAttachmentDownloadObserver();
