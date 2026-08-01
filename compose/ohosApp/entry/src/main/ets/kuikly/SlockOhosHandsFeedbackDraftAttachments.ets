import { fileUri } from '@kit.CoreFileKit';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import fs from '@ohos.file.fs';
import util from '@ohos.util';

export interface SlockOhosFeedbackDraftAttachmentSource {
  id: string;
  fileName: string;
  mimeType: string;
  fileRef: string;
}

export interface SlockOhosFeedbackDraftAttachment {
  id: string;
  fileName: string;
  mimeType: string;
  sizeBytes: number;
  fileRef: string;
  previewUri: string;
  source: string;
}

export interface SlockOhosFeedbackDraftAttachmentBackend {
  ensureDirectory(path: string): void;
  resolveFileRef(ref: string): string;
  isDirectory(path: string): boolean;
  isFile(path: string): boolean;
  fileSize(path: string): number;
  copyFile(sourcePath: string, targetPath: string): void;
  rename(sourcePath: string, targetPath: string): void;
  removeRecursively(path: string): void;
  removeFile(path: string): void;
  list(path: string): Array<string>;
  fileUri(path: string): string;
}

class FsSlockOhosFeedbackDraftAttachmentBackend implements SlockOhosFeedbackDraftAttachmentBackend {
  ensureDirectory(path: string): void {
    try {
      fs.mkdirSync(path, true);
    } catch (_) {
      if (!this.isDirectory(path)) {
        throw new Error('Feedback draft storage is unavailable');
      }
    }
  }

  resolveFileRef(ref: string): string {
    if (ref.startsWith('/')) {
      return ref;
    }
    if (ref.startsWith('file://')) {
      return new fileUri.FileUri(ref).path;
    }
    return '';
  }

  isDirectory(path: string): boolean {
    try {
      return fs.statSync(path).isDirectory();
    } catch (_) {
      return false;
    }
  }

  isFile(path: string): boolean {
    try {
      return fs.statSync(path).isFile();
    } catch (_) {
      return false;
    }
  }

  fileSize(path: string): number {
    return fs.statSync(path).size;
  }

  copyFile(sourcePath: string, targetPath: string): void {
    fs.copyFileSync(sourcePath, targetPath);
  }

  rename(sourcePath: string, targetPath: string): void {
    fs.renameSync(sourcePath, targetPath);
  }

  removeRecursively(path: string): void {
    if (this.isFile(path)) {
      this.removeFile(path);
      return;
    }
    if (!this.isDirectory(path)) {
      return;
    }
    this.list(path).forEach((name: string): void => this.removeRecursively(`${path}/${name}`));
    try {
      fs.rmdirSync(path);
    } catch (_) {
    }
  }

  removeFile(path: string): void {
    try {
      fs.unlinkSync(path);
    } catch (_) {
    }
  }

  list(path: string): Array<string> {
    try {
      return fs.listFileSync(path);
    } catch (_) {
      return [];
    }
  }

  fileUri(path: string): string {
    return fileUri.getUriFromPath(path);
  }
}

export function promoteSlockOhosFeedbackDraftAttachments(
  rootPath: string,
  accountId: string,
  groupId: string,
  sources: Array<SlockOhosFeedbackDraftAttachmentSource>,
  maxBytes: number,
  backend: SlockOhosFeedbackDraftAttachmentBackend = new FsSlockOhosFeedbackDraftAttachmentBackend(),
  accountSegment: (value: string) => string = stableOhosFeedbackDraftPathSegment
): Array<SlockOhosFeedbackDraftAttachment> {
  if (!rootPath.startsWith('/') || groupId.trim().length === 0 || sources.length === 0 || sources.length > 9 || maxBytes <= 0) {
    throw new Error('Invalid feedback draft attachment promotion request');
  }
  const accountPath = `${rootPath}/${accountSegment(accountId.length > 0 ? accountId : 'anonymous')}`;
  backend.ensureDirectory(rootPath);
  backend.ensureDirectory(accountPath);
  const safeGroupId = safeOhosFeedbackDraftPathSegment(groupId).slice(0, 96) || 'group';
  const finalPath = `${accountPath}/${safeGroupId}`;
  const existing = reopenSlockOhosFeedbackDraftAttachmentGroup(finalPath, sources, maxBytes, backend);
  if (existing != null) {
    return existing;
  }
  const stagingPath = `${accountPath}/.${safeGroupId}-${Date.now()}-${Math.floor(Math.random() * 1000000)}`;
  backend.ensureDirectory(stagingPath);
  let committed = false;
  try {
    const staged: Array<SlockOhosFeedbackDraftAttachment> = [];
    sources.forEach((source: SlockOhosFeedbackDraftAttachmentSource, index: number): void => {
      const sourcePath = backend.resolveFileRef(source.fileRef);
      if (!sourcePath.startsWith('/') || !backend.isFile(sourcePath)) {
        throw new Error('Feedback attachment source is unavailable');
      }
      const sourceSize = backend.fileSize(sourcePath);
      if (sourceSize <= 0 || sourceSize > maxBytes) {
        throw new Error('settings_feedback_attachment_too_large');
      }
      const targetName = ohosFeedbackDraftTargetName(index, source.fileName, source.mimeType);
      const targetPath = `${stagingPath}/${targetName}`;
      backend.copyFile(sourcePath, targetPath);
      const copiedSize = backend.fileSize(targetPath);
      if (!backend.isFile(targetPath) || copiedSize !== sourceSize || copiedSize <= 0 || copiedSize > maxBytes) {
        throw new Error('Feedback attachment promotion copy mismatch');
      }
      staged.push({
        id: source.id,
        fileName: source.fileName.length > 0 ? source.fileName : targetName,
        mimeType: source.mimeType,
        sizeBytes: copiedSize,
        fileRef: backend.fileUri(targetPath),
        previewUri: `file://${targetPath}`,
        source: 'settings_feedback_draft'
      });
    });
    backend.removeRecursively(finalPath);
    backend.rename(stagingPath, finalPath);
    committed = true;
    return staged.map((attachment: SlockOhosFeedbackDraftAttachment, index: number): SlockOhosFeedbackDraftAttachment => {
      const targetPath = `${finalPath}/${ohosFeedbackDraftTargetName(index, attachment.fileName, attachment.mimeType)}`;
      return {
        id: attachment.id,
        fileName: attachment.fileName,
        mimeType: attachment.mimeType,
        sizeBytes: backend.fileSize(targetPath),
        fileRef: backend.fileUri(targetPath),
        previewUri: `file://${targetPath}`,
        source: 'settings_feedback_draft'
      };
    });
  } finally {
    if (!committed) {
      backend.removeRecursively(stagingPath);
    }
  }
}

