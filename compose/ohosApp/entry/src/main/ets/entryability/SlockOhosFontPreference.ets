import { MMKV } from '@tencent/mmkv';

/*
 * Task #179 follow-up (OHOS): the app-wide default font must follow
 * Settings > Appearance instead of a hard-coded Space Grotesk face. The
 * shared side persists the choice in the app-scope MMKV store (namespace
 * "slockLocal", see KeyValueFontPreferenceStore) under the key below, so the
 * ability can read it synchronously during onWindowStageCreate — before any
 * Kuikly/shared page exists. MMKV itself is initialized in onCreate via
 * initializeSlockOhosAppScopeMMKV, so the store is always readable here.
 */
const SLOCK_LOCAL_MMKV_NAMESPACE = 'slockLocal';
// The Kotlin side stores keys as "$namespace:$key"
// (SlockOhosMMKVKeyValueStore.namespaced), so the on-disk key carries the
// store namespace prefix.
const FONT_PREFERENCE_STORAGE_KEY = 'slockLocal:appearance:font_preference';

/**
 * Registered family name for the app-wide default font, or null when the
 * choice is the host platform face ("system"). Mirrors the shared
 * FontPreference.fromStorageKey normalization (trim + lowercase) and its
 * sansFamily regular face; unknown or absent values resolve to the shipped
 * default, Space Grotesk. The returned names must stay in SLOCK_FONTS.
 */
export function slockOhosAppFontFamilyForPreference(storageValue: string | null | undefined): string | null {
  const normalized = storageValue?.trim().toLowerCase() ?? '';
  if (normalized === 'system') {
    return null;
  }
  if (normalized === 'hanken_grotesk') {
    return 'hanken_grotesk_regular';
  }
  if (normalized === 'geist_inter') {
    return 'geist_regular';
  }
  return 'space_grotesk_regular';
}

/**
 * Current Settings > Appearance choice as the app-wide default font name;
 * null means the system face. Never throws: an unreadable store keeps the
 * shipped default.
 */
export function resolveSlockOhosAppFontFamily(): string | null {
  try {
    const stored = MMKV.mmkvWithID(SLOCK_LOCAL_MMKV_NAMESPACE).decodeString(FONT_PREFERENCE_STORAGE_KEY);
    return slockOhosAppFontFamilyForPreference(stored);
  } catch (_error) {
    return slockOhosAppFontFamilyForPreference(null);
  }
}
