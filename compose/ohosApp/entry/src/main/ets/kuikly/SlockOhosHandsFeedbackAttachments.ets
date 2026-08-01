import { fileUri } from '@kit.CoreFileKit';
import fs from '@ohos.file.fs';

export interface SlockOhosHandsFeedbackAttachmentStat {
  isFile: boolean;
  sizeBytes: number;
}

export interface SlockOhosHandsFeedbackAttachmentBackend {
  localPathForFileUri(ref: string): string;
  stat(path: string): SlockOhosHandsFeedbackAttachmentStat;
}

class FsSlockOhosHandsFeedbackAttachmentBackend implements SlockOhosHandsFeedbackAttachmentBackend {
  localPathForFileUri(ref: string): string {
    return new fileUri.FileUri(ref).path;
  }

  stat(path: string): SlockOhosHandsFeedbackAttachmentStat {
    const stat = fs.statSync(path);
    return {
      isFile: stat.isFile(),
      sizeBytes: stat.size,
    };
  }
}

/**
 * Converts picker-owned file URIs to the absolute sandbox paths required by
 * Hands' http.MultiFormData.filePath. ArkTS fs can read an ability-scoped
 * `file://<bundle>/data/...` URI directly, but NETSTACK treats filePath as a
 * local path and otherwise fails its lstat with ENOENT / CURL 26.
 */
export function slockOhosHandsFeedbackAttachmentPaths(
  value: Object | undefined,
  maxAttachments: number,
  maxAttachmentBytes: number,
  backend: SlockOhosHandsFeedbackAttachmentBackend = new FsSlockOhosHandsFeedbackAttachmentBackend()
): Array<string> {
  if (!Array.isArray(value) || maxAttachments <= 0 || maxAttachmentBytes <= 0) {
    return [];
  }
  const paths: Array<string> = [];
  (value as Array<Record<string, Object>>).forEach((file: Record<string, Object>) => {
    if (paths.length >= maxAttachments) {
      return;
    }
    const fileRef = (file["fileRef"] as string | undefined) ?? "";
    try {
      const path = fileRef.startsWith('/')
        ? fileRef
        : fileRef.startsWith('file://')
          ? backend.localPathForFileUri(fileRef)
          : '';
      if (!path.startsWith('/')) {
        return;
      }
      const stat = backend.stat(path);
      if (stat.isFile && stat.sizeBytes > 0 && stat.sizeBytes <= maxAttachmentBytes) {
        paths.push(path);
      }
    } catch (_) {
    }
  });
  return paths;
}
