import { BusinessError } from '@kit.BasicServicesKit';
import { http } from '@kit.NetworkKit';
import { KRAny } from '@kuikly-open/render';
import { KuiklyRenderBaseModule } from '@kuikly-open/render';
import { KuiklyRenderCallback } from '@kuikly-open/render';
import util from '@ohos.util';
import fs from '@ohos.file.fs';

export class SlockNetworkModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME = 'HRSlockNetworkModule';
  private static readonly REQUEST = 'request';

  syncMode(): boolean {
    return false;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback): KRAny {
    switch (method) {
      case SlockNetworkModule.REQUEST: {
        this.request(params, callback);
        break;
      }
      default: {
        callback({ 'code': -1, 'message': 'Method not implemented' });
        break;
      }
    }
    return null;
  }

  private async request(params: KRAny, callback: KuiklyRenderCallback) {
    const client = http.createHttp();
    try {
      const command = this.parseRecord(params);
      const response = await client.request(command['url'] as string, {
        method: this.requestMethod(((command['method'] as string) ?? 'GET').toUpperCase()),
        header: this.stringRecord(command['headers']),
        extraData: this.requestBody(command),
        connectTimeout: Number(command['timeoutMillis'] ?? 30000),
        readTimeout: Number(command['timeoutMillis'] ?? 30000)
      });
      const responseCode = Number(response.responseCode ?? 0);
      const body = this.httpResultToString(response.result);
      callback({
        'code': responseCode,
        'bodyText': responseCode >= 200 && responseCode < 300 ? body : '',
        'errorBodyText': responseCode >= 200 && responseCode < 300 ? '' : body
      });
    } catch (error) {
      const businessError = error as BusinessError;
      callback({
        'code': 0,
        'bodyText': '',
        'errorBodyText': businessError.message ?? `${error}`,
        'message': businessError.message ?? `${error}`
      });
    } finally {
      client.destroy();
    }
  }

  private requestMethod(method: string): http.RequestMethod {
    switch (method) {
      case 'DELETE':
        return http.RequestMethod.DELETE;
      case 'PATCH':
        return method as http.RequestMethod;
      case 'POST':
        return http.RequestMethod.POST;
      case 'PUT':
        return http.RequestMethod.PUT;
      case 'GET':
      default:
        return http.RequestMethod.GET;
    }
  }

  private requestBody(command: Record<string, Object>): string | ArrayBuffer | undefined {
    if (command['multipartFiles'] !== undefined) {
      return this.multipartBody(command);
    }
    const bodyBase64 = command['bodyBase64'] as string | undefined;
    if (bodyBase64 !== undefined) {
      const helper = new util.Base64Helper();
      return helper.decodeSync(bodyBase64).buffer as ArrayBuffer;
    }
    return command['bodyText'] as string | undefined;
  }

  private multipartBody(command: Record<string, Object>): ArrayBuffer {
    const headers = this.stringRecord(command['headers']);
    const contentType = headers['Content-Type'] ?? headers['content-type'] ?? '';
    const boundary = this.multipartBoundary(contentType);
    const chunks: Array<Uint8Array> = [];
    const fields = (command['multipartFields'] as Record<string, Object> | undefined) ?? {};
    Object.keys(fields).forEach((key: string) => {
      chunks.push(this.utf8(`--${boundary}\r\n`));
      chunks.push(this.utf8(`Content-Disposition: form-data; name="${key}"\r\n\r\n`));
      chunks.push(this.utf8(`${fields[key] ?? ''}`));
      chunks.push(this.utf8('\r\n'));
    });

    const files = (command['multipartFiles'] as Array<Record<string, Object>> | undefined) ?? [];
    let addedFiles = 0;
    files.forEach((file: Record<string, Object>) => {
      const name = `${file['name'] ?? 'files'}`;
      const fileName = this.sanitizeMultipartFileName(`${file['fileName'] ?? 'attachment'}`);
      const mimeType = `${file['mimeType'] ?? 'application/octet-stream'}`;
      const bytes = this.multipartFileBytes(file);
      if (bytes === null) {
        return;
      }
      chunks.push(this.utf8(`--${boundary}\r\n`));
      chunks.push(this.utf8(`Content-Disposition: form-data; name="${name}"; filename="${fileName}"\r\n`));
      chunks.push(this.utf8(`Content-Type: ${mimeType}\r\n\r\n`));
      chunks.push(bytes);
      chunks.push(this.utf8('\r\n'));
      addedFiles += 1;
    });
    if (files.length > 0 && addedFiles === 0) {
      throw new Error('No readable multipart files');
    }
    chunks.push(this.utf8(`--${boundary}--\r\n`));
    return this.concatBytes(chunks).buffer as ArrayBuffer;
  }

  private multipartBoundary(contentType: string): string {
    const marker = 'boundary=';
    const index = contentType.indexOf(marker);
    if (index < 0) {
      return `slock-${Date.now()}`;
    }
    return contentType.substring(index + marker.length).trim().replace(/"/g, '');
  }

  private multipartFileBytes(file: Record<string, Object>): Uint8Array | null {
    const bodyBase64 = file['bodyBase64'] as string | undefined;
    if (bodyBase64 !== undefined) {
      const helper = new util.Base64Helper();
      return helper.decodeSync(bodyBase64);
    }
    const fileRef = file['fileRef'] as string | undefined;
    if (fileRef === undefined || fileRef.length === 0) {
      return null;
    }
    const handle = fs.openSync(fileRef, fs.OpenMode.READ_ONLY);
    try {
      const stat = fs.statSync(handle.fd);
      const buffer = new ArrayBuffer(stat.size);
      const readLength = fs.readSync(handle.fd, buffer);
      return new Uint8Array(buffer, 0, readLength);
    } finally {
      fs.closeSync(handle);
    }
  }

  private utf8(value: string): Uint8Array {
    const encoder = new util.TextEncoder();
    return encoder.encodeInto(value);
  }

  private concatBytes(chunks: Array<Uint8Array>): Uint8Array {
    const total = chunks.reduce((sum: number, chunk: Uint8Array) => sum + chunk.byteLength, 0);
    const result = new Uint8Array(total);
    let offset = 0;
    chunks.forEach((chunk: Uint8Array) => {
      result.set(chunk, offset);
      offset += chunk.byteLength;
    });
    return result;
  }

  private sanitizeMultipartFileName(value: string): string {
    return value.replace(/"/g, "'").replace(/\r/g, '').replace(/\n/g, '');
  }

  private stringRecord(value: Object | undefined): Record<string, string> {
    if (value === undefined || value === null) {
      return {};
    }
    const raw = value as Record<string, Object>;
    const result: Record<string, string> = {};
    Object.keys(raw).forEach((key: string) => {
      const entry = raw[key];
      if (entry !== undefined && entry !== null) {
        result[key] = `${entry}`;
      }
    });
    return result;
  }

  private httpResultToString(result: string | ArrayBuffer | Object): string {
    if (typeof result === 'string') {
      return result;
    }
    if (result instanceof ArrayBuffer) {
      const textDecoder = new util.TextDecoder('utf-8');
      return textDecoder.decodeWithStream(new Uint8Array(result));
    }
    return JSON.stringify(result);
  }

  private parseRecord(params: KRAny): Record<string, Object> {
    if (typeof params === 'string') {
      return JSON.parse(params) as Record<string, Object>;
    }
    return params as Record<string, Object>;
  }
}
