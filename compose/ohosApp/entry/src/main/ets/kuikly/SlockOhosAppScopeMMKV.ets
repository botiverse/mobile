import { common } from '@kit.AbilityKit';
import { registerSingleServiceProvider } from '@kuiklybase/knoi';
import { MMKV } from '@tencent/mmkv';

const SERVICE_NAME = 'slockOhosAppScopeMMKV';

class SlockOhosAppScopeMMKVService {
  private readonly context: common.Context;
  private initialized: boolean = false;

  constructor(context: common.Context) {
    this.context = context;
  }

  containsKey(namespace: string, key: string): boolean {
    if (namespace.length === 0 || key.length === 0) {
      return false;
    }
    return this.store(namespace).containsKey(key);
  }

  getString(namespace: string, key: string): string {
    if (namespace.length === 0 || key.length === 0) {
      return '';
    }
    return this.store(namespace).decodeString(key) ?? '';
  }

  putString(namespace: string, key: string, value: string): void {
    if (namespace.length === 0 || key.length === 0) {
      return;
    }
    this.store(namespace).encodeString(key, value);
  }

  removeString(namespace: string, key: string): void {
    if (namespace.length === 0 || key.length === 0) {
      return;
    }
    this.store(namespace).removeValueForKey(key);
  }

  private store(namespace: string): MMKV {
    this.ensureInitialized();
    return MMKV.mmkvWithID(namespace);
  }

  private ensureInitialized(): void {
    if (this.initialized) {
      return;
    }
    try {
      MMKV.initialize(this.context);
    } catch (_error) {
      // MMKV may already be initialized by another app-scope service.
    }
    this.initialized = true;
  }
}

export function registerSlockOhosAppScopeMMKV(context: common.Context): void {
  registerSingleServiceProvider(SERVICE_NAME, new SlockOhosAppScopeMMKVService(context.getApplicationContext()));
}