function reopenSlockOhosFeedbackDraftAttachmentGroup(
  finalPath: string,
  sources: Array<SlockOhosFeedbackDraftAttachmentSource>,
  maxBytes: number,
  backend: SlockOhosFeedbackDraftAttachmentBackend
): Array<SlockOhosFeedbackDraftAttachment> | null {
  if (!backend.isDirectory(finalPath) || backend.list(finalPath).length !== sources.length) {
    return null;
  }
  const attachments: Array<SlockOhosFeedbackDraftAttachment> = [];
  for (let index = 0; index < sources.length; index += 1) {
    const source = sources[index];
    const targetPath = `${finalPath}/${ohosFeedbackDraftTargetName(index, source.fileName, source.mimeType)}`;
    if (!backend.isFile(targetPath)) {
      return null;
    }
    const sizeBytes = backend.fileSize(targetPath);
    if (sizeBytes <= 0 || sizeBytes > maxBytes) {
      return null;
    }
    const fallbackName = targetPath.split('/').pop() ?? 'attachment';
    attachments.push({
      id: source.id,
      fileName: source.fileName.length > 0 ? source.fileName : fallbackName,
      mimeType: source.mimeType,
      sizeBytes: sizeBytes,
      fileRef: backend.fileUri(targetPath),
      previewUri: `file://${targetPath}`,
      source: 'settings_feedback_draft'
    });
  }
  return attachments;
}

export function discardSlockOhosFeedbackDraftAttachments(
  rootPath: string,
  accountId: string,
  fileRefs: Array<string>,
  backend: SlockOhosFeedbackDraftAttachmentBackend = new FsSlockOhosFeedbackDraftAttachmentBackend(),
  accountSegment: (value: string) => string = stableOhosFeedbackDraftPathSegment
): void {
  const accountPath = `${rootPath}/${accountSegment(accountId.length > 0 ? accountId : 'anonymous')}`;
  const prefix = `${accountPath}/`;
  fileRefs.forEach((ref: string): void => {
    const path = backend.resolveFileRef(ref);
    if (path.startsWith(prefix) && backend.isFile(path)) {
      backend.removeFile(path);
      const slash = path.lastIndexOf('/');
      const groupPath = slash > prefix.length ? path.slice(0, slash) : '';
      if (groupPath.startsWith(prefix) && backend.list(groupPath).length === 0) {
        backend.removeRecursively(groupPath);
      }
    }
  });
}

function ohosFeedbackDraftTargetName(index: number, fileName: string, mimeType: string): string {
  const rawName = fileName.replace(/\\/g, '/').split('/').pop() ?? '';
  const dot = rawName.lastIndexOf('.');
  const rawStem = dot > 0 ? rawName.slice(0, dot) : rawName;
  const rawExtension = dot > 0 ? rawName.slice(dot + 1) : '';
  const stem = safeOhosFeedbackDraftPathSegment(rawStem).slice(0, 64) || 'attachment';
  const extension = /^[A-Za-z0-9]{1,10}$/.test(rawExtension)
    ? `.${rawExtension.toLowerCase()}`
    : ohosFeedbackDraftExtensionForMimeType(mimeType);
  return `${index.toString().padStart(2, '0')}-${stem}${extension}`;
}

function ohosFeedbackDraftExtensionForMimeType(mimeType: string): string {
  switch (mimeType.split(';')[0].trim().toLowerCase()) {
    case 'image/avif': return '.avif';
    case 'image/gif': return '.gif';
    case 'image/heic': return '.heic';
    case 'image/heif': return '.heif';
    case 'image/jpeg':
    case 'image/jpg': return '.jpg';
    case 'image/png': return '.png';
    case 'image/webp': return '.webp';
    default: return '.bin';
  }
}

function safeOhosFeedbackDraftPathSegment(value: string): string {
  return value.replace(/[^A-Za-z0-9_-]/g, '_').replace(/^[_-]+|[_-]+$/g, '');
}

export function stableOhosFeedbackDraftPathSegment(value: string): string {
  const digest = cryptoFramework.createMd('SHA256');
  digest.updateSync({ data: new util.TextEncoder().encodeInto(value) });
  return slockOhosFeedbackDraftAccountSegmentFromDigest(digest.digestSync().data);
}

export function slockOhosFeedbackDraftAccountSegmentFromDigest(bytes: Uint8Array): string {
  if (bytes.length < 16) {
    throw new Error('Feedback draft account digest is unavailable');
  }
  let segment = 'account-';
  for (let index = 0; index < 16; index += 1) {
    segment += bytes[index].toString(16).padStart(2, '0');
  }
  return segment;
}
