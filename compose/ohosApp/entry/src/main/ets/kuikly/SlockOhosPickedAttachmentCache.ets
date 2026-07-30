import { fileUri } from '@kit.CoreFileKit';
import fs from '@ohos.file.fs';

export interface SlockOhosPickedAttachmentCacheBackend {
  copyFile(sourceFd: number, targetPath: string): void;
  fileSize(path: string): number;
  fileUri(path: string): string;
  removeFile(path: string): void;
}

class FsSlockOhosPickedAttachmentCacheBackend implements SlockOhosPickedAttachmentCacheBackend {
  copyFile(sourceFd: number, targetPath: string): void {
    fs.copyFileSync(sourceFd, targetPath);
  }

  fileSize(path: string): number {
    return fs.statSync(path).size;
  }

  fileUri(path: string): string {
    return fileUri.getUriFromPath(path);
  }

  removeFile(path: string): void {
    try {
      fs.unlinkSync(path);
    } catch (_) {
    }
  }
}

export interface SlockOhosCachedPickedAttachment {
  fileName: string;
  mimeType: string;
  previewUri: string;
  fileRef: string;
  sizeBytes: number;
}

/**
 * Freezes a picker-backed file while its temporary media grant is live.
 * Kuikly's OHOS image cache accepts addressable file:// URIs, while picker
 * file://media URIs cannot be decoded by OH_ImageSourceNative_CreateFromUri.
 */
export function cacheSlockOhosPickedAttachment(
  sourceFd: number,
  targetPath: string,
  fileName: string,
  mimeType: string,
  expectedSizeBytes: number,
  backend: SlockOhosPickedAttachmentCacheBackend = new FsSlockOhosPickedAttachmentCacheBackend()
): SlockOhosCachedPickedAttachment {
  let complete = false;
  try {
    if (expectedSizeBytes <= 0) {
      throw new Error('Picked attachment is empty');
    }
    backend.copyFile(sourceFd, targetPath);
    const cachedSizeBytes = backend.fileSize(targetPath);
    if (cachedSizeBytes !== expectedSizeBytes) {
      throw new Error('Picked attachment cache copy size mismatch');
    }
    const cachedUri = backend.fileUri(targetPath);
    if (!cachedUri.startsWith('file://')) {
      throw new Error('Picked attachment cache URI is unavailable');
    }
    const result: SlockOhosCachedPickedAttachment = {
      fileName: fileName,
      mimeType: mimeType,
      previewUri: cachedUri,
      fileRef: cachedUri,
      sizeBytes: cachedSizeBytes
    };
    complete = true;
    return result;
  } finally {
    if (!complete) {
      backend.removeFile(targetPath);
    }
  }
}
