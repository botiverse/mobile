import { http } from '@kit.NetworkKit';
import fs from '@ohos.file.fs';
import util from '@ohos.util';

/** Hands public feedback diagnostics contract shared with Android/iOS. */
export const SLOCK_OHOS_FEEDBACK_DIAGNOSTICS_CONTENT_TYPE = 'application/zip';
export const SLOCK_OHOS_FEEDBACK_DIAGNOSTICS_MAX_BYTES = 200 * 1024 * 1024;
const SLOCK_OHOS_HANDS_BASE_URL = 'https://hands.build';
const SLOCK_OHOS_HANDS_APP_SLUG = 'raft-ohos';
const SLOCK_OHOS_HANDS_CLIENT_KEY = 'qk_425c2f5b28aae3d46037634665b99bc72605bb6e';
const SLOCK_OHOS_HANDS_REQUEST_TIMEOUT_MS = 10 * 60 * 1000;
const SLOCK_OHOS_FEEDBACK_DIAGNOSTICS_MAX_R2_KEY_LENGTH = 1024;

export interface SlockOhosFeedbackDiagnosticsReference {
  r2_key: string;
  filename: string;
  content_type: string;
  size: number;
}

export interface SlockOhosFeedbackDiagnosticsProgress {
  sent: number;
  total: number;
}

interface SlockOhosFeedbackDiagnosticsPresignedUpload {
  attachment_id: string;
  r2_key: string;
  upload_url: string;
  expires_at: number;
}

interface SlockOhosFeedbackDiagnosticsPresignFile {
  filename: string;
  content_type: string;
  size: number;
}

interface SlockOhosFeedbackDiagnosticsPresignBody {
  files: SlockOhosFeedbackDiagnosticsPresignFile[];
}

function resultText(result: string | ArrayBuffer | Object): string {
  if (typeof result === 'string') return result;
  if (result instanceof ArrayBuffer) {
    return new util.TextDecoder('utf-8').decodeWithStream(new Uint8Array(result));
  }
  return JSON.stringify(result);
}

function safeString(value: Object | undefined): string {
  return typeof value === 'string' ? value.trim() : '';
}

function safePositiveInteger(value: Object | undefined): number {
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : 0;
}

function validArchiveName(value: string): boolean {
  return value.length > 0 && value.length <= 256 &&
    !value.includes('/') && !value.includes('\\') &&
    !/[\u0000-\u001f\u007f]/.test(value);
}

function validReferenceText(value: string, maxLength: number): boolean {
  return value.length > 0 && value.length <= maxLength &&
    !/[\u0000-\u001f\u007f]/.test(value);
}

function validHttpsUrl(value: string): boolean {
  const prefix = 'https://';
  if (!value.startsWith(prefix) || value.length <= prefix.length) return false;
  const authorityAndPath = value.slice(prefix.length);
  let authorityEnd = authorityAndPath.length;
  const slash = authorityAndPath.indexOf('/');
  const query = authorityAndPath.indexOf('?');
  const fragment = authorityAndPath.indexOf('#');
  if (slash >= 0 && slash < authorityEnd) authorityEnd = slash;
  if (query >= 0 && query < authorityEnd) authorityEnd = query;
  if (fragment >= 0 && fragment < authorityEnd) authorityEnd = fragment;
  if (authorityEnd <= 0) return false;
  // Keep this in lockstep with shared isValidFeedbackDiagnosticsUploadUrl(): no control,
  // DEL, or whitespace characters may reach NetworkKit from a presigned response.
  return !/[\u0000-\u001f\u007f]/.test(value) && !/\s/.test(value);
}

/**
 * Native-only Hands diagnostics uploader. Client key and signed URL never cross the Kuikly
 * callback boundary. OHOS's HTTP client accepts an ArrayBuffer body; the send-progress event is
 * used when available and the terminal callback is always fenced by the owning operation.
 */
export class SlockOhosFeedbackDiagnosticsUploader {
  private request: http.HttpRequest | null = null;
  private cancelled: boolean = false;

  cancel(): void {
    this.cancelled = true;
    const request = this.request;
    this.request = null;
    if (request !== null) {
      try { request.destroy(); } catch (_error) { }
    }
  }

  async upload(
    archivePath: string,
    onProgress: (progress: SlockOhosFeedbackDiagnosticsProgress) => void,
  ): Promise<SlockOhosFeedbackDiagnosticsReference> {
    const stat = fs.statSync(archivePath);
    const size = Number(stat.size);
    const fileName = archivePath.slice(archivePath.lastIndexOf('/') + 1);
    if (!stat.isFile() || size <= 0 || size > SLOCK_OHOS_FEEDBACK_DIAGNOSTICS_MAX_BYTES) {
      throw new Error(size > SLOCK_OHOS_FEEDBACK_DIAGNOSTICS_MAX_BYTES
        ? 'diagnostics archive too large'
        : 'diagnostics archive unavailable');
    }
    if (!validArchiveName(fileName)) throw new Error('diagnostics archive filename invalid');
    const body = this.readArchive(archivePath, size);
    this.ensureActive();
    const presigned = await this.presign(fileName, size);
    this.ensureActive();
    onProgress({ sent: 0, total: size });
    const response = await this.put(presigned.upload_url, body, size, onProgress);
    if (response < 200 || response >= 300) throw new Error(`diagnostics upload failed code=${response}`);
    onProgress({ sent: size, total: size });
    return {
      r2_key: presigned.r2_key,
      filename: fileName,
      content_type: SLOCK_OHOS_FEEDBACK_DIAGNOSTICS_CONTENT_TYPE,
      size: size,
    };
  }

