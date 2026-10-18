import fs from '@ohos.file.fs';
import { ohosDiagnosticsStorageDirectories } from '../entryability/SlockOhosAppDiagnostics';
import {
  SLOCK_OHOS_ATTACHMENT_CACHE_DIR_NAME,
  SLOCK_OHOS_PICKED_THREAD_ATTACHMENT_CACHE_DIR_NAME,
} from './SlockOhosPickedAttachmentCache';
import { slockOhosPrivateAttachmentDirectory } from './SlockOhosPrivateAttachmentStore';

/**
 * Settings -> Storage measurement for the OHOS host (parity with Android
 * StorageManagementRepository). Every directory name comes from the code that writes it.
 */

/** Must equal SlockSqlDatabase.DatabaseName (compose/shared commonMain SlockSqlDatabase.kt). */
export const SLOCK_OHOS_DATABASE_FILE_NAME: string = 'slock_cache_v4.db';
const SLOCK_OHOS_DATABASE_FILE_SUFFIXES: Array<string> = ['', '-wal', '-shm', '-journal'];
/**
 * The shared Kotlin driver guesses its base path (RelationalStoreFactory.ohos.kt
 * ohosDatabaseBasePath): the entry module files dir first, then a legacy "hap" spelling.
 * The entry UIAbilityContext.filesDir is normally the first of these; all are checked and the
 * first directory that actually holds the database is measured.
 */
const SLOCK_OHOS_DATABASE_FALLBACK_DIRECTORIES: Array<string> = [
  '/data/storage/el2/base/haps/entry/files',
  '/data/storage/el2/base/hap/entry/files',
];
const SLOCK_OHOS_PRIVATE_ATTACHMENT_PART_MARKER: string = '.part-';
const SLOCK_OHOS_STORAGE_SIZE_UNITS: Array<string> = ['B', 'KB', 'MB', 'GB'];

export interface SlockOhosStorageSnapshot {
  localDatabaseBytes: number;
  imageCacheBytes: number;
  attachmentDownloadBytes: number;
  temporaryAndLogBytes: number;
}

export interface SlockOhosStorageRoots {
  databaseDirectories: Array<string>;
  /** Disposable download/open cache; cleared by the attachment staging row. */
  attachmentCacheDirectory: string;
  /**
   * Private committed downloads (the "downloaded" state shown in the UI). Measured, but only
   * orphan partial files are cleared; completed copies are removed via their own delete action.
   */
  privateAttachmentDirectory: string;
  /** Composer image compression outputs; cleared by the temporary row. */
  pickedThreadAttachmentDirectory: string;
  /** Counted in the temporary row; cleared through SlockOhosAppDiagnostics.clearLocalFiles. */
  diagnosticsDirectories: Array<string>;
}

export function slockOhosStorageRoots(rawFilesDir: string, rawCacheDir: string): SlockOhosStorageRoots {
  const filesDir = rawFilesDir.trim().replace(/\/+$/, '');
  const cacheDir = rawCacheDir.trim().replace(/\/+$/, '');
  const databaseDirectories: Array<string> = [];
  [filesDir].concat(SLOCK_OHOS_DATABASE_FALLBACK_DIRECTORIES)
    .forEach((directory: string): void => {
      if (directory.length > 0 && databaseDirectories.indexOf(directory) < 0) {
        databaseDirectories.push(directory);
      }
    });
  let privateAttachmentDirectory = '';
  try {
    privateAttachmentDirectory = slockOhosPrivateAttachmentDirectory(filesDir);
  } catch (_) {
    privateAttachmentDirectory = '';
  }
  return {
    databaseDirectories: databaseDirectories,
    attachmentCacheDirectory: `${cacheDir}/${SLOCK_OHOS_ATTACHMENT_CACHE_DIR_NAME}`,
    privateAttachmentDirectory: privateAttachmentDirectory,
    pickedThreadAttachmentDirectory: `${cacheDir}/${SLOCK_OHOS_PICKED_THREAD_ATTACHMENT_CACHE_DIR_NAME}`,
    diagnosticsDirectories: ohosDiagnosticsStorageDirectories(filesDir),
  };
}

export async function measureSlockOhosStorage(roots: SlockOhosStorageRoots): Promise<SlockOhosStorageSnapshot> {
  const localDatabaseBytes = await slockOhosDatabaseBytes(roots.databaseDirectories);
  const attachmentCacheBytes = await slockOhosTreeBytes(roots.attachmentCacheDirectory);
  const privateAttachmentBytes = roots.privateAttachmentDirectory.length > 0
    ? await slockOhosTreeBytes(roots.privateAttachmentDirectory)
    : 0;
  let temporaryAndLogBytes = await slockOhosTreeBytes(roots.pickedThreadAttachmentDirectory);
  for (let index = 0; index < roots.diagnosticsDirectories.length; index += 1) {
    temporaryAndLogBytes += await slockOhosTreeBytes(roots.diagnosticsDirectories[index]);
  }
  return {
    localDatabaseBytes: localDatabaseBytes,
    // The OHOS image adapter keeps decoded PixelMaps in memory only (SlockOhosImageAdapter.cpp);
    // there is no image disk cache to measure or clear.
    imageCacheBytes: 0,
    attachmentDownloadBytes: attachmentCacheBytes + privateAttachmentBytes,
    temporaryAndLogBytes: temporaryAndLogBytes,
  };
}

