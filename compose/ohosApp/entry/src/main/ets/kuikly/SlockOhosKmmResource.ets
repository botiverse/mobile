import { registerServiceProvider, invoke } from '@kuiklybase/knoi';
import { i18n } from '@kit.LocalizationKit';
import { SlockOhosDiagnostics } from './SlockOhosDiagnostics';

const KMM_RESOURCE_LOG_TAG = 'SlockKmmResource';

// KmmResource host contract (vendored from KuiklyBase-components
// KmmResource/ohosApp/resource_core — no published HAR exists for it).
// Shared Compose stringResource()/colorResource() reach this service via
// KNOI; if it is not registered before the first shared page renders, the
// native lookup dies with SIGSEGV (task #19, faultlog getNormalStringByName).
export interface OhosResourceService {
  getString(resName: string): string;

  getString(resName: string, args: Array<string | number>): string;

  getPlural(resName: string, args: number): string;

  getImage(resName: string): ArrayBuffer;

  getColor(resName: string): number;

  getImageBase64(resName: string): string;

  getFile(resName: string): Uint8Array;
}

export class OhosResourceServiceImpl implements OhosResourceService {
  static context: Context;

  getString(resName: string): string;

  getString(resName: string, args: Array<string | number>): string;

  getString(resName: string, args?: Array<string | number>): string {
    if (args != null && args.length > 0) {
      return OhosResourceServiceImpl.context.resourceManager.getStringByNameSync(resName, ...args);
    } else {
      return OhosResourceServiceImpl.context.resourceManager.getStringByNameSync(resName);
    }
  }

  getPlural(resName: string, args: number): string {
    return OhosResourceServiceImpl.context.resourceManager.getPluralStringByNameSync(resName, args);
  }

  getImage(resName: string): Uint8Array {
    return OhosResourceServiceImpl.context.resourceManager.getMediaByNameSync(resName);
  }

  getColor(resName: string): number {
    return OhosResourceServiceImpl.context.resourceManager.getColorByNameSync(resName);
  }

  getImageBase64(resName: string): string {
    return OhosResourceServiceImpl.context.resourceManager.getMediaBase64ByNameSync(resName);
  }

  getFile(resName: string): Uint8Array {
    return OhosResourceServiceImpl.context.resourceManager.getRawFileContentSync(resName);
  }
}

export interface SlockOhosSystemLocaleService {
  currentLanguageTag(): string;
}

export class SlockOhosSystemLocaleServiceImpl implements SlockOhosSystemLocaleService {
  currentLanguageTag(): string {
    return i18n.System.getSystemLanguages()[0] ?? 'en';
  }
}

let kmmResourceComposeInitialized = false;

// Must run after KNOI setup()/init() (libshared.so loaded) and before any
// Kuikly/shared page is created. Idempotent: re-entry (ability re-create)
// is a no-op so the service provider is never double-registered.
export function initKmmResourceCompose(context: Context): void {
  if (kmmResourceComposeInitialized) {
    SlockOhosDiagnostics.record(KMM_RESOURCE_LOG_TAG, 'kmm_resource_init skipped (already initialized)');
    return;
  }
  OhosResourceServiceImpl.context = context.getApplicationContext();
  invoke('init_resource_compose', context.resourceManager);
  registerServiceProvider('OhosResourceService', /** singleton */ true, OhosResourceServiceImpl);
  registerServiceProvider('slockOhosSystemLocale', /** singleton */ true, SlockOhosSystemLocaleServiceImpl);
  kmmResourceComposeInitialized = true;
  SlockOhosDiagnostics.record(KMM_RESOURCE_LOG_TAG, 'kmm_resource_init complete (OhosResourceService registered)');
}
