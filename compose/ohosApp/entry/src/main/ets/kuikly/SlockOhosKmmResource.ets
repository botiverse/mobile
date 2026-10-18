import { registerServiceProvider, invoke } from '@kuiklybase/knoi';
import { i18n, resourceManager } from '@kit.LocalizationKit';
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

// task #190: in-app language override. Shared pushes the active locale tag
// through the KNOI `slockOhosAppLocale` service (the KuiklyBase
// OhosResourceService contract carries no locale parameter and stays
// unchanged). When a tag is set, string/plural lookups run through an
// override ResourceManager built with that locale; otherwise the default
// (system-locale) manager is used exactly as before.
let slockOhosAppLocaleTag: string | null = null;
let slockOhosOverrideResourceManager: resourceManager.ResourceManager | null = null;

/**
 * Normalizes an incoming app-locale tag for `resourceManager.Configuration.locale`.
 * Empty/`system` clears the override (follow the system locale); underscores
 * become hyphens so both `zh_CN` and `zh-CN` shapes are accepted. Returns
 * null when no override should apply.
 */
export function normalizeSlockOhosAppLocaleTag(tag: string): string | null {
  const trimmed = tag.trim();
  if (trimmed === '' || trimmed.toLowerCase() === 'system') {
    return null;
  }
  return trimmed.replace(/_/g, '-');
}

function slockOhosStringResourceManager(): resourceManager.ResourceManager {
  return slockOhosOverrideResourceManager ?? OhosResourceServiceImpl.context.resourceManager;
}

export interface SlockOhosAppLocaleService {
  setLocale(tag: string): void;
}

export class SlockOhosAppLocaleServiceImpl implements SlockOhosAppLocaleService {
  setLocale(tag: string): void {
    const normalized = normalizeSlockOhosAppLocaleTag(tag);
    if (normalized === slockOhosAppLocaleTag) {
      return;
    }
    slockOhosAppLocaleTag = normalized;
    slockOhosOverrideResourceManager = null;
    if (normalized === null) {
      SlockOhosDiagnostics.record(KMM_RESOURCE_LOG_TAG, 'app_locale cleared (follow system)');
      return;
    }
    try {
      // Configuration is a class (no object literal): start from the current
      // device configuration and override only the locale field.
      const configuration = OhosResourceServiceImpl.context.resourceManager.getConfigurationSync();
      configuration.locale = normalized;
      slockOhosOverrideResourceManager =
        OhosResourceServiceImpl.context.resourceManager.getOverrideResourceManager(configuration);
      SlockOhosDiagnostics.record(KMM_RESOURCE_LOG_TAG, `app_locale set to ${normalized}`);
    } catch (error) {
      // Keep serving from the system-locale manager rather than failing the
      // string pipeline; the next setLocale call retries.
      slockOhosAppLocaleTag = null;
      SlockOhosDiagnostics.record(KMM_RESOURCE_LOG_TAG, `app_locale override failed for ${normalized}`);
    }
  }
}

export class OhosResourceServiceImpl implements OhosResourceService {
  static context: Context;

  getString(resName: string): string;

  getString(resName: string, args: Array<string | number>): string;

  getString(resName: string, args?: Array<string | number>): string {
    if (args != null && args.length > 0) {
      return slockOhosStringResourceManager().getStringByNameSync(resName, ...args);
    } else {
      return slockOhosStringResourceManager().getStringByNameSync(resName);
    }
  }

  getPlural(resName: string, args: number): string {
    return slockOhosStringResourceManager().getPluralStringByNameSync(resName, args);
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
  registerServiceProvider('slockOhosAppLocale', /** singleton */ true, SlockOhosAppLocaleServiceImpl);
  kmmResourceComposeInitialized = true;
  SlockOhosDiagnostics.record(KMM_RESOURCE_LOG_TAG, 'kmm_resource_init complete (OhosResourceService registered)');
}
