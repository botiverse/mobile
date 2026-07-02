import { common } from '@kit.AbilityKit';
import { MMKV } from '@tencent/mmkv';

let slockOhosMMKVInitialized = false;

export function initializeSlockOhosAppScopeMMKV(context: common.Context): void {
  if (slockOhosMMKVInitialized) {
    return;
  }
  try {
    MMKV.initialize(context.getApplicationContext());
  } catch (_error) {
    // MMKV may already be initialized by another app-scope service.
  }
  slockOhosMMKVInitialized = true;
}
