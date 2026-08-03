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

export interface SlockOhosPrivateAttachmentOpenAliasBackend {
  stat(path: string): SlockOhosPrivateAttachmentStat | null;
  copy(sourcePath: string, targetPath: string): Promise<void>;
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

class FsSlockOhosPrivateAttachmentOpenAliasBackend implements SlockOhosPrivateAttachmentOpenAliasBackend {
  stat(path: string): SlockOhosPrivateAttachmentStat | null {
    try {
      const stat = fs.statSync(path);
      return { isFile: stat.isFile(), sizeBytes: stat.size, mtime: stat.mtime };
    } catch (_) {
      return null;
    }
  }

  async copy(sourcePath: string, targetPath: string): Promise<void> {
    await fs.copyFile(sourcePath, targetPath);
  }

  unlink(path: string): void {
    try {
      fs.unlinkSync(path);
    } catch (_) {
      // Missing or partially-created aliases are already clean.
    }
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

/**
 * The persistent owner/attachment identity deliberately has no presentation suffix. External
 * file consumers, however, use the URI suffix in addition to the Want MIME type. Give only the
 * disposable open alias a trusted MIME-derived suffix; never rename the authoritative copy.
 */
export function slockOhosPrivateAttachmentOpenAliasDirectoryName(
  ownerKey: string,
  attachmentId: string
): string {
  const persistentName = slockOhosPrivateAttachmentFileName(ownerKey, attachmentId, '', '');
  return `open-${persistentName}`;
}

export function slockOhosPrivateAttachmentOpenAliasFileName(
  requestedFileName: string,
  extension: string
): string {
  const withoutQuery = requestedFileName.includes('://')
    ? requestedFileName.split('?')[0].split('#')[0]
    : requestedFileName;
  const pathSegments = withoutQuery.replaceAll('\\', '/').split('/');
  const leaf = pathSegments[pathSegments.length - 1] ?? '';
  const requestedExtension = extension.trim().toLowerCase();
  const leafExtensionMatch = leaf.toLowerCase().match(/\.[a-z0-9]{1,10}$/);
  const normalizedExtension = /^\.[a-z0-9]{1,10}$/.test(requestedExtension)
    ? requestedExtension
    : leafExtensionMatch?.[0] ?? '.bin';
  let sanitized = '';
  for (let index = 0; index < leaf.length; index += 1) {
    const character = leaf.charAt(index);
    const code = leaf.charCodeAt(index);
    const reserved = character === ':' || character === '*' || character === '?' || character === '"' ||
      character === '<' || character === '>' || character === '|';
    sanitized += code <= 31 || code === 127 || reserved ? '_' : character;
  }
  sanitized = sanitized.trim().replace(/^\.+|\.+$/g, '');
  if (sanitized.length === 0 || sanitized === '.' || sanitized === '..') sanitized = 'attachment';
  if (!sanitized.toLowerCase().endsWith(normalizedExtension)) sanitized += normalizedExtension;
  const maximumBaseLength = 120 - normalizedExtension.length;
  const base = sanitized.slice(0, Math.max(1, maximumBaseLength));
  return base.toLowerCase().endsWith(normalizedExtension) ? base : `${base}${normalizedExtension}`;
}

/**
 * Materializes a disposable, suffix-bearing alias for external consumers. Reuse is allowed only
 * while it is byte-sized like the immutable source and no older than that source.
 */
export async function materializeSlockOhosPrivateAttachmentOpenAlias(
  sourcePath: string,
  targetPath: string,
  backend: SlockOhosPrivateAttachmentOpenAliasBackend = new FsSlockOhosPrivateAttachmentOpenAliasBackend()
): Promise<string> {
  if (!sourcePath.startsWith('/') || !targetPath.startsWith('/') || sourcePath === targetPath) {
    throw new Error('Attachment open alias path is unavailable');
  }
  const sourceStat = backend.stat(sourcePath);
  if (sourceStat === null || !sourceStat.isFile || sourceStat.sizeBytes <= 0) {
    throw new Error('Downloaded attachment is unavailable');
  }
  const existingStat = backend.stat(targetPath);
  if (
    existingStat !== null &&
    existingStat.isFile &&
    existingStat.sizeBytes === sourceStat.sizeBytes &&
    existingStat.mtime >= sourceStat.mtime
  ) {
    return targetPath;
  }
  if (existingStat !== null) backend.unlink(targetPath);
  try {
    await backend.copy(sourcePath, targetPath);
    const copiedStat = backend.stat(targetPath);
    if (copiedStat === null || !copiedStat.isFile || copiedStat.sizeBytes !== sourceStat.sizeBytes) {
      throw new Error('Attachment open alias copy is incomplete');
    }
    return targetPath;
  } catch (error) {
    backend.unlink(targetPath);
    throw new Error(error instanceof Error ? error.message : 'Attachment open alias copy failed');
  }
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
