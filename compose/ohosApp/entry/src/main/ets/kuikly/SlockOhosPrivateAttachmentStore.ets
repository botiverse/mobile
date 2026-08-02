import fs from '@ohos.file.fs';
import { ensureSlockOhosAttachmentCacheDirectory } from './SlockOhosPickedAttachmentCache';

const SLOCK_PRIVATE_ATTACHMENT_DIRECTORY = 'slock-attachment-downloads';
const SLOCK_PRIVATE_ATTACHMENT_COPY_CHUNK_BYTES = 64 * 1024;

export interface SlockOhosPrivateAttachmentStat {
  isFile: boolean;
  sizeBytes: number;
  mtime: number;
}

export interface SlockOhosPrivateAttachmentCopyBackend {
  stat(path: string): SlockOhosPrivateAttachmentStat | null;
  openSource(path: string): Object;
  openTarget(uri: string): Object;
  read(source: Object, buffer: ArrayBuffer): number;
  write(target: Object, buffer: ArrayBuffer, length: number): number;
  close(handle: Object): void;
}

export interface SlockOhosPrivateAttachmentCleanupBackend {
  list(directory: string): Array<string>;
  unlink(path: string): void;
}

class FsSlockOhosPrivateAttachmentCopyBackend implements SlockOhosPrivateAttachmentCopyBackend {
  stat(path: string): SlockOhosPrivateAttachmentStat | null {
    try {
      const stat = fs.statSync(path);
      return { isFile: stat.isFile(), sizeBytes: stat.size, mtime: stat.mtime };
    } catch (_) {
      return null;
    }
  }

  openSource(path: string): Object {
    return fs.openSync(path, fs.OpenMode.READ_ONLY);
  }

  openTarget(uri: string): Object {
    return fs.openSync(uri, fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
  }

  read(source: Object, buffer: ArrayBuffer): number {
    return fs.readSync((source as fs.File).fd, buffer, { length: buffer.byteLength });
  }

  write(target: Object, buffer: ArrayBuffer, length: number): number {
    return fs.writeSync((target as fs.File).fd, buffer, { length: length });
  }

  close(handle: Object): void {
    fs.closeSync((handle as fs.File).fd);
  }
}

class FsSlockOhosPrivateAttachmentCleanupBackend implements SlockOhosPrivateAttachmentCleanupBackend {
  list(directory: string): Array<string> {
    return fs.listFileSync(directory);
  }

  unlink(path: string): void {
    fs.unlinkSync(path);
  }
}

export function isSlockOhosPrivateAttachmentOwnerKey(value: string): boolean {
  if (value.length !== 64) return false;
  for (let index = 0; index < value.length; index += 1) {
    const character = value.charAt(index);
    if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f'))) return false;
  }
  return true;
}

function safePathSegment(value: string, fallback: string): string {
  let result = '';
  for (let index = 0; index < value.length; index += 1) {
    const character = value.charAt(index);
    result +=
      (character >= 'a' && character <= 'z') ||
      (character >= 'A' && character <= 'Z') ||
      (character >= '0' && character <= '9') ||
      character === '.' || character === '-' || character === '_'
        ? character
        : '_';
  }
  return result.replace(/^\.+|\.+$/g, '').slice(0, 120) || fallback;
}

export function slockOhosPrivateAttachmentFileName(
  ownerKey: string,
  attachmentId: string,
  _requestedFileName: string,
  _extension: string = ''
): string {
  if (!isSlockOhosPrivateAttachmentOwnerKey(ownerKey)) throw new Error('Invalid attachment owner');
  const safeAttachmentId = safePathSegment(attachmentId, 'attachment').slice(0, 36);
  // The server-provided display name and MIME type are presentation metadata, not
  // persistent-file authority. The same attachment must resolve to the same private
  // copy after a rename or metadata correction.
  return `${ownerKey}-${safeAttachmentId}`;
}

export function cleanupSlockOhosPrivateAttachmentOrphanParts(
  finalPath: string,
  backend: SlockOhosPrivateAttachmentCleanupBackend = new FsSlockOhosPrivateAttachmentCleanupBackend()
): number {
  const separator = finalPath.lastIndexOf('/');
  if (separator <= 0 || separator === finalPath.length - 1) throw new Error('Invalid attachment path');
  const directory = finalPath.substring(0, separator);
  const finalName = finalPath.substring(separator + 1);
  const orphanPrefix = `${finalName}.part-`;
  let removed = 0;
  backend.list(directory).forEach((name: string) => {
    if (name.startsWith(orphanPrefix) && name.length > orphanPrefix.length) {
      backend.unlink(`${directory}/${name}`);
      removed += 1;
    }
  });
  return removed;
}

export function slockOhosPrivateAttachmentDirectory(filesDir: string): string {
  const root = filesDir.trim().replace(/\/+$/, '');
  if (!root.startsWith('/')) throw new Error('Private files directory is unavailable');
  return `${root}/${SLOCK_PRIVATE_ATTACHMENT_DIRECTORY}`;
}

export function ensureSlockOhosPrivateAttachmentDirectory(filesDir: string): string {
  const directory = slockOhosPrivateAttachmentDirectory(filesDir);
  ensureSlockOhosAttachmentCacheDirectory(directory);
  return directory;
}

export function copySlockOhosPrivateAttachmentToProviderUri(
  sourcePath: string,
  targetUri: string,
  backend: SlockOhosPrivateAttachmentCopyBackend = new FsSlockOhosPrivateAttachmentCopyBackend()
): number {
  const sourceStat = backend.stat(sourcePath);
  if (sourceStat === null || !sourceStat.isFile || sourceStat.sizeBytes <= 0) {
    throw new Error('Downloaded attachment is unavailable');
  }
  if (targetUri.trim().length === 0) throw new Error('Missing export target');
  const source = backend.openSource(sourcePath);
  let target: Object | null = null;
  let copied = 0;
  let primaryFailure: Error | null = null;
  try {
    target = backend.openTarget(targetUri);
    const buffer = new ArrayBuffer(SLOCK_PRIVATE_ATTACHMENT_COPY_CHUNK_BYTES);
    while (true) {
      const readBytes = backend.read(source, buffer);
      if (readBytes <= 0) break;
      let writeOffset = 0;
      while (writeOffset < readBytes) {
        const remainingBuffer = writeOffset === 0 ? buffer : buffer.slice(writeOffset, readBytes);
        const writtenBytes = backend.write(target, remainingBuffer, readBytes - writeOffset);
        if (writtenBytes <= 0) throw new Error('Attachment export write was truncated');
        writeOffset += writtenBytes;
        copied += writtenBytes;
      }
    }
  } catch (error) {
    primaryFailure = error as Error;
  }
  if (target !== null) {
    try {
      backend.close(target);
    } catch (error) {
      if (primaryFailure === null) primaryFailure = error as Error;
    }
  }
  try {
    backend.close(source);
  } catch (error) {
    if (primaryFailure === null) primaryFailure = error as Error;
  }
  if (primaryFailure !== null) throw primaryFailure;
  if (copied !== sourceStat.sizeBytes) throw new Error('Attachment export size mismatch');
  return copied;
}
