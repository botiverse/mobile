import { BusinessError } from '@kit.BasicServicesKit';
import { http } from '@kit.NetworkKit';
import { KRAny } from '@kuikly-open/render';
import { KuiklyRenderBaseModule } from '@kuikly-open/render';
import { KuiklyRenderCallback } from '@kuikly-open/render';
import util from '@ohos.util';
import fs from '@ohos.file.fs';
import { SlockOhosApiEnvironmentStore } from '../SlockOhosApiEnvironmentStore';

export class SlockNetworkModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME = 'HRSlockNetworkModule';
  private static readonly REQUEST = 'request';
  private static readonly READ_PLATFORM_FILE = 'readPlatformFile';
  private static readonly CANCEL = 'cancel';
  private readonly activeRequests: Map<string, http.HttpRequest> = new Map();
  private readonly canceledRequests: Set<string> = new Set();

  syncMode(): boolean {
    return false;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    switch (method) {
      case SlockNetworkModule.REQUEST: {
        this.request(params, callback);
        break;
      }
      case SlockNetworkModule.READ_PLATFORM_FILE: {
        this.readPlatformFile(params, callback);
        break;
      }
      case SlockNetworkModule.CANCEL: {
        this.cancel(params);
        break;
      }
      default: {
        callback?.({ 'code': -1, 'message': `Method not implemented: ${method}` });
        break;
      }
    }
    return null;
  }

  private async request(params: KRAny, callback: KuiklyRenderCallback | null) {
    const client = http.createHttp();
    const command = this.parseRecord(params);
    const requestId = this.requestId(command);
    if (requestId !== null) {
      this.canceledRequests.delete(requestId);
      this.activeRequests.set(requestId, client);
    }
    try {
      const response = await client.request(SlockOhosApiEnvironmentStore.rewriteApiUrl(command['url'] as string, this), {
        method: this.requestMethod(((command['method'] as string) ?? 'GET').toUpperCase()),
        header: this.stringRecord(command['headers']),
        extraData: this.requestBody(command),
        connectTimeout: Number(command['timeoutMillis'] ?? 30000),
        readTimeout: Number(command['timeoutMillis'] ?? 30000)
      });
      if (this.isCanceled(requestId)) {
        return;
      }
      const responseCode = Number(response.responseCode ?? 0);
      const body = this.httpResultToString(response.result);
      callback?.({
        'code': responseCode,
        'bodyText': responseCode >= 200 && responseCode < 300 ? body : '',
        'errorBodyText': responseCode >= 200 && responseCode < 300 ? '' : body
      });
    } catch (error) {
      if (this.isCanceled(requestId)) {
        return;
      }
      const businessError = error as BusinessError;
      callback?.({
        'code': 0,
        'bodyText': '',
        'errorBodyText': businessError.message ?? `${error}`,
        'message': businessError.message ?? `${error}`
      });
    } finally {
      if (requestId !== null) {
        this.activeRequests.delete(requestId);
        this.canceledRequests.delete(requestId);
      }
      this.destroyClient(client);
    }
  }

  private cancel(params: KRAny) {
    const command = this.parseRecord(params);
    const requestId = this.requestId(command);
    if (requestId === null) {
      return;
    }
    this.canceledRequests.add(requestId);
    const client = this.activeRequests.get(requestId);
    if (client !== undefined) {
      this.activeRequests.delete(requestId);
      this.destroyClient(client);
    }
  }

  private readPlatformFile(params: KRAny, callback: KuiklyRenderCallback | null) {
    const command = this.parseRecord(params);
    const fileRef = `${command['fileRef'] ?? ''}`;
    try {
      const bytes = this.platformFileBytes(fileRef);
      const helper = new util.Base64Helper();
      callback?.({
        'code': 200,
        'bodyBase64': helper.encodeToStringSync(bytes),
        'sizeBytes': bytes.byteLength
      });
    } catch (error) {
      const businessError = error as BusinessError;
      callback?.({
        'code': 400,
        'bodyBase64': '',
        'sizeBytes': 0,
        'errorCode': 'attachment_read_failed',
        'message': businessError.message ?? `${error}`
      });
    }
  }

  private destroyClient(client: http.HttpRequest) {
    try {
      client.destroy();
    } catch (_error) {
      // A cancellation may destroy the client before the request coroutine reaches finally.
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
    const bodyBase64 = command['bodyBase64'] as string | undefined;
    if (bodyBase64 !== undefined) {
      const helper = new util.Base64Helper();
      return helper.decodeSync(bodyBase64).buffer as ArrayBuffer;
    }
    return command['bodyText'] as string | undefined;
  }

  private platformFileBytes(fileRef: string): Uint8Array {
    if (fileRef.length === 0) {
      throw new Error('No readable file');
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

  private requestId(command: Record<string, Object>): string | null {
    const raw = command['requestId'];
    if (raw === undefined || raw === null) {
      return null;
    }
    const value = `${raw}`;
    return value.length > 0 ? value : null;
  }

  private isCanceled(requestId: string | null): boolean {
    return requestId !== null && this.canceledRequests.has(requestId);
  }
}
