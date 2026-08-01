import environment from '@ohos.file.environment';
import fs from '@ohos.file.fs';
import util from '@ohos.util';
import { BusinessError } from '@kit.BasicServicesKit';
import { ensureSlockOhosAttachmentCacheDirectory } from './SlockOhosPickedAttachmentCache';

const SLOCK_PUBLIC_DOWNLOAD_SUBDIRECTORY = 'Raft';
const SLOCK_PUBLIC_DOWNLOAD_MAX_NAME_LENGTH = 120;
const SLOCK_PUBLIC_DOWNLOAD_MAX_COLLISION_SUFFIX = 9999;

export interface SlockOhosPublicDownloadStat {
  isFile: boolean;
  sizeBytes: number;
}

export interface SlockOhosPublicDownloadBackend {
  userDownloadDirectory(): string;
  ensureDirectory(path: string): void;
  stat(path: string): SlockOhosPublicDownloadStat | null;
  temporaryPath(directory: string): string;
  copyFile(sourcePath: string, targetPath: string): void;
  publishWithoutOverwrite(stagedPath: string, targetPath: string): boolean;
  removeFile(path: string): void;
}

export interface SlockOhosPublicDownloadResult {
  path: string;
  fileName: string;
  sizeBytes: number;
}

class FsSlockOhosPublicDownloadBackend implements SlockOhosPublicDownloadBackend {
  userDownloadDirectory(): string {
    return environment.getUserDownloadDir();
  }

  ensureDirectory(path: string): void {
    ensureSlockOhosAttachmentCacheDirectory(path);
  }

  stat(path: string): SlockOhosPublicDownloadStat | null {
    try {
      const stat = fs.statSync(path);
      return { isFile: stat.isFile(), sizeBytes: stat.size };
    } catch (_) {
      return null;
    }
  }

  copyFile(sourcePath: string, targetPath: string): void {
    fs.copyFileSync(sourcePath, targetPath);
  }

  temporaryPath(directory: string): string {
    return `${directory}/.raft-pending-${util.generateRandomUUID()}`;
  }

  publishWithoutOverwrite(stagedPath: string, targetPath: string): boolean {
    try {
      fs.moveFileSync(stagedPath, targetPath, 1);
      return true;
    } catch (error) {
      const businessError = error as BusinessError;
      if (businessError.code === 13900015) {
        return false;
      }
      throw new Error(`Public Downloads publish failed: ${businessError.code ?? 'unknown'} ${businessError.message ?? ''}`);
    }
  }

  removeFile(path: string): void {
    try {
      fs.unlinkSync(path);
    } catch (_) {
    }
  }
}

export function slockOhosPublicDownloadFileName(requestedFileName: string, fallbackExtension: string): string {
  const requestedBaseName = requestedFileName.split('/').pop()?.split('\\').pop() ?? 'attachment';
  let sanitized = '';
  for (let index = 0; index < requestedBaseName.length; index += 1) {
    const character = requestedBaseName.charAt(index);
    if (
      (character >= 'a' && character <= 'z') ||
      (character >= 'A' && character <= 'Z') ||
      (character >= '0' && character <= '9') ||
      character === '.' ||
      character === '-' ||
      character === '_' ||
      character === ' '
    ) {
      sanitized += character;
    } else {
      sanitized += '_';
    }
  }
  let baseName = sanitized.trim().replace(/^\.+|\.+$/g, '').slice(0, SLOCK_PUBLIC_DOWNLOAD_MAX_NAME_LENGTH);
  if (baseName.length === 0) {
    baseName = 'attachment';
  }
  if (baseName.includes('.') && !baseName.endsWith('.')) {
    return baseName;
  }
  const extension = fallbackExtension.trim().replace(/^\.+/, '').replace(/[^A-Za-z0-9]/g, '').slice(0, 12);
  return extension.length > 0 ? `${baseName}.${extension}` : baseName;
}

function collisionFileName(fileName: string, suffix: number): string {
  if (suffix === 0) return fileName;
  const dotIndex = fileName.lastIndexOf('.');
  const hasExtension = dotIndex > 0 && dotIndex < fileName.length - 1;
  const stem = hasExtension ? fileName.substring(0, dotIndex) : fileName;
  const extension = hasExtension ? fileName.substring(dotIndex) : '';
  const suffixText = ` (${suffix})`;
  const maximumStemLength = Math.max(1, SLOCK_PUBLIC_DOWNLOAD_MAX_NAME_LENGTH - suffixText.length - extension.length);
  return `${stem.slice(0, maximumStemLength)}${suffixText}${extension}`;
}

/**
 * Publishes one non-destructive copy into the app's public Downloads/Raft folder.
 *
 * This path deliberately does not use DocumentViewPicker: API 12+ grants normal
 * applications direct access to the directory returned by getUserDownloadDir().
 */
export function exportSlockOhosAttachmentToPublicDownloads(
  sourcePath: string,
  requestedFileName: string,
  fallbackExtension: string,
  backend: SlockOhosPublicDownloadBackend = new FsSlockOhosPublicDownloadBackend()
): SlockOhosPublicDownloadResult {
  const source = backend.stat(sourcePath);
  if (source === null || !source.isFile || source.sizeBytes <= 0) {
    throw new Error('Attachment source is unavailable');
  }
  const downloadDirectory = backend.userDownloadDirectory().trim().replace(/\/+$/, '');
  if (!downloadDirectory.startsWith('/')) {
    throw new Error('Public Downloads directory is unavailable');
  }
  const targetDirectory = `${downloadDirectory}/${SLOCK_PUBLIC_DOWNLOAD_SUBDIRECTORY}`;
  backend.ensureDirectory(targetDirectory);
  const preferredFileName = slockOhosPublicDownloadFileName(requestedFileName, fallbackExtension);
  const stagedPath = backend.temporaryPath(targetDirectory);
  let publishedPath = '';
  try {
    backend.copyFile(sourcePath, stagedPath);
    const staged = backend.stat(stagedPath);
    if (staged === null || !staged.isFile || staged.sizeBytes !== source.sizeBytes) {
      throw new Error('Public Downloads staged copy verification failed');
    }
    let targetFileName = '';
    for (let suffix = 0; suffix <= SLOCK_PUBLIC_DOWNLOAD_MAX_COLLISION_SUFFIX; suffix += 1) {
      const candidateName = collisionFileName(preferredFileName, suffix);
      const candidatePath = `${targetDirectory}/${candidateName}`;
      if (backend.publishWithoutOverwrite(stagedPath, candidatePath)) {
        targetFileName = candidateName;
        publishedPath = candidatePath;
        break;
      }
    }
    if (publishedPath.length === 0) {
      throw new Error('Unable to allocate a public Downloads file name');
    }
    const published = backend.stat(publishedPath);
    if (published === null || !published.isFile || published.sizeBytes !== source.sizeBytes) {
      throw new Error('Public Downloads copy verification failed');
    }
    return { path: publishedPath, fileName: targetFileName, sizeBytes: source.sizeBytes };
  } catch (error) {
    backend.removeFile(publishedPath.length > 0 ? publishedPath : stagedPath);
    throw error as Error;
  }
}