  private readArchive(path: string, size: number): ArrayBuffer {
    const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
    try {
      const body = new ArrayBuffer(size);
      const readBytes = fs.readSync(file.fd, body, { length: size });
      if (readBytes !== size) throw new Error('diagnostics archive read failed');
      return body;
    } finally {
      fs.closeSync(file);
    }
  }

  private async presign(fileName: string, size: number): Promise<SlockOhosFeedbackDiagnosticsPresignedUpload> {
    const client = http.createHttp();
    this.request = client;
    try {
      const files: SlockOhosFeedbackDiagnosticsPresignFile[] = [{
        filename: fileName,
        content_type: SLOCK_OHOS_FEEDBACK_DIAGNOSTICS_CONTENT_TYPE,
        size: size,
      }];
      const body: SlockOhosFeedbackDiagnosticsPresignBody = { files: files };
      const response = await client.request(
        `${SLOCK_OHOS_HANDS_BASE_URL}/public/v2/apps/${SLOCK_OHOS_HANDS_APP_SLUG}/feedback/presign`,
        {
          method: http.RequestMethod.POST,
          header: {
            'Accept': 'application/json',
            'Content-Type': 'application/json',
            'X-Hands-Client-Key': SLOCK_OHOS_HANDS_CLIENT_KEY,
          },
          extraData: JSON.stringify(body),
          expectDataType: http.HttpDataType.STRING,
          connectTimeout: SLOCK_OHOS_HANDS_REQUEST_TIMEOUT_MS,
          readTimeout: SLOCK_OHOS_HANDS_REQUEST_TIMEOUT_MS,
          usingCache: false,
        },
      );
      if (Number(response.responseCode ?? 0) < 200 || Number(response.responseCode ?? 0) >= 300) {
        throw new Error(`diagnostics presign failed code=${response.responseCode}`);
      }
      const parsed = JSON.parse(resultText(response.result)) as Record<string, Object>;
      const uploads = parsed['uploads'];
      if (!Array.isArray(uploads) || uploads.length !== 1) throw new Error('diagnostics presign response invalid');
      const upload = uploads[0] as Record<string, Object>;
      const result: SlockOhosFeedbackDiagnosticsPresignedUpload = {
        attachment_id: safeString(upload['attachment_id']),
        r2_key: safeString(upload['r2_key']),
        upload_url: safeString(upload['upload_url']),
        expires_at: safePositiveInteger(upload['expires_at']),
      };
      if (!validReferenceText(result.attachment_id, 256) ||
        !validReferenceText(result.r2_key, SLOCK_OHOS_FEEDBACK_DIAGNOSTICS_MAX_R2_KEY_LENGTH) ||
        !validHttpsUrl(result.upload_url) || result.expires_at <= 0) {
        throw new Error('diagnostics presign response invalid');
      }
      return result;
    } finally {
      try { client.destroy(); } catch (_error) { }
      if (this.request === client) this.request = null;
    }
  }

  private async put(
    url: string,
    body: ArrayBuffer,
    size: number,
    onProgress: (progress: SlockOhosFeedbackDiagnosticsProgress) => void,
  ): Promise<number> {
    if (!validHttpsUrl(url)) throw new Error('diagnostics upload URL invalid');
    const request = http.createHttp();
    this.request = request;
    const progress = (data: http.DataSendProgressInfo): void => {
      if (this.cancelled) return;
      const sent = Number(data?.sendSize ?? 0);
      const total = Number(data?.totalSize ?? size);
      const boundedTotal = total > 0 ? total : size;
      onProgress({ sent: Math.max(0, Math.min(sent, boundedTotal)), total: boundedTotal });
    };
    // NetworkKit exposes dataSendProgress on API 11+; this event is best-effort on older devices.
    try {
      request.on('dataSendProgress', progress);
    } catch (_error) { }
    try {
      const response = await request.request(url, {
        method: http.RequestMethod.PUT,
        header: { 'Content-Type': SLOCK_OHOS_FEEDBACK_DIAGNOSTICS_CONTENT_TYPE },
        extraData: body,
        expectDataType: http.HttpDataType.STRING,
        connectTimeout: SLOCK_OHOS_HANDS_REQUEST_TIMEOUT_MS,
        readTimeout: SLOCK_OHOS_HANDS_REQUEST_TIMEOUT_MS,
        usingCache: false,
      });
      this.ensureActive();
      return Number(response.responseCode ?? 0);
    } finally {
      try { request.off('dataSendProgress', progress); } catch (_error) { }
      try { request.destroy(); } catch (_error) { }
      this.request = null;
    }
  }

  private ensureActive(): void {
    if (this.cancelled) throw new Error('diagnostics upload cancelled');
  }
}
