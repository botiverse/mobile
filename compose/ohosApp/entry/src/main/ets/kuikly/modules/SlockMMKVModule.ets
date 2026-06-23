import { common } from '@kit.AbilityKit';
import { KRAny, KuiklyRenderBaseModule, KuiklyRenderCallback } from '@kuikly-open/render';
import { MMKV } from '@tencent/mmkv';

export class SlockMMKVModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME = 'KuiklyMMKVModule';
  private static initialized: boolean = false;

  syncMode(): boolean {
    return true;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    this.ensureInitialized();
    switch (method) {
      case 'encode':
        return this.encode(params);
      case 'decode':
        return this.decode(params);
      case 'containsKey':
        return this.containsKey(params);
      case 'removeValueForKey':
        return this.removeValueForKey(params);
      case 'removeValuesForKeys':
        return this.removeValuesForKeys(params);
      case 'allKeys':
        return this.allKeys(params, callback);
      case 'count':
        return this.count(params);
      case 'totalSize':
        return this.totalSize(params);
      case 'actualSize':
        return this.actualSize(params);
      case 'clearAll':
        return this.clearAll(params);
      case 'trim':
        return this.trim(params);
      case 'batchEncode':
        return this.batchEncode(params);
      default:
        return null;
    }
  }

  private ensureInitialized(): void {
    if (SlockMMKVModule.initialized) {
      return;
    }
    try {
      const context = getContext(this) as common.UIAbilityContext;
      MMKV.initialize(context.getApplicationContext());
    } catch (_error) {
      // MMKV may already be initialized by the host process.
    }
    SlockMMKVModule.initialized = true;
  }

  private mmkv(mmapID: string | null | undefined): MMKV {
    if (mmapID && mmapID.length > 0) {
      return MMKV.mmkvWithID(mmapID);
    }
    return MMKV.defaultMMKV();
  }

  private parseParams(params: KRAny): Record<string, Object> {
    if (typeof params !== 'string' || params.length === 0) {
      return {};
    }
    return JSON.parse(params) as Record<string, Object>;
  }

  private encode(params: KRAny): KRAny {
    const json = this.parseParams(params);
    const key = json['key'] as string;
    if (!key) {
      return null;
    }
    const store = this.mmkv(json['mmapID'] as string | null);
    switch (json['type'] as string) {
      case 'string':
        store.encodeString(key, (json['value'] as string) ?? '');
        break;
      case 'int':
        store.encodeInt32(key, (json['value'] as number) ?? 0);
        break;
      case 'long':
        store.encodeInt64(key, BigInt((json['value'] as number) ?? 0));
        break;
      case 'float':
      case 'double':
        store.encodeDouble(key, (json['value'] as number) ?? 0);
        break;
      case 'bool':
        store.encodeBool(key, (json['value'] as boolean) ?? false);
        break;
    }
    return null;
  }

  private decode(params: KRAny): string {
    const json = this.parseParams(params);
    const key = json['key'] as string;
    if (!key) {
      return '';
    }
    const store = this.mmkv(json['mmapID'] as string | null);
    switch (json['type'] as string) {
      case 'string': {
        const defaultValue = (json['defaultValue'] as string) ?? '';
        return store.decodeString(key) || defaultValue;
      }
      case 'int': {
        const defaultValue = (json['defaultValue'] as number) ?? 0;
        return (store.decodeInt32(key, defaultValue) ?? defaultValue).toString();
      }
      case 'long': {
        const defaultValue = BigInt((json['defaultValue'] as number) ?? 0);
        return (store.decodeInt64(key, defaultValue) ?? defaultValue).toString();
      }
      case 'float':
      case 'double': {
        const defaultValue = (json['defaultValue'] as number) ?? 0;
        return (store.decodeDouble(key, defaultValue) ?? defaultValue).toString();
      }
      case 'bool': {
        const defaultValue = (json['defaultValue'] as boolean) ?? false;
        return store.decodeBool(key, defaultValue) ? 'true' : 'false';
      }
      default:
        return '';
    }
  }

  private containsKey(params: KRAny): string {
    const json = this.parseParams(params);
    const key = json['key'] as string;
    if (!key) {
      return 'false';
    }
    return this.mmkv(json['mmapID'] as string | null).containsKey(key) ? 'true' : 'false';
  }

  private removeValueForKey(params: KRAny): KRAny {
    const json = this.parseParams(params);
    const key = json['key'] as string;
    if (key) {
      this.mmkv(json['mmapID'] as string | null).removeValueForKey(key);
    }
    return null;
  }

  private removeValuesForKeys(params: KRAny): KRAny {
    const json = this.parseParams(params);
    const keys = json['keys'] as Array<string>;
    if (keys) {
      this.mmkv(json['mmapID'] as string | null).removeValuesForKeys(keys);
    }
    return null;
  }

  private allKeys(params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    const json = this.parseParams(params);
    const keys = this.mmkv(json['mmapID'] as string | null).allKeys() || [];
    callback?.({ 'keys': keys.join(',') });
    return null;
  }

  private count(params: KRAny): string {
    const json = this.parseParams(params);
    return this.mmkv(json['mmapID'] as string | null).count().toString();
  }

  private totalSize(params: KRAny): string {
    const json = this.parseParams(params);
    return this.mmkv(json['mmapID'] as string | null).totalSize().toString();
  }

  private actualSize(params: KRAny): string {
    const json = this.parseParams(params);
    return this.mmkv(json['mmapID'] as string | null).actualSize().toString();
  }

  private clearAll(params: KRAny): KRAny {
    const json = this.parseParams(params);
    this.mmkv(json['mmapID'] as string | null).clearAll();
    return null;
  }

  private trim(params: KRAny): KRAny {
    const json = this.parseParams(params);
    this.mmkv(json['mmapID'] as string | null).trim();
    return null;
  }

  private batchEncode(params: KRAny): KRAny {
    const json = this.parseParams(params);
    const entries = json['entries'] as Array<Record<string, Object>>;
    const store = this.mmkv(json['mmapID'] as string | null);
    if (!entries) {
      return null;
    }
    entries.forEach((entry: Record<string, Object>) => {
      const key = entry['key'] as string;
      if (!key) {
        return;
      }
      switch ((entry['type'] as string) || 'string') {
        case 'string':
          store.encodeString(key, (entry['value'] as string) ?? '');
          break;
        case 'int':
          store.encodeInt32(key, (entry['value'] as number) ?? 0);
          break;
        case 'long':
          store.encodeInt64(key, BigInt((entry['value'] as number) ?? 0));
          break;
        case 'float':
        case 'double':
          store.encodeDouble(key, (entry['value'] as number) ?? 0);
          break;
        case 'bool':
          store.encodeBool(key, (entry['value'] as boolean) ?? false);
          break;
      }
    });
    return null;
  }
}
