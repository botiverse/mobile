import { BusinessError } from '@kit.BasicServicesKit';
import fs from '@ohos.file.fs';
import util from '@ohos.util';

export const SLOCK_OHOS_PLATFORM_FILE_READER_SERVICE = 'slockOhosPlatformFileReader';
export const SLOCK_OHOS_PLATFORM_FILE_MAX_STREAM_CHUNK_BYTES = 64 * 1024;

export interface SlockOhosPlatformFileBackend {
  open(fileRef: string): Object;
  statSize(handle: Object): number;
  read(handle: Object, maxBytes: number): ArrayBuffer;
  close(handle: Object): void;
}

class FsSlockOhosPlatformFileBackend implements SlockOhosPlatformFileBackend {
  open(fileRef: string): Object {
    return fs.openSync(fileRef, fs.OpenMode.READ_ONLY);
  }

  statSize(handle: Object): number {
    return fs.statSync((handle as fs.File).fd).size;
  }

  read(handle: Object, maxBytes: number): ArrayBuffer {
    const bodyBytes = new ArrayBuffer(maxBytes);
    const readBytes = fs.readSync((handle as fs.File).fd, bodyBytes, { length: maxBytes });
    return readBytes === maxBytes ? bodyBytes : bodyBytes.slice(0, Math.max(0, readBytes));
  }

  close(handle: Object): void {
    fs.closeSync(handle as fs.File);
  }
}

interface OpenPlatformFileStream {
  handle: Object;
  sizeBytes: number;
}

export class SlockOhosPlatformFileReader {
  private static readonly MAX_STREAM_CHUNK_BYTES = SLOCK_OHOS_PLATFORM_FILE_MAX_STREAM_CHUNK_BYTES;
  private readonly backend: SlockOhosPlatformFileBackend;
  private readonly openStreams: Map<string, OpenPlatformFileStream> = new Map();
  private nextStreamId: number = 0;

  constructor(backend: SlockOhosPlatformFileBackend = new FsSlockOhosPlatformFileBackend()) {
    this.backend = backend;
  }

  readFile(fileRef: string): Record<string, Object> {
    if (fileRef.length === 0) {
      return this.error('missing_file_ref', 'Missing file ref');
    }
    try {
      const file = fs.openSync(fileRef, fs.OpenMode.READ_ONLY);
      try {
        const stat = fs.statSync(file.fd);
        const bodyBytes = new ArrayBuffer(stat.size);
        fs.readSync(file.fd, bodyBytes);
        return {
          'status': 'ok',
          'code': 200,
          'bodyBase64': new util.Base64Helper().encodeToStringSync(new Uint8Array(bodyBytes)),
          'sizeBytes': stat.size
        };
      } finally {
        fs.closeSync(file);
      }
    } catch (error) {
      return this.fileError(error);
    }
  }

  statFile(fileRef: string): Record<string, Object> {
    if (fileRef.length === 0) {
      return this.error('missing_file_ref', 'Missing file ref');
    }
    try {
      const handle = this.backend.open(fileRef);
      try {
        const sizeBytes = this.backend.statSize(handle);
        if (sizeBytes <= 0) {
          return this.error('attachment_size_unavailable', 'Attachment size unavailable');
        }
        return {
          'status': 'ok',
          'code': 200,
          'sizeBytes': sizeBytes
        };
      } finally {
        this.backend.close(handle);
      }
    } catch (error) {
      return this.fileError(error);
    }
  }

  openFileStream(fileRef: string, expectedSizeBytes: number): Record<string, Object> {
    if (fileRef.length === 0) {
      return this.error('missing_file_ref', 'Missing file ref');
    }
    let handle: Object;
    try {
      handle = this.backend.open(fileRef);
    } catch (error) {
      return this.fileError(error);
    }
    try {
      const sizeBytes = this.backend.statSize(handle);
      if (sizeBytes <= 0 || sizeBytes !== expectedSizeBytes) {
        this.backend.close(handle);
        return this.error(
          'attachment_size_changed',
          `Attachment size changed: expected=${expectedSizeBytes} actual=${sizeBytes}`
        );
      }
      this.nextStreamId += 1;
      const streamId = `slock-file-${Date.now()}-${this.nextStreamId}`;
      this.openStreams.set(streamId, { handle: handle, sizeBytes: sizeBytes });
      return {
        'status': 'ok',
        'code': 200,
        'streamId': streamId,
        'sizeBytes': sizeBytes
      };
    } catch (error) {
      try {
        this.backend.close(handle);
      } catch (_) {
        // Preserve the original open/stat failure.
      }
      return this.fileError(error);
    }
  }

  readFileChunk(streamId: string, maxBytes: number): Record<string, Object> {
    const stream = this.openStreams.get(streamId);
    if (stream === undefined) {
      return this.error('attachment_stream_closed', 'Attachment stream is closed');
    }
    try {
      const boundedBytes = Math.max(
        1,
        Math.min(maxBytes, SlockOhosPlatformFileReader.MAX_STREAM_CHUNK_BYTES)
      );
      const bodyBytes = this.backend.read(stream.handle, boundedBytes);
      const readBytes = bodyBytes.byteLength;
      if (readBytes <= 0) {
        return {
          'status': 'ok',
          'code': 200,
          'bodyBase64': '',
          'readBytes': 0,
          'eof': true
        };
      }
      return {
        'status': 'ok',
        'code': 200,
        'bodyBase64': new util.Base64Helper().encodeToStringSync(new Uint8Array(bodyBytes)),
        'readBytes': readBytes,
        'eof': false
      };
    } catch (error) {
      return this.fileError(error);
    }
  }

  closeFileStream(streamId: string): Record<string, Object> {
    const stream = this.openStreams.get(streamId);
    if (stream === undefined) {
      return { 'status': 'ok', 'code': 200, 'closed': false };
    }
    this.openStreams.delete(streamId);
    try {
      this.backend.close(stream.handle);
      return { 'status': 'ok', 'code': 200, 'closed': true };
    } catch (error) {
      return this.fileError(error);
    }
  }

  private fileError(error: Object): Record<string, Object> {
    const businessError = error as BusinessError;
    return this.error(
      'attachment_read_failed',
      businessError.message ?? `${error}`
    );
  }

  private error(errorCode: string, message: string): Record<string, Object> {
    return {
      'status': 'error',
      'code': 400,
      'errorCode': errorCode,
      'errorBodyText': message
    };
  }
}
