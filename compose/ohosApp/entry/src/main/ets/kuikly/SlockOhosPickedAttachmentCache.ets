import { fileUri } from '@kit.CoreFileKit';
import fs from '@ohos.file.fs';

export interface SlockOhosAttachmentCacheDirectoryBackend {
  createDirectory(path: string): void;
  isDirectory(path: string): boolean;
}

class FsSlockOhosAttachmentCacheDirectoryBackend implements SlockOhosAttachmentCacheDirectoryBackend {
  createDirectory(path: string): void {
    fs.mkdirSync(path, true);
  }

  isDirectory(path: string): boolean {
    try {
      return fs.statSync(path).isDirectory();
    } catch (_) {
      return false;
    }
  }
}

/**
 * HarmonyOS may report 13900015 (EEXIST) for recursive mkdir when the cache
 * directory already exists. Treat an existing directory as success, while
 * preserving the original error when the path is absent or is not a directory.
 */
export function ensureSlockOhosAttachmentCacheDirectory(
  path: string,
  backend: SlockOhosAttachmentCacheDirectoryBackend = new FsSlockOhosAttachmentCacheDirectoryBackend()
): void {
  if (backend.isDirectory(path)) {
    return;
  }
  try {
    backend.createDirectory(path);
  } catch (error) {
    const createError = error as Error;
    if (!backend.isDirectory(path)) {
      throw createError;
    }
  }
}

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
 * Kuikly's OHOS image cache passes local image refs directly to
 * OH_ImageSourceNative_CreateFromUri. That decoder accepts a file URI backed
 * by an absolute sandbox path (`file:///data/...`), not the ability-scoped
 * authority URI (`file://<bundle>/data/...`) produced by getUriFromPath.
 * Keep the latter as the platform file identity and expose the former only to
 * the renderer. Multipart consumers must resolve that identity back to its
 * absolute sandbox path before assigning http.MultiFormData.filePath.
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
    if (!targetPath.startsWith('/')) {
      throw new Error('Picked attachment cache path is unavailable');
    }
    backend.copyFile(sourceFd, targetPath);
    const cachedSizeBytes = backend.fileSize(targetPath);
    if (cachedSizeBytes !== expectedSizeBytes) {
      throw new Error('Picked attachment cache copy size mismatch');
    }
    const cachedFileRef = backend.fileUri(targetPath);
    if (!cachedFileRef.startsWith('file://')) {
      throw new Error('Picked attachment cache URI is unavailable');
    }
    const cachedPreviewUri = `file://${targetPath}`;
    const result: SlockOhosCachedPickedAttachment = {
      fileName: fileName,
      mimeType: mimeType,
      previewUri: cachedPreviewUri,
      fileRef: cachedFileRef,
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