export function slockOhosStorageTotalBytes(snapshot: SlockOhosStorageSnapshot): number {
  return snapshot.localDatabaseBytes + snapshot.imageCacheBytes +
    snapshot.attachmentDownloadBytes + snapshot.temporaryAndLogBytes;
}

/** Returns false when any entry could not be removed. Completed private copies are kept. */
export async function clearSlockOhosAttachmentStaging(roots: SlockOhosStorageRoots): Promise<boolean> {
  const cacheCleared = await clearSlockOhosDirectoryContents(
    roots.attachmentCacheDirectory,
    (_name: string): boolean => true
  );
  const partsCleared = roots.privateAttachmentDirectory.length === 0 ||
    await clearSlockOhosDirectoryContents(
      roots.privateAttachmentDirectory,
      (name: string): boolean => {
        const marker = name.lastIndexOf(SLOCK_OHOS_PRIVATE_ATTACHMENT_PART_MARKER);
        return marker > 0 && marker + SLOCK_OHOS_PRIVATE_ATTACHMENT_PART_MARKER.length < name.length;
      }
    );
  return cacheCleared && partsCleared;
}

/** Diagnostics logs are cleared separately through SlockOhosAppDiagnostics.clearLocalFiles. */
export async function clearSlockOhosTemporaryFiles(roots: SlockOhosStorageRoots): Promise<boolean> {
  return clearSlockOhosDirectoryContents(roots.pickedThreadAttachmentDirectory, (_name: string): boolean => true);
}

/** Mirrors Android formatStorageSizeLabel (SlockKuiklyActivity.kt) for ASCII-decimal locales. */
export function formatSlockOhosStorageSizeLabel(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  let value = bytes;
  let index = 0;
  while (value >= 1024 && index < SLOCK_OHOS_STORAGE_SIZE_UNITS.length - 1) {
    value /= 1024;
    index += 1;
  }
  const number = index === 0 ? `${Math.floor(value)}` : value.toFixed(1);
  return `${number} ${SLOCK_OHOS_STORAGE_SIZE_UNITS[index]}`;
}

async function slockOhosDatabaseBytes(directories: Array<string>): Promise<number> {
  for (let index = 0; index < directories.length; index += 1) {
    const basePath = `${directories[index]}/${SLOCK_OHOS_DATABASE_FILE_NAME}`;
    const mainBytes = await slockOhosRegularFileBytes(basePath);
    if (mainBytes === null) continue;
    let total = 0;
    for (let suffixIndex = 0; suffixIndex < SLOCK_OHOS_DATABASE_FILE_SUFFIXES.length; suffixIndex += 1) {
      const bytes = await slockOhosRegularFileBytes(`${basePath}${SLOCK_OHOS_DATABASE_FILE_SUFFIXES[suffixIndex]}`);
      total += bytes ?? 0;
    }
    return total;
  }
  return 0;
}

async function slockOhosRegularFileBytes(path: string): Promise<number | null> {
  try {
    const stat = await fs.lstat(path);
    return stat.isFile() ? stat.size : null;
  } catch (_) {
    return null;
  }
}

/** Symbolic links are never followed or counted. Missing paths count as 0. */
async function slockOhosTreeBytes(path: string): Promise<number> {
  let stat: fs.Stat | null = null;
  try {
    stat = await fs.lstat(path);
  } catch (_) {
    return 0;
  }
  if (stat === null || stat.isSymbolicLink()) return 0;
  if (stat.isFile()) return stat.size;
  if (!stat.isDirectory()) return 0;
  let names: Array<string> = [];
  try {
    names = await fs.listFile(path);
  } catch (_) {
    return 0;
  }
  let total = 0;
  for (let index = 0; index < names.length; index += 1) {
    total += await slockOhosTreeBytes(`${path}/${names[index]}`);
  }
  return total;
}

/** Removes matching children of directory; the directory itself is kept. */
async function clearSlockOhosDirectoryContents(
  directory: string,
  shouldRemove: (name: string) => boolean
): Promise<boolean> {
  let rootStat: fs.Stat | null = null;
  try {
    rootStat = await fs.lstat(directory);
  } catch (_) {
    return true;
  }
  if (rootStat === null || rootStat.isSymbolicLink() || !rootStat.isDirectory()) return false;
  let names: Array<string> = [];
  try {
    names = await fs.listFile(directory);
  } catch (_) {
    return false;
  }
  let cleared = true;
  for (let index = 0; index < names.length; index += 1) {
    const name = names[index];
    if (!shouldRemove(name)) continue;
    const child = `${directory}/${name}`;
    try {
      const childStat = await fs.lstat(child);
      if (childStat.isDirectory() && !childStat.isSymbolicLink()) {
        await fs.rmdir(child);
      } else {
        await fs.unlink(child);
      }
    } catch (_) {
      cleared = false;
    }
  }
  return cleared;
}
