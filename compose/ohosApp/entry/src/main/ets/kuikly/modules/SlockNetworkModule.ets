import { BusinessError } from '@kit.BasicServicesKit';
import { http } from '@kit.NetworkKit';
import { KRAny } from '@kuikly-open/render';
import { KuiklyRenderBaseModule } from '@kuikly-open/render';
import { KuiklyRenderCallback } from '@kuikly-open/render';
import util from '@ohos.util';

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
    const bodyBase64 = command['bodyBase64'] as string | undefined;
    if (bodyBase64 !== undefined) {
      const helper = new util.Base64Helper();
      return helper.decodeSync(bodyBase64).buffer as ArrayBuffer;
    }
    return command['bodyText'] as string | undefined;
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
